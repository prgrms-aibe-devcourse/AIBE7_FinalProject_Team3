export const sellerDropKeys = {
  all: ['sellerDrops'] as const,
  detail: (dropId: number) => ['sellerDrops', 'detail', dropId] as const,
  list: (params: import('../../../api/seller/drops').SellerDropListParams) =>
    ['sellerDrops', 'list', params] as const,
  wishCount: (dropId: number) => ['sellerDrops', 'wishCount', dropId] as const,
}
