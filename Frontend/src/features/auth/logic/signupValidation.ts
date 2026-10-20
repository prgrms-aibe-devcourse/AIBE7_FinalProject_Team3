// 가입·닉네임 입력의 화면 사전 검증. 서버 규칙(MEMBER_AUTH 1.2.3)과 같으며 최종 기준은 서버 응답이다.

export const NICKNAME_RULE_MESSAGE =
  '닉네임은 한글·영문·숫자·_를 사용해 2~10자로 입력해주세요.'
export const PASSWORD_RULE_MESSAGE =
  '비밀번호는 공백 없이 영문·숫자·특수문자를 포함해 8~64자로 입력해주세요.'

export function nicknameError(nickname: string) {
  const value = nickname.trim()
  const length = [...value].length
  if (length < 2 || length > 10 || !/^[가-힣A-Za-z0-9_]+$/.test(value)) {
    return NICKNAME_RULE_MESSAGE
  }
  return ''
}

export function passwordError(password: string) {
  if (
    password.length < 8 ||
    password.length > 64 ||
    !/^[\x21-\x7e]+$/.test(password) ||
    !/[A-Za-z]/.test(password) ||
    !/[0-9]/.test(password) ||
    !/[^A-Za-z0-9]/.test(password)
  ) {
    return PASSWORD_RULE_MESSAGE
  }
  return ''
}
