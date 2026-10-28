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

test('재고 추천 아래에 비슷한 DROP 실적을 보여 주고 상품명을 넣으면 비슷한 굿즈로 좁힌다', async ({
  page,
}) => {
  await loginSeller(page)
  await page.goto('/#/seller/drops/new')
  const panel = page.getByRole('region', { name: '비슷한 DROP 실적' })
  await expect(panel).toContainText('카테고리와 SKU 가격을 입력하면')

  await page.getByLabel('카테고리').selectOption({ label: '패션' })
  await page.getByLabel('가격', { exact: true }).fill('59000')
  await expect(panel).toContainText(
    '내 지난 DROP 중 비슷한 것이 없어 다른 판매자의 같은 카테고리 굿즈를 기준으로 했어요.',
  )
  await expect(panel).toContainText(
    '같은 카테고리 DROP 3개 중 1개가 매진됐고, 매진까지 보통 40분 걸렸어요.',
  )
  await expect(panel.locator('li.is-used')).toContainText(
    '20개 이상 중 55%가 매진됐고, 매진까지 보통 1시간 30분 걸렸어요.',
  )

  await page.getByLabel('상품명').fill('고양이 아크릴 키링')
  await expect(panel).toContainText('다른 판매자의 비슷한 굿즈를 기준으로')

  await page.getByLabel('카테고리').selectOption({ index: 2 })
  await expect(panel).toContainText('비교할 다른 판매자 자료가 아직 부족해요.')
  await expect(panel).toContainText(
    '비교할 판매자·DROP 수가 적어 다른 판매자 자료는 보여 주지 않아요.',
  )
})

test('근거 보기를 누르면 추천 근거 설명을 보여 주고, 입력을 바꾸면 설명을 닫는다', async ({
  page,
}) => {
  await loginSeller(page)
  await page.goto('/#/seller/drops/new')
  const panel = page.getByRole('region', { name: '재고 추천' })
  await page.getByLabel('카테고리').selectOption({ label: '패션' })
  await page.getByLabel('가격', { exact: true }).fill('59000')
  await expect(panel).toContainText('준비 권장 수량44개')

  const explanation = panel.getByRole('region', { name: '추천 근거 설명' })
  await explanation.getByRole('button', { name: '근거 보기' }).click()
  await expect(explanation).toContainText(
    '시장 평균 판매 실적으로 준비 권장 수량은 44개입니다.',
  )
  await expect(explanation).toContainText('55%가 매진됐고')
  // 설명은 재고 칸을 바꾸지 않는다
  await expect(page.getByLabel('재고', { exact: true })).toHaveValue('')

  await page.getByLabel('재고', { exact: true }).fill('60')
  await expect(explanation).not.toContainText('준비 권장 수량은 44개입니다.')
  await expect(
    explanation.getByRole('button', { name: '근거 보기' }),
  ).toBeEnabled()
})

test('표본이 부족해 추천하지 못했으면 그 이유를 설명하고, 한도를 넘으면 안내한다', async ({
  page,
}) => {
  await loginSeller(page)
  await page.goto('/#/seller/drops/new')
  const panel = page.getByRole('region', { name: '재고 추천' })
  await page.getByLabel('카테고리').selectOption({ index: 2 })
  await page.getByLabel('가격', { exact: true }).fill('12000')
  const explanation = panel.getByRole('region', { name: '추천 근거 설명' })
  await explanation.getByRole('button', { name: '추천하지 못한 이유' }).click()
  await expect(explanation).toContainText(
    '비교할 종료 DROP이 부족해 준비 권장 수량을 계산하지 않았습니다.',
  )
  await expect(explanation).toContainText('부족한 데이터')

  await page.route(
    '**/api/v1/seller/stock-recommendations/explanation',
    (route) =>
      route.fulfill({
        status: 429,
        json: {
          success: false,
          error: { code: 'DEMAND_EXPLANATION_LIMIT_EXCEEDED' },
        },
      }),
  )
  await page.getByLabel('가격', { exact: true }).fill('13000')
  await explanation.getByRole('button', { name: '추천하지 못한 이유' }).click()
  await expect(explanation.getByRole('alert')).toContainText(
    '오늘 요청할 수 있는 AI 설명 횟수를 모두 썼어요.',
  )
})
