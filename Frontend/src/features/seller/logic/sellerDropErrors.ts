import axios from 'axios'
import { apiErrorCode } from '../../auth/logic/authUser'
import { SellerDropFormError } from './sellerDropForm'

export type DropFieldError = { field: string; reason: string }
export type DropFormError = { message: string; fields: DropFieldError[] }

export function sellerDropError(error: unknown): DropFormError {
  if (error instanceof SellerDropFormError)
    return {
      message: '입력 내용을 확인해주세요.',
      fields: [{ field: error.field, reason: error.message }],
    }
  const code = apiErrorCode(error)
  const status = axios.isAxiosError(error) ? error.response?.status : undefined
  const body: unknown = axios.isAxiosError(error)
    ? error.response?.data?.error?.fieldErrors
    : undefined
  const fields = Array.isArray(body)
    ? body.filter(
        (item): item is DropFieldError =>
          item &&
          typeof item.field === 'string' &&
          typeof item.reason === 'string',
      )
    : []
  const messages: Record<string, string> = {
    VALIDATION_FAILED: '입력 내용을 확인해주세요.',
    INVALID_SCHEDULE: '판매 종료는 시작보다 늦어야 해요.',
    DROP_NOT_EDITABLE: 'DRAFT 상태의 DROP만 수정할 수 있어요.',
    DROP_ACCESS_DENIED: '이 DROP을 관리할 권한이 없어요.',
    DROP_NOT_FOUND: 'DROP을 찾을 수 없어요.',
    INVALID_STATE_TRANSITION: 'DROP 상태가 바뀌었어요. 다시 확인해주세요.',
    INVALID_OPTION_COMBINATION: '옵션 구성과 판매 가능한 SKU를 확인해주세요.',
    DUPLICATE_OPTION_COMBINATION: '중복된 옵션 조합을 확인해주세요.',
  }
  if (code === 'INVALID_SCHEDULE' && !fields.length)
    fields.push({ field: 'saleEndsAt', reason: messages.INVALID_SCHEDULE })
  if (
    (code === 'INVALID_OPTION_COMBINATION' ||
      code === 'DUPLICATE_OPTION_COMBINATION') &&
    !fields.length
  )
    fields.push({ field: 'options', reason: messages[code] })
  return {
    message:
      (code && messages[code]) ||
      (status === 401
        ? '로그인 후 이용해주세요.'
        : status === 403
          ? '판매자 권한이 필요해요.'
          : '처리하지 못했어요. 잠시 후 다시 시도해주세요.'),
    fields,
  }
}
