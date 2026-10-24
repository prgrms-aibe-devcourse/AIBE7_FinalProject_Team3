import { AxiosError, type AxiosResponse } from 'axios'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { api } from '../../client'
import { StorageUploadError } from '../../storage/upload'
import {
  changeMyPassword,
  registerProfileImage,
  removeProfileImage,
  requestProfileImageUploadUrl,
  updateMyNickname,
  uploadProfileImage,
} from '../me'
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

afterEach(() => {
  vi.unstubAllGlobals()
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

// 프로필 이미지(IMAGE_UPLOAD 1.2, MEMBER_AUTH 1.12·1.13, GR-132)
const upload = {
  imageId: '1d0f9e8c-7b6a-4539-8412-6c5d4e3f2a1b',
  uploadUrl:
    'https://storage.example.com/upload/profiles/1d0f9e8c.png?token=signed',
  imageUrl: 'https://storage.example.com/public/profiles/1d0f9e8c.png',
  expiresAt: '2026-10-24T12:00:00+09:00',
}
const profile = { userId: me.userId, profileImageUrl: upload.imageUrl }
const file = () => new File(['image'], '프로필.png', { type: 'image/png' })

function useProfileApi(storageStatus = 200) {
  useAdapter((config) => {
    if (config.url === '/auth/csrf') return { status: 204 }
    if (config.url === '/users/me/profile-image/upload-url') {
      return { status: 200, data: { success: true, data: upload } }
    }
    if (config.url === '/users/me/profile-image') {
      if (config.method === 'delete') return { status: 204 }
      return { status: 200, data: { success: true, data: profile } }
    }
    throw new Error(`예상하지 못한 요청: ${config.url}`)
  })
  const fetch = vi
    .fn()
    .mockResolvedValue(new Response('{}', { status: storageStatus }))
  vi.stubGlobal('fetch', fetch)
  return fetch
}

test('프로필 이미지 업로드 URL은 파일 메타데이터로 발급받는다', async () => {
  useProfileApi()
  const input = {
    fileName: '프로필.png',
    contentType: 'image/png',
    fileSize: 5,
  }
  await expect(requestProfileImageUploadUrl(input)).resolves.toEqual(upload)
  const request = calls.find((call) => call.url.endsWith('/upload-url'))
  expect(request).toMatchObject({
    method: 'POST',
    url: '/users/me/profile-image/upload-url',
    body: input,
    credentials: true,
  })
})

test('프로필 이미지 업로드는 발급 후 Storage에 쿠키 없이 PUT하고 imageUrl만 돌려준다', async () => {
  const fetch = useProfileApi()
  const image = file()
  const signal = new AbortController().signal
  await expect(uploadProfileImage(image, signal)).resolves.toBe(upload.imageUrl)
  expect(calls.find((call) => call.url.endsWith('/upload-url'))?.body).toEqual({
    fileName: image.name,
    contentType: image.type,
    fileSize: image.size,
  })
  expect(fetch).toHaveBeenCalledWith(upload.uploadUrl, {
    method: 'PUT',
    headers: { 'Content-Type': 'image/png' },
    body: image,
    credentials: 'omit',
    referrerPolicy: 'no-referrer',
    signal,
  })
  // 업로드 단계에서는 등록하지 않는다
  expect(requests()).not.toContain('PUT /users/me/profile-image')
})

test('Storage 업로드가 실패하면 StorageUploadError로 끝난다', async () => {
  useProfileApi(403)
  await expect(
    uploadProfileImage(file(), new AbortController().signal),
  ).rejects.toEqual(new StorageUploadError(403))
})

test('발급 응답 형식이 다르거나 발급 도중 취소되면 Storage에 올리지 않는다', async () => {
  const fetch = useProfileApi()
  useAdapter((config) => {
    if (config.url === '/auth/csrf') return { status: 204 }
    return {
      status: 200,
      data: { success: true, data: { ...upload, uploadUrl: '' } },
    }
  })
  await expect(
    uploadProfileImage(file(), new AbortController().signal),
  ).rejects.toThrow('업로드 URL 응답 형식')

  const controller = new AbortController()
  useAdapter((config) => {
    if (config.url === '/auth/csrf') return { status: 204 }
    controller.abort()
    return { status: 200, data: { success: true, data: upload } }
  })
  await expect(uploadProfileImage(file(), controller.signal)).rejects.toThrow()
  expect(fetch).not.toHaveBeenCalled()
})

test('프로필 이미지 등록은 imageUrl을 그대로 보내고 두 필드를 돌려준다', async () => {
  useProfileApi()
  await expect(registerProfileImage(upload.imageUrl)).resolves.toEqual(profile)
  const request = calls.find((call) => call.url === '/users/me/profile-image')
  expect(request).toMatchObject({
    method: 'PUT',
    body: { imageUrl: upload.imageUrl },
    credentials: true,
  })
})

test.each([
  ['data 없음', { success: true, data: null }],
  ['profileImageUrl 없음', { success: true, data: { userId: me.userId } }],
  ['실패 응답', { success: false, data: profile }],
])('프로필 이미지 등록 응답 형식 오류(%s)를 거부한다', async (_, body) => {
  useAdapter((config) => {
    if (config.url === '/auth/csrf') return { status: 204 }
    return { status: 200, data: body }
  })
  await expect(registerProfileImage(upload.imageUrl)).rejects.toThrow(
    '프로필 이미지 등록 응답 형식이 올바르지 않습니다.',
  )
})

test('프로필 이미지 등록 검증 실패(400)는 재시도 없이 전파한다', async () => {
  useAdapter((config) => {
    if (config.url === '/auth/csrf') return { status: 204 }
    return failure(400, 'VALIDATION_FAILED')
  })
  await expect(registerProfileImage(upload.imageUrl)).rejects.toMatchObject({
    response: { status: 400 },
  })
  expect(requests()).toEqual(['GET /auth/csrf', 'PUT /users/me/profile-image'])
})

test('프로필 이미지 삭제는 본문 없이 DELETE하고 204로 끝난다', async () => {
  useProfileApi()
  await expect(removeProfileImage()).resolves.toBeUndefined()
  const request = calls.find((call) => call.url === '/users/me/profile-image')
  expect(request).toMatchObject({ method: 'DELETE', body: undefined })
  expect(request?.credentials).toBe(true)
})
