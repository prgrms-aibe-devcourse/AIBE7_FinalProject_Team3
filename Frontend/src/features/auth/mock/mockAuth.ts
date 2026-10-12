export type MockAccount = {
  email: string
  nickname: string
  provider: 'LOCAL' | 'KAKAO' | 'GOOGLE'
  role: 'MEMBER' | 'SELLER'
}

export const localAccount: MockAccount = {
  email: 'grab@example.com',
  nickname: '드롭헌터',
  provider: 'LOCAL',
  role: 'MEMBER',
}

export function socialAccount(provider: 'KAKAO' | 'GOOGLE'): MockAccount {
  return {
    email: `${provider.toLowerCase()}@example.com`,
    nickname: '소셜회원',
    provider,
    role: 'MEMBER',
  }
}

export const mockLoginErrors = {
  credentials: '이메일 또는 비밀번호를 확인해주세요.',
  inactive: '사용할 수 없는 계정이에요.',
  network: '연결에 실패했어요. 잠시 뒤 다시 시도해주세요.',
  linked: '같은 이메일로 가입한 계정이 있어요. 계정 연결이 필요합니다.',
  social: '소셜 인증이 취소되거나 실패했어요.',
} as const

export const mockSignupErrors = {
  mismatch: '인증 코드가 일치하지 않아요.',
  expired: '인증 코드가 만료됐어요. 다시 발송해주세요.',
  attempts: '확인 횟수를 초과했어요. 새 코드를 요청해주세요.',
  existing: '이미 가입된 이메일이에요.',
  nickname: '이미 사용 중인 닉네임이에요.',
  save: '저장에 실패했어요. 다시 시도해주세요.',
  context: '가입 시간이 만료됐어요. 소셜 로그인을 다시 시작해주세요.',
} as const

export function nicknameError(nickname: string) {
  const value = nickname.trim()
  const length = [...value].length
  if (length < 2 || length > 10 || !/^[가-힣A-Za-z0-9_]+$/.test(value)) {
    return '닉네임은 한글·영문·숫자·_를 사용해 2~10자로 입력해주세요.'
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
    return '비밀번호는 공백 없이 영문·숫자·특수문자를 포함해 8~64자로 입력해주세요.'
  }
  return ''
}
