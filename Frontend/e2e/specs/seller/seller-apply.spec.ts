import { expect, test } from '../../fixtures'

test('직접 진입은 로그인 후 신청 화면으로 복귀하고 Mock 신청을 접수한다', async ({
  page,
}) => {
  await page.goto('/#/seller/apply')
  await expect(page).toHaveURL(/#\/login$/)
  await page.getByLabel('이메일').fill('grab@example.com')
  await page.getByLabel('비밀번호').fill('password1234')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).toHaveURL(/#\/seller\/apply$/)
  await page.getByLabel('브랜드명').fill('길동상점')
  await page.getByLabel('연락 이메일').fill('seller@example.com')
  await page.getByLabel('브랜드 소개').fill('한정판 상품 판매점')
  await page.getByRole('button', { name: 'Mock 신청 결과 보기' }).click()
  await expect(page.getByText('심사를 기다리고 있어요.')).toBeVisible()
  await expect(
    page.getByRole('button', { name: 'Mock 신청 결과 보기' }),
  ).toHaveCount(0)
})

test('반려 사유와 승인 상태를 보여주되 승인 예시만으로 판매자 역할을 주지 않는다', async ({
  page,
}) => {
  await page.goto('/#/seller/apply')
  await page.getByLabel('이메일').fill('grab@example.com')
  await page.getByLabel('비밀번호').fill('password1234')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await page.getByLabel('신청 상태 예시').selectOption('REJECTED')
  await expect(page.getByText('사업자 정보 확인이 필요합니다.')).toBeVisible()
  await expect(
    page.getByRole('button', { name: 'Mock 신청 결과 보기' }),
  ).toHaveCount(0)
  await page.getByLabel('신청 상태 예시').selectOption('APPROVED')
  await expect(
    page.getByText('판매자 권한은 계정 역할을 다시 확인해야'),
  ).toBeVisible()
  await expect(
    page.getByRole('link', { name: '판매자 작업 공간 ↗' }),
  ).toHaveCount(0)
})

test('중복 신청과 조회 실패를 구분한다', async ({ page }) => {
  await page.goto('/#/seller/apply')
  await page.getByLabel('이메일').fill('grab@example.com')
  await page.getByLabel('비밀번호').fill('password1234')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await page.getByLabel('브랜드명').fill('길동상점')
  await page.getByLabel('연락 이메일').fill('seller@example.com')
  await page.getByLabel('브랜드 소개').fill('한정판 상품 판매점')
  await page.getByLabel('신청 결과 예시').selectOption('DUPLICATE')
  await page.getByRole('button', { name: 'Mock 신청 결과 보기' }).click()
  await expect(page.getByRole('alert')).toHaveText(
    '이미 접수된 신청이 있습니다.',
  )
  await page.getByLabel('신청 상태 예시').selectOption('ERROR')
  await expect(page.getByRole('alert')).toHaveText(
    '신청 정보를 불러오지 못했어요. 다시 시도해주세요.',
  )
})
