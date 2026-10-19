import { expect, loginSeller, test } from '../../fixtures'

test('목록의 상태·페이지를 상세 뒤로 가기에서도 유지한다', async ({ page }) => {
  await loginSeller(page)
  await page.goto('/#/seller/drops?status=DRAFT&page=0')
  await page.getByRole('link', { name: '오버핏 니트 가디건' }).click()
  await expect(page).toHaveURL(/seller\/drops\/401\?status=DRAFT&page=0/)
  await expect(
    page.getByRole('heading', { name: '오버핏 니트 가디건' }),
  ).toBeVisible()
  await expect(page.getByRole('link', { name: '수정' })).toHaveAttribute(
    'href',
    '#/seller/drops/401/edit',
  )
  await page.getByRole('link', { name: '← DROP 관리' }).click()
  await expect(page).toHaveURL(/seller\/drops\?status=DRAFT&page=0/)
})

test('상세에서 공개 후 최신 상태를 조회하고 공개 상품의 편집은 막는다', async ({
  page,
}) => {
  await loginSeller(page)
  await page.goto('/#/seller/drops/401')
  await page.getByRole('button', { name: '공개 확인' }).click()
  await page.getByRole('button', { name: '공개 확정' }).click()
  await expect(page.locator('.status-pill')).toHaveText('WISH')
  await expect(page.getByRole('link', { name: '수정' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: '소비자 화면 ↗' })).toBeVisible()
  await page.goto('/#/seller/drops/401/edit')
  await expect(page.getByText('편집할 DRAFT를 찾을 수 없어요.')).toBeVisible()
})

test('WISH 취소와 소유권·재고 오류를 구분한다', async ({ page }) => {
  await loginSeller(page)
  await page.route('**/api/v1/seller/drops/101/stocks', (route) =>
    route.fulfill({ status: 500, json: { success: false } }),
  )
  await page.goto('/#/seller/drops/101')
  await expect(page.getByText('옵션 재고를 불러오지 못했어요.')).toBeVisible()
  await expect(page.getByRole('heading', { name: '상품 정보' })).toBeVisible()
  await page.getByRole('button', { name: '출시 취소' }).click()
  await page
    .getByLabel('취소 사유', { exact: true })
    .fill('상품 공급 일정 변경')
  await page.getByRole('button', { name: '취소 사유 확인' }).click()
  await page.getByRole('button', { name: '취소 확정' }).click()
  await expect(page.locator('.status-pill')).toHaveText('CANCELED')
  await page.goto('/#/seller/drops/999')
  await expect(page.getByText('이 DROP을 관리할 권한이 없어요.')).toBeVisible()
  await page.goto('/#/seller/drops/bad')
  await expect(page.getByText('잘못된 DROP 번호예요.')).toBeVisible()
})

test('종료 DROP의 주문·재고·실적 이동에 ID를 전달한다', async ({ page }) => {
  await loginSeller(page)
  await page.goto('/#/seller/drops/301')
  await expect(
    page.getByRole('link', { name: '이 DROP의 주문 ↗' }),
  ).toHaveAttribute('href', '#/seller/orders?dropId=301')
  await expect(
    page.getByRole('link', { name: '옵션별 재고 ↗' }),
  ).toHaveAttribute('href', '#/seller/inventory?dropId=301')
  await expect(page.getByRole('link', { name: '판매 실적 ↗' })).toHaveAttribute(
    'href',
    '#/seller/sales?dropId=301',
  )
})

test('해당 DROP 주문 링크는 주문 목록에 상품 필터를 적용한다', async ({
  page,
}) => {
  await loginSeller(page)
  await page.goto('/#/seller/drops/201')
  await page.getByRole('link', { name: '이 DROP의 주문 ↗' }).click()
  await expect(page).toHaveURL(/seller\/orders\?dropId=201/)
  await expect(page.getByText('DROP #201 주문')).toBeVisible()
  await expect(
    page.getByRole('button', { name: 'ORD-20260918-000500 주문 상세' }),
  ).toBeVisible()
  await expect(
    page.getByRole('button', { name: 'ORD-20260918-000501 주문 상세' }),
  ).toHaveCount(0)
})
