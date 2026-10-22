import { expect, test } from '../../fixtures'
import { installCatalogScenario } from '../../mock/catalog'

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
    await expect(page.getByText('이 DROP은 품절됐어요.')).toBeVisible()
    await expect(page.locator('.buy-form')).toHaveCount(0)

    await page.goto('/#/drops/201')
    await page.getByLabel('착용감').selectOption('1')
    await page.getByLabel('소매').selectOption('2')
    await page.getByLabel('수량').fill('6')
    await expect(
      page.getByRole('button', { name: '수량을 확인해주세요' }),
    ).toBeDisabled()
  })

  test('구매 행동은 status가 아니라 actions로 정한다', async ({ page }) => {
    await installCatalogScenario(page, 'transition')
    // 저장 상태는 WISH지만 판매가 시작된 DROP은 구매 폼을 보여준다.
    await page.goto('/#/drops/104')
    await expect(page.locator('.buy-form')).toBeVisible()
    // 저장 상태는 GRAB지만 판매 기간이 끝난 DROP은 구매 폼 대신 종료 안내를 보여준다.
    await page.goto('/#/drops/302')
    await expect(page.getByText('이 DROP은 판매가 종료됐어요.')).toBeVisible()
    await expect(page.locator('.buy-form')).toHaveCount(0)
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
      page.getByRole('button', { name: 'WISH 등록하기' }),
    ).toBeVisible()
  })

  test('카드는 브랜드명과 남은 수량을 보여준다', async ({ page }) => {
    await page.goto('/#/grab')
    const grabCard = page
      .getByRole('region', { name: 'GRAB 상품 목록' })
      .getByRole('article')
      .first()
    await expect(grabCard.locator('.creator')).not.toBeEmpty()
    await expect(grabCard).toContainText('남은 수량')

    await page.goto('/#/wish')
    const wishCard = page
      .getByRole('region', { name: 'WISH 상품 목록' })
      .getByRole('article')
      .first()
    await expect(wishCard.locator('.creator')).not.toBeEmpty()
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
