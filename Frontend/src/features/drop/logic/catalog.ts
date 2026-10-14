export const CATALOG_PAGE_SIZE = 24

export type CatalogDateSort =
  `${'publishedAt' | 'saleStartsAt' | 'createdAt'},${'asc' | 'desc'}`
export type CatalogQuery = {
  category?: string
  keyword?: string
  soldOut?: boolean
  page?: number
} & (
  | { status: 'WISH'; sort?: CatalogDateSort | 'wishCount,desc' }
  | { status: 'GRAB'; sort?: CatalogDateSort | 'soldQuantity,desc' }
)
