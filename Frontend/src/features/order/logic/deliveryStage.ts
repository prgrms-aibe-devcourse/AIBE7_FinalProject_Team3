import type { components } from '../../../api/schema/order'

export type DeliveryStage = NonNullable<
  components['schemas']['OrderShippingResponse']['deliveryStage']
>

// GR-86의 세부 배송 단계. 순서가 자동 진행 순서와 같다.
export const deliveryStages: DeliveryStage[] = [
  'COLLECTED',
  'LINE_HAUL_OUT',
  'LINE_HAUL_IN',
  'OUT_FOR_DELIVERY',
]

export const deliveryStageText: Record<DeliveryStage, string> = {
  COLLECTED: '집화완료',
  LINE_HAUL_OUT: '간선상차',
  LINE_HAUL_IN: '간선하차',
  OUT_FOR_DELIVERY: '배송출발',
}
