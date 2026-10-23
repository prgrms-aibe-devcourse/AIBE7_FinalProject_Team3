import { expect, getAuth, login, test, toast, type Page } from '../../fixtures'

// 마이페이지 닉네임 수정·비밀번호 변경(GR-131). PATCH 응답은 installAuthApi가 처리하고,
// 오류는 failAccount('nickname' | 'password', ...)로 다음 응답부터 차례로 바꾼다.

const NETWORK = '연결에 실패했어요. 잠시 뒤 다시 시도해주세요.'
const NICKNAME_RULE =
  '닉네임은 한글·영문·숫자·_를 사용해 2~10자로 입력해주세요.'
const PASSWORD_RULE =
  '비밀번호는 공백 없이 영문·숫자·특수문자를 포함해 8~64자로 입력해주세요.'

const summaryNickname = (page: Page) => page.locator('.account-heading strong')
const nicknameInput = (page: Page) => page.getByLabel('닉네임 변경')
const saveNickname = (page: Page) =>
  page.getByRole('button', { name: '저장', exact: true }).click()
const passwordForm = (page: Page) =>
  page.getByRole('form', { name: '비밀번호 변경' })
const currentPassword = (page: Page) => page.getByLabel('현재 비밀번호')
const newPassword = (page: Page) => page.getByLabel('새 비밀번호')
const changePassword = (page: Page) =>
  page.getByRole('button', { name: '비밀번호 변경', exact: true }).click()

async function openEditor(page: Page) {
  await login(page)
  await page.goto('/#/my')
  await page.getByRole('button', { name: '정보 수정' }).click()
}

async function expectFieldError(
  page: Page,
  input: ReturnType<Page['getByLabel']>,
  message: string,
) {
  await expect(input).toHaveAttribute('aria-invalid', 'true')
  await expect(input).toBeFocused()
  const id = await input.getAttribute('aria-describedby')
  await expect(page.locator(`[id="${id}"]`)).toHaveText(message)
}

test('닉네임을 바꾸면 서버 값으로 표시되고 새로고침해도 유지된다', async ({
  page,
}) => {
  await openEditor(page)
  await nicknameInput(page).fill(' 새닉네임 ')
  await saveNickname(page)

  await expect(toast(page)).toHaveText('닉네임을 변경했어요.')
  await expect(summaryNickname(page)).toHaveText('새닉네임')
  // 패널은 열린 채 입력값이 서버가 공백을 제거한 값으로 바뀐다
  await expect(nicknameInput(page)).toHaveValue('새닉네임')
  const [request] = getAuth(page).state.accountRequests
  expect(request).toEqual({
    api: 'nickname',
    body: { nickname: ' 새닉네임 ' },
    xsrf: 'e2e-csrf',
  })

  await page.reload()
  await expect(summaryNickname(page)).toHaveText('새닉네임')
})

test('닉네임 사전 검증에 걸리면 요청하지 않는다', async ({ page }) => {
  await openEditor(page)
  await nicknameInput(page).fill('a')
  await saveNickname(page)
  await expectFieldError(page, nicknameInput(page), NICKNAME_RULE)

  // 공백 제거 후 현재 닉네임과 같으면 변경이 아니다
  await nicknameInput(page).fill(' 드롭헌터 ')
  await saveNickname(page)
  await expectFieldError(page, nicknameInput(page), '현재 닉네임과 같아요.')
  expect(getAuth(page).state.accountRequests).toHaveLength(0)
})

test('닉네임 저장 오류는 입력 옆에 표시하고 기존 값을 유지한다', async ({
  page,
}) => {
  getAuth(page).failAccount(
    'nickname',
    { status: 409, code: 'DUPLICATE_NICKNAME' },
    { status: 400, code: 'NICKNAME_UNCHANGED' },
    { status: 400, code: 'INVALID_NICKNAME' },
    { status: 500, code: 'INTERNAL_SERVER_ERROR' },
    'network',
  )
  await openEditor(page)
  await nicknameInput(page).fill('새닉네임')

  for (const message of [
    '이미 사용 중인 닉네임이에요.',
    '현재 닉네임과 같아요.',
    NICKNAME_RULE,
  ]) {
    await saveNickname(page)
    await expectFieldError(page, nicknameInput(page), message)
  }
  for (let attempt = 0; attempt < 2; attempt += 1) {
    await saveNickname(page)
    await expect(page.getByRole('alert')).toHaveText(NETWORK)
  }
  await expect(summaryNickname(page)).toHaveText('드롭헌터')
  expect(getAuth(page).state.accountRequests).toHaveLength(5)
})

test('닉네임 저장 중에는 다시 제출되지 않는다', async ({ page }) => {
  await openEditor(page)
  let release: () => void = () => undefined
  const released = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.route('**/api/v1/users/me', async (route) => {
    if (route.request().method() === 'PATCH') await released
    await route.fallback()
  })

  await nicknameInput(page).fill('새닉네임')
  await saveNickname(page)
  const saving = page.getByRole('button', { name: '저장 중…' })
  await expect(saving).toBeDisabled()
  await nicknameInput(page).press('Enter')
  release()

  await expect(toast(page)).toHaveText('닉네임을 변경했어요.')
  expect(getAuth(page).state.accountRequests).toHaveLength(1)
})

