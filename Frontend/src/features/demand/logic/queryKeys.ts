export const demandKeys = {
  all: ['demand'] as const,
  analysis: (dropId: number) => ['demand', 'analysis', dropId] as const,
  // 요청 본문(JSON 문자열)으로 구분한다
  stockRecommendation: (request: string) =>
    ['demand', 'stockRecommendation', request] as const,
  similarSales: (dropId: number) => ['demand', 'similarSales', dropId] as const,
  // 생성 단계는 요청 본문(JSON 문자열)으로 구분한다
  similarSalesForm: (request: string) =>
    ['demand', 'similarSalesForm', request] as const,
}
