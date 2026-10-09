import { expect, test } from './fixtures'

test('목록 조건을 직접 진입·새로고침·상세에서 뒤로 가기로 복원한다', async ({
  page,
}) => {
  await page.goto(
    '/#/wish?category=리빙&keyword=머그&sort=wishCount,desc&page=0',
  )
  const list = page.getByRole('region', { name: 'WISH 상품 목록' })
  await expect(page.getByPlaceholder('상품명 검색')).toHaveValue('머그')
  await expect(
    page.getByRole('button', { name: '리빙', exact: true }),
  ).toHaveClass('active')
  await expect(list.getByRole('article')).toHaveCount(1)
  await page.reload()
  await expect(page.getByPlaceholder('상품명 검색')).toHaveValue('머그')
  await list.getByRole('heading').getByRole('link').click()
  await expect(page).toHaveURL(/#\/drops\/102$/)
  await page.goBack()
  await expect(page.getByPlaceholder('상품명 검색')).toHaveValue('머그')
  await expect(list.getByRole('article')).toHaveCount(1)
})

test('카테고리 변경·뒤로 가기로 조건을 복원하고 검색 시 페이지를 초기화한다', async ({
  page,
}) => {
  await page.goto('/#/wish?category=리빙&sort=publishedAt,asc&page=1')
  await expect(page.getByText('조건에 맞는 상품이 없어요.')).toBeVisible()
  await page.getByRole('button', { name: '패션', exact: true }).click()
  let query = new URLSearchParams(new URL(page.url()).hash.split('?')[1])
  expect(query.get('category')).toBe('패션')
  expect(query.get('sort')).toBe('publishedAt,asc')
  expect(query.has('page')).toBe(false)
  await page.goBack()
  await expect(
    page.getByRole('button', { name: '리빙', exact: true }),
  ).toHaveClass('active')
  await page.getByPlaceholder('상품명 검색').fill('머그')
  query = new URLSearchParams(new URL(page.url()).hash.split('?')[1])
  expect(query.get('keyword')).toBe('머그')
  expect(query.has('page')).toBe(false)
  await expect(
    page.getByRole('region', { name: 'WISH 상품 목록' }).getByRole('article'),
  ).toHaveCount(1)
})

test('GRAB 인기 정렬·품절 조건·페이지를 URL에서 적용한다', async ({ page }) => {
  await page.goto('/#/grab?sort=soldQuantity,desc&soldOut=false')
  const cards = page
    .getByRole('region', { name: 'GRAB 상품 목록' })
    .getByRole('article')
  await expect(cards).toHaveCount(3)
  await expect(cards.nth(0)).toContainText('뮤직 클럽 티셔츠')
  await expect(cards.nth(1)).toContainText('에브리데이 캔버스 백')
  await page.goto('/#/grab?sort=soldQuantity,desc&soldOut=false&page=1')
  await expect(page.getByText('조건에 맞는 상품이 없어요.')).toBeVisible()
  await page.goto('/#/grab?soldOut=true')
  await expect(page.getByText('조건에 맞는 상품이 없어요.')).toBeVisible()
})

test('잘못된 URL 조건을 안내하고 초기화할 수 있다', async ({ page }) => {
  await page.goto('/#/wish?sort=soldQuantity,desc&page=-1')
  await expect(page.getByText('조회 조건이 올바르지 않아요.')).toBeVisible()
  await page.getByRole('link', { name: '조회 조건 초기화' }).click()
  await expect(page).toHaveURL(/#\/wish$/)
  await expect(
    page.getByRole('region', { name: 'WISH 상품 목록' }).getByRole('article'),
  ).toHaveCount(3)
})

test('정렬·품절 제외 UI를 URL에 반영하고 페이지 이동에서 조건을 유지한다', async ({
  page,
}) => {
  await page.goto('/#/grab?category=패션&page=1')
  await page
    .getByLabel('정렬', { exact: true })
    .selectOption('soldQuantity,desc')
  await expect(
    page.getByRole('region', { name: 'GRAB 상품 목록' }).getByRole('article'),
  ).toHaveCount(2)
  await page.getByLabel('품절 제외').click()
  await expect(page.getByLabel('품절 제외')).toBeChecked()
  await expect(page).toHaveURL(/soldOut=false/)
  await expect(
    page.getByRole('button', { name: '이전', exact: true }),
  ).toBeDisabled()
  await expect(
    page.getByRole('button', { name: '다음', exact: true }),
  ).toBeDisabled()
  const url = page.url()
  await page.goto(`${url}&page=1`)
  await page.getByRole('link', { name: '이전', exact: true }).click()
  await expect(page.getByLabel('정렬', { exact: true })).toHaveValue(
    'soldQuantity,desc',
  )
  await expect(page.getByLabel('품절 제외')).toBeChecked()
  await expect(
    page.getByRole('button', { name: '패션', exact: true }),
  ).toHaveAttribute('aria-pressed', 'true')
  await expect(
    page.getByRole('navigation', { name: '상품 목록 페이지' }),
  ).toContainText('1 / 1 페이지')
})

test('상품 이미지 실패 시 대체 표시를 제공하고 링크와 WISH 버튼을 분리한다', async ({
  page,
}) => {
  await page.route('**/*', (route) =>
    route.request().resourceType() === 'image'
      ? route.abort()
      : route.fallback(),
  )
  await page.goto('/#/wish')
  const cards = page
    .getByRole('region', { name: 'WISH 상품 목록' })
    .getByRole('article')
  await expect(cards.first().getByText('상품 이미지 준비 중')).toBeVisible()
  await expect(
    cards.first().getByRole('button', { name: '♡ WISH' }),
  ).toHaveAttribute('aria-pressed', 'false')
  expect(await cards.first().locator('a button, button a').count()).toBe(0)
  await cards.first().getByRole('heading').getByRole('link').click()
  await expect(page).toHaveURL(/#\/drops\/103$/)
})

test('PC·태블릿·모바일에서 목록은 4·3·2열이며 넘치지 않는다', async ({
  page,
}) => {
  for (const [width, columns] of [
    [1440, 4],
    [768, 3],
    [390, 2],
  ]) {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/#/wish')
    const list = page.getByRole('region', { name: 'WISH 상품 목록' })
    await expect(list).toBeVisible()
    const layout = await list.evaluate((element) => ({
      columns: getComputedStyle(element).gridTemplateColumns.split(' ').length,
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }))
    expect(layout.columns).toBe(columns)
    expect(layout.scrollWidth).toBeLessThanOrEqual(layout.clientWidth)
  }
})
