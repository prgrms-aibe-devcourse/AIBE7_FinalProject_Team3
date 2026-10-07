import { expect, login, test, toast } from './fixtures'

const card = (page: import('@playwright/test').Page, name: string) =>
  page.getByRole('article').filter({ hasText: name })

test.describe('상품 탐색', () => {
  test('WISH 목록에서 상세로 이동한다', async ({ page }) => {
    await page.goto('/#/wish')
    await page
      .getByRole('heading', { name: '나의 작은 휴식, 데일리 머그' })
      .getByRole('link')
      .click()

    await expect(page).toHaveURL(/#\/drops\/102$/)
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(
      '나의 작은 휴식, 데일리 머그',
    )
    await expect(
      page.getByRole('button', { name: '♡ WISH 등록하기' }),
    ).toBeVisible()
  })

  test('GRAB 목록에서 상세로 이동한다', async ({ page }) => {
    await page.goto('/#/wish')
    await page.getByRole('link', { name: 'GRAB', exact: true }).click()
    await expect(page).toHaveURL(/#\/grab$/)

    await page
      .getByRole('heading', { name: '뮤직 클럽 티셔츠 · 리미티드' })
      .getByRole('link')
      .click()

    await expect(page).toHaveURL(/#\/drops\/201$/)
    await expect(
      page.getByRole('button', { name: '옵션을 선택해주세요' }),
    ).toBeDisabled()
  })

  test('카테고리·검색으로 거르고 결과가 없으면 안내한다', async ({ page }) => {
    await page.goto('/#/wish')
    const list = page.getByRole('region', { name: 'WISH 상품 목록' })
    await expect(list.getByRole('article')).toHaveCount(3)

    await page.getByRole('button', { name: '리빙', exact: true }).click()
    await expect(list.getByRole('article')).toHaveCount(1)
    await expect(list).toContainText('나의 작은 휴식, 데일리 머그')

    await page.getByRole('button', { name: '전체', exact: true }).click()
    // 브랜드명도 대소문자 없이 찾는다
    await page.getByPlaceholder('상품 또는 브랜드 검색').fill('slow object')
    await expect(list.getByRole('article')).toHaveCount(1)
    await expect(list).toContainText('매일을 담는 캔버스 토트')

    await page
      .getByPlaceholder('상품 또는 브랜드 검색')
      .fill('존재하지 않는 상품')
    await expect(list).toBeHidden()
    await expect(page.getByText('조건에 맞는 상품이 없어요.')).toBeVisible()
  })
})

test.describe('WISH', () => {
  test('데모 기본 WISH는 로그인 후에만 보인다', async ({ page }) => {
    await page.goto('/#/wish')
    const tote = card(page, '매일을 담는 캔버스 토트')
    await expect(tote.getByRole('button')).toHaveText('♡ WISH')
    await expect(tote).toContainText('248 WISH')

    await login(page)
    await expect(tote.getByRole('button')).toHaveText('✓ WISHED')
    await expect(tote).toContainText('249 WISH')
  })

  test('로그인 전에는 WISH 대신 로그인 안내를 띄운다', async ({ page }) => {
    await page.goto('/#/wish')
    await card(page, '나의 작은 휴식, 데일리 머그')
      .getByRole('button', { name: '♡ WISH' })
      .click()

    await expect(toast(page)).toHaveText('로그인 후 WISH를 이용할 수 있어요.')
    await expect(
      card(page, '나의 작은 휴식, 데일리 머그').getByRole('button'),
    ).toHaveText('♡ WISH')
  })

  test('로그인 후 WISH를 담고 MY에서 취소한다', async ({ page }) => {
    await login(page)
    const mug = card(page, '나의 작은 휴식, 데일리 머그')

    await mug.getByRole('button', { name: '♡ WISH' }).click()
    await expect(toast(page)).toHaveText('WISH에 담았어요.')
    await expect(mug.getByRole('button')).toHaveText('✓ WISHED')
    await expect(mug).toContainText('187 WISH')

    await page.getByRole('link', { name: 'MY', exact: true }).click()
    const wishPanel = page
      .locator('.panel')
      .filter({ has: page.getByRole('heading', { name: 'WISH' }) })
    // 101은 데모 기본 WISH다
    await expect(wishPanel).toContainText('매일을 담는 캔버스 토트')
    const mugItem = wishPanel
      .locator('.compact-item')
      .filter({ hasText: '나의 작은 휴식, 데일리 머그' })
    await mugItem.getByRole('button', { name: '취소' }).click()

    await expect(toast(page)).toHaveText('WISH를 취소했어요.')
    await expect(mugItem).toHaveCount(0)
  })
})

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

  test('옵션을 고르고 주문하면 MY 주문에 담긴다', async ({ page }) => {
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
    await page.getByRole('button', { name: '지금 GRAB 하기 ↗' }).click()

    await expect(page).toHaveURL(/#\/my$/)
    await expect(toast(page)).toHaveText(
      '주문이 생성됐어요. 결제는 데모에서 생략합니다.',
    )
    const order = page.locator('.compact-item.order')
    await expect(order).toContainText('뮤직 클럽 티셔츠 · 리미티드')
    await expect(order).toContainText('착용감: 릴랙스 · 소매: 롱 · 2개')
    // 43,000원 × 2 + 배송비 3,000원
    await expect(order).toContainText('89,000원')
  })
})
