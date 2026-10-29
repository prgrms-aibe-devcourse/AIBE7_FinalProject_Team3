// 로그인 후 돌아갈 경로. 앱 내부 경로만 허용하고 없거나 맞지 않으면 WISH로 보낸다.
export const DEFAULT_RETURN_PATH = '/wish'

// 운영자 전용 경로는 로그인 전에 "누가" 거길 보고 있었는지 모르는 채로 저장된 값이다
// (로그아웃·세션 만료로 관리자 페이지의 !user 가드가 자기 경로를 from으로 남기는 경우,
// GR-132). 로그인한 사람이 관리자인지 확인하기 전에 그 경로로 돌려보내면 안 되므로 제외한다.
export function safeReturnPath(value: unknown) {
  return typeof value === 'string' &&
    value.startsWith('/') &&
    !value.startsWith('//') &&
    !value.startsWith('/admin')
    ? value
    : DEFAULT_RETURN_PATH
}

// 소셜 로그인은 제공자로 이동하며 앱이 다시 로드돼 location.state가 사라지므로 경로를 sessionStorage에 잠시 둔다(GR-47 M00-02).
// 저장하는 값은 화면 경로뿐이며 토큰·가입 컨텍스트는 저장하지 않는다.
const SOCIAL_RETURN_PATH_KEY = 'grab.socialReturnTo'

export function saveSocialReturnPath(path: string) {
  try {
    sessionStorage.setItem(SOCIAL_RETURN_PATH_KEY, path)
  } catch {
    // 저장소를 쓸 수 없으면 복귀 경로 없이 기본 경로로 돌아온다.
  }
}

// 콜백에서 읽은 즉시 지우고, 저장된 값도 다시 검사한다.
export function takeSocialReturnPath() {
  try {
    const path = sessionStorage.getItem(SOCIAL_RETURN_PATH_KEY)
    sessionStorage.removeItem(SOCIAL_RETURN_PATH_KEY)
    return safeReturnPath(path)
  } catch {
    return DEFAULT_RETURN_PATH
  }
}
