import { expect, loginSeller, test } from '../../fixtures'

test('비회원은 로그인으로, 일반 회원은 권한 안내로 보낸다', async ({
  page,
}) => {
  await page.goto('/#/seller/drops?status=WISH&page=0')
  await expect(page).toHaveURL(/#\/login$/)
  await page.getByLabel('이메일').fill('grab@example.com')
  await page.getByLabel('비밀번호').fill('password1234')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).toHaveURL(/seller\/drops\?status=WISH&page=0/)
  await expect(page.getByText('판매자 권한이 필요해요.')).toBeVisible()
})

test('서버 페이지를 조회하고 목록 조건을 상세·뒤로 가기에 보존한다', async ({
  page,
}) => {
  await loginSeller(page)
  await page.route('**/api/v1/seller/drops?*', (route) => {
    const url = new URL(route.request().url())
    const page = Number(url.searchParams.get('page'))
    const content = page
      ? [{ dropId: 201, name: '뮤직 클럽 티셔츠 · 리미티드', status: 'GRAB' }]
      : Array.from({ length: 20 }, (_, index) => ({
          dropId: index === 0 ? 401 : 2000 + index,
          name: `DROP ${index}`,
          status: 'DRAFT',
        }))
    return route.fulfill({
      json: {
        success: true,
        data: {
          content,
          totalElements: 21,
          totalPages: 2,
          hasNext: page === 0,
        },
      },
    })
  })
  await page.goto('/#/seller/drops?status=ALL&page=0')
  await page.getByRole('button', { name: '다음', exact: true }).click()
  await expect(page).toHaveURL(/status=ALL.*page=1/)
  await expect(page.getByText('2 / 2')).toBeVisible()
  await page.reload()
  await expect(page.getByText('2 / 2')).toBeVisible()
  await page.getByRole('link', { name: '보기 ↗' }).click()
  await expect(page).toHaveURL(/#\/seller\/drops\/201\?status=ALL&page=1/)
  await page.getByRole('link', { name: '← DROP 관리' }).click()
  await expect(page.getByText('2 / 2')).toBeVisible()
})

test('상태 관리로 상세에 들어가도 목록 필터와 페이지를 유지한다', async ({
  page,
}) => {
  await loginSeller(page)
  await page.route('**/api/v1/seller/drops?*', (route) =>
    route.fulfill({
      json: {
        success: true,
        data: {
          content: [{ dropId: 101, name: 'WISH 상품', status: 'WISH' }],
          totalElements: 21,
          totalPages: 2,
          hasNext: false,
        },
      },
    }),
  )
  await page.goto('/#/seller/drops?status=WISH&page=1')
  await page.getByRole('link', { name: '상태 관리', exact: true }).click()
  await expect(page).toHaveURL(/\/seller\/drops\/101\?status=WISH&page=1$/)
  await page.getByRole('link', { name: '← DROP 관리' }).click()
  await expect(page).toHaveURL(/\/seller\/drops\?status=WISH&page=1$/)
  await expect(
    page.getByRole('button', { name: 'WISH', exact: true }),
  ).toHaveAttribute('aria-pressed', 'true')
  await expect(page.getByText('2 / 2')).toBeVisible()
})

test('조회 실패·빈 결과를 구분하고 서버에 저장한 DRAFT가 목록에 표시된다', async ({
  page,
}) => {
  await loginSeller(page)
  let failing = true
  await page.route('**/api/v1/seller/drops?*', (route) =>
    failing
      ? route.fulfill({ status: 500, json: { success: false } })
      : route.fallback(),
  )
  await page.goto('/#/seller/drops')
  await expect(page.getByText('DROP 목록을 불러오지 못했어요.')).toBeVisible()
  failing = false
  await page.getByRole('button', { name: '다시 시도' }).click()
  await expect(
    page.getByRole('link', { name: '오버핏 니트 가디건' }),
  ).toBeVisible()
  await page.route('**/api/v1/seller/drops?*', (route) => {
    if (new URL(route.request().url()).searchParams.get('status') !== 'ENDED')
      return route.fallback()
    return route.fulfill({
      json: {
        success: true,
        data: { content: [], totalElements: 0, totalPages: 0, hasNext: false },
      },
    })
  })
  await page.getByRole('button', { name: 'ENDED', exact: true }).click()
  await expect(page.getByText('해당 상태의 DROP이 없어요.')).toBeVisible()
  await page.getByRole('link', { name: '새 DROP 만들기' }).click()
  await page.getByLabel('상품명').fill('서버에 저장한 DROP')
  await page.getByRole('button', { name: '임시 저장' }).click()
  await expect(page).toHaveURL(/\/drops\/1001\/edit$/)
  await page.getByRole('link', { name: '← DROP STUDIO' }).click()
  await expect(
    page.getByRole('link', { name: '서버에 저장한 DROP' }),
  ).toBeVisible()
  await page.reload()
  await expect(
    page.getByRole('link', { name: '서버에 저장한 DROP' }),
  ).toBeVisible()
})

test('공개 실패는 상태를 유지하고 성공 후 상세에서 실제 취소를 요청한다', async ({
  page,
}) => {
  await loginSeller(page)
  let failed = false
  await page.route('**/api/v1/seller/drops/401/publish', (route) => {
    if (failed) return route.fallback()
    failed = true
    return route.fulfill({ status: 500, json: { success: false } })
  })
  await page.goto('/#/seller/drops?status=DRAFT&page=0')
  const draft = page
    .getByRole('article')
    .filter({ hasText: '오버핏 니트 가디건' })
  await draft.getByRole('button', { name: '공개 확인' }).click()
  await draft.getByRole('button', { name: '공개 확정' }).click()
  await expect(draft.getByRole('alert')).toContainText('처리하지 못했어요')
  await draft.getByRole('button', { name: '공개 확정' }).click()
  await expect(draft).toHaveCount(0)
  await page.getByRole('button', { name: 'WISH', exact: true }).click()
  const wish = page
    .getByRole('article')
    .filter({ hasText: '오버핏 니트 가디건' })
  await wish.getByRole('link', { name: '상태 관리' }).click()
  await page.getByRole('button', { name: '출시 취소' }).click()
  await page
    .getByLabel('취소 사유', { exact: true })
    .fill('상품 공급 일정 변경')
  await page.getByRole('button', { name: '취소 사유 확인' }).click()
  await page.getByRole('button', { name: '취소 확정' }).click()
  await expect(page.locator('.status-pill')).toHaveText('CANCELED')
})
