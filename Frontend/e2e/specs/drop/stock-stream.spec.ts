import { expect, test, type Page } from '../../fixtures'
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
  await expect(page.getByRole('alert')).toHaveCount(0)
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
      page.evaluate(
        () =>
          window.__stockStream.sources.filter(
            (source) => source.readyState !== 2,
          ).length,
      ),
    )
    .toBe(1)
  await expect
    .poll(() =>
      page.evaluate(() =>
        window.__stockStream.sources
          .filter((source) => source.readyState !== 2)
          .flatMap((source) =>
            (
              new URL(source.url, location.href).searchParams.get('dropIds') ??
              ''
            )
              .split(',')
              .filter(Boolean),
          ),
      ),
    )
    .not.toContain('102')
})

const DROP_NAME = '뮤직 클럽 티셔츠 · 리미티드'
const DETAIL_PATH = '/api/v1/drops/201'
const STOCKS_PATH = '/api/v1/drops/201/stocks'

function waitForStatus(page: Page, path: string, status: number) {
  return page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === path && response.status() === status,
  )
}

async function installFailureRoutes(
  page: Page,
  failure: { detail: boolean; stocks: boolean },
) {
  await page.route('**/api/v1/drops/201', (route) =>
    failure.detail
      ? route.fulfill({ status: 500, json: { success: false } })
      : route.fallback(),
  )
  await page.route('**/api/v1/drops/201/stocks', (route) =>
    failure.stocks
      ? route.fulfill({ status: 500, json: { success: false } })
      : route.fallback(),
  )
}

async function openDropDetail(page: Page) {
  await page.goto('/#/drops/201')
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(DROP_NAME)
  await page.getByLabel('착용감').selectOption({ label: '릴랙스' })
  await page.getByLabel('소매').selectOption({ label: '롱' })
  await expect(page.locator('.sku-result')).toContainText('재고 5개')
  await expect
    .poll(() =>
      page.evaluate(() => window.__stockStream.sources.at(-1)?.readyState),
    )
    .toBe(1)
  await page.waitForLoadState('networkidle')
}

async function emitStockChange(page: Page) {
  await page.evaluate(() =>
    window.__stockStream.emit('drop-changed', {
      dropId: 201,
      changes: ['STOCK'],
    }),
  )
}

function option2012(page: Page) {
  return getPublicApiState(page)
    .drops.find((drop) => drop.dropId === 201)!
    .options.find((item) => item.optionId === 2012)!
}

test('상세와 재고 갱신이 모두 실패하면 안내하고 재시도로 최신 재고를 반영한다', async ({
  page,
}) => {
  const failure = { detail: false, stocks: false }
  await installFailureRoutes(page, failure)
  await openDropDetail(page)
  await page.getByLabel('수량').fill('2')

  failure.detail = true
  failure.stocks = true
  const detailFailed = waitForStatus(page, DETAIL_PATH, 500)
  const stocksFailed = waitForStatus(page, STOCKS_PATH, 500)
  await emitStockChange(page)
  await Promise.all([detailFailed, stocksFailed])

  const alert = page.getByRole('alert')
  await expect(alert).toHaveCount(1)
  await expect(alert).toContainText('최신 상품 정보를 불러오지 못했어요.')
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(DROP_NAME)
  await expect(page.getByLabel('수량')).toHaveValue('2')
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true)
  const retry = page.getByRole('button', { name: '다시 시도' })
  await retry.focus()
  await expect(retry).toBeFocused()

  option2012(page).availableStock = 4
  failure.detail = false
  failure.stocks = false
  const detailRecovered = waitForStatus(page, DETAIL_PATH, 200)
  const stocksRecovered = waitForStatus(page, STOCKS_PATH, 200)
  await page.getByRole('button', { name: '다시 시도' }).click()
  await Promise.all([detailRecovered, stocksRecovered])

  await expect(alert).toHaveCount(0)
  await expect(page.locator('.sku-result')).toContainText('재고 4개')
  await expect(page.getByLabel('수량')).toHaveValue('2')
  await expect(page.locator('.buy-form')).toBeVisible()
})

