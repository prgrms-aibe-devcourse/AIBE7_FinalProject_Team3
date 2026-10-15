import { expect, loginSeller, test } from '../../fixtures'

test('비회원은 로그인으로, 일반 회원은 권한 안내로 보낸다', async ({
  page,
}) => {
  await page.goto('/#/seller/drops?status=WISH&page=0')
  await expect(page).toHaveURL(/#\/login$/)
  await page.getByLabel('이메일').fill('grab@example.com')
  await page.getByLabel('비밀번호').fill('password1234')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).toHaveURL(/seller\/drops\?status=WISH&page=0/)
  await expect(page.getByText('판매자 권한이 필요해요.')).toBeVisible()
})

test('목록의 상태·페이지를 URL에 보존하고 상세에서 돌아온다', async ({
  page,
}) => {
  await loginSeller(page)
  await page.evaluate(() => {
    window.location.hash = '#/seller/drops?status=DRAFT&page=0'
  })
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
  await page.goBack()
  await page.goForward()
  await expect(page.getByText('2 / 3')).toBeVisible()
  await page.getByRole('link', { name: '보기 ↗' }).first().click()
  await expect(page).toHaveURL(/#\/seller\/drops\/\d+\?status=ALL&page=1/)
})

test('조회 실패·빈 결과를 구분하고 DRAFT 저장 후 목록에 표시한다', async ({
  page,
}) => {
  await loginSeller(page)
  await page.evaluate(() => {
    window.location.hash = '#/seller/drops'
  })
  await page.getByLabel('목록 결과 예시').selectOption('ERROR')
  await expect(page.getByText('DROP 목록을 불러오지 못했어요.')).toBeVisible()
  await page.getByRole('button', { name: '다시 시도' }).click()
  await page.getByLabel('목록 결과 예시').selectOption('EMPTY')
  await expect(page.getByText('해당 상태의 DROP이 없어요.')).toBeVisible()
  await page.getByRole('link', { name: '새 DROP 만들기' }).click()
  await page.getByLabel('상품명').fill('새 Mock DROP')
  await page.getByRole('button', { name: '임시 저장' }).click()
  await page.getByRole('link', { name: '← DROP STUDIO' }).click()
  await page
    .getByRole('navigation', { name: '주요 메뉴' })
    .getByRole('link', { name: 'DROP 관리' })
    .click()
  await page.getByRole('button', { name: 'DRAFT' }).click()
  await expect(page.getByRole('link', { name: '새 Mock DROP' })).toBeVisible()
})

test('목록에서 공개 실패와 출시 취소를 Mock 상태로 확인한다', async ({
  page,
}) => {
  await loginSeller(page)
  await page.evaluate(() => {
    window.location.hash = '#/seller/drops?status=DRAFT&page=0'
  })
  const draft = page
    .getByRole('article')
    .filter({ hasText: '오버핏 니트 가디건' })
  await page.getByLabel('상태 변경 결과 예시').selectOption('FAILURE')
  await draft.getByRole('button', { name: '공개 확인' }).click()
  await draft.getByRole('button', { name: 'Mock 공개 확인' }).click()
  await expect(draft.getByRole('alert')).toHaveText(/변경하지 못했어요/)
  await page.getByLabel('상태 변경 결과 예시').selectOption('SUCCESS')
  await draft.getByRole('button', { name: '공개 확인' }).click()
  await draft.getByRole('button', { name: 'Mock 공개 확인' }).click()
  await expect(draft).toHaveCount(0)
  await page.getByRole('button', { name: 'WISH' }).click()
  const wish = page
    .getByRole('article')
    .filter({ hasText: '오버핏 니트 가디건' })
  await expect(wish).toBeVisible()
  await wish.getByRole('button', { name: '출시 취소' }).click()
  await wish.getByLabel('취소 사유').fill('상품 공급 일정 변경')
  await wish.getByRole('button', { name: 'Mock 취소 확인' }).click()
  await expect(wish).toHaveCount(0)
})
