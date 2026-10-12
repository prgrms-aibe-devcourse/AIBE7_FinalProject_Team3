import { Link } from 'react-router-dom'
import type { BuyerOrder } from '../mock/mockBuyerOrders'
import { won } from '../../../utils/price'

export default function BuyerOrderItem({ order }: { order: BuyerOrder }) {
  return (
    <div className="buyer-item">
      <img src={order.image} alt="" />
      <div>
        <Link className="text-link" to={`/drops/${order.dropId}`}>
          {order.productName}
        </Link>
        <p>
          {order.optionLabel} · {order.quantity}개
        </p>
      </div>
      <strong>{won(order.itemsAmount)}</strong>
    </div>
  )
}
