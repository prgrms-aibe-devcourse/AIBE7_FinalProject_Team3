import type { AxiosError, InternalAxiosRequestConfig } from 'axios'
import { api } from '../client'
import { hasXsrfCookie, prepareCsrf } from './csrf'

type AuthConfig = InternalAxiosRequestConfig & {
  __authRetried?: boolean
  __csrfRetried?: boolean
}

// 재발급 대상에서 제외하는 인증 경로. 이 응답의 401은 입력 오류이거나 세션 만료와 무관하다.
const AUTH_PATHS = [
  '/auth/login',
  '/auth/refresh',
  '/auth/logout',
  '/auth/csrf',
  '/auth/signup',
  '/auth/email-verification',
]

const isAuthPath = (url = '') => AUTH_PATHS.some((path) => url.includes(path))

let onSessionExpired: () => void = () => undefined

export function configureAuthSession(handler: {
  onSessionExpired: () => void
}) {
  onSessionExpired = handler.onSessionExpired
}

type RefreshOutcome = { retryable: boolean }
let refreshPromise: Promise<RefreshOutcome> | null = null
let logoutInProgress = false

// 같은 탭의 동시 401은 재발급 요청 하나를 공유한다.
export function refreshSession(): Promise<RefreshOutcome> {
  if (!refreshPromise) {
    refreshPromise = api
      .post('/auth/refresh')
      .then<RefreshOutcome>(() => ({ retryable: true }))
      .catch<RefreshOutcome>((error: AxiosError) => ({
        // 재발급 401은 다른 탭이 이미 교체했을 수 있으므로 원래 요청을 한 번 더 시도한다.
        // 그 외(네트워크·5xx)는 인증 실패가 아니므로 재시도 없이 원래 오류를 전파한다.
        retryable: error.response?.status === 401,
      }))
      .finally(() => {
        refreshPromise = null
      })
  }
  return refreshPromise
}

// 로그아웃은 진행 중인 재발급이 끝난 뒤에 보내, 삭제한 쿠키가 재발급 Set-Cookie로 되살아나는 것을 막는다.
export async function waitForActiveRefresh() {
  await refreshPromise
}

export function beginLogout() {
  logoutInProgress = true
}

export function endLogout() {
  logoutInProgress = false
}

async function resolveUnauthorized(
  error: AxiosError,
  config: AuthConfig,
): Promise<unknown> {
  // 로그아웃 중에는 재발급하지 않는다(쿠키를 되살리지 않는다).
  if (logoutInProgress) throw error
  config.__authRetried = true
  const outcome = await refreshSession()
  if (!outcome.retryable || logoutInProgress) throw error
  return api.request(config)
}

async function resolveCsrfFailure(
  error: AxiosError,
  config: AuthConfig,
): Promise<unknown> {
  // 쿠키가 있으면 권한 부족이므로 그대로 전파한다.
  if (hasXsrfCookie()) throw error
  if (config.__csrfRetried) throw error
  config.__csrfRetried = true
  await prepareCsrf()
  return api.request(config)
}

function handleRejection(error: AxiosError): Promise<unknown> {
  const config = error.config as AuthConfig | undefined
  const status = error.response?.status
  if (config && status === 403 && !config.__csrfRetried) {
    return resolveCsrfFailure(error, config)
  }
  if (
    config &&
    status === 401 &&
    !config.__authRetried &&
    !isAuthPath(config.url)
  ) {
    return resolveUnauthorized(error, config)
  }
  if (
    config &&
    status === 401 &&
    config.__authRetried &&
    !isAuthPath(config.url)
  ) {
    onSessionExpired()
    return Promise.reject(error)
  }
  return Promise.reject(error)
}

let installed = false

export function installAuthSession() {
  if (installed) return
  installed = true
  api.interceptors.request.use(async (config) => {
    const method = (config.method ?? 'get').toUpperCase()
    const stateChanging = ['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)
    if (stateChanging && !hasXsrfCookie()) await prepareCsrf()
    return config
  })
  api.interceptors.response.use((response) => response, handleRejection)
}
