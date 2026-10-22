import { expect, test } from '../../fixtures'
import { getPublicApiState } from '../../mock/api'

test('상태 영역 재고와 모바일 구매 바는 같은 값을 보여준다', async ({
  page,
}) => {
  await page.goto('/#/drops/201')
  const stock = page.locator('.detail-state .detail-stock')
  await expect(stock).toHaveAttribute('data-low', 'false')
  await expect(stock).toContainText('19개')
  await expect(
    page.locator('.detail-summary > .detail-description'),
  ).toHaveCount(0)
  await expect(page.locator('.buy-form button[type="submit"]')).toHaveCount(1)

  const bar = page.locator('.detail-purchase')
  const mobile = page.viewportSize()!.width <= 720
  await expect(bar).toHaveCSS('position', mobile ? 'fixed' : 'static')
  if (mobile) {
    await expect(page.locator('.detail-mobile-stock')).toContainText('19개')
    await page.locator('.detail-shipping').scrollIntoViewIfNeeded()
    const barBox = (await bar.boundingBox())!
    expect(barBox.y + barBox.height).toBeLessThanOrEqual(
      page.viewportSize()!.height,
    )
    await page.locator('footer').scrollIntoViewIfNeeded()
    const footerBox = (await page.locator('footer').boundingBox())!
    expect(footerBox.y + footerBox.height).toBeLessThanOrEqual(barBox.y)
  }

  getPublicApiState(page)
    .drops.find((drop) => drop.dropId === 201)!
    .options.forEach((option, index) => {
      option.availableStock = index === 0 ? 10 : 0
    })
  await page.evaluate(() =>
    window.__stockStream.emit('drop-changed', {
      dropId: 201,
      changes: ['STOCK'],
    }),
  )
  await expect(stock).toContainText('10개')
  await expect(stock).toHaveAttribute('data-low', 'true')
  await expect(stock.locator('.detail-stock-value')).toHaveCSS(
    'color',
    'rgb(200, 73, 62)',
  )
})

test('WISH는 정확한 오픈 시각을 표시하고 구매 바를 숨긴다', async ({
  page,
}) => {
  await page.goto('/#/drops/102')
  await expect(page.locator('.detail-open-time time')).toHaveAttribute(
    'datetime',
    /T/,
  )
  await expect(page.locator('.detail-open-time')).toContainText('오픈')
  await expect(page.locator('.detail-wish-count')).toContainText('WISH')
  await expect(page.locator('.detail-purchase')).toHaveCount(0)
})
