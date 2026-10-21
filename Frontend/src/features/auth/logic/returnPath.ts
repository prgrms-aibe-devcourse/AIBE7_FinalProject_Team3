// 로그인 후 돌아갈 경로. 앱 내부 경로만 허용하고 없거나 맞지 않으면 WISH로 보낸다.
export const DEFAULT_RETURN_PATH = '/wish'

export function safeReturnPath(value: unknown) {
  return typeof value === 'string' &&
    value.startsWith('/') &&
    !value.startsWith('//')
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
