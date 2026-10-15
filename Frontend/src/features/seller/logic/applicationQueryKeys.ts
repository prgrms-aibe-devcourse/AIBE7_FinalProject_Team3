export const sellerApplicationKeys = {
  all: ['sellerApplication'] as const,
  mine: () => [...sellerApplicationKeys.all, 'me'] as const,
}

export const adminSellerApplicationKeys = {
  all: ['adminSellerApplications'] as const,
  list: (params: { status: string; page: number; size: number }) =>
    [...adminSellerApplicationKeys.all, 'list', params] as const,
}
