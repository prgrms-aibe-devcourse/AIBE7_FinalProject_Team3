import { expect, login, test, toast } from '../../fixtures'
import { installCatalogScenario } from '../../mock/catalog'

const card = (page: import('@playwright/test').Page, name: string) =>
  page.getByRole('article').filter({ hasText: name })

test.describe('WISH', () => {
  test('데모 기본 WISH는 로그인 후에만 보인다', async ({ page }) => {
    await page.goto('/#/wish')
    const tote = card(page, '매일을 담는 캔버스 토트')
    await expect(tote.getByRole('button')).toHaveText('♡ WISH')
    await expect(tote).toContainText('248 WISH')

    await login(page)
    await expect(tote.getByRole('button')).toHaveText('✓ WISHED')
    await expect(tote).toContainText('248 WISH')
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
    await expect(wishPanel).toContainText('오픈 일정 · 10월 4일')
    const mugItem = wishPanel
      .locator('.compact-item')
      .filter({ hasText: '나의 작은 휴식, 데일리 머그' })
    await mugItem.getByRole('button', { name: '취소' }).click()

    await expect(toast(page)).toHaveText('WISH를 취소했어요.')
    await expect(mugItem).toHaveCount(0)
  })
})

test.describe('WISH 서버 연동', () => {
  test('등록 후 다시 로그인해도 서버 WISH가 유지된다', async ({ page }) => {
    await login(page)
    const mug = card(page, '나의 작은 휴식, 데일리 머그')
    await mug.getByRole('button', { name: '♡ WISH' }).click()
    await expect(mug.getByRole('button')).toHaveText('✓ WISHED')

    // 새로 열면 클라이언트 로그인은 풀리지만 서버 상태는 남는다.
    await login(page)
    await expect(
      card(page, '나의 작은 휴식, 데일리 머그').getByRole('button'),
    ).toHaveText('✓ WISHED')
  })

  test('담은 WISH가 20건을 넘어도 카드 버튼 상태에 반영된다', async ({
    page,
  }) => {
    await installCatalogScenario(page, 'wishes')
    await login(page)
    const last = page
      .getByRole('article')
      .filter({ has: page.getByRole('heading', { name: 'WISH 더미 23' }) })
    await expect(last.getByRole('button')).toHaveText('✓ WISHED')
  })

  test('마이페이지 WISH를 20개씩 페이지로 본다', async ({ page }) => {
    await installCatalogScenario(page, 'wishes')
    await login(page)
    await page.getByRole('link', { name: 'MY', exact: true }).click()
    const wishPanel = page
      .locator('.panel')
      .filter({ has: page.getByRole('heading', { name: 'WISH' }) })
    await expect(wishPanel.locator('.compact-item')).toHaveCount(20)
    await expect(wishPanel).toContainText('1 / 2 페이지')
    await wishPanel.getByRole('button', { name: '다음' }).click()
    await expect(wishPanel.locator('.compact-item')).toHaveCount(4)
    await expect(wishPanel).toContainText('2 / 2 페이지')
  })

  test('담은 WISH가 없으면 빈 상태를 보여준다', async ({ page }) => {
    await installCatalogScenario(page, 'no-wishes')
    await login(page)
    await page.getByRole('link', { name: 'MY', exact: true }).click()
    await expect(page.getByText('아직 담은 WISH가 없어요.')).toBeVisible()
  })

  test('판매가 시작된 뒤 등록하면 409를 안내한다', async ({ page }) => {
    await installCatalogScenario(page, 'wish-409')
    await login(page)
    await card(page, '플레이리스트 그래픽 티셔츠')
      .getByRole('button', { name: '♡ WISH' })
      .click()
    await expect(toast(page)).toHaveText(
      '이미 판매가 시작되어 WISH할 수 없어요.',
    )
  })
})
