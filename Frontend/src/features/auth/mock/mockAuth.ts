export const mockLoginErrors = {
  credentials: '이메일 또는 비밀번호를 확인해주세요.',
  inactive: '사용할 수 없는 계정이에요.',
  network: '연결에 실패했어요. 잠시 뒤 다시 시도해주세요.',
  linked: '같은 이메일로 가입한 계정이 있어요. 계정 연결이 필요합니다.',
  social: '소셜 인증이 취소되거나 실패했어요.',
} as const

export const mockSignupErrors = {
  nickname: '이미 사용 중인 닉네임이에요.',
  save: '저장에 실패했어요. 다시 시도해주세요.',
  context: '가입 시간이 만료됐어요. 소셜 로그인을 다시 시작해주세요.',
} as const
