import { expect, getAuth, login, test, toast, type Page } from '../../fixtures'

// 마이페이지 닉네임 수정·비밀번호 변경(GR-131). PATCH 응답은 installAuthApi가 처리하고,
// 오류는 failAccount('nickname' | 'password', ...)로 다음 응답부터 차례로 바꾼다.
// 폼은 계정 설정 행의 "변경"으로 여는 모달 안에 있고, 저장에 성공하면 닫힌다(GR-160).

const NETWORK = '연결에 실패했어요. 잠시 뒤 다시 시도해주세요.'
const NICKNAME_RULE =
  '닉네임은 한글·영문·숫자·_를 사용해 2~10자로 입력해주세요.'
const PASSWORD_RULE =
  '비밀번호는 공백 없이 영문·숫자·특수문자를 포함해 8~64자로 입력해주세요.'

const summaryNickname = (page: Page) => page.locator('.account-heading strong')
const settingsRow = (page: Page, name: string) =>
  page.getByRole('group', { name })
const editButton = (page: Page, name: '닉네임' | '비밀번호') =>
  settingsRow(page, name).getByRole('button', { name: '변경' })
const dialog = (page: Page, name: '닉네임 변경' | '비밀번호 변경') =>
  page.getByRole('dialog', { name })
const nicknameInput = (page: Page) => page.getByLabel('새 닉네임')
const saveNickname = (page: Page) =>
  page.getByRole('button', { name: '저장', exact: true }).click()
const passwordForm = (page: Page) =>
  page.getByRole('form', { name: '비밀번호 변경' })
const currentPassword = (page: Page) =>
  page.getByLabel('현재 비밀번호', { exact: true })
const newPassword = (page: Page) =>
  page.getByLabel('새 비밀번호', { exact: true })
const confirmPassword = (page: Page) =>
  page.getByLabel('새 비밀번호 확인', { exact: true })
const changePassword = (page: Page) =>
  passwordForm(page)
    .getByRole('button', { name: '비밀번호 변경', exact: true })
    .click()

async function openRow(page: Page, name: '닉네임' | '비밀번호') {
  await login(page)
  await page.goto('/#/my')
  await editButton(page, name).click()
}

const openEditor = (page: Page) => openRow(page, '닉네임')
const openPasswordEditor = (page: Page) => openRow(page, '비밀번호')

