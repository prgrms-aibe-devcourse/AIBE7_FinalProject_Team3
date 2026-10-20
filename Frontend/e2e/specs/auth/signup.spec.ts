import { expect, getAuth, test, toast, type Page } from '../../fixtures'

// LOCAL 회원가입 3단계(GR-31). 응답은 installAuthApi가 주고 오류는 failSignup으로 바꾼다.

const EMAIL = 'new@example.com'
const CODE = '123456'
const PASSWORD = 'Password123!'

async function requestCode(page: Page, email = EMAIL) {
  await page.goto('/#/signup')
  await page.getByLabel('이메일 주소').fill(email)
  await page.getByRole('button', { name: '인증 코드 보내기' }).click()
  await expect(page.getByLabel('인증 코드 6자리')).toBeVisible()
}

async function confirmCode(page: Page) {
  await page.getByLabel('인증 코드 6자리').fill(CODE)
  await page.getByRole('button', { name: '코드 확인' }).click()
}

async function toDetails(page: Page, email = EMAIL) {
  await requestCode(page, email)
  await confirmCode(page)
  await expect(page.getByLabel('인증된 이메일')).toHaveValue(email)
}

async function fillDetails(page: Page, nickname = '새회원') {
  await page.getByLabel('닉네임', { exact: true }).fill(nickname)
  await page.getByLabel('비밀번호').fill(PASSWORD)
}

const submitDetails = (page: Page) =>
  page.getByRole('button', { name: '회원가입 완료' }).click()

// 재발송 타이머는 렌더마다 1초 타이머를 다시 걸므로 1초씩 진행한다.
async function passResendInterval(page: Page) {
  for (let second = 0; second < 61; second += 1) {
    await page.clock.runFor(1000)
  }
  await expect(
    page.getByRole('button', { name: '인증 코드 재발송' }),
  ).toBeEnabled()
}

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

const phoneInputs = (page: Page) =>
  page.locator('input[type="tel"], input[name*="phone" i]')

