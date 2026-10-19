import axios from 'axios'

// DROP_NOT_FOUND(404)·DROP_ACCESS_DENIED(403)는 "선택 해제" 대상이지 일반 오류가 아니다.
export function isDropNotAccessible(error: unknown): boolean {
  if (!axios.isAxiosError(error)) return false
  const status = error.response?.status
  return status === 404 || status === 403
}
