import type { Page, Route } from '@playwright/test'
import { json } from './api/shared'

export const DEMO_EMAIL = 'grab@example.com'
export const DEMO_PASSWORD = 'password1234'

export type AuthProvider = 'LOCAL' | 'KAKAO' | 'GOOGLE'
type LoginFailure = 'credentials' | 'disabled' | null

// LOCAL 가입 3단계(MEMBER_AUTH 1.2.1~1.2.3)와 소셜 가입 완료(1.6, social). 오류는 failSignup으로 다음 응답부터 차례로 바꾼다.
export type SignupStep = 'request' | 'confirm' | 'signup' | 'social'
export type SignupReply =
  | {
      status: number
      code: string
      fieldErrors?: { field: string; reason: string }[]
    }
  | 'network'
export type SignupRequest = {
  step: SignupStep
  body: unknown
  xsrf: string | undefined
}

const SIGNUP_PATHS: Record<string, SignupStep> = {
  '/api/v1/auth/email-verification': 'request',
  '/api/v1/auth/email-verification/confirm': 'confirm',
  '/api/v1/auth/signup': 'signup',
  '/api/v1/auth/oauth2/signup': 'social',
}

// 내 정보 수정(1.10 닉네임, 1.11 비밀번호, GR-131)과 프로필 이미지(IMAGE_UPLOAD 1.2, 1.12·1.13, GR-132).
// 오류는 failAccount로 다음 응답부터 차례로 바꾼다. 프로필 이미지는 같은 경로에 PUT·DELETE가 있어 메서드까지 본다.
// Storage 업로드(storage.grab.test/upload/**)는 fixture의 installDropImageUploads가 처리한다.
export type AccountApi =
  | 'nickname'
  | 'password'
  | 'profileImageUploadUrl'
  | 'profileImage'
  | 'profileImageDelete'
export type AccountRequest = {
  api: AccountApi
  body: unknown
  xsrf: string | undefined
}

const ACCOUNT_PATHS: Record<string, AccountApi> = {
  'PATCH /api/v1/users/me': 'nickname',
  'PATCH /api/v1/users/me/password': 'password',
  'POST /api/v1/users/me/profile-image/upload-url': 'profileImageUploadUrl',
  'PUT /api/v1/users/me/profile-image': 'profileImage',
  'DELETE /api/v1/users/me/profile-image': 'profileImageDelete',
}

// 발급마다 새 UUID 경로를 준다(서버와 같음). 표시 이미지는 fixture가 로컬 이미지로 바꾼다.
const profileImageUpload = (sequence: number) => {
  const id = `00000000-0000-4000-8000-${String(sequence).padStart(12, '0')}`
  return {
    imageId: id,
    uploadUrl: `https://storage.grab.test/upload/profile/${id}?token=fixture`,
    imageUrl: `https://storage.grab.test/images/profile/${id}.png`,
    expiresAt: '2026-10-01T12:00:00+09:00',
  }
}

// 소셜 로그인 시작(1.4)은 실제 제공자로 가지 않고 콜백 결과 주소로 바로 302한다(GR-47 M00-05).
const SOCIAL_START_PROVIDERS: Record<string, AuthProvider> = {
  '/api/v1/auth/oauth2/kakao': 'KAKAO',
  '/api/v1/auth/oauth2/google': 'GOOGLE',
}

export type AuthState = {
  loggedIn: boolean
  roles: string[]
  provider: AuthProvider
  nickname: string
  email: string
  // 등록(1.12)·삭제(1.13)가 바꾸고 users/me가 돌려주는 값. 새로고침 유지 확인에 쓴다.
  profileImageUrl: string | null
  profileImageUploads: number
  // LOCAL 로그인·비밀번호 변경(1.11)이 비교하는 현재 비밀번호
  password: string
  // 다음 보호 API 요청을 한 번 401로 만든다. 재발급 후 재시도 성공 시나리오에 쓴다.
  accessExpired: boolean
  // Access·Refresh 모두 무효. 재발급 실패 → 비로그인 전환 시나리오에 쓴다.
  invalidSession: boolean
  loginFailure: LoginFailure
  logoutFails: boolean
  refreshCount: number
  signupReplies: Record<SignupStep, SignupReply[]>
  signupRequests: SignupRequest[]
  accountReplies: Record<AccountApi, SignupReply[]>
  accountRequests: AccountRequest[]
  // 다음 소셜 시작이 콜백에 붙일 쿼리(result=… 또는 error=…). result=success면 그 제공자 회원으로 로그인된다.
  socialCallbackQuery: string
  socialStarts: string[]
}

