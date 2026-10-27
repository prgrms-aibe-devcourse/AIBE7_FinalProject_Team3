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

test('상품명과 설명을 입력하면 비슷한 상품 표본으로 비교했다고 표시한다', async ({
  page,
}) => {
  await loginSeller(page)
  await page.goto('/#/seller/drops/new')
  const panel = page.getByRole('region', { name: '재고 추천' })
  await page.getByLabel('카테고리').selectOption({ label: '패션' })
  await page.getByLabel('가격', { exact: true }).fill('59000')
  await expect(panel).toContainText('다른 판매자 DROP 10~19개')
  await expect(panel).not.toContainText('상품명·설명이 비슷한')

  const request = page.waitForRequest(
    (candidate) =>
      candidate.url().includes('/seller/stock-recommendations') &&
      candidate.postDataJSON()?.description === '부드러운 코튼 티셔츠',
  )
  await page.getByLabel('상품명').fill('오버핏 티셔츠')
  await page.getByLabel('상품 설명').fill('부드러운 코튼 티셔츠')
  expect((await request).postDataJSON()).toMatchObject({
    name: '오버핏 티셔츠',
    description: '부드러운 코튼 티셔츠',
  })
  await expect(panel).toContainText('다른 판매자 비슷한 상품 10~19개')
  await expect(panel).toContainText(
    '상품명·설명이 비슷한 종료 DROP을 골라 비교했어요.',
  )
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

test('옵션별 추천은 비슷한 상품 판매 비율을 보여 주고 누르면 SKU별 수량을 채운다', async ({
  page,
}) => {
  await loginSeller(page)
  await page.goto('/#/seller/drops/new')
  const panel = page.getByRole('region', { name: '재고 추천' })
  await page.getByLabel('상품명').fill('오버핏 티셔츠')
  await page.getByLabel('상품 설명').fill('부드러운 코튼 티셔츠')
  await page.getByLabel('카테고리').selectOption({ label: '패션' })
  await page.getByRole('button', { name: '＋ 그룹 추가' }).click()
  await page.getByLabel('그룹명').fill('색상')
  await page.getByLabel('선택값', { exact: true }).fill('블랙, 화이트')
  const prices = page.getByLabel('가격', { exact: true })
  await prices.nth(0).fill('39000')
  await prices.nth(1).fill('39000')
  await expect(panel).toContainText('준비 권장 수량44개')

  const request = page.waitForRequest((candidate) =>
    candidate.url().includes('/stock-recommendations/option-allocation'),
  )
  await panel.getByRole('button', { name: '옵션별 추천' }).click()
  expect((await request).postDataJSON()).toMatchObject({
    optionGroups: [{ name: '색상', values: ['블랙', '화이트'] }],
    options: [{ values: ['블랙'] }, { values: ['화이트'] }],
  })
  const options = panel.getByLabel('옵션별 추천')
  await expect(options).toContainText('블랙 75% · 화이트 25%')
  await expect(options).toContainText('비슷한 상품 판매 비율')

  await options.getByRole('button', { name: '옵션별 수량 채우기' }).click()
  const stocks = page.getByLabel('재고', { exact: true })
  await expect(stocks.nth(0)).toHaveValue('33')
  await expect(stocks.nth(1)).toHaveValue('11')
  await expect(toast(page)).toContainText(
    '옵션별 추천 수량을 재고 칸에 채웠어요.',
  )

  // 입력이 바뀌면 이전 결과를 지운다
  await prices.nth(1).fill('41000')
  await expect(options).not.toContainText('블랙 75%')
})

test('옵션별 추천 하루 한도를 넘으면 안내하고 재고 입력을 막지 않는다', async ({
  page,
}) => {
  await loginSeller(page)
  await page.goto('/#/seller/drops/new')
  const panel = page.getByRole('region', { name: '재고 추천' })
  await page.getByLabel('상품명').fill('오버핏 티셔츠')
  await page.getByLabel('상품 설명').fill('부드러운 코튼 티셔츠')
  await page.getByLabel('카테고리').selectOption({ label: '패션' })
  await page.getByRole('button', { name: '＋ 그룹 추가' }).click()
  await page.getByLabel('그룹명').fill('한도')
  await page.getByLabel('선택값', { exact: true }).fill('A')
  await page.getByLabel('가격', { exact: true }).nth(0).fill('39000')

  await panel.getByRole('button', { name: '옵션별 추천' }).click()
  await expect(panel).toContainText('오늘 옵션별 추천 횟수를 다 썼어요.')
  await page.getByLabel('재고', { exact: true }).nth(0).fill('20')
  await expect(page.getByLabel('재고', { exact: true }).nth(0)).toHaveValue(
    '20',
  )
})
