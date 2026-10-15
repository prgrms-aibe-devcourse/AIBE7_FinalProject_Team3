export const sellerApplicationKeys = {
  all: ['sellerApplication'] as const,
  mine: () => [...sellerApplicationKeys.all, 'me'] as const,
}