export type AuthController = {
  state: AuthState
  setRoles: (roles: string[]) => void
  setProvider: (provider: AuthProvider) => void
  expireAccess: () => void
  invalidateSession: () => void
  failLogin: (kind: Exclude<LoginFailure, null>) => void
  failLogout: () => void
  failSignup: (step: SignupStep, ...replies: SignupReply[]) => void
  failAccount: (api: AccountApi, ...replies: SignupReply[]) => void
  setSocialCallback: (query: string) => void
}

const controllers = new WeakMap<Page, AuthController>()

export function getAuth(page: Page): AuthController {
  const controller = controllers.get(page)
  if (!controller) throw new Error('installAuthApi가 먼저 설치되어야 합니다.')
  return controller
}

const unauthorized = (route: Route, code: string) =>
  json(
    route,
    { success: false, data: null, error: { code, message: code } },
    401,
  )

const userBody = (state: AuthState) => ({
  success: true,
  data: {
    userId: '00000000-0000-0000-0000-000000000001',
    email: state.email,
    nickname: state.nickname,
    provider: state.provider,
    roles: state.roles,
    profileImageUrl: state.profileImageUrl,
    createdAt: '2026-10-15T00:00:00Z',
  },
  message: null,
})

export function installAuthApi(
  page: Page,
  initial: Partial<AuthState> = {},
): AuthController {
  const state: AuthState = {
    loggedIn: false,
    roles: ['USER'],
    provider: 'LOCAL',
    nickname: '드롭헌터',
    email: DEMO_EMAIL,
    profileImageUrl: null,
    profileImageUploads: 0,
    password: DEMO_PASSWORD,
    accessExpired: false,
    invalidSession: false,
    loginFailure: null,
    logoutFails: false,
    refreshCount: 0,
    signupReplies: { request: [], confirm: [], signup: [], social: [] },
    signupRequests: [],
    accountReplies: {
      nickname: [],
      password: [],
      profileImageUploadUrl: [],
      profileImage: [],
      profileImageDelete: [],
    },
    accountRequests: [],
    socialCallbackQuery: 'result=success',
    socialStarts: [],
    ...initial,
  }
  const controller: AuthController = {
    state,
    setRoles: (roles) => {
      state.roles = roles
    },
    setProvider: (provider) => {
      state.provider = provider
    },
    expireAccess: () => {
      state.accessExpired = true
    },
    invalidateSession: () => {
      state.invalidSession = true
    },
    failLogin: (kind) => {
      state.loginFailure = kind
    },
    failLogout: () => {
      state.logoutFails = true
    },
    failSignup: (step, ...replies) => {
      state.signupReplies[step].push(...replies)
    },
    failAccount: (api, ...replies) => {
      state.accountReplies[api].push(...replies)
    },
    setSocialCallback: (query) => {
      state.socialCallbackQuery = query
    },
  }
  controllers.set(page, controller)

  // installPublicApi 뒤에 등록되어 인증 경로만 처리하고 나머지는 공개 API로 넘긴다.
  void page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const method = route.request().method()

    if (path === '/api/v1/auth/csrf') {
      await route.fulfill({
        status: 204,
        headers: { 'set-cookie': 'XSRF-TOKEN=e2e-csrf; Path=/' },
      })
      return
    }

    if (path === '/api/v1/auth/login' && method === 'POST') {
      if (state.loginFailure === 'credentials') {
        await unauthorized(route, 'INVALID_CREDENTIALS')
        return
      }
      if (state.loginFailure === 'disabled') {
        await json(
          route,
          {
            success: false,
            data: null,
            error: { code: 'ACCOUNT_DISABLED', message: 'disabled' },
          },
          403,
        )
        return
      }
      const body = route.request().postDataJSON() as {
        email?: string
        password?: string
      }
      if (body.password !== state.password) {
        await unauthorized(route, 'INVALID_CREDENTIALS')
        return
      }
      state.loggedIn = true
      state.email = body.email ?? state.email
      await json(route, {
        success: true,
        data: { user: userBody(state).data },
        message: null,
      })
      return
    }

    const signupStep = SIGNUP_PATHS[path]
    if (signupStep && method === 'POST') {
      state.signupRequests.push({
        step: signupStep,
        body: route.request().postDataJSON(),
        xsrf: route.request().headers()['x-xsrf-token'],
      })
      const reply = state.signupReplies[signupStep].shift()
      if (reply === 'network') {
        await route.abort('failed')
        return
      }
      if (reply) {
        await json(
          route,
          {
            success: false,
            data: null,
            error: {
              code: reply.code,
              message: reply.code,
              fieldErrors: reply.fieldErrors ?? [],
            },
          },
          reply.status,
        )
        return
      }
      // 소셜 가입 완료(201)는 인증 쿠키를 발급하므로 로그인 상태가 된다.
      if (signupStep === 'social') {
        const body = route.request().postDataJSON() as { nickname?: string }
        state.loggedIn = true
        state.nickname = body.nickname ?? state.nickname
        await json(
          route,
          {
            success: true,
            data: {
              user: {
                userId: userBody(state).data.userId,
                email: state.email,
                nickname: state.nickname,
                roles: state.roles,
              },
            },
          },
          201,
        )
        return
      }
      // 가입 완료(201)는 인증 쿠키를 발급하지 않으므로 로그인 상태를 바꾸지 않는다.
      if (signupStep === 'signup') {
        const body = route.request().postDataJSON() as { nickname?: string }
        await json(
          route,
          {
            success: true,
            data: {
              userId: '00000000-0000-0000-0000-000000000002',
              email: 'new@example.com',
              nickname: body.nickname ?? '',
              roles: ['USER'],
              createdAt: '2026-10-20T00:00:00Z',
            },
          },
          201,
        )
        return
      }
      await route.fulfill({ status: 204 })
      return
    }

    const socialProvider = SOCIAL_START_PROVIDERS[path]
    if (socialProvider && method === 'GET') {
      state.socialStarts.push(path)
      if (state.socialCallbackQuery === 'result=success') {
        state.loggedIn = true
        state.provider = socialProvider
      }
      if (state.socialCallbackQuery === 'result=signup_required') {
        state.provider = socialProvider
      }
      await route.fulfill({
        status: 302,
        headers: { location: `/#/auth/callback?${state.socialCallbackQuery}` },
      })
      return
    }

    if (path === '/api/v1/auth/refresh' && method === 'POST') {
      state.refreshCount += 1
      if (state.invalidSession || !state.loggedIn) {
        await unauthorized(route, 'INVALID_TOKEN')
        return
      }
      await route.fulfill({ status: 204 })
      return
    }

    if (path === '/api/v1/auth/logout' && method === 'POST') {
      if (state.logoutFails) {
        await json(
          route,
          {
            success: false,
            data: null,
            error: { code: 'INTERNAL_SERVER_ERROR', message: 'logout' },
          },
          500,
        )
        return
      }
      state.loggedIn = false
      await route.fulfill({ status: 204 })
      return
    }

    const accountApi = ACCOUNT_PATHS[`${method} ${path}`]
    if (accountApi) {
      if (!state.loggedIn || state.invalidSession) {
        await unauthorized(route, 'AUTHENTICATION_REQUIRED')
        return
      }
      // DELETE는 본문이 없어 null이다
      const body = (route.request().postDataJSON() ?? {}) as {
        nickname?: string
        currentPassword?: string
        newPassword?: string
        imageUrl?: string
      }
      state.accountRequests.push({
        api: accountApi,
        body,
        xsrf: route.request().headers()['x-xsrf-token'],
      })
      const reply = state.accountReplies[accountApi].shift()
      if (reply === 'network') {
        await route.abort('failed')
        return
      }
      const fail = (
        status: number,
        code: string,
        fieldErrors: { field: string; reason: string }[] = [],
      ) =>
        json(
          route,
          {
            success: false,
            data: null,
            error: { code, message: code, fieldErrors },
          },
          status,
        )
      if (reply) {
        await fail(reply.status, reply.code, reply.fieldErrors)
        return
      }
      if (accountApi === 'nickname') {
        // 서버처럼 앞뒤 공백을 제거해 저장하고 1.9와 같은 7개 필드로 응답한다
        state.nickname = (body.nickname ?? '').trim()
        await json(route, userBody(state))
        return
      }
      if (accountApi === 'profileImageUploadUrl') {
        state.profileImageUploads += 1
        await json(route, {
          success: true,
          data: profileImageUpload(state.profileImageUploads),
          message: null,
        })
        return
      }
      if (accountApi === 'profileImage') {
        // 1.12 응답은 1.10과 달리 두 필드다
        state.profileImageUrl = body.imageUrl ?? null
        await json(route, {
          success: true,
          data: {
            userId: userBody(state).data.userId,
            profileImageUrl: state.profileImageUrl,
          },
          message: null,
        })
        return
      }
      if (accountApi === 'profileImageDelete') {
        state.profileImageUrl = null
        await route.fulfill({ status: 204 })
        return
      }
      if (body.currentPassword !== state.password) {
        await fail(400, 'PASSWORD_MISMATCH')
        return
      }
      state.password = body.newPassword ?? state.password
      await route.fulfill({ status: 204 })
      return
    }

    if (path === '/api/v1/users/me' && method === 'GET') {
      if (!state.loggedIn || state.invalidSession) {
        await unauthorized(route, 'AUTHENTICATION_REQUIRED')
        return
      }
      if (state.accessExpired) {
        state.accessExpired = false
        await unauthorized(route, 'INVALID_TOKEN')
        return
      }
      await json(route, userBody(state))
      return
    }

    await route.fallback()
  })

  return controller
}