test('상세만 갱신에 실패해도 재고 결과와 안내를 함께 표시한다', async ({
  page,
}) => {
  const failure = { detail: false, stocks: false }
  await installFailureRoutes(page, failure)
  await openDropDetail(page)

  option2012(page).availableStock = 7
  failure.detail = true
  const detailFailed = waitForStatus(page, DETAIL_PATH, 500)
  const stocksSucceeded = waitForStatus(page, STOCKS_PATH, 200)
  await emitStockChange(page)
  await Promise.all([detailFailed, stocksSucceeded])

  await expect(page.getByRole('alert')).toContainText(
    '최신 상품 정보를 불러오지 못했어요.',
  )
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(DROP_NAME)
  await expect(page.locator('.sku-result')).toContainText('재고 7개')
})

test('재고만 갱신에 실패해도 기존 상세 화면과 안내를 유지한다', async ({
  page,
}) => {
  const failure = { detail: false, stocks: false }
  await installFailureRoutes(page, failure)
  await openDropDetail(page)

  failure.stocks = true
  const detailSucceeded = waitForStatus(page, DETAIL_PATH, 200)
  const stocksFailed = waitForStatus(page, STOCKS_PATH, 500)
  await emitStockChange(page)
  await Promise.all([detailSucceeded, stocksFailed])

  await expect(page.getByRole('alert')).toContainText(
    '최신 상품 정보를 불러오지 못했어요.',
  )
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(DROP_NAME)
  await expect(page.locator('.sku-result')).toContainText('재고 5개')
})

test('한쪽 조회만 회복하면 안내를 유지하고 모두 회복해야 지운다', async ({
  page,
}) => {
  const failure = { detail: false, stocks: false }
  await installFailureRoutes(page, failure)
  await openDropDetail(page)

  failure.detail = true
  failure.stocks = true
  const firstDetailFailed = waitForStatus(page, DETAIL_PATH, 500)
  const firstStocksFailed = waitForStatus(page, STOCKS_PATH, 500)
  await emitStockChange(page)
  await Promise.all([firstDetailFailed, firstStocksFailed])
  const alert = page.getByRole('alert')
  await expect(alert).toHaveCount(1)

  failure.detail = false
  const detailRecovered = waitForStatus(page, DETAIL_PATH, 200)
  const stocksStillFailed = waitForStatus(page, STOCKS_PATH, 500)
  const retry = page.getByRole('button', { name: '다시 시도' })
  await retry.click()
  await Promise.all([detailRecovered, stocksStillFailed])
  await expect(alert).toHaveCount(1)

  await expect(retry).toBeEnabled()
  failure.stocks = false
  const detailRecoveredAgain = waitForStatus(page, DETAIL_PATH, 200)
  const stocksRecovered = waitForStatus(page, STOCKS_PATH, 200)
  await retry.click()
  await Promise.all([detailRecoveredAgain, stocksRecovered])
  await expect(alert).toHaveCount(0)
})

test('연속 재시도는 같은 키 요청을 2초 간격으로 합친다', async ({ page }) => {
  const failure = { detail: false, stocks: false }
  const detailStarts: number[] = []
  await page.route('**/api/v1/drops/201', (route) => {
    if (!failure.detail) return route.fallback()
    detailStarts.push(Date.now())
    return route.fulfill({ status: 500, json: { success: false } })
  })
  await page.route('**/api/v1/drops/201/stocks', (route) =>
    failure.stocks
      ? route.fulfill({ status: 500, json: { success: false } })
      : route.fallback(),
  )
  await openDropDetail(page)

  failure.detail = true
  failure.stocks = true
  const stockFailed = waitForStatus(page, STOCKS_PATH, 500)
  await emitStockChange(page)
  await stockFailed
  const alert = page.getByRole('alert')
  await expect(alert).toBeVisible()

  detailStarts.length = 0
  const retry = page.getByRole('button', { name: '다시 시도' })
  const firstRetry = waitForStatus(page, DETAIL_PATH, 500)
  await retry.click()
  await firstRetry
  await expect(retry).toBeEnabled()

  const secondRetry = waitForStatus(page, DETAIL_PATH, 500)
  await retry.click()
  await secondRetry
  expect(detailStarts).toHaveLength(2)
  expect(detailStarts[1] - detailStarts[0]).toBeGreaterThanOrEqual(1900)
})

