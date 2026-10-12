import type { BuyerOrder } from '../mock/mockBuyerOrders'
import { won } from '../../../utils/price'

export default function BuyerOrderAmounts({ order }: { order: BuyerOrder }) {
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
