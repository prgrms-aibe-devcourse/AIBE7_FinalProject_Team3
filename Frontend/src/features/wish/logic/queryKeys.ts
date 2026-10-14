export const wishKeys = {
  all: ['wishes'] as const,
  mine: () => [...wishKeys.all, 'me'] as const,
  list: (page: number, size: number) =>
    [...wishKeys.all, 'me', 'page', page, size] as const,
}