test('최초 상세 조회 실패는 전체 오류 화면과 재시도를 유지한다', async ({
  page,
}) => {
  const failure = { detail: true, stocks: true }
  await installFailureRoutes(page, failure)
  await page.goto('/#/drops/201')
  await expect(page.getByText('상품 정보를 표시하지 못했어요.')).toBeVisible()
  await expect(page.getByRole('alert')).toContainText(
    '상품 정보를 표시하지 못했어요.',
  )
  await expect(page.locator('.detail-layout')).toHaveCount(0)

  failure.detail = false
  failure.stocks = false
  const detailRecovered = waitForStatus(page, DETAIL_PATH, 200)
  const stocksRecovered = waitForStatus(page, STOCKS_PATH, 200)
  await page.getByRole('button', { name: '다시 시도' }).click()
  await Promise.all([detailRecovered, stocksRecovered])

  await expect(page.getByRole('heading', { level: 1 })).toHaveText(DROP_NAME)
  await expect(page.getByRole('alert')).toHaveCount(0)
  await expect(page.locator('.detail-layout')).toBeVisible()
})

test('목록은 현재 페이지 ID를 한 연결로 구독하고 재고 변경을 목록 재조회로 반영한다', async ({
  page,
}) => {
  await page.goto('/#/grab')
  const cards = page.locator('.product-card')
  await expect(cards.first()).toBeVisible()
  await expect
    .poll(() =>
      page.evaluate(
        () =>
          window.__stockStream.sources.filter(
            (source) => source.readyState !== 2,
          ).length,
      ),
    )
    .toBe(1)
  await expect
    .poll(() =>
      page.evaluate(
        () =>
          window.__stockStream.sources.filter(
            (source) => source.readyState !== 2,
          )[0]?.readyState,
      ),
    )
    .toBe(1)

  const sourceIds = await page.evaluate(() => {
    const source = window.__stockStream.sources.find(
      (item) => item.readyState !== 2,
    )
    return (
      new URL(source!.url, location.href).searchParams.get('dropIds') ?? ''
    )
      .split(',')
      .filter(Boolean)
      .map(Number)
      .sort((a, b) => a - b)
  })
  const cardIds = await cards
    .locator('a[href*="/drops/"]')
    .evaluateAll((links) =>
      [
        ...new Set(
          links.map((link) =>
            Number((link.getAttribute('href') ?? '').split('/').pop()),
          ),
        ),
      ].sort((a, b) => a - b),
    )
  expect(sourceIds).toEqual(cardIds)

  const state = getPublicApiState(page)
  const drop = state.drops.find(
    (item) =>
      cardIds.includes(item.dropId) &&
      item.options.some((option) => option.availableStock > 0),
  )!
  const card = page.locator('.product-card').filter({
    has: page.locator(`a[href$="/drops/${drop.dropId}"]`),
  })
  for (const option of drop.options) option.availableStock = 0
  const refreshed = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/drops' &&
      response.status() === 200,
  )
  await page.evaluate(
    (dropId) =>
      window.__stockStream.emit('drop-changed', {
        dropId,
        changes: ['STOCK'],
      }),
    drop.dropId,
  )
  await refreshed

  await expect(card).toContainText('SOLD OUT')
  await expect(
    card.getByRole('link', { name: /품절 · 상세 보기/ }),
  ).toBeVisible()
  await expect
    .poll(() =>
      page.evaluate(
        () =>
          window.__stockStream.sources.filter(
            (source) => source.readyState !== 2,
          ).length,
      ),
    )
    .toBe(1)
})
