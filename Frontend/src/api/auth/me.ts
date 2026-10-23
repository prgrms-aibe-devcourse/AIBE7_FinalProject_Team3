import { api } from '../client'
import type { components, paths } from '../schema/user'
import type { UserMe } from './auth'

type UserNicknameUpdateRequest =
  components['schemas']['UserNicknameUpdateRequest']
type PasswordChangeRequest = components['schemas']['PasswordChangeRequest']
type UserMeUpdateResponse =
  paths['/api/v1/users/me']['patch']['responses'][200]['content']['*/*']

// 내 정보 수정(MEMBER_AUTH 1.10·1.11). CSRF·Credential·401 재발급은 공통 클라이언트가 처리한다.
// 값은 가공하지 않고 보낸다. 닉네임 앞뒤 공백 제거는 서버가 하고, 비밀번호는 원문 그대로 비교한다.

// 1.10 닉네임 수정. 응답은 users/me(1.9)와 같은 7개 필드라 ['auth', 'me'] 캐시를 그대로 바꿀 수 있다.
export async function updateMyNickname(nickname: string): Promise<UserMe> {
  const body: UserNicknameUpdateRequest = { nickname }
  const { data } = await api.patch<UserMeUpdateResponse>('/users/me', body)
  if (data.success !== true || !data.data) {
    throw new Error('내 정보 수정 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}

// 1.11 비밀번호 변경(LOCAL 회원). 204이며 기존 로그인 세션은 유지된다.
export async function changeMyPassword(
  currentPassword: string,
  newPassword: string,
) {
  const body: PasswordChangeRequest = { currentPassword, newPassword }
  await api.patch('/users/me/password', body)
}
