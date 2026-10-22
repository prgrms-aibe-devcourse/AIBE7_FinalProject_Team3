import { expect, test } from '../../fixtures'
import { getPublicApiState } from '../../mock/api'

test('다른 그룹 선택에 맞춰 선택지 재고를 갱신하고 품절 주문을 막는다', async ({
  page,
}) => {
  await page.goto('/#/drops/201')
  const fit = page.getByLabel('착용감')
  const sleeve = page.getByLabel('소매')
  await expect(fit.locator('option[value="1"]')).toHaveText(
    '릴랙스 · 13개 남음',
  )
  await sleeve.selectOption('2')
  await expect(fit.locator('option[value="1"]')).toHaveText('릴랙스 · 5개 남음')
  await expect(fit.locator('option[value="2"]')).toHaveText('와이드 · 품절')
  await fit.selectOption('2')
  await expect(
    page.getByRole('button', { name: '선택 옵션 품절' }),
  ).toBeDisabled()
  await expect(sleeve.locator('option[value="1"]')).toHaveText(
    '하프 · 6개 남음',
  )
})

test('없는 조합은 품절과 구분해 선택 불가로 표시한다', async ({ page }) => {
  const drop = getPublicApiState(page).drops.find(
    (item) => item.dropId === 201,
  )!
  drop.options = drop.options.filter((option) => option.optionId !== 2014)
  await page.goto('/#/drops/201')
  await page.getByLabel('소매').selectOption('2')
  await expect(
    page.getByLabel('착용감').locator('option[value="2"]'),
  ).toHaveText('와이드 · 선택 불가')
})
