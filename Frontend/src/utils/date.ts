export const dateLabel = (value: string) =>
  new Intl.DateTimeFormat('ko-KR', {
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))

export const daysUntil = (value: string) =>
  Math.max(0, Math.ceil((new Date(value).getTime() - Date.now()) / 86_400_000))

// <input type="date">가 쓰는 yyyy-MM-dd. toISOString()은 UTC라 날짜가 밀릴 수 있어 로컬 값으로 만든다.
export const inputDate = (value: Date) =>
  `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(
    value.getDate(),
  ).padStart(2, '0')}`

// 선택한 날짜의 시작·끝 순간. 서버가 양끝을 포함해 세므로 끝날은 23:59:59.999로 맞춘다(SELLER.md 2.1).
export const dayStart = (value: string) => new Date(`${value}T00:00:00`)
export const dayEnd = (value: string) => new Date(`${value}T23:59:59.999`)
