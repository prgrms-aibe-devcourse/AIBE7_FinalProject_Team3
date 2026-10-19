import { expect, test } from '../../fixtures'
import { getPublicApiState } from '../../mock/api'

test('최초 조회 뒤 ready가 오면 빠진 변경을 다시 조회한다', async ({
  page,
}) => {
  await page.goto('/#/grab')
  await page.evaluate(() => {
    window.__stockStream.autoReady = false
    location.hash = '#/drops/201'
  })
  await page.getByLabel('착용감').selectOption({ label: '릴랙스' })
  await page.getByLabel('소매').selectOption({ label: '롱' })
  await expect(page.locator('.sku-result')).toContainText('재고 5개')

  getPublicApiState(page)
    .drops.find((drop) => drop.dropId === 201)!
    .options.find((item) => item.optionId === 2012)!.availableStock = 0
  await page.evaluate(() =>
    window.__stockStream.emit('ready', {
      dropIds: [201],
      serverTime: new Date().toISOString(),
    }),
  )
  await expect(page.locator('.sku-result')).toContainText('품절')
})

test('ready 뒤 재고 변경을 반영하고 선택과 수량을 유지한다', async ({
  page,
}) => {
  await page.goto('/#/drops/201')
  await page.getByLabel('착용감').selectOption({ label: '릴랙스' })
  await page.getByLabel('소매').selectOption({ label: '롱' })
  await page.getByLabel('수량').fill('2')
  await expect(page.locator('.sku-result')).toContainText('재고 5개')
  await expect
    .poll(() =>
      page.evaluate(() => window.__stockStream.sources.at(-1)?.readyState),
    )
    .toBe(1)

  const option = getPublicApiState(page)
    .drops.find((drop) => drop.dropId === 201)!
    .options.find((item) => item.optionId === 2012)!
  option.availableStock = 0
  await page.evaluate(() =>
    window.__stockStream.emit('drop-changed', {
      dropId: 201,
      changes: ['STOCK'],
    }),
  )
  await expect(page.locator('.sku-result')).toContainText('품절')
  await expect(
    page.getByRole('button', { name: '선택 옵션 품절' }),
  ).toBeDisabled()
  await expect(page.getByLabel('수량')).toHaveValue('2')

  option.availableStock = 3
  await page.evaluate(() =>
    window.__stockStream.emit('drop-changed', {
      dropId: 201,
      changes: ['STOCK'],
    }),
  )
  await expect(page.locator('.sku-result')).toContainText('재고 3개')
  await expect(page.getByLabel('수량')).toHaveValue('2')
})

test('연결 오류 뒤 재연결하여 변경을 다시 받는다', async ({ page }) => {
  await page.goto('/#/drops/201')
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(
    '뮤직 클럽 티셔츠 · 리미티드',
  )
  const originalCount = await page.evaluate(
    () => window.__stockStream.sources.length,
  )
  await page.evaluate(() => window.__stockStream.error())
  await expect
    .poll(() =>
      page.evaluate(
        () => window.__stockStream.sources.at(-1)?.readyState === 2,
      ),
    )
    .toBe(true)
  await expect
    .poll(() => page.evaluate(() => window.__stockStream.sources.length), {
      timeout: 10_000,
    })
    .toBeGreaterThan(originalCount)
  await expect
    .poll(() =>
      page.evaluate(() => window.__stockStream.sources.at(-1)?.readyState),
    )
    .toBe(1)

  const option = getPublicApiState(page)
    .drops.find((drop) => drop.dropId === 201)!
    .options.find((item) => item.optionId === 2012)!
  option.availableStock = 2
  await page.getByLabel('착용감').selectOption({ label: '릴랙스' })
  await page.getByLabel('소매').selectOption({ label: '롱' })
  await page.evaluate(() =>
    window.__stockStream.emit('drop-changed', {
      dropId: 201,
      changes: ['STOCK'],
    }),
  )
  await expect(page.locator('.sku-result')).toContainText('재고 2개')
})

test('상품 이동과 화면 이탈 때 이전 연결을 닫는다', async ({ page }) => {
  await page.goto('/#/drops/201')
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(
    '뮤직 클럽 티셔츠 · 리미티드',
  )
  await page.evaluate(() => {
    location.hash = '#/drops/102'
  })
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(
    '나의 작은 휴식, 데일리 머그',
  )
  await expect
    .poll(() =>
      page.evaluate(() =>
        window.__stockStream.sources
          .filter((source) => source.readyState !== 2)
          .map((source) =>
            new URL(source.url, location.href).searchParams.get('dropIds'),
          ),
      ),
    )
    .toEqual(['102'])
  await page.evaluate(() => {
    location.hash = '#/grab'
  })
  await expect(page).toHaveURL(/#\/grab$/)
  await expect
    .poll(() =>
      page.evaluate(() =>
        window.__stockStream.sources.every((source) => source.readyState === 2),
      ),
    )
    .toBe(true)
})
