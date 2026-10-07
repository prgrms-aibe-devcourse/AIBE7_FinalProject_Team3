import { expect, test, type Page } from './fixtures'

// 기준 이미지는 CI와 같은 Playwright Docker 이미지에서만 만든다(npm run e2e:docker).
// 판매자 대시보드·판매자 DROP 상세는 디자인 개선 예정이라 기준에서 제외한다.
test.describe('화면 기준', { tag: '@visual' }, () => {
  const shot = async (page: Page, name: string) => {
    await page.evaluate(() => document.fonts.ready)
    await expect(page).toHaveScreenshot(`${name}.png`, { fullPage: true })
  }

  for (const [name, path] of [
    ['wish-list', '/#/wish'],
    ['grab-list', '/#/grab'],
    ['wish-detail', '/#/drops/102'],
    ['grab-detail', '/#/drops/201'],
    ['login', '/#/login'],
    ['seller-orders', '/#/seller/orders'],
  ]) {
    test(name, async ({ page }) => {
      await page.goto(path)
      await shot(page, name)
    })
  }

  test('seller-orders-detail', async ({ page }) => {
    await page.goto('/#/seller/orders')
    await page
      .getByRole('button', { name: 'ORD-20260918-000500 주문 상세' })
      .click()
    await shot(page, 'seller-orders-detail')
  })
})
