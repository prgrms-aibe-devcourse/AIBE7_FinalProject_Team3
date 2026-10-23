import { AxiosError, type AxiosResponse } from 'axios'
import { beforeEach, expect, test } from 'vitest'
import { api } from '../../client'
import { changeMyPassword, updateMyNickname } from '../me'
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

const requests = () => calls.map((call) => `${call.method} ${call.url}`)

const me = {
  userId: '6f1a2b3c-4d5e-4f70-8192-a3b4c5d6e7f8',
  email: 'user@example.com',
  nickname: '한정판수집가',
  provider: 'LOCAL',
  roles: ['USER'],
  profileImageUrl: null,
  createdAt: '2026-09-18T14:00:00+09:00',
}

const failure = (status: number, code: string) => ({
  status,
  data: { success: false, data: null, error: { code, message: code } },
})

beforeEach(() => {
  configureAuthSession({ onSessionExpired: () => undefined })
  installAuthSession()
})

test('닉네임 수정은 입력값을 가공하지 않고 보내고 내 정보를 돌려준다', async () => {
  useAdapter((config) => {
    if (config.url === '/auth/csrf') return { status: 204 }
    if (config.url === '/users/me') {
      return { status: 200, data: { success: true, data: me } }
    }
    throw new Error(`예상하지 못한 요청: ${config.url}`)
  })

  await expect(updateMyNickname(' 한정판수집가 ')).resolves.toEqual(me)
  const request = calls.find((call) => call.url === '/users/me')
  expect(request?.method).toBe('PATCH')
  expect(request?.body).toEqual({ nickname: ' 한정판수집가 ' })
  expect(request?.credentials).toBe(true)
})

test('닉네임 수정 응답 형식이 다르면 실패로 처리한다', async () => {
  useAdapter((config) => {
    if (config.url === '/auth/csrf') return { status: 204 }
    if (config.url === '/users/me') {
      return { status: 200, data: { success: true, data: null } }
    }
    throw new Error(`예상하지 못한 요청: ${config.url}`)
  })

  await expect(updateMyNickname('한정판수집가')).rejects.toThrow(
    '내 정보 수정 응답 형식이 올바르지 않습니다.',
  )
})

test('비밀번호 변경은 두 값을 그대로 보내고 204로 끝난다', async () => {
  useAdapter((config) => {
    if (config.url === '/auth/csrf') return { status: 204 }
    if (config.url === '/users/me/password') return { status: 204 }
    throw new Error(`예상하지 못한 요청: ${config.url}`)
  })

  await expect(
    changeMyPassword(' Password123! ', 'NewPassword456!'),
  ).resolves.toBeUndefined()
  const request = calls.find((call) => call.url === '/users/me/password')
  expect(request?.method).toBe('PATCH')
  expect(request?.body).toEqual({
    currentPassword: ' Password123! ',
    newPassword: 'NewPassword456!',
  })
  expect(request?.credentials).toBe(true)
})

test('비밀번호 오류(400)는 재발급·재시도 없이 전파한다', async () => {
  useAdapter((config) => {
    if (config.url === '/auth/csrf') return { status: 204 }
    if (config.url === '/users/me/password') {
      return failure(400, 'PASSWORD_MISMATCH')
    }
    throw new Error(`예상하지 못한 요청: ${config.url}`)
  })

  await expect(
    changeMyPassword('Wrong123!', 'NewPassword456!'),
  ).rejects.toMatchObject({ response: { status: 400 } })
  expect(requests()).toEqual(['GET /auth/csrf', 'PATCH /users/me/password'])
})

test('Access 만료 401은 재발급 후 한 번 다시 보낸다', async () => {
  let expired = true
  useAdapter((config) => {
    if (config.url === '/auth/csrf') return { status: 204 }
    if (config.url === '/auth/refresh') {
      expired = false
      return { status: 204 }
    }
    if (config.url === '/users/me') {
      if (expired) return failure(401, 'INVALID_TOKEN')
      return { status: 200, data: { success: true, data: me } }
    }
    throw new Error(`예상하지 못한 요청: ${config.url}`)
  })

  await expect(updateMyNickname('한정판수집가')).resolves.toEqual(me)
  expect(requests().filter((request) => request !== 'GET /auth/csrf')).toEqual([
    'PATCH /users/me',
    'POST /auth/refresh',
    'PATCH /users/me',
  ])
})
