export function openDropStockStream(dropId: number) {
  return new EventSource(`/api/v1/drops/stock-stream?dropIds=${dropId}`, {
    withCredentials: true,
  })
}
