import { api } from '../client'
import type { paths } from '../schema/drop'

type SellerDropStocksResponse =
  paths['/api/v1/seller/drops/{dropId}/stocks']['get']['responses'][200]['content']['*/*']

export async function getSellerDropStocks(dropId: number) {
  const { data } = await api.get<SellerDropStocksResponse>(
    `/seller/drops/${dropId}/stocks`,
  )
  if (
    data.success !== true ||
    !data.data ||
    !Array.isArray(data.data.options)
  ) {
    throw new Error('판매자 재고 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}
