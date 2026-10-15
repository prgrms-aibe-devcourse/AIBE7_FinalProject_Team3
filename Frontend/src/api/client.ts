import axios from 'axios'

// GR-45: 인증 쿠키(HttpOnly)와 CSRF(XSRF-TOKEN)는 브라우저가 관리한다.
// JS는 토큰 원문을 읽거나 저장하지 않는다.
export const api = axios.create({
  baseURL: '/api/v1',
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
  withXSRFToken: true,
})
