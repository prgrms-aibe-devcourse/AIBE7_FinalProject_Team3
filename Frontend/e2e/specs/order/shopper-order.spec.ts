import { expect, login, test, toast } from '../../fixtures'

test.describe('데모 주문', () => {
  test('로그인 전 주문은 로그인 화면으로 보낸다', async ({ page }) => {
    await page.goto('/#/drops/201')
    await page.getByLabel('착용감').selectOption({ label: '릴랙스' })
    await page.getByLabel('소매').selectOption({ label: '롱' })
    await page.getByRole('button', { name: '로그인 후 GRAB ↗' }).click()

    await expect(page).toHaveURL(/#\/login$/)
    await expect(toast(page)).toHaveText('로그인 후 주문할 수 있어요.')
  })

  test('품절 옵션은 주문할 수 없다', async ({ page }) => {
    await page.goto('/#/drops/201')
    await page.getByLabel('착용감').selectOption({ label: '와이드' })
    await page.getByLabel('소매').selectOption({ label: '롱' })

    await expect(page.getByText('품절', { exact: true })).toBeVisible()
    await expect(
      page.getByRole('button', { name: '선택 옵션 품절' }),
    ).toBeDisabled()
  })

  // 주문 생성·결제는 GR-118 전까지 Mock이라 실제 주문 상세·목록(GR-117)과 연결하지 않고 결제 결과까지만 확인한다.
  test('옵션을 고르면 결제 화면으로 이동해 결제 결과를 확인한다', async ({
    page,
  }) => {
    await login(page)
    await page.getByRole('link', { name: 'GRAB', exact: true }).click()
    await page
      .getByRole('heading', { name: '뮤직 클럽 티셔츠 · 리미티드' })
      .getByRole('link')
      .click()

    await page.getByLabel('착용감').selectOption({ label: '릴랙스' })
    await page.getByLabel('소매').selectOption({ label: '롱' })
    await expect(page.getByText('재고 5개')).toBeVisible()
    await page.getByLabel('수량').fill('2')
    await expect(page.locator('.total-row')).toContainText('86,000원')
    await page.getByRole('button', { name: '데모 GRAB 하기 ↗' }).click()

    await expect(page).toHaveURL(/#\/checkout\/GR-/)
    await expect(toast(page)).toHaveText(
      'Mock 주문을 만들었어요. 배송지와 결제 결과를 확인해주세요.',
    )
    await expect(page.locator('.buyer-total')).toContainText('89,000원')
    await page.getByLabel('받는 분').fill('김그랩')
    await page.getByLabel('연락처').fill('01012345678')
    await page.getByLabel('우편번호').fill('06236')
    await page.getByLabel('기본 주소').fill('서울시 강남구')
    await page.getByRole('button', { name: 'Mock 결제 결과 보기' }).click()
    await expect(page.getByText('결제 성공 예시')).toBeVisible()
  })
})
