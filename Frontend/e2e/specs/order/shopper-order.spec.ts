import { expect, login, test, toast } from '../../fixtures'

test.describe('상품 주문', () => {
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

  test('옵션을 고르면 주문을 만들고 결제한 뒤 내 주문에서 확인한다', async ({
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
    await page.getByRole('button', { name: 'GRAB 하기 ↗' }).click()

    // 상품 상세는 주문을 만들지 않고 결제 화면으로 옵션·수량만 넘긴다.
    await expect(page).toHaveURL(
      /#\/checkout\?dropId=201&optionId=2012&quantity=2$/,
    )
    // 43,000원 × 2 + 배송비 3,000원
    await expect(page.locator('.buyer-total')).toContainText('89,000원')
    await page.getByLabel('받는 분').fill('김그랩')
    await page.getByLabel('연락처').fill('01012345678')
    await page.getByLabel('우편번호').fill('06236')
    await page.getByLabel('기본 주소').fill('서울시 강남구')
    await page.getByRole('button', { name: '주문하기' }).click()
    await expect(toast(page)).toHaveText(
      '주문을 만들었어요. 결제를 진행해주세요.',
    )
    await page.getByRole('button', { name: '결제하기' }).click()
    await expect(page.locator('.buyer-result')).toContainText('결제 완료')

    await page.getByRole('link', { name: '주문 상세로 이동 ↗' }).click()
    await expect(page.locator('.buyer-status')).toHaveText('결제 완료')
    await page.getByRole('link', { name: '← 내 주문 목록' }).click()
    await expect(page.locator('.compact-item.order').first()).toContainText(
      '뮤직 클럽 티셔츠 · 리미티드',
    )
    await expect(page.locator('.compact-item.order').first()).toContainText(
      '결제 완료',
    )
  })
})
