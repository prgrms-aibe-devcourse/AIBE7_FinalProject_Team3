import { expect, getAuth, test, toast, type Page } from '../../fixtures'

// KAKAO·GOOGLE 소셜 로그인(GR-47). 시작 요청은 installAuthApi가 실제 제공자 대신 콜백 결과 주소로 302하고,
// 콜백 쿼리는 setSocialCallback, 소셜 가입 완료 오류는 failSignup('social', ...)로 바꾼다.

const RETURN_PATH_KEY = 'grab.socialReturnTo'

const socialButton = (page: Page, name: 'KAKAO' | 'GOOGLE') =>
  page.getByRole('button', { name })

const nicknameInput = (page: Page) => page.getByLabel('닉네임')
const submitSignup = (page: Page) =>
  page.getByRole('button', { name: '회원가입 완료' }).click()

const phoneInputs = (page: Page) =>
  page.locator('input[type="tel"], input[name*="phone" i]')

// 다음 요청 하나를 release 전까지 붙잡아 처리 중 화면을 확인한다.
async function holdNext(page: Page, path: string) {
  let release: () => void = () => undefined
  const released = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.route(
    `**${path}`,
    async (route) => {
      await released
      await route.fallback()
    },
    { times: 1 },
  )
  return release
}

async function expectNoSecretsInStorage(page: Page) {
  const storage = await page.evaluate(async () => ({
    local: JSON.stringify({ ...localStorage }),
    session: JSON.stringify({ ...sessionStorage }),
    cookie: document.cookie,
    databases: (await indexedDB.databases()).map((db) => db.name),
  }))
  expect(storage.local).toBe('{}')
  // 복귀 경로는 콜백에서 읽은 즉시 지운다
  expect(storage.session).toBe('{}')
  expect(storage.databases).toEqual([])
  for (const name of ['access_token', 'refresh_token', 'oauth2_signup_token']) {
    expect(storage.cookie).not.toContain(name)
  }
}

