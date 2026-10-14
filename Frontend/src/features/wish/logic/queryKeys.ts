export const wishKeys = {
  all: ['wishes'] as const,
  mine: () => [...wishKeys.all, 'me'] as const,
}
