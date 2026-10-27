import { expect, loginSeller, test, toast } from '../../fixtures'

test('카테고리와 가격을 입력하면 재고 추천과 가격대 참고를 보여 주고 추천 수량을 채운다', async ({
  page,
}) => {
  await loginSeller(page)
  await page.goto('/#/seller/drops/new')
  const panel = page.getByRole('region', { name: '재고 추천' })
  await expect(panel).toContainText('카테고리와 SKU 가격을 입력하면')

  await page.getByLabel('카테고리').selectOption({ label: '패션' })
  await page.getByLabel('가격', { exact: true }).fill('59000')
  await expect(panel).toContainText('예상 판매량40개')
  await expect(panel).toContainText('준비 권장 수량44개')
  await expect(panel).toContainText('시장 평균추천에 사용')
  await expect(panel).toContainText('입력 가격은 같은 카테고리에서 중간')
  await expect(panel).toContainText(
    '다른 판매자 DROP 가격은 보통 45,000원~72,000원이에요.',
  )
  await expect(panel).toContainText('비슷한 가격대 판매율 71%')
  await expect(page.getByLabel('재고', { exact: true })).toHaveValue('')

  await panel.getByRole('button', { name: '추천 수량 채우기' }).click()
  await expect(page.getByLabel('재고', { exact: true })).toHaveValue('44')
  await expect(toast(page)).toContainText('추천 수량을 재고 칸에 채웠어요.')
})

test('비교할 표본이 없으면 수량 없이 안내만 하고 입력을 막지 않는다', async ({
  page,
}) => {
  await loginSeller(page)
  await page.goto('/#/seller/drops/new')
  const panel = page.getByRole('region', { name: '재고 추천' })
  await page.getByLabel('카테고리').selectOption({ index: 2 })
  await page.getByLabel('가격', { exact: true }).fill('12000')
  await expect(panel).toContainText(
    '비교할 종료 DROP이 부족해 추천하지 않았어요.',
  )
  await expect(
    panel.getByRole('button', { name: '추천 수량 채우기' }),
  ).toHaveCount(0)
  await page.getByLabel('재고', { exact: true }).fill('20')
  await expect(page.getByLabel('재고', { exact: true })).toHaveValue('20')
})
