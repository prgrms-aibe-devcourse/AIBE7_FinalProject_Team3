import { AxiosError, type AxiosResponse } from 'axios'
import { afterEach, beforeEach, expect, test } from 'vitest'
import { api } from '../../client'
import { logout } from '../auth'
import { configureAuthSession, endLogout, installAuthSession } from '../session'

type AdapterConfig = {
  url?: string
  method?: string
  data?: unknown
}

type Result = { status: number; data?: unknown }

const calls: string[] = []

function respond(
  config: AdapterConfig,
  status: number,
  data?: unknown,
): AxiosResponse {
  const response = {
    data,
    status,
    statusText: '',
    headers: {},
    config,
  } as unknown as AxiosResponse
  if (status >= 200 && status < 300) return response
  throw new AxiosError(
    'request failed',
    String(status),
    config as never,
    null,
    response,
  )
}

function useAdapter(
  handler: (config: AdapterConfig) => Result | Promise<Result>,
) {
  calls.length = 0
  api.defaults.adapter = async (config) => {
    calls.push(`${config.method?.toUpperCase()} ${config.url}`)
    const result = await handler(config)
    return respond(config, result.status, result.data)
  }
}

const countCalls = (suffix: string) =>
  calls.filter((call) => call.endsWith(suffix)).length

let sessionExpired = 0

beforeEach(() => {
  sessionExpired = 0
  configureAuthSession({ onSessionExpired: () => (sessionExpired += 1) })
  installAuthSession()
})

afterEach(() => {
  endLogout()
  delete (globalThis as Record<string, unknown>).document
})

test('공통 클라이언트는 자격 증명과 CSRF 헤더를 사용한다', () => {
  expect(api.defaults.withCredentials).toBe(true)
  expect(api.defaults.xsrfCookieName).toBe('XSRF-TOKEN')
  expect(api.defaults.xsrfHeaderName).toBe('X-XSRF-TOKEN')
  expect(api.defaults.withXSRFToken).toBe(true)
})

test('보호 API 401은 재발급 후 한 번 재시도한다', async () => {
  let widget = 0
  useAdapter((config) => {
    if (config.url === '/auth/csrf') return { status: 204 }
    if (config.url === '/auth/refresh') return { status: 204 }
    if (config.url === '/widgets') {
      widget += 1
      return { status: widget === 1 ? 401 : 200, data: { ok: true } }
    }
    throw new Error(`예상하지 못한 요청: ${config.url}`)
  })

  const response = await api.get('/widgets')
  expect(response.status).toBe(200)
  expect(widget).toBe(2)
  expect(countCalls('/auth/refresh')).toBe(1)
  expect(sessionExpired).toBe(0)
})

test('동시 401은 재발급 요청을 하나로 모은다', async () => {
  let widget = 0
  useAdapter((config) => {
    if (config.url === '/auth/csrf') return { status: 204 }
    if (config.url === '/auth/refresh') return { status: 204 }
    if (config.url === '/widgets') {
      widget += 1
      return { status: widget <= 3 ? 401 : 200, data: { ok: true } }
    }
    throw new Error(`예상하지 못한 요청: ${config.url}`)
  })

  await Promise.all([
    api.get('/widgets'),
    api.get('/widgets'),
    api.get('/widgets'),
  ])
  expect(widget).toBe(6)
  expect(countCalls('/auth/refresh')).toBe(1)
})

test('재발급 401은 원래 요청을 한 번 더 보낸 뒤 비로그인으로 전환한다', async () => {
  useAdapter((config) => {
    if (config.url === '/auth/csrf') return { status: 204 }
    if (config.url === '/auth/refresh') return { status: 401 }
    if (config.url === '/widgets') return { status: 401 }
    throw new Error(`예상하지 못한 요청: ${config.url}`)
  })

  await expect(api.get('/widgets')).rejects.toMatchObject({
    response: { status: 401 },
  })
  expect(countCalls('/auth/refresh')).toBe(1)
  expect(countCalls('/widgets')).toBe(2)
  expect(sessionExpired).toBe(1)
})

test('재발급 500은 로그아웃하지 않고 원래 오류를 전파한다', async () => {
  useAdapter((config) => {
    if (config.url === '/auth/csrf') return { status: 204 }
    if (config.url === '/auth/refresh') return { status: 500 }
    if (config.url === '/widgets') return { status: 401 }
    throw new Error(`예상하지 못한 요청: ${config.url}`)
  })

  await expect(api.get('/widgets')).rejects.toMatchObject({
    response: { status: 401 },
  })
  expect(countCalls('/auth/refresh')).toBe(1)
  expect(countCalls('/widgets')).toBe(1)
  expect(sessionExpired).toBe(0)
})

test('인증 경로의 401은 재발급하지 않는다', async () => {
  useAdapter((config) => {
    if (config.url === '/auth/csrf') return { status: 204 }
    if (config.url === '/auth/login') return { status: 401 }
    throw new Error(`예상하지 못한 요청: ${config.url}`)
  })

  await expect(api.post('/auth/login', {})).rejects.toMatchObject({
    response: { status: 401 },
  })
  expect(countCalls('/auth/refresh')).toBe(0)
})

test('쿠키 없는 403은 CSRF를 다시 받아 한 번 재시도한다', async () => {
  let widget = 0
  useAdapter((config) => {
    if (config.url === '/auth/csrf') return { status: 204 }
    if (config.url === '/widgets') {
      widget += 1
      return { status: widget === 1 ? 403 : 204 }
    }
    throw new Error(`예상하지 못한 요청: ${config.url}`)
  })

  const response = await api.post('/widgets', {})
  expect(response.status).toBe(204)
  expect(widget).toBe(2)
})

test('쿠키가 있는 403은 권한 오류로 전파한다', async () => {
  ;(globalThis as Record<string, unknown>).document = {
    cookie: 'XSRF-TOKEN=test',
  }
  useAdapter((config) => {
    if (config.url === '/widgets') return { status: 403 }
    throw new Error(`예상하지 못한 요청: ${config.url}`)
  })

  await expect(api.post('/widgets', {})).rejects.toMatchObject({
    response: { status: 403 },
  })
  expect(countCalls('/widgets')).toBe(1)
})

test('로그아웃은 진행 중인 재발급이 끝난 뒤 전송되고 원래 요청은 재시도하지 않는다', async () => {
  let widget = 0
  let releaseRefresh: () => void = () => undefined
  let markRefreshStarted: () => void = () => undefined
  const refreshStarted = new Promise<void>((resolve) => {
    markRefreshStarted = resolve
  })
  const events: string[] = []

  useAdapter(async (config) => {
    if (config.url === '/auth/csrf') return { status: 204 }
    if (config.url === '/widgets') {
      widget += 1
      return { status: 401 }
    }
    if (config.url === '/auth/refresh') {
      events.push('refresh-start')
      markRefreshStarted()
      await new Promise<void>((resolve) => {
        releaseRefresh = resolve
      })
      events.push('refresh-done')
      return { status: 204 }
    }
    if (config.url === '/auth/logout') {
      events.push('logout')
      return { status: 204 }
    }
    throw new Error(`예상하지 못한 요청: ${config.url}`)
  })

  const pending = api.get('/widgets')
  await refreshStarted
  const loggingOut = logout()
  await Promise.resolve()
  expect(events).not.toContain('logout')

  releaseRefresh()
  await Promise.allSettled([pending, loggingOut])
  expect(widget).toBe(1)
  expect(events).toEqual(['refresh-start', 'refresh-done', 'logout'])
})
