import { expect, test } from './fixtures'
import { installCatalogScenario } from './mock/catalog'
import { installHomeScenario } from './mock/home'

test.describe('소비자 메인', () => {
  test('인기 GRAB·WISH를 독립 슬라이드로 보여준다', async ({ page }) => {
    await page.goto('/')

    await expect(
      page.getByRole('heading', { name: '발견한 취향을 놓치지 않도록.' }),
    ).toBeVisible()
    await expect(
      page.getByRole('group', { name: '지금 인기 GRAB' }),
    ).toBeVisible()
    await expect(
      page.getByRole('group', { name: '곧 만날 인기 WISH' }),
    ).toBeVisible()
    await expect(
      page.getByRole('link', { name: '전체 보기 ↗' }).first(),
    ).toHaveAttribute('href', /sort=soldQuantity,desc/)
    await expect(
      page.getByRole('link', { name: '전체 보기 ↗' }).last(),
    ).toHaveAttribute('href', /sort=wishCount,desc/)
  })

  test('한 영역이 실패해도 다른 영역은 보인다', async ({ page }) => {
    await installHomeScenario(page, 'failure')
    await page.goto('/')

    await expect(page.getByText('인기 상품을 표시하지 못했어요.')).toBeVisible()
    await expect(page.getByRole('button', { name: '다시 시도' })).toBeVisible()
    await expect(
      page.getByRole('group', { name: '곧 만날 인기 WISH' }),
    ).toBeVisible()
  })

  test('빈 영역은 빈 상태와 전체 보기를 보여준다', async ({ page }) => {
    await installHomeScenario(page, 'empty')
    await page.goto('/')

    await expect(page.getByText('아직 준비된 상품이 없어요.')).toBeVisible()
    await expect(
      page.getByRole('link', { name: '전체 보기' }).first(),
    ).toBeVisible()
    await expect(
      page.getByRole('group', { name: '곧 만날 인기 WISH' }),
    ).toBeVisible()
  })

  test('슬라이드는 한 장씩 이동하고 양끝에서 멈춘다', async ({ page }) => {
    // 모바일에서만 3개 중 2개가 보여 이동 조작이 나타난다.
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/')

    const rail = page.getByRole('group', { name: '곧 만날 인기 WISH' })
    const position = rail.locator('.popular-position')
    const items = rail.locator('.popular-item')

    // 화면 밖 카드는 Tab 포커스를 받지 않는다.
    await expect(items.nth(0)).not.toHaveAttribute('inert', '')
    await expect(items.nth(1)).not.toHaveAttribute('inert', '')
    await expect(items.nth(2)).toHaveAttribute('inert', '')

    await expect(position).toHaveText('1 / 3')
    await expect(rail.getByRole('button', { name: /이전/ })).toBeDisabled()

    await rail.getByRole('button', { name: /다음/ }).click()
    await expect(position).toHaveText('2 / 3')
    await expect(rail.getByRole('button', { name: /다음/ })).toBeDisabled()

    await rail.focus()
    await page.keyboard.press('ArrowLeft')
    await expect(position).toHaveText('1 / 3')
    await expect(rail.getByRole('button', { name: /이전/ })).toBeDisabled()
  })

  test('품절·비활성 GRAB은 인기 후보에서 제외된다', async ({ page }) => {
    await installCatalogScenario(page, 'states')
    await page.goto('/')

    const rail = page.getByRole('group', { name: '지금 인기 GRAB' })
    // states 시나리오는 201(품절)과 203(전체 비활성)을 무효화한다.
    await expect(rail.getByRole('article')).toHaveCount(1)
    await expect(rail).toContainText('슬로우 모닝 머그')
  })

  test('화면 크기가 바뀌어도 이동이 어긋나지 않는다', async ({ page }) => {
    await installHomeScenario(page, 'many')
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/')

    const rail = page.getByRole('group', { name: '곧 만날 인기 WISH' })
    const position = rail.locator('.popular-position')

    // 모바일(2장 노출)에서 끝까지 이동한다.
    for (let n = 2; n <= 7; n += 1) {
      await rail.getByRole('button', { name: /다음/ }).click()
      await expect(position).toHaveText(`${n} / 8`)
    }

    // PC(4장 노출)로 전환하면 표시 위치가 보정된다.
    await page.setViewportSize({ width: 1440, height: 900 })
    await expect(position).toHaveText('5 / 8')

    // 보정된 위치에서 이전 이동이 실제로 동작한다.
    await rail.getByRole('button', { name: /이전/ }).click()
    await expect(position).toHaveText('4 / 8')
  })
})
