import type { Drop } from '../../types/drop'

export const minPrice = (drop: Drop) => Math.min(...drop.skus.map((sku) => sku.price))
export const stock = (drop: Drop) => drop.skus.reduce((sum, sku) => sum + sku.stock, 0)
