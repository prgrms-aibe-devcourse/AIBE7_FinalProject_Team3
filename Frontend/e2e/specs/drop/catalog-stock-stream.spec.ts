import { expect, login, test, type Page } from '../../fixtures'
import { getPublicApiState } from '../../mock/api'

const LIST_PATH = '/api/v1/drops'
const DROP_NAME = '뮤직 클럽 티셔츠 · 리미티드'

function activeSourceIds(page: Page) {
  return page.evaluate(() =>
    window.__stockStream.sources
      .filter((source) => source.readyState !== 2)
      .flatMap((source) =>
        (new URL(source.url, location.href).searchParams.get('dropIds') ?? '')
          .split(',')
          .filter(Boolean)
          .map(Number),
      )
      .sort((a, b) => a - b),
  )
}

function visibleCardIds(page: Page) {
  return page
    .locator('.product-card a[href*="/drops/"]')
    .evaluateAll((links) =>
      [
        ...new Set(
          links.map((link) =>
            Number((link.getAttribute('href') ?? '').split('/').pop()),
          ),
        ),
      ].sort((a, b) => a - b),
    )
}

async function waitForActiveConnection(page: Page) {
  await expect
    .poll(
      () =>
        page.evaluate(
          () =>
            window.__stockStream.sources.filter(
              (source) => source.readyState !== 2,
            ).length,
        ),
      { timeout: 15000 },
    )
    .toBe(1)
  await expect
    .poll(
      () =>
        page.evaluate(
          () =>
            window.__stockStream.sources.filter(
              (source) => source.readyState !== 2,
            )[0]?.readyState,
        ),
      { timeout: 15000 },
    )
    .toBe(1)
}

async function waitForSourceIds(page: Page, ids: number[]) {
  await expect
    .poll(() => activeSourceIds(page), { timeout: 15000 })
    .toEqual(ids)
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
}

function waitForListResponse(page: Page, status: number) {
  return page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === LIST_PATH &&
      response.status() === status,
  )
}

function emitChange(page: Page, dropId: number, changes: string[]) {
  return page.evaluate(
    ({ dropId, changes }) =>
      window.__stockStream.emit('drop-changed', { dropId, changes }),
    { dropId, changes },
  )
}

test('목록 갱신 실패는 카드와 정렬 입력을 유지하고 재시도로 복구한다', async ({
  page,
}) => {
  let failing = false
  await page.route(
    (url) => url.pathname === LIST_PATH,
    (route) =>
      failing
        ? route.fulfill({ status: 500, json: { success: false } })
        : route.fallback(),
  )
  await page.goto('/#/grab')
  await expect(page.locator('.product-card')).toHaveCount(3)
  await waitForActiveConnection(page)
  const sorted = waitForListResponse(page, 200)
  await page.getByLabel('정렬', { exact: true }).selectOption('publishedAt,asc')
  await sorted

  const ids = await visibleCardIds(page)
  failing = true
  const failed = waitForListResponse(page, 500)
  await emitChange(page, ids[0]!, ['STOCK'])
  await failed

  const alert = page.getByRole('alert')
  await expect(alert).toHaveCount(1)
  await expect(alert).toContainText('최신 상품 목록을 불러오지 못했어요.')
  await expect(page.locator('.product-card')).toHaveCount(3)
  await expect(page.getByLabel('정렬', { exact: true })).toHaveValue(
    'publishedAt,asc',
  )
  await expect(page.getByText('상품 목록을 표시하지 못했어요.')).toHaveCount(0)
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true)
  const retry = page.getByRole('button', { name: '다시 시도' })
  await retry.focus()
  await expect(retry).toBeFocused()

  failing = false
  const recovered = waitForListResponse(page, 200)
  await retry.click()
  await recovered
  await expect(alert).toHaveCount(0)
  await expect(page.locator('.product-card')).toHaveCount(3)
})

