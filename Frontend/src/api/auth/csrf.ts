import { api } from '../client'

// XSRF-TOKEN 쿠키는 HttpOnly가 아니라 Axios가 요청 헤더로 실어 보낸다.
// JS는 쿠키 존재 여부만 확인하고 값은 읽지 않는다.
export function hasXsrfCookie() {
  if (typeof document === 'undefined') return false
  return document.cookie
    .split('; ')
    .some((entry) => entry.startsWith('XSRF-TOKEN='))
}

let csrfPromise: Promise<void> | null = null

// 앱 시작·상태 변경 요청 전에 한 번만 호출한다. 동시 호출은 하나로 모은다.
export function prepareCsrf() {
  if (!csrfPromise) {
    csrfPromise = api
      .get('/auth/csrf')
      .then(() => undefined)
      .catch(() => undefined)
      .finally(() => {
        csrfPromise = null
      })
  }
  return csrfPromise
}