// 보호 화면에서 로그인 화면으로 넘어온 뒤 소셜 버튼을 키보드로 누른다
async function startFromProtectedPage(page: Page, name: 'KAKAO' | 'GOOGLE') {
  await page.goto('/#/my')
  await expect(page).toHaveURL(/#\/login$/)
  await socialButton(page, name).focus()
  await page.keyboard.press('Enter')
}

test('기존 회원은 KAKAO 로그인 후 원래 화면으로 돌아가고 새로고침해도 로그인 상태다', async ({
  page,
}) => {
  const auth = getAuth(page)
  await startFromProtectedPage(page, 'KAKAO')

  await expect(page).toHaveURL(/#\/my$/)
  await expect(toast(page)).toContainText('로그인했어요.')
  await expect(page.getByRole('group', { name: '닉네임' })).toBeVisible()
  await expect(page.getByRole('group', { name: '비밀번호' })).toHaveCount(0)
  expect(auth.state.socialStarts).toEqual(['/api/v1/auth/oauth2/kakao'])
  await expectNoSecretsInStorage(page)

  // 콜백 화면은 replace로 떠났으므로 뒤로 가도 콜백을 다시 처리하지 않는다
  await page.goBack()
  await expect(page).not.toHaveURL(/auth\/callback/)

  await page.goto('/#/my')
  await page.reload()
  await expect(page).toHaveURL(/#\/my$/)
  await expect(page.getByRole('group', { name: '닉네임' })).toBeVisible()
  await expect(page.getByRole('group', { name: '비밀번호' })).toHaveCount(0)
})

test('최초 GOOGLE 인증은 닉네임만 입력해 가입하고 원래 화면으로 돌아간다', async ({
  page,
}) => {
  const auth = getAuth(page)
  auth.setSocialCallback('result=signup_required')
  auth.failSignup(
    'social',
    { status: 409, code: 'DUPLICATE_NICKNAME' },
    { status: 400, code: 'INVALID_NICKNAME' },
  )
  await startFromProtectedPage(page, 'GOOGLE')

  await expect(page).toHaveURL(/#\/signup\?type=social$/)
  await expect(
    page.getByRole('heading', { name: '소셜 계정으로 가입' }),
  ).toBeVisible()
  expect(auth.state.socialStarts).toEqual(['/api/v1/auth/oauth2/google'])
  await expect(phoneInputs(page)).toHaveCount(0)
  await expect(page.getByLabel('비밀번호')).toHaveCount(0)
  await expect(page.getByText('GOOGLE')).toHaveCount(0)
  expect(page.url()).not.toMatch(/token|code=/)

  // 화면 사전 검증은 요청을 보내지 않는다
  await nicknameInput(page).fill('a')
  await submitSignup(page)
  await expect(nicknameInput(page)).toHaveAttribute('aria-invalid', 'true')
  await expect(nicknameInput(page)).toHaveAttribute(
    'aria-describedby',
    'social-signup-nickname-error',
  )
  await expect(nicknameInput(page)).toBeFocused()
  expect(auth.state.signupRequests).toHaveLength(0)

  await nicknameInput(page).fill('  중복닉  ')
  await submitSignup(page)
  await expect(page.locator('#social-signup-nickname-error')).toHaveText(
    '이미 사용 중인 닉네임이에요.',
  )
  await expect(nicknameInput(page)).toBeFocused()

  await nicknameInput(page).fill('규칙밖닉')
  await submitSignup(page)
  await expect(page.locator('#social-signup-nickname-error')).toContainText(
    '2~10자',
  )

  await nicknameInput(page).fill('새닉네임')
  await submitSignup(page)
  await expect(page).toHaveURL(/#\/my$/)
  await expect(toast(page)).toContainText('회원가입이 완료됐어요.')
  await expect(page.getByRole('group', { name: '닉네임' })).toBeVisible()
  await expect(page.getByRole('group', { name: '비밀번호' })).toHaveCount(0)

  const requests = auth.state.signupRequests
  expect(requests.map((request) => request.body)).toEqual([
    { nickname: '중복닉' },
    { nickname: '규칙밖닉' },
    { nickname: '새닉네임' },
  ])
  expect(requests.every((request) => request.step === 'social')).toBe(true)
  expect(requests.every((request) => request.xsrf === 'e2e-csrf')).toBe(true)
  await expectNoSecretsInStorage(page)

  // 가입 화면은 replace로 떠났으므로 뒤로 가도 다시 제출할 수 없다
  await page.goBack()
  await expect(page).not.toHaveURL(/signup/)
})

for (const [code, message] of [
  [
    'OAUTH2_AUTHENTICATION_FAILED',
    '소셜 로그인이 취소되었거나 실패했어요. 다시 시도해주세요.',
  ],
  [
    'OAUTH2_EMAIL_REQUIRED',
    '이메일 제공에 동의해야 가입할 수 있어요. 다시 시도해주세요.',
  ],
  [
    'ACCOUNT_LINK_REQUIRED',
    '이미 다른 방법으로 가입된 이메일이에요. 가입한 방법으로 로그인해주세요.',
  ],
  ['ACCOUNT_DISABLED', '사용할 수 없는 계정이에요.'],
  ['INTERNAL_SERVER_ERROR', '연결에 실패했어요. 잠시 뒤 다시 시도해주세요.'],
  ['UNKNOWN_CODE', '소셜 로그인을 완료하지 못했어요. 다시 시도해주세요.'],
] as const) {
  test(`콜백 ${code}는 로그인 화면에 안내하고 저장한 경로를 지운다`, async ({
    page,
  }) => {
    await page.goto('/#/login')
    await page.evaluate(
      (key) => sessionStorage.setItem(key, '/my'),
      RETURN_PATH_KEY,
    )
    await page.goto(`/#/auth/callback?error=${code}`)

    await expect(page).toHaveURL(/#\/login$/)
    await expect(page.getByRole('alert')).toHaveText(message)
    await expect(socialButton(page, 'KAKAO')).toBeEnabled()
    expect(getAuth(page).state.loggedIn).toBe(false)
    await expectNoSecretsInStorage(page)
  })
}

test('쿼리 없는 콜백·로그인을 확인하지 못한 success는 미완료로 안내한다', async ({
  page,
}) => {
  const message = '소셜 로그인을 완료하지 못했어요. 다시 시도해주세요.'
  await page.goto('/#/auth/callback')
  await expect(page).toHaveURL(/#\/login$/)
  await expect(page.getByRole('alert')).toHaveText(message)

  // 쿠키가 실리지 않은 경우처럼 users/me가 비로그인을 돌려준다
  await page.goto('/#/auth/callback?result=success')
  await expect(page).toHaveURL(/#\/login$/)
  await expect(page.getByRole('alert')).toHaveText(message)
})

test('소셜 실패 후 다시 시도하면 로그인해 원래 화면으로 돌아간다', async ({
  page,
}) => {
  const auth = getAuth(page)
  auth.setSocialCallback('error=OAUTH2_AUTHENTICATION_FAILED')
  await startFromProtectedPage(page, 'KAKAO')
  await expect(page).toHaveURL(/#\/login$/)
  await expect(page.getByRole('alert')).toContainText('취소되었거나 실패했어요')

  auth.setSocialCallback('result=success')
  await socialButton(page, 'KAKAO').click()
  await expect(page).toHaveURL(/#\/my$/)
  await expect(toast(page)).toContainText('로그인했어요.')
  expect(auth.state.socialStarts).toHaveLength(2)
})

test('로그인을 확인하지 못한 success 뒤 다시 시도하면 원래 화면으로 돌아간다', async ({
  page,
}) => {
  const auth = getAuth(page)
  await page.goto('/#/my')
  await expect(page).toHaveURL(/#\/login$/)
  // 쿠키가 실리지 않은 경우처럼 콜백 처리 동안 users/me가 비로그인을 돌려준다
  const meUrl = '**/api/v1/users/me'
  await page.route(meUrl, (route) =>
    route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({
        success: false,
        data: null,
        error: { code: 'UNAUTHORIZED', message: 'error' },
      }),
    }),
  )
  await socialButton(page, 'KAKAO').click()
  await expect(page).toHaveURL(/#\/login$/)
  await expect(page.getByRole('alert')).toHaveText(
    '소셜 로그인을 완료하지 못했어요. 다시 시도해주세요.',
  )

  await page.unroute(meUrl)
  await socialButton(page, 'KAKAO').click()
  await expect(page).toHaveURL(/#\/my$/)
  expect(auth.state.socialStarts).toHaveLength(2)
})

test('저장된 복귀 경로가 외부 주소면 WISH로 이동한다', async ({ page }) => {
  getAuth(page).state.loggedIn = true
  for (const value of ['//evil.example', 'https://evil.example']) {
    await page.goto('/#/login')
    await page.evaluate(
      ([key, path]) => sessionStorage.setItem(key, path),
      [RETURN_PATH_KEY, value],
    )
    await page.goto('/#/auth/callback?result=success')
    await expect(page).toHaveURL(/localhost:\d+\/#\/wish$/)
  }
})

for (const [code, status, message] of [
  [
    'OAUTH2_SIGNUP_CONTEXT_INVALID',
    401,
    '가입 시간이 지났어요. 소셜 로그인을 다시 시작해주세요.',
  ],
  [
    'DUPLICATE_EMAIL',
    409,
    '이미 가입된 이메일이에요. 가입한 방법으로 로그인해주세요.',
  ],
] as const) {
  test(`소셜 가입 ${code}는 로그인 화면에서 다시 시작하게 한다`, async ({
    page,
  }) => {
    const auth = getAuth(page)
    auth.failSignup('social', { status, code })
    await page.goto('/#/signup?type=social')
    await nicknameInput(page).fill('드롭헌터2')
    const refreshBefore = auth.state.refreshCount
    await submitSignup(page)

    await expect(page).toHaveURL(/#\/login$/)
    await expect(page.getByRole('alert')).toHaveText(message)
    expect(auth.state.refreshCount).toBe(refreshBefore)
    expect(auth.state.signupRequests).toHaveLength(1)
    expect(auth.state.loggedIn).toBe(false)
  })
}

test('소셜 가입을 다시 시작해 로그인하면 원래 화면으로 돌아간다', async ({
  page,
}) => {
  const auth = getAuth(page)
  auth.setSocialCallback('result=signup_required')
  auth.failSignup('social', {
    status: 401,
    code: 'OAUTH2_SIGNUP_CONTEXT_INVALID',
  })
  await startFromProtectedPage(page, 'GOOGLE')
  await expect(page).toHaveURL(/#\/signup\?type=social$/)
  await nicknameInput(page).fill('드롭헌터2')
  await submitSignup(page)
  await expect(page).toHaveURL(/#\/login$/)
  await expect(page.getByRole('alert')).toHaveText(
    '가입 시간이 지났어요. 소셜 로그인을 다시 시작해주세요.',
  )

  auth.setSocialCallback('result=success')
  await socialButton(page, 'GOOGLE').click()
  await expect(page).toHaveURL(/#\/my$/)
  await expect(toast(page)).toContainText('로그인했어요.')
})

test('가입 뒤 로그인 상태를 확인하지 못하면 가입 완료를 알리고 다시 로그인하면 원래 화면으로 간다', async ({
  page,
}) => {
  const auth = getAuth(page)
  auth.setSocialCallback('result=signup_required')
  await startFromProtectedPage(page, 'GOOGLE')
  await expect(page).toHaveURL(/#\/signup\?type=social$/)

  // 가입 201 직후 users/me 재조회만 한 번 실패한다. 가입 화면이 뜰 때 앱이 스스로 보내는 users/me가
  // 이 실패를 먼저 가져가면 재조회가 성공해 /my로 가므로, 가입 요청이 처리된 뒤의 첫 조회에만 적용한다
  let meFailed = false
  await page.route('**/api/v1/users/me', (route) => {
    if (meFailed || auth.state.signupRequests.length === 0) {
      return route.fallback()
    }
    meFailed = true
    return route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({
        success: false,
        data: null,
        error: { code: 'INTERNAL_SERVER_ERROR', message: 'error' },
      }),
    })
  })
  await nicknameInput(page).fill('새닉네임')
  await submitSignup(page)

  await expect(page).toHaveURL(/#\/login$/)
  await expect(page.getByRole('alert')).toHaveText(
    '회원가입은 완료됐어요. 소셜 로그인으로 다시 로그인해주세요.',
  )
  await expect(toast(page)).not.toContainText('회원가입이 완료됐어요.')
  expect(auth.state.signupRequests).toHaveLength(1)

  auth.setSocialCallback('result=success')
  await socialButton(page, 'GOOGLE').click()
  await expect(page).toHaveURL(/#\/my$/)
  await expect(toast(page)).toContainText('로그인했어요.')
})

test('소셜 가입은 처리 중 버튼을 막고 CSRF·네트워크 실패 후 다시 제출할 수 있다', async ({
  page,
}) => {
  const auth = getAuth(page)
  auth.failSignup('social', { status: 403, code: 'ACCESS_DENIED' }, 'network')
  // 새로고침 등으로 복귀 경로 state가 없으면 WISH로 간다
  await page.goto('/#/signup?type=social')
  await nicknameInput(page).fill('드롭헌터2')

  await submitSignup(page)
  await expect(page.getByRole('alert')).toHaveText(
    '요청을 처리하지 못했어요. 다시 시도해주세요.',
  )
  await submitSignup(page)
  await expect(page.getByRole('alert')).toHaveText(
    '연결에 실패했어요. 잠시 뒤 다시 시도해주세요.',
  )

  const release = await holdNext(page, '/api/v1/auth/oauth2/signup')
  await submitSignup(page)
  const pending = page.getByRole('button', { name: '가입하는 중…' })
  await expect(pending).toBeDisabled()
  release()

  await expect(page).toHaveURL(/#\/wish$/)
  expect(auth.state.signupRequests).toHaveLength(3)
})

test('소셜 버튼은 제공자로 이동하는 동안 다시 누를 수 없다', async ({
  page,
}) => {
  const release = await holdNext(page, '/api/v1/auth/oauth2/kakao')
  await page.goto('/#/login')
  // 이동이 시작되면 locator 단언은 이동이 끝나기를 기다리므로, 클릭과 상태 확인을 페이지 안에서 한 번에 한다
  const disabled = await page.evaluate(async () => {
    const buttons = [...document.querySelectorAll('button')].filter((button) =>
      ['KAKAO', 'GOOGLE'].includes(button.textContent ?? ''),
    )
    buttons[0].click()
    buttons[0].click()
    await new Promise(requestAnimationFrame)
    return buttons.map((button) => button.disabled)
  })
  expect(disabled).toEqual([true, true])
  release()
  await expect(page).toHaveURL(/#\/wish$/)
  expect(getAuth(page).state.socialStarts).toHaveLength(1)
})
