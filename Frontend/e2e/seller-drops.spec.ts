import { expect, test } from './fixtures'

test('목록의 상태·페이지를 URL에 보존하고 상세에서 돌아온다', async ({
  page,
}) => {
  await page.goto('/#/seller/drops?status=DRAFT&page=0')
  await expect(page.getByRole('button', { name: 'DRAFT' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  await expect(
    page.getByRole('link', { name: '오버핏 니트 가디건' }),
  ).toBeVisible()
  await page.getByRole('button', { name: '전체', exact: true }).click()
  await page.getByRole('button', { name: '다음' }).click()
  await expect(page).toHaveURL(/status=ALL.*page=1/)
  await page.reload()
  await expect(page.getByText('2 / 3')).toBeVisible()
  await page.getByRole('link', { name: '보기 ↗' }).first().click()
  await expect(page).toHaveURL(/#\/seller\/drops\/\d+\?status=ALL&page=1/)
})

test('조회 실패·빈 결과를 구분하고 DRAFT 저장 후 목록에 표시한다', async ({
  page,
}) => {
  await page.goto('/#/seller/drops')
  await page.getByLabel('목록 결과 예시').selectOption('ERROR')
  await expect(page.getByText('DROP 목록을 불러오지 못했어요.')).toBeVisible()
  await page.getByRole('button', { name: '다시 시도' }).click()
  await page.getByLabel('목록 결과 예시').selectOption('EMPTY')
  await expect(page.getByText('해당 상태의 DROP이 없어요.')).toBeVisible()
  await page.getByRole('link', { name: '새 DROP 만들기' }).click()
  await page.getByLabel('상품명').fill('새 Mock DROP')
  await page.getByRole('button', { name: '임시 저장' }).click()
  await page.getByRole('link', { name: '← DROP STUDIO' }).click()
  await page.getByRole('link', { name: '전체 관리 ↗' }).click()
  await page.getByRole('button', { name: 'DRAFT' }).click()
  await expect(page.getByRole('link', { name: '새 Mock DROP' })).toBeVisible()
})
