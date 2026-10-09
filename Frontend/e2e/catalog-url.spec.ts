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
