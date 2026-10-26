export const demandKeys = {
  all: ['demand'] as const,
  analysis: (dropId: number) => ['demand', 'analysis', dropId] as const,
  plans: (dropId: number) => ['demand', 'plans', dropId] as const,
  planList: (dropId: number, page: number) =>
    ['demand', 'plans', dropId, page] as const,
  plan: (planId: string) => ['demand', 'plan', planId] as const,
}
