import { expect, test } from 'vitest'
import { AxiosError } from 'axios'
import { sellerDropError } from '../logic/sellerDropErrors'
import { SellerDropFormError } from '../logic/sellerDropForm'

const serverError = (code: string, status = 400, fieldErrors: unknown = []) =>
  new AxiosError('실패', undefined, undefined, undefined, {
    status,
    data: { error: { code, fieldErrors } },
    statusText: '',
    headers: {},
    config: { headers: {} },
  } as never)

test('필드 오류의 위치와 이유를 보존하고 잘못된 항목은 제외한다', () => {
  const fields = [
    { field: 'images[0].altText', reason: '대체 설명을 확인해주세요.' },
    { field: 'categoryId', reason: '활성 카테고리를 선택해주세요.' },
  ]
  expect(
    sellerDropError(
      serverError('VALIDATION_FAILED', 400, [...fields, null, { field: 1 }]),
    ).fields,
  ).toEqual(fields)
  expect(
    sellerDropError(
      new SellerDropFormError('saleEndsAt', '종료 시각을 확인해주세요.'),
    ).fields,
  ).toEqual([{ field: 'saleEndsAt', reason: '종료 시각을 확인해주세요.' }])
})

test.each([
  ['DROP_NOT_FOUND', '찾을 수 없어요'],
  ['DROP_ACCESS_DENIED', '관리할 권한'],
  ['DROP_NOT_EDITABLE', 'DRAFT'],
  ['INVALID_STATE_TRANSITION', '상태가 바뀌었어요'],
])('%s를 성공이나 일반 네트워크 오류로 표시하지 않는다', (code, text) => {
  expect(sellerDropError(serverError(code)).message).toContain(text)
})

test('일정·옵션 도메인 오류에 필드가 없으면 해당 입력 근처에 안내한다', () => {
  expect(sellerDropError(serverError('INVALID_SCHEDULE')).fields[0].field).toBe(
    'saleEndsAt',
  )
  expect(
    sellerDropError(serverError('INVALID_OPTION_COMBINATION')).fields[0].field,
  ).toBe('options')
  expect(
    sellerDropError(serverError('DUPLICATE_OPTION_COMBINATION')).fields[0]
      .field,
  ).toBe('options')
})