test('품절 제외 목록은 카드와 총건수를 줄이고 새 ID 집합으로 연결을 교체한다', async ({
  page,
}) => {
  await page.addInitScript(() => {
    Math.random = () => 0
  })
  await page.goto('/#/grab?sort=soldQuantity,desc&soldOut=false')
  await expect(page.locator('.product-card')).toHaveCount(3)
  await waitForActiveConnection(page)
  const before = await visibleCardIds(page)
  expect(before).toContain(201)

  const drop = getPublicApiState(page).drops.find(
    (item) => item.dropId === 201,
  )!
  for (const option of drop.options) option.availableStock = 0
  const refreshed = waitForListResponse(page, 200)
  await emitChange(page, 201, ['STOCK'])
  await refreshed

  await expect(page.locator('.product-card')).toHaveCount(2)
  await expect(page.locator('.catalog-heading small')).toHaveText(' 2개')
  await expect(page.getByText(DROP_NAME)).toHaveCount(0)
  const after = await visibleCardIds(page)
  await waitForSourceIds(page, after)
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

test('SALES만 변경되면 인기순은 바뀌고 같은 ID 집합의 연결은 유지한다', async ({
  page,
}) => {
  await page.goto('/#/grab?sort=soldQuantity,desc&soldOut=false')
  await expect(page.locator('.product-card')).toHaveCount(3)
  await waitForActiveConnection(page)
  const sourcesBefore = await page.evaluate(
    () => window.__stockStream.sources.length,
  )

  getPublicApiState(page).drops.find(
    (item) => item.dropId === 202,
  )!.soldQuantity = 100
  const refreshed = waitForListResponse(page, 200)
  await emitChange(page, 202, ['SALES'])
  await refreshed

  await expect(page.locator('.product-card').first()).toContainText(
    '슬로우 모닝 머그',
  )
  expect(await page.evaluate(() => window.__stockStream.sources.length)).toBe(
    sourcesBefore,
  )
})

test('필터 변경과 뒤로 가기에서 이전 연결을 닫고 새 ID 집합으로 다시 연결한다', async ({
  page,
}) => {
  await page.addInitScript(() => {
    Math.random = () => 0
  })
  await page.goto('/#/grab')
  await expect(page.locator('.product-card')).toHaveCount(3)
  await waitForActiveConnection(page)
  const originalIds = await visibleCardIds(page)

  const filtered = waitForListResponse(page, 200)
  await page.getByRole('button', { name: '패션', exact: true }).click()
  await filtered
  await expect(page.locator('.product-card')).toHaveCount(2)
  const fashionIds = await visibleCardIds(page)
  expect(fashionIds).not.toEqual(originalIds)
  await waitForSourceIds(page, fashionIds)

  const restored = waitForListResponse(page, 200)
  await page.goBack()
  await restored
  await expect(page.locator('.product-card')).toHaveCount(3)
  await waitForSourceIds(page, originalIds)
})

test('STATUS 변경으로 목록에서 빠진 상품과 총건수를 갱신한다', async ({
  page,
}) => {
  await page.goto('/#/grab?sort=publishedAt,desc')
  await expect(page.locator('.product-card')).toHaveCount(3)
  await waitForActiveConnection(page)

  getPublicApiState(page).drops.find((item) => item.dropId === 202)!.status =
    'ENDED'
  const refreshed = waitForListResponse(page, 200)
  await emitChange(page, 202, ['STATUS'])
  await refreshed

  await expect(page.locator('.product-card')).toHaveCount(2)
  await expect(page.getByText('슬로우 모닝 머그')).toHaveCount(0)
  await expect(page.locator('.catalog-heading small')).toHaveText(' 2개')
})

test('목록을 벗어나면 연결을 정리하고 다시 진입하면 하나만 연결한다', async ({
  page,
}) => {
  await page.addInitScript(() => {
    Math.random = () => 0
  })
  await page.goto('/#/grab')
  await waitForActiveConnection(page)

  await page.evaluate(() => {
    location.hash = '#/'
  })
  await expect(page).toHaveURL(/#\/$/)
  await expect
    .poll(() =>
      page.evaluate(
        () =>
          window.__stockStream.sources.filter(
            (source) => source.readyState !== 2,
          ).length,
      ),
    )
    .toBe(0)

  await login(page)
  await page.evaluate(() => {
    location.hash = '#/my'
  })
  await expect(page).toHaveURL(/#\/my$/)
  await expect
    .poll(() =>
      page.evaluate(
        () =>
          window.__stockStream.sources.filter(
            (source) => source.readyState !== 2,
          ).length,
      ),
    )
    .toBe(0)

  await page.evaluate(() => {
    location.hash = '#/wish'
  })
  await waitForActiveConnection(page)
  await page.evaluate(() => {
    location.hash = '#/grab'
  })
  await waitForActiveConnection(page)
})
