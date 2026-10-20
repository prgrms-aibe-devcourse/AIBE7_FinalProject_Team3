import type { Page, Route } from '@playwright/test'
import { json } from './api/shared'

export const DEMO_EMAIL = 'grab@example.com'
export const DEMO_PASSWORD = 'password1234'

export type AuthProvider = 'LOCAL' | 'KAKAO' | 'GOOGLE'
type LoginFailure = 'credentials' | 'disabled' | null

// LOCAL 가입 3단계(MEMBER_AUTH 1.2.1~1.2.3). 오류는 failSignup으로 다음 응답부터 차례로 바꾼다.
export type SignupStep = 'request' | 'confirm' | 'signup'
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
}

export type AuthState = {
  loggedIn: boolean
  roles: string[]
  provider: AuthProvider
  nickname: string
  email: string
  // 다음 보호 API 요청을 한 번 401로 만든다. 재발급 후 재시도 성공 시나리오에 쓴다.
  accessExpired: boolean
  // Access·Refresh 모두 무효. 재발급 실패 → 비로그인 전환 시나리오에 쓴다.
  invalidSession: boolean
  loginFailure: LoginFailure
  logoutFails: boolean
  refreshCount: number
  signupReplies: Record<SignupStep, SignupReply[]>
  signupRequests: SignupRequest[]
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
    profileImageUrl: null,
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
    accessExpired: false,
    invalidSession: false,
    loginFailure: null,
    logoutFails: false,
    refreshCount: 0,
    signupReplies: { request: [], confirm: [], signup: [] },
    signupRequests: [],
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
      if (body.password !== DEMO_PASSWORD) {
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
