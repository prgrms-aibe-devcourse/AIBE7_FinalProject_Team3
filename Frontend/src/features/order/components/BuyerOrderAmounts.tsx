import { won } from '../../../utils/price'

// 결제 화면(Mock 주문)과 주문 상세(API 주문)가 함께 쓰므로 금액 필드만 받는다.
type OrderAmounts = {
  itemsAmount: number
  shippingAmount: number
  totalAmount: number
}

export default function BuyerOrderAmounts({ order }: { order: OrderAmounts }) {
  return (
    <dl className="buyer-amounts">
      <div>
        <dt>상품 금액</dt>
        <dd>{won(order.itemsAmount)}</dd>
      </div>
      <div>
        <dt>배송비</dt>
        <dd>{won(order.shippingAmount)}</dd>
      </div>
      <div className="buyer-total">
        <dt>총 결제 금액</dt>
        <dd>{won(order.totalAmount)}</dd>
      </div>
    </dl>
  )
}
