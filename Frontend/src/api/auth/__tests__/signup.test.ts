import { AxiosError, type AxiosResponse } from 'axios'
import { beforeEach, expect, test } from 'vitest'
import { api } from '../../client'
import { configureAuthSession, installAuthSession } from '../session'
import {
  confirmEmailVerification,
  requestEmailVerification,
  signup,
} from '../signup'

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

const signedUp = {
  userId: '6f1a2b3c-4d5e-4f70-8192-a3b4c5d6e7f8',
  email: 'user@example.com',
  nickname: '드롭헌터',
  roles: ['USER'],
  createdAt: '2026-09-18T14:00:00+09:00',
}

beforeEach(() => {
  configureAuthSession({ onSessionExpired: () => undefined })
  installAuthSession()
})

test('코드 요청은 CSRF를 준비한 뒤 이메일만 보낸다', async () => {
  useAdapter((config) => {
    if (config.url === '/auth/csrf') return { status: 204 }
    if (config.url === '/auth/email-verification') return { status: 204 }
    throw new Error(`예상하지 못한 요청: ${config.url}`)
  })

  await requestEmailVerification('user@example.com')
  expect(calls.map((call) => `${call.method} ${call.url}`)).toEqual([
    'GET /auth/csrf',
    'POST /auth/email-verification',
  ])
  expect(calls[1].body).toEqual({ email: 'user@example.com' })
  expect(calls[1].credentials).toBe(true)
})

test('코드 확인은 이메일과 코드를 보낸다', async () => {
  useAdapter((config) => {
    if (config.url === '/auth/csrf') return { status: 204 }
    if (config.url === '/auth/email-verification/confirm') {
      return { status: 204 }
    }
    throw new Error(`예상하지 못한 요청: ${config.url}`)
  })

  await confirmEmailVerification('user@example.com', '123456')
  const confirm = calls.find(
    (call) => call.url === '/auth/email-verification/confirm',
  )
  expect(confirm?.method).toBe('POST')
  expect(confirm?.body).toEqual({ email: 'user@example.com', code: '123456' })
  expect(confirm?.credentials).toBe(true)
})

test('가입 완료는 이메일 없이 비밀번호·닉네임만 보내고 회원 정보를 돌려준다', async () => {
  useAdapter((config) => {
    if (config.url === '/auth/csrf') return { status: 204 }
    if (config.url === '/auth/signup') {
      return { status: 201, data: { success: true, data: signedUp } }
    }
    throw new Error(`예상하지 못한 요청: ${config.url}`)
  })

  await expect(signup('Password123!', '드롭헌터')).resolves.toEqual(signedUp)
  const request = calls.find((call) => call.url === '/auth/signup')
  expect(request?.method).toBe('POST')
  expect(request?.body).toEqual({
    password: 'Password123!',
    nickname: '드롭헌터',
  })
  expect(request?.credentials).toBe(true)
})

test('가입 완료 응답 형식이 다르면 실패로 처리한다', async () => {
  useAdapter((config) => {
    if (config.url === '/auth/csrf') return { status: 204 }
    if (config.url === '/auth/signup') {
      return { status: 201, data: { success: false, data: null } }
    }
    throw new Error(`예상하지 못한 요청: ${config.url}`)
  })

  await expect(signup('Password123!', '드롭헌터')).rejects.toThrow(
    '회원가입 응답 형식이 올바르지 않습니다.',
  )
})

test('가입 컨텍스트 401은 재발급·재시도 없이 전파한다', async () => {
  const data = { error: { code: 'EMAIL_SIGNUP_CONTEXT_INVALID' } }
  useAdapter((config) => {
    if (config.url === '/auth/csrf') return { status: 204 }
    if (config.url === '/auth/refresh') return { status: 204 }
    if (config.url === '/auth/signup') return { status: 401, data }
    throw new Error(`예상하지 못한 요청: ${config.url}`)
  })

  await expect(signup('Password123!', '드롭헌터')).rejects.toMatchObject({
    response: { status: 401, data },
  })
  expect(calls.filter((call) => call.url === '/auth/refresh')).toHaveLength(0)
  expect(calls.filter((call) => call.url === '/auth/signup')).toHaveLength(1)
})
