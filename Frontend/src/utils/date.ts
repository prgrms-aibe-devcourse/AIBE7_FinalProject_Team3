export const dateLabel = (value: string) =>
  new Intl.DateTimeFormat('ko-KR', {
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))

export const daysUntil = (value: string) =>
  Math.max(0, Math.ceil((new Date(value).getTime() - Date.now()) / 86_400_000))