test('LOCAL 계정은 비밀번호를 바꾸면 입력을 비우고 안내한다', async ({
  page,
}) => {
  await openEditor(page)
  // 현재 비밀번호는 규칙을 검사하지 않는다(가입 당시 규칙이 달랐어도 변경 가능)
  await currentPassword(page).fill('password1234')
  await newPassword(page).fill('NewPassword456!')
  await changePassword(page)

  await expect(toast(page)).toHaveText('비밀번호를 변경했어요.')
  await expect(currentPassword(page)).toHaveValue('')
  await expect(newPassword(page)).toHaveValue('')
  expect(getAuth(page).state.accountRequests).toEqual([
    {
      api: 'password',
      body: {
        currentPassword: 'password1234',
        newPassword: 'NewPassword456!',
      },
      xsrf: 'e2e-csrf',
    },
  ])
  // 세션은 유지된다
  await expect(page).toHaveURL(/#\/my$/)
})

test('비밀번호 사전 검증에 걸리면 요청하지 않는다', async ({ page }) => {
  await openEditor(page)
  await currentPassword(page).fill('password1234')
  await newPassword(page).fill('short')
  await changePassword(page)
  await expectFieldError(page, newPassword(page), PASSWORD_RULE)

  await currentPassword(page).fill('Password123!')
  await newPassword(page).fill('Password123!')
  await changePassword(page)
  await expectFieldError(
    page,
    newPassword(page),
    '현재 비밀번호와 다른 비밀번호를 입력해주세요.',
  )
  expect(getAuth(page).state.accountRequests).toHaveLength(0)
})

test('비밀번호 변경 실패는 원인별로 다르게 표시한다', async ({ page }) => {
  await openEditor(page)

  // 현재 비밀번호 불일치: 현재 비밀번호만 비우고 포커스
  await currentPassword(page).fill('Wrong123!')
  await newPassword(page).fill('NewPassword456!')
  await changePassword(page)
  await expectFieldError(
    page,
    currentPassword(page),
    '현재 비밀번호가 일치하지 않아요.',
  )
  await expect(currentPassword(page)).toHaveValue('')
  await expect(newPassword(page)).toHaveValue('NewPassword456!')

  getAuth(page).failAccount(
    'password',
    { status: 400, code: 'INVALID_PASSWORD' },
    { status: 400, code: 'PASSWORD_UNCHANGED' },
    'network',
  )
  await currentPassword(page).fill('password1234')
  await changePassword(page)
  await expectFieldError(page, newPassword(page), PASSWORD_RULE)
  await changePassword(page)
  await expectFieldError(
    page,
    newPassword(page),
    '현재 비밀번호와 다른 비밀번호를 입력해주세요.',
  )
  await changePassword(page)
  await expect(passwordForm(page).getByRole('alert')).toHaveText(NETWORK)
  await expect(currentPassword(page)).toHaveValue('password1234')
})

for (const provider of ['KAKAO', 'GOOGLE'] as const) {
  test(`${provider} 계정에는 비밀번호 변경이 없고 닉네임은 바꿀 수 있다`, async ({
    page,
  }) => {
    getAuth(page).setProvider(provider)
    await openEditor(page)
    await expect(passwordForm(page)).toHaveCount(0)
    await expect(page.getByLabel('현재 비밀번호')).toHaveCount(0)
    await expect(
      page.getByText('소셜 계정에는 비밀번호 변경이 제공되지 않습니다.'),
    ).toBeVisible()

    await nicknameInput(page).fill('소셜회원')
    await saveNickname(page)
    await expect(summaryNickname(page)).toHaveText('소셜회원')
  })
}

test('키보드만으로 닉네임과 비밀번호를 바꿀 수 있다', async ({ page }) => {
  await login(page)
  await page.goto('/#/my')
  const editButton = page.getByRole('button', { name: '정보 수정' })
  await editButton.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('button', { name: '닫기' })).toHaveAttribute(
    'aria-expanded',
    'true',
  )

  await page.keyboard.press('Tab')
  await expect(page.getByLabel('이메일')).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(nicknameInput(page)).toBeFocused()
  await page.keyboard.press('ControlOrMeta+A')
  await page.keyboard.type('키보드회원')
  await page.keyboard.press('Enter')
  await expect(summaryNickname(page)).toHaveText('키보드회원')

  await page.keyboard.press('Tab')
  await page.keyboard.press('Tab')
  await expect(currentPassword(page)).toBeFocused()
  await page.keyboard.type('password1234')
  await page.keyboard.press('Tab')
  await expect(newPassword(page)).toBeFocused()
  await page.keyboard.type('NewPassword456!')
  await page.keyboard.press('Enter')
  await expect(toast(page)).toHaveText('비밀번호를 변경했어요.')
})
