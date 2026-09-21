import type { Drop } from './drop'

export type Order = {
  id: string
  drop: Drop
  optionLabel: string
  quantity: number
  total: number
}
