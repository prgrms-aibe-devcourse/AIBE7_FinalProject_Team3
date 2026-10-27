import { expect, loginSeller, test } from '../../fixtures'

test.beforeEach(async ({ page }) => {
  await loginSeller(page)
})

test('판매 시작 전 WISH DROP 상세에만 수요 분석 참고 카드를 보여 준다', async ({
  page,
}) => {
  await page.goto('/#/seller/drops/101')
  const card = page.getByRole('region', { name: '수요 분석' })
  await expect(card).toContainText('지금 확인해 보세요')
  await expect(card).toContainText('WISH 248명')
  await expect(card).toContainText('판매 시작까지 3일')
  const metrics = card.locator('.demand-metrics')
  await expect(metrics).toContainText('예상 판매량82개')
  await expect(metrics).toContainText('범위 73~91개')
  await expect(metrics).toContainText('준비 권장 수량91개')
  await expect(metrics).toContainText('권장보다 부족31개')
  await expect(card.locator('.demand-initial')).toContainText(
    '공개할 때 추천 44개 (과거 판매량 기준) → WISH 기준 91개 (+47개)',
  )
  // 저장·결정 없이 참고용으로만 보여 준다
  await expect(
    card.getByRole('button', { name: /저장|수락|거절/ }),
  ).toHaveCount(0)
  await expect(card.getByRole('link', { name: '옵션별 재고' })).toHaveAttribute(
    'href',
    '#/seller/inventory?dropId=101',
  )

  await page.goto('/#/seller/drops/401')
  await expect(page.getByRole('heading', { name: '상품 정보' })).toBeVisible()
  await expect(page.getByRole('region', { name: '수요 분석' })).toHaveCount(0)
})

test('근거와 가정을 펼쳐 기준을 바꿔 다시 계산하고 기본 가정으로 되돌린다', async ({
  page,
}) => {
  await page.goto('/#/seller/drops/101')
  const card = page.getByRole('region', { name: '수요 분석' })
  await card.getByText('근거와 가정 보기').click()
  await expect(card.getByRole('table', { name: '옵션별 배분' })).toContainText(
    '내추럴',
  )
  await expect(card.locator('.demand-basis.is-used')).toContainText(
    'DROP 10~19개 · WISH 약 1,740명',
  )

  await card.getByRole('radio', { name: '내 실적' }).check()
  await card.getByRole('button', { name: '다시 계산' }).click()
  await expect(card.locator('.demand-change')).toContainText(
    '준비 권장 91개 → 110개 (+19)',
  )
  await expect(card.locator('.demand-basis.is-used')).toContainText('내 실적')
  await expect(card.getByText('비교한 종료 DROP이 적어')).toBeVisible()
  await expect(card.locator('.demand-metrics')).toContainText(
    '준비 권장 수량110개',
  )

  await card.getByRole('button', { name: '기본 가정으로' }).click()
  await expect(card.locator('.demand-change')).toHaveCount(0)
  await expect(card.locator('.demand-metrics')).toContainText(
    '준비 권장 수량91개',
  )

  await card.getByRole('radio', { name: '직접 입력' }).check()
  await card.getByLabel('전환율 (%)').fill('0')
  await card.getByRole('button', { name: '다시 계산' }).click()
  await expect(card.getByRole('alert')).toContainText(
    '전환율은 1~100 사이 정수(%)',
  )
})

test('분석을 볼 수 없으면 카드 안에서 안내하고 상품 정보는 그대로 보여 준다', async ({
  page,
}) => {
  await page.route('**/api/v1/seller/drops/101/demand-analysis', (route) =>
    route.fulfill({
      status: 409,
      json: {
        success: false,
        error: { code: 'DEMAND_ANALYSIS_UNAVAILABLE', fieldErrors: [] },
      },
    }),
  )
  await page.goto('/#/seller/drops/101')
  const card = page.getByRole('region', { name: '수요 분석' })
  await expect(card.getByRole('alert')).toContainText(
    '판매가 시작되어 수요 분석을 볼 수 없어요',
  )
  await expect(card.getByRole('button', { name: '다시 시도' })).toHaveCount(0)
  await expect(page.getByRole('heading', { name: '상품 정보' })).toBeVisible()
})
