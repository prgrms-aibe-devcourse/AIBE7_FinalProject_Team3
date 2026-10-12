import { dateLabel } from '../../utils/date'
import { won } from '../../utils/price'
import type { SellerOrderDetail as Detail } from './mockSellerOrders'

type Props = {
  detail: Detail
  // 송장 수정이 가능한 주문(SHIPPED)에서만 넘긴다
  onEditShipment?: () => void
}

export default function SellerOrderDetail({ detail, onEditShipment }: Props) {
  return (
    <div className="order-detail">
      <table>
        <thead>
          <tr>
            <th>상품</th>
            <th>옵션</th>
            <th>단가</th>
            <th>수량</th>
            <th>금액</th>
          </tr>
        </thead>
        <tbody>
          {detail.items.map((item) => (
            <tr key={`${item.productName}-${item.optionName}`}>
              <td>{item.productName}</td>
              <td>{item.optionName}</td>
              <td>{won(item.unitPrice)}</td>
              <td>{item.quantity}</td>
              <td>{won(item.subtotal)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <dl className="order-amounts">
        <div>
          <dt>상품 금액</dt>
          <dd>{won(detail.itemsAmount)}</dd>
        </div>
        <div>
          <dt>배송비</dt>
          <dd>{won(detail.shippingAmount)}</dd>
        </div>
        <div>
          <dt>결제 금액</dt>
          <dd>{won(detail.totalAmount)}</dd>
        </div>
      </dl>
      <div className="order-shipping">
        {detail.shipping ? (
          <>
            <span>
              {detail.shipping.carrier} · {detail.shipping.trackingNumber}
              {detail.shipping.deliveredAt
                ? ` · ${dateLabel(detail.shipping.deliveredAt)} 배송 완료`
                : ''}
            </span>
            {onEditShipment && (
              <button
                className="text-link"
                type="button"
                onClick={onEditShipment}
              >
                송장 수정
              </button>
            )}
          </>
        ) : (
          <span>등록된 송장이 없습니다.</span>
        )}
      </div>
    </div>
  )
}
