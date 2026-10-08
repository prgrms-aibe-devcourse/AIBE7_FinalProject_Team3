import { api } from '../client'
import type { paths } from '../schema/category'

type CategoryResponse =
  paths['/api/v1/categories']['get']['responses'][200]['content']['*/*']

export async function getCategories() {
  const { data } = await api.get<CategoryResponse>('/categories')
  if (data.success !== true || !Array.isArray(data.data)) {
    throw new Error('카테고리 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}
