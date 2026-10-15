import { expect, login, test } from '../../fixtures'

test('로그인 오류와 원래 화면 복귀', async ({ page }) => {
  await page.goto('/#/my')
  await expect(page).toHaveURL(/#\/login$/)
  await page.getByLabel('이메일').fill('grab@example.com')
  await page.getByLabel('비밀번호').fill('password1234')
  await page.getByLabel('LOCAL 결과 예시').selectOption('credentials')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveText(
    '이메일 또는 비밀번호를 확인해주세요.',
  )
  await page.getByLabel('LOCAL 결과 예시').selectOption('success')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).toHaveURL(/#\/my$/)
  await expect(page.getByText('드롭헌터')).toBeVisible()

  await page.reload()
  await expect(page).toHaveURL(/#\/login$/)
})

test('LOCAL 이메일 코드와 회원가입 오류 예시', async ({ page }) => {
  await page.goto('/#/signup')
  await page.getByLabel('이메일 주소').fill('new@example.com')
  await page.getByRole('button', { name: '인증 코드 보내기' }).click()
  await expect(
    page.getByRole('button', { name: /초 후 재발송/ }),
  ).toBeDisabled()
  await page.getByLabel('인증 코드 6자리').fill('123456')
  await page.getByLabel('코드 확인 결과 예시').selectOption('mismatch')
  await page.getByRole('button', { name: '코드 확인' }).click()
  await expect(page.getByRole('alert')).toHaveText(
    '인증 코드가 일치하지 않아요.',
  )
  await page.getByLabel('코드 확인 결과 예시').selectOption('success')
  await page.getByRole('button', { name: '코드 확인' }).click()
  await expect(page.getByLabel('인증된 이메일')).toHaveValue('new@example.com')
  await page.getByLabel('닉네임', { exact: true }).fill('새회원')
  await page.getByLabel('비밀번호').fill('Password123!')
  await page.getByLabel('가입 결과 예시').selectOption('nickname')
  await page.getByRole('button', { name: '회원가입 완료' }).click()
  await expect(page.getByRole('alert')).toHaveText(
    '이미 사용 중인 닉네임이에요.',
  )
  await page.getByLabel('가입 결과 예시').selectOption('success')
  await page.getByRole('button', { name: '회원가입 완료' }).click()
  await expect(page).toHaveURL(/#\/wish$/)
})

test('소셜 첫 가입과 계정 유형별 마이페이지', async ({ page }) => {
  await page.goto('/#/login')
  await page.getByLabel('소셜 결과 예시').selectOption('new')
  await page.getByRole('button', { name: 'GOOGLE' }).click()
  await expect(
    page.getByRole('heading', { name: 'GOOGLE 첫 가입' }),
  ).toBeVisible()
  await page.getByLabel('닉네임', { exact: true }).fill('소셜회원')
  await page.getByRole('button', { name: '회원가입 완료' }).click()
  await page.getByRole('link', { name: 'MY', exact: true }).click()
  await expect(page.getByText('GOOGLE 계정 · 일반 회원')).toBeVisible()
  await expect(
    page.getByRole('link', { name: '판매자 신청 ↗' }),
  ).toHaveAttribute('href', '#/seller/apply')
  await page.getByLabel('계정 유형 예시').selectOption('SELLER')
  await expect(
    page.getByRole('link', { name: '판매자 작업 공간 ↗' }),
  ).toBeVisible()
  await expect(
    page.getByText('소셜 계정에는 비밀번호 변경이 제공되지 않습니다.'),
  ).toBeVisible()
})

test('마이페이지 닉네임 저장 실패는 기존 값을 유지하고 WISH·주문 빈 상태를 구분한다', async ({
  page,
}) => {
  await login(page)
  await page.getByRole('link', { name: 'MY', exact: true }).click()
  await expect(page.getByText('아직 주문이 없어요.')).toBeVisible()
  await page.getByRole('button', { name: '정보 수정' }).click()
  await page.getByLabel('닉네임 변경').fill('새닉네임')
  await page.getByLabel('편집 결과 예시').selectOption('save')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByRole('alert')).toHaveText(
    '저장에 실패했어요. 다시 시도해주세요.',
  )
  await expect(page.locator('.account-heading strong')).toHaveText('드롭헌터')
  await page.getByLabel('편집 결과 예시').selectOption('success')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.locator('.account-heading strong')).toHaveText('새닉네임')
  await page.getByRole('button', { name: '취소' }).click()
  await expect(page.getByText('아직 담은 WISH가 없어요.')).toBeVisible()
})
