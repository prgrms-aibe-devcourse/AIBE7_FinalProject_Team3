import { expect, login, test, toast } from './fixtures'

const card = (page: import('@playwright/test').Page, name: string) =>
  page.getByRole('article').filter({ hasText: name })

test.describe('상품 탐색', () => {
  test('종료·취소는 상세만 보이고 DRAFT는 숨긴다', async ({ page }) => {
    await page.goto('/#/drops/301')
    await expect(page.getByText('이 DROP은 판매가 종료됐어요.')).toBeVisible()
    await expect(page.locator('.buy-form')).toHaveCount(0)

    await page.goto('/#/drops/403')
    await expect(page.getByText('이 DROP은 판매가 취소됐어요.')).toBeVisible()
    await expect(page.locator('.buy-form')).toHaveCount(0)

    await page.goto('/#/drops/401')
    await expect(page.getByText('페이지를 찾을 수 없어요.')).toBeVisible()
  })

  test('전체 품절과 수량 초과는 데모 주문을 막는다', async ({ page }) => {
    await page.goto('/#/drops/204')
    await expect(page.getByRole('button', { name: '전체 품절' })).toBeDisabled()

    await page.goto('/#/drops/201')
    await page.getByLabel('착용감').selectOption({ label: '릴랙스' })
    await page.getByLabel('소매').selectOption({ label: '롱' })
    await page.getByLabel('수량').fill('6')
    await expect(
      page.getByRole('button', { name: '수량을 확인해주세요' }),
    ).toBeDisabled()
  })

  test('DETAIL 이미지는 설명 아래 순서대로 보이고 빈 경우와 실패를 처리한다', async ({
    page,
  }) => {
    await page.goto('/#/drops/102')
    const pictures = page.locator('.detail-pictures img')
    await expect(pictures).toHaveCount(2)
    await expect(pictures.nth(0)).toHaveAttribute(
      'alt',
      '데일리 머그의 전체 형태와 유약 색상',
    )
    await expect(pictures.nth(1)).toHaveAttribute('loading', 'lazy')
    await expect(page.locator('.detail-shipping')).toContainText(
      '주문 후 순차 제작, 5영업일 이내 출고',
    )

    await page.goto('/#/drops/101')
    await expect(page.locator('.detail-pictures')).toHaveCount(0)
    await expect(
      page.getByRole('heading', { name: '상품 상세 설명' }),
    ).toBeVisible()

    await page.route('**/photo-1529139574466-a303027c1d8b*', (route) =>
      route.abort(),
    )
    await page.goto('/#/drops/201')
    await page.locator('.detail-pictures').scrollIntoViewIfNeeded()
    await expect(
      page.locator('.detail-pictures').getByRole('img', {
        name: '뮤직 클럽 티셔츠의 프린트와 소재',
      }),
    ).toBeVisible()
  })

  test('상세 갤러리는 키보드로 전환하고 한 장·이미지 실패를 처리한다', async ({
    page,
  }) => {
    await page.route('**/photo-1577937927133-66ef06acdf18*', (route) =>
      route.abort(),
    )
    await page.goto('/#/drops/102')
    await expect(
      page.getByRole('group', {
        name: '나의 작은 휴식, 데일리 머그 상품 갤러리',
      }),
    ).toBeVisible()
    const second = page.getByRole('button', { name: /2번 이미지:/ })
    await expect(second).toBeVisible()
    await second.focus()
    await page.keyboard.press('Enter')
    await expect(second).toHaveAttribute('aria-pressed', 'true')
    await expect(
      page.locator('.detail-media').getByRole('img', {
        name: '데일리 머그의 손잡이와 표면 질감 이미지 없음',
      }),
    ).toBeVisible()

    await page.goto('/#/drops/101')
    await expect(page.locator('.detail-thumbnails')).toHaveCount(0)
    await expect(page.locator('.detail-media img')).toHaveAttribute(
      'alt',
      '매일을 담는 캔버스 토트 대표 이미지',
    )
  })

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
    await expect(
      page.getByRole('button', { name: '전체', exact: true }),
    ).toHaveClass('active')
    await expect(list.getByRole('article')).toHaveCount(3)
    // 상품명은 부분 일치로 찾는다
    await page.getByPlaceholder('상품명 검색').fill('캔버스 토트')
    await expect(list.getByRole('article')).toHaveCount(1)
    await expect(list).toContainText('매일을 담는 캔버스 토트')

    await page.getByPlaceholder('상품명 검색').fill('존재하지 않는 상품')
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

  test('옵션을 고르면 결제 화면으로 이동하고 MY 주문에 담긴다', async ({
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
    await page.getByRole('button', { name: '데모 GRAB 하기 ↗' }).click()

    await expect(page).toHaveURL(/#\/checkout\/GR-/)
    await expect(toast(page)).toHaveText(
      'Mock 주문을 만들었어요. 배송지와 결제 결과를 확인해주세요.',
    )
    await expect(page.locator('.buyer-total')).toContainText('89,000원')
    await page.getByLabel('받는 분').fill('김그랩')
    await page.getByLabel('연락처').fill('01012345678')
    await page.getByLabel('우편번호').fill('06236')
    await page.getByLabel('기본 주소').fill('서울시 강남구')
    await page.getByRole('button', { name: 'Mock 결제 결과 보기' }).click()
    await expect(page.getByText('결제 성공 예시')).toBeVisible()
    await page.getByRole('link', { name: '주문 상세로 이동 ↗' }).click()
    await expect(page).toHaveURL(/#\/my\/orders\/GR-/)
    await expect(page.getByText('결제 완료', { exact: true })).toBeVisible()
    await page.getByRole('link', { name: '← 내 주문 목록' }).click()
    const order = page.locator('.compact-item.order')
    await expect(order).toContainText('뮤직 클럽 티셔츠 · 리미티드')
    await expect(order).toContainText('착용감: 릴랙스 · 소매: 롱 · 2개')
    // 43,000원 × 2 + 배송비 3,000원
    await expect(order).toContainText('89,000원')
    await page.getByRole('link', { name: '주문 상세 ↗' }).click()
    await expect(page).toHaveURL(/#\/my\/orders\/GR-/)
  })
})
