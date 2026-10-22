import { expect, test } from '../../fixtures'
import { getPublicApiState } from '../../mock/api'

for (const reduced of [false, true]) {
  test(`재고 전환은 최신 스냅샷을 표시한다: reduced motion ${reduced}`, async ({
    page,
  }) => {
    await page.emulateMedia({
      reducedMotion: reduced ? 'reduce' : 'no-preference',
    })
    await page.goto('/#/drops/201')
    const stock = page.locator('.detail-state .detail-stock')
    await expect(stock.locator('.detail-stock-current')).toHaveText('19')
    await expect(stock.locator('.detail-stock-previous')).toHaveCount(0)
    await expect
      .poll(() =>
        page.evaluate(() => window.__stockStream.sources.at(-1)?.readyState),
      )
      .toBe(1)
    // 전환 중 DOM과 연속 갱신을 검증할 수 있게 애니메이션 시작 시 일시 정지한다.
    await page.evaluate(() =>
      document.addEventListener('animationstart', (event) => {
        if (event.animationName === 'detail-stock-in') {
          ;(event.target as Element)
            .getAnimations()
            .forEach((animation) => animation.pause())
        }
      }),
    )
    const drop = getPublicApiState(page).drops.find(
      (item) => item.dropId === 201,
    )!
    for (const quantity of [10, 100, 0]) {
      drop.options.forEach((option, index) => {
        option.availableStock = index === 0 ? quantity : 0
      })
      // 0 재고에서는 서버 actions가 구매 영역을 제거하므로 숫자 검증은 stocks만 갱신한다.
      await page.evaluate(() =>
        window.__stockStream.emit('drop-changed', {
          dropId: 201,
          changes: ['SALES'],
        }),
      )
      await expect(stock.locator('.detail-stock-current')).toHaveText(
        String(quantity),
      )
      await expect(stock).toHaveAttribute('data-low', String(quantity <= 10))
      const previous = stock.locator('.detail-stock-previous')
      if (reduced) {
        await expect(previous).toBeHidden()
        await expect(stock.locator('.detail-stock-current')).toHaveCSS(
          'animation-name',
          'none',
        )
      } else {
        await expect(previous).toHaveText(
          String(quantity === 10 ? 19 : quantity === 100 ? 10 : 100),
        )
        await expect(previous).toHaveAttribute('aria-hidden', 'true')
        await expect(stock.locator('.detail-stock-current')).toHaveCSS(
          'animation-duration',
          '0.24s',
        )
      }
    }
    if (!reduced) {
      await stock
        .locator('.detail-stock-current')
        .evaluate((element) =>
          element.getAnimations().forEach((animation) => animation.finish()),
        )
      await expect(stock.locator('.detail-stock-previous')).toHaveCount(0)
    }
    await page.goto('/#/drops/202')
    await expect(page.locator('.detail-stock-previous')).toHaveCount(0)
  })
}
