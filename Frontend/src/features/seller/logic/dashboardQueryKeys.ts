export const dashboardKeys = {
  all: ['sellerDashboard'] as const,
  summary: (from?: string, to?: string) =>
    [...dashboardKeys.all, 'summary', from ?? '', to ?? ''] as const,
  upcoming: (eventType: 'START' | 'END') =>
    [...dashboardKeys.all, 'upcoming', eventType] as const,
  dropStats: (query: {
    status?: string
    dropId?: number
    page?: number
    size?: number
  }) => [...dashboardKeys.all, 'dropStats', query] as const,
}

export const sellerDropStocksKeys = {
  all: ['sellerDropStocks'] as const,
  detail: (dropId: number) => [...sellerDropStocksKeys.all, dropId] as const,
}
