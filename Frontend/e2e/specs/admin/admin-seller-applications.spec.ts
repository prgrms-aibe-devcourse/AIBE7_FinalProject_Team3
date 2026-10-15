import { expect, getAuth, test } from '../../fixtures'

type Item = {
  applicationId: string
  brandName: string
  contactEmail: string
  businessRegistrationNumber: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  appliedAt: string
}

const item = (
  applicationId: string,
  brandName: string,
  status: Item['status'] = 'PENDING',
): Item => ({
  applicationId,
  brandName,
  contactEmail: `${applicationId}@example.com`,
  businessRegistrationNumber: '1234567890',
  status,
  appliedAt: '2026-09-18T14:00:00+09:00',
})

function listResponse(content: Item[], hasNext = false) {
  return {
    success: true,
    data: {
      content,
      page: 0,
      size: 20,
      totalElements: content.length,
      totalPages: 1,
      hasNext,
    },
  }
}

async function loginFromAdminRedirect(page: import('@playwright/test').Page) {
  getAuth(page).setRoles(['USER', 'ADMIN'])
  await page.goto('/#/admin/seller-applications')
  await expect(page).toHaveURL(/#\/login$/)
  await page.getByLabel('이메일').fill('grab@example.com')
  await page.getByLabel('비밀번호').fill('password1234')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).toHaveURL(/#\/admin\/seller-applications$/)
}

test('비관리자는 접근 권한 안내를 본다', async ({ page }) => {
  await page.goto('/#/admin/seller-applications')
  await expect(page).toHaveURL(/#\/login$/)
  await page.getByLabel('이메일').fill('grab@example.com')
  await page.getByLabel('비밀번호').fill('password1234')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByText('접근 권한이 없어요.')).toBeVisible()
})

test('상태 필터·페이지 이동이 URL에 반영되고 목록을 다시 조회한다', async ({
  page,
}) => {
  await page.route('**/api/v1/admin/seller-applications*', (route) => {
    const url = new URL(route.request().url())
    const status = url.searchParams.get('status')
    const reqPage = url.searchParams.get('page')
    if (status === 'APPROVED') {
      return route.fulfill({
        json: listResponse([item('app-3', '오늘의 공방', 'APPROVED')]),
      })
    }
    if (reqPage === '1') {
      return route.fulfill({
        json: listResponse([item('app-2', '세 번째 브랜드')]),
      })
    }
    return route.fulfill({
      json: listResponse([item('app-1', '길동상점')], true),
    })
  })

  await loginFromAdminRedirect(page)
  await expect(page.getByRole('button', { name: /길동상점/ })).toBeVisible()
  await page.getByRole('button', { name: '다음' }).click()
  await expect(page).toHaveURL(/status=PENDING.*page=1/)
  await expect(
    page.getByRole('button', { name: /세 번째 브랜드/ }),
  ).toBeVisible()
  await page.getByRole('button', { name: '승인', exact: true }).click()
  await expect(page).toHaveURL(/status=APPROVED.*page=0/)
  await expect(page.getByRole('button', { name: /오늘의 공방/ })).toBeVisible()
})

test('승인은 두 번 눌러 확정하고 성공하면 목록을 다시 불러온다', async ({
  page,
}) => {
  let approved = false
  await page.route('**/api/v1/admin/seller-applications*', (route) =>
    route.fulfill({
      json: listResponse(approved ? [] : [item('app-1', '길동상점')]),
    }),
  )
  await page.route(
    '**/api/v1/admin/seller-applications/app-1/approve',
    (route) => {
      approved = true
      return route.fulfill({
        json: {
          success: true,
          data: {
            applicationId: 'app-1',
            status: 'APPROVED',
            approvedAt: '2026-09-18T15:00:00+09:00',
          },
        },
      })
    },
  )

  await loginFromAdminRedirect(page)
  await page.getByRole('button', { name: /길동상점/ }).click()
  await page.getByRole('button', { name: '승인 확인' }).click()
  await expect(page.getByText(/다시 누르면 확정됩니다/)).toBeVisible()
  await page.getByRole('button', { name: '승인 처리 확정' }).click()
  await expect(page.locator('.toast')).toHaveText('신청을 승인했어요.')
  await expect(page.getByText('조건에 맞는 신청이 없어요.')).toBeVisible()
})

test('반려 사유 누락은 막고, 이미 처리된 신청은 409 안내 후 목록을 다시 불러온다', async ({
  page,
}) => {
  let listCalls = 0
  await page.route('**/api/v1/admin/seller-applications*', (route) => {
    listCalls += 1
    return route.fulfill({ json: listResponse([item('app-1', '길동상점')]) })
  })
  await page.route(
    '**/api/v1/admin/seller-applications/app-1/reject',
    (route) =>
      route.fulfill({
        status: 409,
        json: {
          success: false,
          data: null,
          error: {
            code: 'INVALID_STATE_TRANSITION',
            message: '허용되지 않은 상태 전이입니다.',
          },
        },
      }),
  )

  await loginFromAdminRedirect(page)
  await page.getByRole('button', { name: /길동상점/ }).click()
  const callsBeforeSubmit = listCalls
  await page.getByRole('button', { name: '반려 확인' }).click()
  // 사유 없이 제출하면 브라우저가 막아 네트워크 요청으로 이어지지 않는다.
  await expect(page.getByRole('button', { name: /길동상점/ })).toBeVisible()
  expect(listCalls).toBe(callsBeforeSubmit)

  await page.getByLabel('반려 사유 (필수)').fill('정보 부족')
  await page.getByRole('button', { name: '반려 확인' }).click()
  await page.getByRole('button', { name: '반려 처리 확정' }).click()
  await expect(page.getByRole('alert')).toHaveText(
    '이미 처리된 신청이에요. 목록을 다시 불러올게요.',
  )
  await expect.poll(() => listCalls).toBeGreaterThan(callsBeforeSubmit)
})
