export const dashboardKeys = {
  all: ['sellerDashboard'] as const,
  summary: (from?: string, to?: string) =>
    [...dashboardKeys.all, 'summary', from ?? '', to ?? ''] as const,
  upcoming: (eventType: 'START' | 'END') =>
    [...dashboardKeys.all, 'upcoming', eventType] as const,
}
