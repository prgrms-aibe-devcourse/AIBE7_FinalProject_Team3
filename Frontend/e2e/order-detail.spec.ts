import { expect, test, type Page } from './fixtures'

async function openOrder(page: Page, id: string) {
  await page.goto(`/#/my/orders/${id}`)
  await expect(page).toHaveURL(/#\/login$/)
  await page.getByLabel('이메일').fill('grab@example.com')
  await page.getByLabel('비밀번호').fill('password1234')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`#/my/orders/${id}$`))
}

test('직접 진입한 결제 대기 주문에서 결제 화면으로 이동한다', async ({
  page,
}) => {
  await openOrder(page, 'demo-pending')
  await expect(page.getByText('결제 대기', { exact: true })).toBeVisible()
  await expect(page.getByText('송장이 등록되면')).toBeVisible()
  await page.getByRole('link', { name: '결제 화면으로 이동 ↗' }).click()
  await expect(page).toHaveURL(/#\/checkout\/demo-pending$/)
})

test('결과 확인 중에는 재결제와 취소를 제공하지 않는다', async ({ page }) => {
  await openOrder(page, 'demo-unknown')
  await expect(page.getByText('결제 결과를 확인 중입니다.')).toBeVisible()
  await expect(
    page.getByText('결제 결과를 확인 중이라 취소할 수 없습니다.'),
  ).toBeVisible()
  await expect(
    page.getByRole('link', { name: '결제 화면으로 이동 ↗' }),
  ).toHaveCount(0)
  await expect(page.getByLabel('취소 사유 (필수)')).toHaveCount(0)
})

test('배송·종료 상태에 맞는 정보와 취소 가능 여부를 보여준다', async ({
  page,
}) => {
  await openOrder(page, 'demo-shipped')
  await expect(page.getByText('123456789012')).toBeVisible()
  await expect(page.getByLabel('취소 사유 (필수)')).toHaveCount(0)
  await page.getByRole('link', { name: '← 내 주문 목록' }).click()
  await expect(page).toHaveURL(/#\/my$/)

  await page.goto('/#/my/orders/demo-delivered')
  await expect(
    page.getByText('배송 완료', { exact: true }).first(),
  ).toBeVisible()
  await page.goto('/#/my/orders/demo-canceled')
  await expect(page.getByText('단순 변심')).toBeVisible()
  await page.goto('/#/my/orders/demo-expired')
  await expect(page.getByText('주문 만료')).toBeVisible()
})

test('취소 사유 확인 뒤 성공·실패·결과 불명을 구분한다', async ({ page }) => {
  await openOrder(page, 'demo-paid')
  await page.getByLabel('취소 사유 (필수)').fill('단순 변심')
  await page.getByLabel('취소 결과 예시').selectOption('FAILED')
  await page.getByRole('button', { name: '취소 내용 확인' }).click()
  await expect(page.getByText('단순 변심” 사유로')).toBeVisible()
  await page.getByRole('button', { name: 'Mock 취소 결과 보기' }).click()
  await expect(page.getByRole('alert')).toContainText('결제 취소 실패')
  await expect(page.getByText('결제 완료', { exact: true })).toBeVisible()

  await page.getByLabel('취소 결과 예시').selectOption('UNKNOWN')
  await page.getByRole('button', { name: '취소 내용 확인' }).click()
  await page.getByRole('button', { name: 'Mock 취소 결과 보기' }).click()
  await expect(page.getByText('취소 결과 확인 중입니다.')).toBeVisible()
  await expect(page.getByLabel('취소 사유 (필수)')).toHaveCount(0)

  await page.goto('/#/my/orders/demo-preparing')
  await page.getByLabel('취소 사유 (필수)').fill('배송 전 취소')
  await page.getByRole('button', { name: '취소 내용 확인' }).click()
  await page.getByRole('button', { name: 'Mock 취소 결과 보기' }).click()
  await expect(page.getByText('취소 완료', { exact: true })).toBeVisible()
})

test('없는 주문은 찾을 수 없음으로 표시한다', async ({ page }) => {
  await openOrder(page, 'missing')
  await expect(page.getByText('주문을 찾을 수 없어요.')).toBeVisible()
})
