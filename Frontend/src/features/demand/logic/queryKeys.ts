export const demandKeys = {
  all: ['demand'] as const,
  // 요청 본문(JSON 문자열)으로 구분한다
  stockRecommendation: (request: string) =>
    ['demand', 'stockRecommendation', request] as const,
}