async function fillPasswords(
  page: Page,
  current: string,
  next: string,
  confirm = next,
) {
  await currentPassword(page).fill(current)
  await newPassword(page).fill(next)
  await confirmPassword(page).fill(confirm)
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

test('닉네임을 바꾸면 모달이 닫히고 서버 값으로 표시되며 새로고침해도 유지된다', async ({
  page,
}) => {
  await openEditor(page)
  await expect(dialog(page, '닉네임 변경')).toBeVisible()
  await expect(nicknameInput(page)).toBeFocused()
  await expect(dialog(page, '닉네임 변경').getByLabel('이메일')).toHaveCount(0)
  await nicknameInput(page).fill(' 새닉네임 ')
  await saveNickname(page)

  await expect(toast(page)).toHaveText('닉네임을 변경했어요.')
  await expect(dialog(page, '닉네임 변경')).toHaveCount(0)
  // 서버가 공백을 제거한 값으로 계정 카드와 설정 행이 바뀐다
  await expect(summaryNickname(page)).toHaveText('새닉네임')
  await expect(settingsRow(page, '닉네임')).toContainText('새닉네임')
  await expect(editButton(page, '닉네임')).toBeFocused()
  const [request] = getAuth(page).state.accountRequests
  expect(request).toEqual({
    api: 'nickname',
    body: { nickname: ' 새닉네임 ' },
    xsrf: 'e2e-csrf',
  })

  await page.reload()
  await expect(summaryNickname(page)).toHaveText('새닉네임')
})

test('모달은 바깥·Esc·취소·닫기로 닫히고 입력과 오류는 남지 않는다', async ({
  page,
}) => {
  await openEditor(page)
  await nicknameInput(page).fill('a')
  await saveNickname(page)
  await expectFieldError(page, nicknameInput(page), NICKNAME_RULE)

  await page.keyboard.press('Escape')
  await expect(dialog(page, '닉네임 변경')).toHaveCount(0)
  await expect(editButton(page, '닉네임')).toBeFocused()

  await editButton(page, '닉네임').click()
  await expect(nicknameInput(page)).toHaveValue('드롭헌터')
  await expect(nicknameInput(page)).not.toHaveAttribute('aria-invalid', 'true')
  await dialog(page, '닉네임 변경')
    .getByRole('button', { name: '취소' })
    .click()
  await expect(dialog(page, '닉네임 변경')).toHaveCount(0)

  await editButton(page, '비밀번호').click()
  await dialog(page, '비밀번호 변경')
    .getByRole('button', { name: '닫기' })
    .click()
  await expect(dialog(page, '비밀번호 변경')).toHaveCount(0)
  await expect(editButton(page, '비밀번호')).toBeFocused()

  await editButton(page, '비밀번호').click()
  // 패널 바깥(배경)을 누르면 닫힌다
  await page.mouse.click(5, 5)
  await expect(dialog(page, '비밀번호 변경')).toHaveCount(0)
  expect(getAuth(page).state.accountRequests).toHaveLength(0)
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

test('닉네임 저장 오류는 입력 아래에 표시하고 모달과 기존 값을 유지한다', async ({
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
    await expect(dialog(page, '닉네임 변경').getByRole('alert')).toHaveText(
      NETWORK,
    )
  }
  await expect(dialog(page, '닉네임 변경')).toBeVisible()
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

test('LOCAL 계정은 비밀번호를 바꾸면 모달이 닫히고 안내한다', async ({
  page,
}) => {
  await openPasswordEditor(page)
  await expect(currentPassword(page)).toBeFocused()
  // 현재 비밀번호는 규칙을 검사하지 않는다(가입 당시 규칙이 달랐어도 변경 가능)
  await fillPasswords(page, 'password1234', 'NewPassword456!')
  await changePassword(page)

  await expect(toast(page)).toHaveText('비밀번호를 변경했어요.')
  await expect(dialog(page, '비밀번호 변경')).toHaveCount(0)
  // 확인 값은 서버로 보내지 않는다
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
  await openPasswordEditor(page)
  await fillPasswords(page, 'password1234', 'short')
  await changePassword(page)
  await expectFieldError(page, newPassword(page), PASSWORD_RULE)

  await fillPasswords(page, 'Password123!', 'Password123!')
  await changePassword(page)
  await expectFieldError(
    page,
    newPassword(page),
    '현재 비밀번호와 다른 비밀번호를 입력해주세요.',
  )
  expect(getAuth(page).state.accountRequests).toHaveLength(0)
})

test('새 비밀번호 확인이 비었거나 다르면 확인 입력 아래에 안내하고 요청하지 않는다', async ({
  page,
}) => {
  await openPasswordEditor(page)
  await fillPasswords(page, 'password1234', 'NewPassword456!', '')
  await changePassword(page)
  await expectFieldError(
    page,
    confirmPassword(page),
    '새 비밀번호를 한 번 더 입력해주세요.',
  )

  await confirmPassword(page).fill('NewPassword457!')
  await changePassword(page)
  await expectFieldError(
    page,
    confirmPassword(page),
    '새 비밀번호가 일치하지 않아요.',
  )
  // 새 비밀번호를 고치면 확인 불일치 안내도 지운다
  await newPassword(page).fill('NewPassword457!')
  await expect(confirmPassword(page)).not.toHaveAttribute(
    'aria-invalid',
    'true',
  )
  expect(getAuth(page).state.accountRequests).toHaveLength(0)
})

test('비밀번호 변경 실패는 원인별로 다르게 표시하고 모달을 유지한다', async ({
  page,
}) => {
  await openPasswordEditor(page)

  // 현재 비밀번호 불일치: 현재 비밀번호만 비우고 포커스
  await fillPasswords(page, 'Wrong123!', 'NewPassword456!')
  await changePassword(page)
  await expectFieldError(
    page,
    currentPassword(page),
    '현재 비밀번호가 일치하지 않아요.',
  )
  await expect(currentPassword(page)).toHaveValue('')
  await expect(newPassword(page)).toHaveValue('NewPassword456!')
  await expect(confirmPassword(page)).toHaveValue('NewPassword456!')

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
  await expect(dialog(page, '비밀번호 변경')).toBeVisible()
})

test('보기 버튼으로 입력한 비밀번호를 글자로 확인하고 다시 가릴 수 있다', async ({
  page,
}) => {
  await openPasswordEditor(page)
  await newPassword(page).fill('NewPassword456!')
  await expect(newPassword(page)).toHaveAttribute('type', 'password')

  const show = page.getByRole('button', { name: '새 비밀번호 보기' })
  await expect(show).toHaveAttribute('aria-pressed', 'false')
  await show.click()
  await expect(newPassword(page)).toHaveAttribute('type', 'text')
  await expect(newPassword(page)).toHaveValue('NewPassword456!')
  const hide = page.getByRole('button', { name: '새 비밀번호 숨기기' })
  await expect(hide).toHaveAttribute('aria-pressed', 'true')
  // 다른 입력은 그대로 가려져 있다
  await expect(currentPassword(page)).toHaveAttribute('type', 'password')
  await expect(confirmPassword(page)).toHaveAttribute('type', 'password')

  await hide.click()
  await expect(newPassword(page)).toHaveAttribute('type', 'password')

  // 모달을 닫았다 열면 다시 가려진 상태로 시작한다
  await page.getByRole('button', { name: '새 비밀번호 보기' }).click()
  await page.keyboard.press('Escape')
  await editButton(page, '비밀번호').click()
  await expect(newPassword(page)).toHaveAttribute('type', 'password')
})

for (const provider of ['KAKAO', 'GOOGLE'] as const) {
  test(`${provider} 계정에는 비밀번호 변경이 없고 닉네임은 바꿀 수 있다`, async ({
    page,
  }) => {
    getAuth(page).setProvider(provider)
    await openEditor(page)
    await expect(settingsRow(page, '비밀번호')).toHaveCount(0)

    await nicknameInput(page).fill('소셜회원')
    await saveNickname(page)
    await expect(summaryNickname(page)).toHaveText('소셜회원')
  })
}

test('키보드만으로 닉네임과 비밀번호를 바꿀 수 있다', async ({ page }) => {
  await login(page)
  await page.goto('/#/my')
  await editButton(page, '닉네임').focus()
  await page.keyboard.press('Enter')
  await expect(nicknameInput(page)).toBeFocused()
  await page.keyboard.press('ControlOrMeta+A')
  await page.keyboard.type('키보드회원')
  await page.keyboard.press('Enter')
  await expect(summaryNickname(page)).toHaveText('키보드회원')
  // 저장에 성공해 모달이 닫히면 포커스는 "변경"으로 돌아온다
  await expect(editButton(page, '닉네임')).toBeFocused()

  await page.keyboard.press('Tab')
  await expect(editButton(page, '비밀번호')).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(currentPassword(page)).toBeFocused()
  await page.keyboard.type('password1234')
  // 입력마다 오른쪽에 보기 버튼이 있다
  await page.keyboard.press('Tab')
  await expect(
    page.getByRole('button', { name: '현재 비밀번호 보기' }),
  ).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(newPassword(page)).toBeFocused()
  await page.keyboard.type('NewPassword456!')
  await page.keyboard.press('Tab')
  await page.keyboard.press('Tab')
  await expect(confirmPassword(page)).toBeFocused()
  await page.keyboard.type('NewPassword456!')
  await page.keyboard.press('Enter')
  await expect(toast(page)).toHaveText('비밀번호를 변경했어요.')
  await expect(editButton(page, '비밀번호')).toBeFocused()
})
