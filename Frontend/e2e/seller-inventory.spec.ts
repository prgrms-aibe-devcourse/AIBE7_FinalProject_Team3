import { expect, loginSeller, test } from './fixtures'

test('비회원은 로그인으로 이동하고 일반 회원은 판매자 안내를 본다', async ({
  page,
}) => {
  await page.goto('/#/seller/inventory?dropId=201')
  await expect(page).toHaveURL(/#\/login$/)
  await page.getByLabel('이메일').fill('grab@example.com')
  await page.getByLabel('비밀번호').fill('password1234')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByText('판매자 권한이 필요해요.')).toBeVisible()
})

test('직접 진입한 DROP의 옵션별 재고와 비활성·0재고를 구분한다', async ({
  page,
}) => {
  await loginSeller(page)
  await page.evaluate(() => {
    window.location.hash = '#/seller/inventory?status=GRAB&page=0&dropId=201'
  })
  const detail = page.getByRole('region', { name: '선택 상품 옵션별 재고' })
  await expect(detail.getByText('뮤직 클럽 티셔츠 · 리미티드')).toBeVisible()
  await expect(detail.getByText('와이드 / 롱')).toBeVisible()
  await expect(detail.getByText('비활성 옵션')).toBeVisible()
  await expect(detail.getByText('가용 재고 0')).toHaveCount(0)
  await expect(detail.getByText('12', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'ENDED' }).click()
  await expect(page).toHaveURL(/status=ENDED&page=0/)
  await page.getByRole('button', { name: /가을 코듀로이 셔츠/ }).click()
  await expect(page.getByRole('region', { name: '상품별 현황' })).toContainText(
    '판매 18',
  )
  await expect(detail.getByText('등록된 옵션 재고가 없어요.')).toBeVisible()
})

test('필터·페이지·선택을 URL에 보존하고 잘못된 DROP 선택을 해제한다', async ({
  page,
}) => {
  await loginSeller(page)
  await page.evaluate(() => {
    window.location.hash = '#/seller/inventory?status=ALL&page=0'
  })
  await page.getByRole('button', { name: '다음' }).click()
  await expect(page).toHaveURL(/status=ALL&page=1/)
  await page.getByRole('button', { name: /에브리데이 캔버스 백/ }).click()
  await expect(page).toHaveURL(/status=ALL&page=1&dropId=203/)
  await page.goBack()
  await expect(
    page.getByRole('region', { name: '선택 상품 옵션별 재고' }),
  ).toContainText('상품을 선택하면')
  await page.evaluate(() => {
    window.location.hash = '#/seller/inventory?dropId=999'
  })
  await expect(page.getByRole('alert')).toContainText('관리 권한이 없어요')
  await expect(page).not.toHaveURL(/dropId=999/)
})

test('목록과 선택 재고의 실패를 분리하고 모바일에서 넘치지 않는다', async ({
  page,
}) => {
  await loginSeller(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.evaluate(() => {
    window.location.hash = '#/seller/inventory?status=GRAB&page=0&dropId=201'
  })
  await page.getByLabel('목록 결과 예시').selectOption('ERROR')
  await expect(page.getByText('재고 목록을 불러오지 못했어요.')).toBeVisible()
  await expect(page.getByText('와이드 / 롱')).toBeVisible()
  await page.getByLabel('재고 결과 예시').selectOption('ERROR')
  await expect(page.getByText('옵션 재고를 불러오지 못했어요.')).toBeVisible()
  await page.getByRole('button', { name: '다시 시도' }).last().click()
  await expect(page.getByText('와이드 / 롱')).toBeVisible()
  const width = await page.evaluate(() => ({
    content: document.documentElement.scrollWidth,
    viewport: document.documentElement.clientWidth,
  }))
  expect(width.content).toBeLessThanOrEqual(width.viewport)
})