test('정상 가입은 세 요청 뒤 로그인 화면으로 가고 로그인 상태가 되지 않는다', async ({
  page,
}) => {
  await requestCode(page)
  await expect(phoneInputs(page)).toHaveCount(0)
  await confirmCode(page)
  await expect(page.getByLabel('인증된 이메일')).toHaveValue(EMAIL)
  await expect(page.getByLabel('인증된 이메일')).toHaveAttribute('readonly')
  await expect(phoneInputs(page)).toHaveCount(0)
  await fillDetails(page)
  await submitDetails(page)

  await expect(page).toHaveURL(/#\/login$/)
  await expect(toast(page)).toContainText('회원가입이 완료됐어요.')
  expect(getAuth(page).state.loggedIn).toBe(false)
  await expect(page.getByRole('heading', { name: '로그인' })).toBeVisible()

  const requests = getAuth(page).state.signupRequests
  expect(requests.map((request) => request.step)).toEqual([
    'request',
    'confirm',
    'signup',
  ])
  expect(requests[0].body).toEqual({ email: EMAIL })
  expect(requests[1].body).toEqual({ email: EMAIL, code: CODE })
  // 가입 완료에는 이메일을 보내지 않는다(가입 쿠키의 컨텍스트를 쓴다).
  expect(requests[2].body).toEqual({ password: PASSWORD, nickname: '새회원' })
  for (const request of requests) expect(request.xsrf).toBeTruthy()

  const storage = await page.evaluate(async () => ({
    local: JSON.stringify({ ...localStorage }),
    session: JSON.stringify({ ...sessionStorage }),
    cookie: document.cookie,
    databases: (await indexedDB.databases()).map((db) => db.name),
  }))
  for (const value of [storage.local, storage.session, storage.cookie]) {
    expect(value).not.toContain(PASSWORD)
    expect(value).not.toContain(CODE)
  }
  expect(storage.local).toBe('{}')
  expect(storage.session).toBe('{}')
  expect(storage.databases).toEqual([])
})

test('코드 요청 실패는 1단계에 머물고 다시 요청할 수 있다', async ({
  page,
}) => {
  getAuth(page).failSignup(
    'request',
    'network',
    { status: 429, code: 'EMAIL_VERIFICATION_RESEND_TOO_SOON' },
    { status: 403, code: 'ACCESS_DENIED' },
  )
  await page.goto('/#/signup')
  await page.getByLabel('이메일 주소').fill(EMAIL)
  const send = page.getByRole('button', { name: '인증 코드 보내기' })
  const alert = page.getByRole('alert')

  await send.click()
  await expect(alert).toHaveText(
    '연결에 실패했어요. 잠시 뒤 다시 시도해주세요.',
  )
  await send.click()
  await expect(alert).toHaveText(
    '인증 코드를 너무 자주 요청했어요. 잠시 뒤 다시 요청해주세요.',
  )
  // CSRF 쿠키가 있는 403은 재시도하지 않고 일반 실패로 안내한다.
  await send.click()
  await expect(alert).toHaveText('요청을 처리하지 못했어요. 다시 시도해주세요.')
  await send.click()
  await expect(page.getByLabel('인증 코드 6자리')).toBeVisible()
  expect(getAuth(page).state.signupRequests).toHaveLength(4)
})

test('코드 확인 오류를 구분하고 같은 단계에서 다시 입력한다', async ({
  page,
}) => {
  getAuth(page).failSignup(
    'confirm',
    { status: 400, code: 'EMAIL_VERIFICATION_CODE_MISMATCH' },
    { status: 400, code: 'EMAIL_VERIFICATION_CODE_EXPIRED' },
    { status: 429, code: 'EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED' },
  )
  await requestCode(page)
  const alert = page.getByRole('alert')

  await confirmCode(page)
  await expect(alert).toHaveText('인증 코드가 일치하지 않아요.')
  await confirmCode(page)
  await expect(alert).toHaveText('인증 코드가 만료됐어요. 다시 발송해주세요.')
  await confirmCode(page)
  await expect(alert).toHaveText(
    '확인 횟수를 초과했어요. 새 코드를 요청해주세요.',
  )
  await expect(page.getByRole('link', { name: '로그인하러 가기' })).toHaveCount(
    0,
  )
  await confirmCode(page)
  await expect(page.getByLabel('인증된 이메일')).toHaveValue(EMAIL)
})

test('재발송은 성공했을 때만 타이머를 다시 시작한다', async ({ page }) => {
  // 앱이 뜬 뒤에 가짜 타이머를 건다. 고정 시각(setFixedTime)에서 먼저 걸면 앱 시작 타이머가 흐르지 않을 수 있다.
  await page.goto('/#/signup')
  await page.getByLabel('이메일 주소').fill(EMAIL)
  await page.clock.install()
  await page.getByRole('button', { name: '인증 코드 보내기' }).click()
  await expect(
    page.getByRole('button', { name: '60초 후 재발송' }),
  ).toBeDisabled()

  await passResendInterval(page)
  getAuth(page).failSignup('request', {
    status: 429,
    code: 'EMAIL_VERIFICATION_RESEND_TOO_SOON',
  })
  const resend = page.getByRole('button', { name: '인증 코드 재발송' })
  await resend.click()
  await expect(page.getByRole('alert')).toHaveText(
    '인증 코드를 너무 자주 요청했어요. 잠시 뒤 다시 요청해주세요.',
  )
  await expect(resend).toBeEnabled()

  await resend.click()
  await expect(
    page.getByRole('button', { name: '60초 후 재발송' }),
  ).toBeDisabled()
  await expect(page.getByRole('alert')).toHaveCount(0)
  const requests = getAuth(page).state.signupRequests
  expect(requests.map((request) => request.step)).toEqual([
    'request',
    'request',
    'request',
  ])
  for (const request of requests) expect(request.body).toEqual({ email: EMAIL })
})

test('이메일 다시 입력은 이메일을 남긴 채 1단계로 돌아간다', async ({
  page,
}) => {
  await requestCode(page, 'typo@example.com')
  await page.getByRole('button', { name: '이메일 다시 입력' }).click()
  await expect(page.getByLabel('이메일 주소')).toHaveValue('typo@example.com')
  await page.getByLabel('이메일 주소').fill(EMAIL)
  await page.getByRole('button', { name: '인증 코드 보내기' }).click()
  await confirmCode(page)
  await expect(page.getByLabel('인증된 이메일')).toHaveValue(EMAIL)
  expect(getAuth(page).state.signupRequests.at(-1)?.body).toEqual({
    email: EMAIL,
    code: CODE,
  })
})

test('이미 가입된 이메일은 로그인 링크로 안내한다', async ({ page }) => {
  getAuth(page).failSignup('confirm', { status: 409, code: 'DUPLICATE_EMAIL' })
  await requestCode(page)
  await confirmCode(page)
  await expect(page.getByRole('alert')).toHaveText(
    '이미 가입된 이메일이에요. 로그인해주세요.',
  )
  await page.getByRole('link', { name: '로그인하러 가기' }).click()
  await expect(page).toHaveURL(/#\/login$/)
})

test('가입 완료 단계의 필드 오류는 입력란 아래에 표시하고 다시 제출한다', async ({
  page,
}) => {
  getAuth(page).failSignup(
    'signup',
    {
      status: 400,
      code: 'VALIDATION_FAILED',
      fieldErrors: [
        { field: 'password', reason: '서버 문구' },
        { field: 'nickname', reason: '서버 문구' },
      ],
    },
    { status: 409, code: 'DUPLICATE_NICKNAME' },
  )
  await toDetails(page)
  const nickname = page.getByLabel('닉네임', { exact: true })
  const password = page.getByLabel('비밀번호')

  await fillDetails(page, '중복회원')
  await submitDetails(page)
  await expect(nickname).toHaveAttribute('aria-invalid', 'true')
  await expect(password).toHaveAttribute('aria-invalid', 'true')
  await expect(page.locator('#signup-nickname-error')).toHaveText(
    '닉네임은 한글·영문·숫자·_를 사용해 2~10자로 입력해주세요.',
  )
  await expect(page.locator('#signup-password-error')).toHaveText(
    '비밀번호는 공백 없이 영문·숫자·특수문자를 포함해 8~64자로 입력해주세요.',
  )
  await expect(page.getByText('서버 문구')).toHaveCount(0)
  await expect(nickname).toBeFocused()

  await submitDetails(page)
  await expect(page.locator('#signup-nickname-error')).toHaveText(
    '이미 사용 중인 닉네임이에요.',
  )
  await expect(page.locator('#signup-password-error')).toHaveCount(0)
  await expect(page.getByLabel('인증된 이메일')).toHaveValue(EMAIL)

  await nickname.fill('새회원')
  await submitDetails(page)
  await expect(page).toHaveURL(/#\/login$/)
  const signups = getAuth(page)
    .state.signupRequests.filter((request) => request.step === 'signup')
    .map((request) => request.body)
  expect(signups).toEqual([
    { password: PASSWORD, nickname: '중복회원' },
    { password: PASSWORD, nickname: '중복회원' },
    { password: PASSWORD, nickname: '새회원' },
  ])
})

test('화면 사전 검증에 걸리면 요청하지 않는다', async ({ page }) => {
  await toDetails(page)
  await page.getByLabel('닉네임', { exact: true }).fill('a')
  await page.getByLabel('비밀번호').fill('password')
  await submitDetails(page)
  await expect(page.locator('#signup-nickname-error')).toBeVisible()
  await expect(page.locator('#signup-password-error')).toBeVisible()
  expect(
    getAuth(page).state.signupRequests.filter(
      (request) => request.step === 'signup',
    ),
  ).toHaveLength(0)
})

test('가입 완료 단계에서 이미 가입된 이메일이면 로그인 링크로 안내한다', async ({
  page,
}) => {
  getAuth(page).failSignup('signup', { status: 409, code: 'DUPLICATE_EMAIL' })
  await toDetails(page)
  await fillDetails(page)
  await submitDetails(page)
  await expect(page.getByRole('alert')).toHaveText(
    '이미 가입된 이메일이에요. 로그인해주세요.',
  )
  await expect(
    page.getByRole('link', { name: '로그인하러 가기' }),
  ).toBeVisible()
})

test('가입 컨텍스트가 무효면 이메일을 남긴 채 1단계로 돌아간다', async ({
  page,
}) => {
  getAuth(page).failSignup('signup', {
    status: 401,
    code: 'EMAIL_SIGNUP_CONTEXT_INVALID',
  })
  await toDetails(page)
  const refreshBefore = getAuth(page).state.refreshCount
  await fillDetails(page)
  await submitDetails(page)
  await expect(page.getByLabel('이메일 주소')).toHaveValue(EMAIL)
  await expect(page.getByRole('alert')).toHaveText(
    '가입 시간이 지났어요. 인증 코드를 다시 받아주세요.',
  )
  // 가입 경로의 401은 재발급 대상이 아니다.
  expect(getAuth(page).state.refreshCount).toBe(refreshBefore)

  await page.getByRole('button', { name: '인증 코드 보내기' }).click()
  await confirmCode(page)
  await fillDetails(page)
  await submitDetails(page)
  await expect(page).toHaveURL(/#\/login$/)
})

test('처리 중에는 버튼을 막고 실패 후 다시 제출할 수 있다', async ({
  page,
}) => {
  const releaseRequest = await holdNext(page, '/api/v1/auth/email-verification')
  await page.goto('/#/signup')
  await page.getByLabel('이메일 주소').fill(EMAIL)
  await page.getByRole('button', { name: '인증 코드 보내기' }).click()
  await expect(page.getByRole('button', { name: '보내는 중…' })).toBeDisabled()
  releaseRequest()
  await expect(page.getByLabel('인증 코드 6자리')).toBeVisible()

  const releaseConfirm = await holdNext(
    page,
    '/api/v1/auth/email-verification/confirm',
  )
  await page.getByLabel('인증 코드 6자리').fill(CODE)
  await page.getByRole('button', { name: '코드 확인' }).click()
  await expect(page.getByRole('button', { name: '코드 확인' })).toBeDisabled()
  await expect(
    page.getByRole('button', { name: '이메일 다시 입력' }),
  ).toBeDisabled()
  releaseConfirm()
  await expect(page.getByLabel('인증된 이메일')).toHaveValue(EMAIL)

  getAuth(page).failSignup('signup', 'network')
  await fillDetails(page)
  await submitDetails(page)
  await expect(page.getByRole('alert')).toHaveText(
    '연결에 실패했어요. 잠시 뒤 다시 시도해주세요.',
  )
  const releaseSignup = await holdNext(page, '/api/v1/auth/signup')
  await submitDetails(page)
  await expect(
    page.getByRole('button', { name: '가입하는 중…' }),
  ).toBeDisabled()
  releaseSignup()
  await expect(page).toHaveURL(/#\/login$/)

  const steps = getAuth(page).state.signupRequests.map(
    (request) => request.step,
  )
  expect(steps).toEqual(['request', 'confirm', 'signup', 'signup'])
})
