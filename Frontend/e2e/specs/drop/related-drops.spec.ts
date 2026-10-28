import { expect, test } from '../../fixtures'

test('WISH 중인 상세는 하단에 비슷한 DROP을 추천 방식에 맞는 문구로 보여 준다', async ({
  page,
}) => {
  await page.goto('/#/drops/101')
  const related = page.getByRole('region', { name: '이런 DROP도 곧 열려요' })
  await expect(related).toBeVisible()
  await expect(related.locator('.product-card')).toHaveCount(2)
})

test('판매 종료 상세는 함께 WISH한 DROP을 비슷한 굿즈로 보여 준다', async ({
  page,
}) => {
  await page.goto('/#/drops/301')
  await expect(page.getByText('이 DROP은 판매가 종료됐어요.')).toBeVisible()
  const related = page.getByRole('region', { name: '비슷한 굿즈가 곧 열려요' })
  await expect(related).toBeVisible()
  await expect(related.locator('.product-card')).toHaveCount(2)
})

test('판매 중인 상세와 추천이 없는 상세에는 영역을 그리지 않는다', async ({
  page,
}) => {
  await page.goto('/#/drops/201')
  await expect(page.locator('.detail-layout')).toBeVisible()
  await expect(page.locator('.related-drops')).toHaveCount(0)

  await page.goto('/#/drops/102')
  await expect(page.locator('.detail-layout')).toBeVisible()
  await expect(page.locator('.related-drops')).toHaveCount(0)
})
