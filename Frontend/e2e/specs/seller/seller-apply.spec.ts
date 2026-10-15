import { expect, test } from '../../fixtures'

const DOCUMENT_LABELS = [
  '사업자등록증 사본',
  '신분증 사본',
  '통장 사본',
  '통신판매업신고증',
]
const DOCUMENT_FILE = 'src/assets/images/grab-symbol.png'

test('비회원은 신청 조회 없이 로그인으로 이동한다', async ({ page }) => {
  let requests = 0
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === '/api/v1/seller-applications/me')
      requests += 1
  })
  await page.goto('/#/seller/apply')
  await expect(page).toHaveURL(/#\/login$/)
  expect(requests).toBe(0)
})

async function loginFromApplyRedirect(page: import('@playwright/test').Page) {
  await page.goto('/#/seller/apply')
  await expect(page).toHaveURL(/#\/login$/)
  await page.getByLabel('이메일').fill('grab@example.com')
  await page.getByLabel('비밀번호').fill('password1234')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).toHaveURL(/#\/seller\/apply$/)
}

test('미신청 상태에서 서류 4종을 올리고 제출하면 PENDING으로 전환된다', async ({
  page,
}) => {
  let applied = false
  await page.route('**/api/v1/seller-applications/me', (route) =>
    applied
      ? route.fulfill({
          json: {
            success: true,
            data: {
              applicationId: 'app-1',
              brandName: '길동상점',
              contactEmail: 'seller@example.com',
              businessRegistrationNumber: '1234567890',
              description: '한정판 상품 판매점',
              status: 'PENDING',
              appliedAt: '2026-10-01T10:00:00+09:00',
            },
          },
        })
      : route.fulfill({
          status: 404,
          json: {
            success: false,
            data: null,
            error: { code: 'SELLER_APPLICATION_NOT_FOUND', message: '없음' },
          },
        }),
  )
  await page.route('**/api/v1/seller-documents*', (route) =>
    route.fulfill({
      status: 201,
      json: {
        success: true,
        data: { documentId: 'doc-1', documentType: 'ID_CARD' },
      },
    }),
  )
  await page.route('**/api/v1/seller-applications', (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    applied = true
    return route.fulfill({
      status: 201,
      json: {
        success: true,
        data: {
          applicationId: 'app-1',
          brandName: '길동상점',
          status: 'PENDING',
          appliedAt: '2026-10-01T10:00:00+09:00',
        },
      },
    })
  })

  await loginFromApplyRedirect(page)
  await page.getByLabel('브랜드명').fill('길동상점')
  await page.getByLabel('연락 이메일').fill('seller@example.com')
  await page.getByLabel('사업자등록번호').fill('1234567890')
  await page.getByLabel('브랜드 소개').fill('한정판 상품 판매점')
  for (const label of DOCUMENT_LABELS) {
    await page.getByLabel(label).setInputFiles(DOCUMENT_FILE)
  }
  await expect(page.getByText('업로드 완료')).toHaveCount(4)

  const submit = page.getByRole('button', { name: '판매자 신청하기' })
  await expect(submit).toBeEnabled()
  await submit.click()
  await expect(page.getByText('심사를 기다리고 있어요.')).toBeVisible()
})

test('반려 상태에서 재신청 폼을 보여주고 중복 신청 오류를 안내한다', async ({
  page,
}) => {
  await page.route('**/api/v1/seller-applications/me', (route) =>
    route.fulfill({
      json: {
        success: true,
        data: {
          applicationId: 'app-2',
          brandName: '메이드 스튜디오',
          contactEmail: 'made@example.com',
          businessRegistrationNumber: '1234567890',
          description: '맞춤 상품',
          status: 'REJECTED',
          appliedAt: '2026-10-01T10:00:00+09:00',
        },
      },
    }),
  )
  await page.route('**/api/v1/seller-documents*', (route) =>
    route.fulfill({
      status: 201,
      json: {
        success: true,
        data: { documentId: 'doc-2', documentType: 'ID_CARD' },
      },
    }),
  )
  await page.route('**/api/v1/seller-applications', (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    return route.fulfill({
      status: 409,
      json: {
        success: false,
        data: null,
        error: {
          code: 'SELLER_APPLICATION_ALREADY_EXISTS',
          message: '이미 접수된 신청이 있습니다.',
        },
      },
    })
  })

  await loginFromApplyRedirect(page)
  await expect(page.getByText('REJECTED')).toBeVisible()
  await expect(
    page.getByText(
      '신청이 반려됐어요. 서류와 정보를 확인한 뒤 다시 신청해주세요.',
    ),
  ).toBeVisible()

  await page.getByLabel('브랜드명').fill('메이드 스튜디오')
  await page.getByLabel('연락 이메일').fill('made@example.com')
  await page.getByLabel('사업자등록번호').fill('1234567890')
  for (const label of DOCUMENT_LABELS) {
    await page.getByLabel(label).setInputFiles(DOCUMENT_FILE)
  }
  await page.getByRole('button', { name: '판매자 신청하기' }).click()
  await expect(page.getByText('이미 접수된 신청이 있어요.')).toBeVisible()
})

test('신청 조회 실패를 안내한다', async ({ page }) => {
  await page.route('**/api/v1/seller-applications/me', (route) =>
    route.fulfill({
      status: 500,
      json: {
        success: false,
        data: null,
        error: { code: 'INTERNAL_ERROR', message: '조회 실패' },
      },
    }),
  )

  await loginFromApplyRedirect(page)
  await expect(
    page.getByText('신청 정보를 불러오지 못했어요. 다시 시도해주세요.'),
  ).toBeVisible()
})
