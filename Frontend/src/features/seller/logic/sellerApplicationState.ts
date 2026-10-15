import axios from 'axios'

type ErrorBody = { error?: { code?: string; message?: string } }

function errorCode(error: unknown): string | undefined {
  if (!axios.isAxiosError(error)) return undefined
  return (error.response?.data as ErrorBody | undefined)?.error?.code
}

// 신청 없음(404 SELLER_APPLICATION_NOT_FOUND)은 오류가 아니라 "미신청" 상태다.
export function isNoApplication(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 404
}

export function applicationErrorMessage(error: unknown): string {
  if (!axios.isAxiosError(error)) {
    return '처리하지 못했어요. 잠시 후 다시 시도해주세요.'
  }
  const status = error.response?.status
  const code = errorCode(error)
  if (status === 401 || status === 403) {
    return '로그인 후 이용할 수 있어요.'
  }
  if (code === 'BUSINESS_NOT_ACTIVE') {
    return '휴업·폐업 상태인 사업자등록번호예요. 상태를 확인해주세요.'
  }
  if (code === 'SELLER_APPLICATION_ALREADY_EXISTS') {
    return '이미 접수된 신청이 있어요.'
  }
  if (code === 'DUPLICATE_BUSINESS_REGISTRATION_NUMBER') {
    return '이미 다른 계정에서 신청한 사업자등록번호예요.'
  }
  if (code === 'VALIDATION_FAILED') {
    return '입력 또는 서류가 올바르지 않아요. 서류 4종이 모두 업로드됐는지 확인해주세요.'
  }
  return '처리하지 못했어요. 잠시 후 다시 시도해주세요.'
}
