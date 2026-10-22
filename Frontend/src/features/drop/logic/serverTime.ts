export function serverTimeAt(
  serverTime: string | undefined,
  receivedAt: number | undefined,
  now = Date.now(),
) {
  if (receivedAt === undefined || !Number.isFinite(receivedAt)) return NaN
  return Date.parse(serverTime ?? '') + Math.max(0, now - receivedAt)
}

export function openingSeconds(
  saleStartsAt: string,
  serverTime: string,
  receivedAt: number,
  now = Date.now(),
) {
  const remaining =
    Date.parse(saleStartsAt) - serverTimeAt(serverTime, receivedAt, now)
  return Number.isFinite(remaining)
    ? Math.max(0, Math.ceil(remaining / 1000))
    : null
}
