import { api } from '../client'
import type { components, paths } from '../schema/user'
import { putToSignedUrl, readSignedImageUpload } from '../storage/upload'
import type { UserMe } from './auth'

type UserNicknameUpdateRequest =
  components['schemas']['UserNicknameUpdateRequest']
type PasswordChangeRequest = components['schemas']['PasswordChangeRequest']
type UserMeUpdateResponse =
  paths['/api/v1/users/me']['patch']['responses'][200]['content']['*/*']
type ImageUploadUrlRequest = components['schemas']['ImageUploadUrlRequest']
type ProfileImageUploadUrlResponse =
  paths['/api/v1/users/me/profile-image/upload-url']['post']['responses'][200]['content']['*/*']
type ProfileImageUpdateRequest =
  components['schemas']['ProfileImageUpdateRequest']
type ProfileImageUpdateResponse =
  paths['/api/v1/users/me/profile-image']['put']['responses'][200]['content']['*/*']

export type ProfileImage = { userId: string; profileImageUrl: string }

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

// 프로필 이미지(IMAGE_UPLOAD 1.2, MEMBER_AUTH 1.12·1.13, GR-132). LOCAL·소셜 회원 모두 쓴다.
// uploadUrl에는 서명 토큰이 있으므로 업로드에만 쓰고 밖으로 돌려주지 않는다. 화면은 imageUrl만 받는다.

// 1.2 업로드 URL 발급. 요청·응답은 DROP 이미지 발급과 같고 경로·권한(USER)만 다르다.
export async function requestProfileImageUploadUrl(
  input: ImageUploadUrlRequest,
  signal?: AbortSignal,
) {
  const { data } = await api.post<ProfileImageUploadUrlResponse>(
    '/users/me/profile-image/upload-url',
    input,
    { signal },
  )
  return readSignedImageUpload(data)
}

// 발급 → Storage PUT. 업로드가 실패하면 예외로 끝나므로 호출부는 등록(1.12)까지 가지 않는다.
export async function uploadProfileImage(file: File, signal: AbortSignal) {
  const upload = await requestProfileImageUploadUrl(
    { fileName: file.name, contentType: file.type, fileSize: file.size },
    signal,
  )
  signal.throwIfAborted()
  await putToSignedUrl(upload.uploadUrl, file, signal)
  return upload.imageUrl
}

// 1.12 등록/수정. imageUrl은 발급 응답 그대로 보낸다. 응답은 두 필드라 캐시의 profileImageUrl만 바꾼다.
export async function registerProfileImage(
  imageUrl: string,
): Promise<ProfileImage> {
  const body: ProfileImageUpdateRequest = { imageUrl }
  const { data } = await api.put<ProfileImageUpdateResponse>(
    '/users/me/profile-image',
    body,
  )
  const image = data.data
  if (data.success !== true || !image?.userId || !image.profileImageUrl) {
    throw new Error('프로필 이미지 등록 응답 형식이 올바르지 않습니다.')
  }
  return { userId: image.userId, profileImageUrl: image.profileImageUrl }
}

// 1.13 삭제. 204이며 이미지가 없어도 성공한다. 캐시의 profileImageUrl은 null로 바꾼다.
export async function removeProfileImage() {
  await api.delete('/users/me/profile-image')
}
