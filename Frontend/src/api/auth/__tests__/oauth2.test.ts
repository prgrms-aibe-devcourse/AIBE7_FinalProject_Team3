import { AxiosError, type AxiosResponse } from 'axios'
import { beforeEach, expect, test } from 'vitest'
import { api } from '../../client'
import { socialLoginUrl, socialSignup } from '../oauth2'
import { configureAuthSession, installAuthSession } from '../session'

type AdapterConfig = {
  url?: string
  method?: string
  data?: unknown
  withCredentials?: boolean
}

type Result = { status: number; data?: unknown }
type Call = { method: string; url: string; body: unknown; credentials: boolean }

const calls: Call[] = []

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

function useAdapter(handler: (config: AdapterConfig) => Result) {
  calls.length = 0
  api.defaults.adapter = async (config) => {
    calls.push({
      method: config.method?.toUpperCase() ?? '',
      url: config.url ?? '',
      body:
        typeof config.data === 'string' ? JSON.parse(config.data) : undefined,
      credentials: config.withCredentials === true,
    })
    const result = handler(config)
    return respond(config, result.status, result.data)
  }
}

const signedUpUser = {
  userId: '6f1a2b3c-4d5e-4f70-8192-a3b4c5d6e7f8',
  email: 'user@example.com',
  nickname: '드롭헌터',
  roles: ['USER'],
}

let sessionExpired = 0

beforeEach(() => {
  sessionExpired = 0
  configureAuthSession({
    onSessionExpired: () => {
      sessionExpired += 1
    },
  })
  installAuthSession()
})

test('시작 주소는 공통 클라이언트 기준 경로의 제공자별 주소다', () => {
  expect(socialLoginUrl('kakao')).toBe('/api/v1/auth/oauth2/kakao')
  expect(socialLoginUrl('google')).toBe('/api/v1/auth/oauth2/google')
})

test('소셜 가입 완료는 닉네임만 보내고 회원 정보를 돌려준다', async () => {
  useAdapter((config) => {
    if (config.url === '/auth/csrf') return { status: 204 }
    if (config.url === '/auth/oauth2/signup') {
      return {
        status: 201,
        data: { success: true, data: { user: signedUpUser } },
      }
    }
    throw new Error(`예상하지 못한 요청: ${config.url}`)
  })

  await expect(socialSignup('드롭헌터')).resolves.toEqual(signedUpUser)
  const request = calls.find((call) => call.url === '/auth/oauth2/signup')
  expect(request?.method).toBe('POST')
  expect(request?.body).toEqual({ nickname: '드롭헌터' })
  expect(request?.credentials).toBe(true)
})

test('소셜 가입 완료 응답 형식이 다르면 실패로 처리한다', async () => {
  useAdapter((config) => {
    if (config.url === '/auth/csrf') return { status: 204 }
    if (config.url === '/auth/oauth2/signup') {
      return { status: 201, data: { success: true, data: {} } }
    }
    throw new Error(`예상하지 못한 요청: ${config.url}`)
  })

  await expect(socialSignup('드롭헌터')).rejects.toThrow(
    '소셜 회원가입 응답 형식이 올바르지 않습니다.',
  )
})

test('소셜 가입 컨텍스트 401은 재발급·재시도·세션 만료 처리 없이 전파한다', async () => {
  const data = { error: { code: 'OAUTH2_SIGNUP_CONTEXT_INVALID' } }
  useAdapter((config) => {
    if (config.url === '/auth/csrf') return { status: 204 }
    if (config.url === '/auth/refresh') return { status: 204 }
    if (config.url === '/auth/oauth2/signup') return { status: 401, data }
    throw new Error(`예상하지 못한 요청: ${config.url}`)
  })

  await expect(socialSignup('드롭헌터')).rejects.toMatchObject({
    response: { status: 401, data },
  })
  expect(calls.filter((call) => call.url === '/auth/refresh')).toHaveLength(0)
  expect(
    calls.filter((call) => call.url === '/auth/oauth2/signup'),
  ).toHaveLength(1)
  expect(sessionExpired).toBe(0)
})
