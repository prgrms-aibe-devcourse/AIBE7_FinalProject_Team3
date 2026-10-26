import { expect, loginSeller, test, toast } from '../../fixtures'

test('판매 시작 전 WISH DROP 상세에만 수요 분석 제안 카드를 보여 준다', async ({
  page,
}) => {
  await loginSeller(page)
  await page.goto('/#/seller/drops/101')
  const card = page.getByRole('region', { name: '수요 분석 제안' })
  await expect(card).toContainText('활성 WISH 248명')
  await expect(card).toContainText('준비 권장 수량 91개')
  await card.getByRole('link', { name: '제안서 보기 ↗' }).click()
  await expect(page).toHaveURL(/seller\/drops\/101\/demand$/)
  await expect(
    page.getByRole('heading', { name: '수요 분석 제안서' }),
  ).toBeVisible()

  await page.goto('/#/seller/drops/401')
  await expect(page.getByRole('heading', { name: '상품 정보' })).toBeVisible()
  await expect(
    page.getByRole('region', { name: '수요 분석 제안' }),
  ).toHaveCount(0)
  await expect(page.getByRole('link', { name: '수요 분석 ↗' })).toHaveCount(0)
})

test('예상 판매량과 준비 권장 수량을 구분하고 가정을 바꾼 변화량을 보여 준다', async ({
  page,
}) => {
  await loginSeller(page)
  await page.goto('/#/seller/drops/101/demand')
  const metrics = page.locator('.demand-metrics').first()
  await expect(metrics).toContainText('예상 판매량82개')
  await expect(metrics).toContainText('범위 73~91개')
  await expect(metrics).toContainText('준비 권장 수량91개')
  await expect(metrics).toContainText('권장보다 부족31개')
  await expect(page.locator('.demand-basis.is-used')).toContainText('시장 평균')
  await expect(page.locator('.demand-basis.is-used')).toContainText(
    'DROP 10~19개 · WISH 약 1,740명',
  )

  await page.getByRole('radio', { name: '내 실적' }).check()
  await page.getByRole('button', { name: '다시 계산' }).click()
  await expect(page.locator('.demand-change')).toContainText(
    '준비 권장 91개 → 110개 (+19)',
  )
  await expect(page.locator('.demand-basis.is-used')).toContainText('내 실적')
  await expect(page.getByText('비교한 종료 DROP이 적어')).toBeVisible()

  await page.getByRole('radio', { name: '직접 입력' }).check()
  await page.getByLabel('전환율 (%)').fill('0')
  await page.getByRole('button', { name: '다시 계산' }).click()
  await expect(page.getByRole('alert')).toContainText(
    '전환율은 1~100 사이 정수(%)',
  )
})

test('계획을 저장하고 수정 수량으로 한 번만 결정한다', async ({ page }) => {
  await loginSeller(page)
  await page.goto('/#/seller/drops/101/demand')
  await expect(page.getByText('저장한 계획이 없어요.')).toBeVisible()
  await page.getByRole('button', { name: '이 가정으로 계획 저장' }).click()
  await expect(toast(page)).toHaveText('판매 계획을 저장했어요.')
  const history = page.locator('.demand-history')
  await expect(
    history.getByRole('button', { name: /결정 전 준비 권장 91개/ }),
  ).toHaveAttribute('aria-expanded', 'true')

  await history.getByRole('button', { name: '수정 후 확정' }).click()
  await history.getByLabel('내추럴').fill('40')
  await history.getByLabel('블랙').fill('-1')
  await history.getByRole('button', { name: '수정 수량 확정' }).click()
  await expect(history.getByRole('alert')).toContainText('0 이상 정수')
  await history.getByLabel('블랙').fill('35')
  await history.getByLabel('사유 (선택)').fill('재고 부담을 줄이기 위해 조정')
  await history.getByRole('button', { name: '수정 수량 확정' }).click()
  await expect(toast(page)).toHaveText('계획 결정을 기록했어요.')
  await expect(history.locator('.demand-decision-result')).toContainText(
    '내추럴 40개 · 블랙 35개',
  )
  await expect(history.getByRole('button', { name: '수락' })).toHaveCount(0)
})

test('판매가 시작된 DROP은 분석 대신 안내와 계획 이력을 보여 준다', async ({
  page,
}) => {
  await loginSeller(page)
  await page.goto('/#/seller/drops/201/demand')
  await expect(
    page.getByText('판매 시작 전 WISH DROP만 분석할 수 있어요.'),
  ).toBeVisible()
  await expect(page.getByText('저장한 계획이 없어요.')).toBeVisible()
  await expect(
    page.getByRole('button', { name: '이 가정으로 계획 저장' }),
  ).toHaveCount(0)

  await page.goto('/#/seller/drops/999/demand')
  await expect(page.getByText('이 DROP을 관리할 권한이 없어요.')).toBeVisible()
  await page.goto('/#/seller/drops/bad/demand')
  await expect(page.getByText('잘못된 DROP 번호예요.')).toBeVisible()
})
