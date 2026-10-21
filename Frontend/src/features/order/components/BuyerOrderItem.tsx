import { Link } from 'react-router-dom'
import { won } from '../../../utils/price'

type Props = {
  productName: string
  optionLabel: string
  quantity: number
  amount: number
  // 주문 생성 전에는 공개 상품 상세가 있어 이미지·상품 링크를 보여준다. 주문 응답에는 둘 다 없다.
  image?: string
  dropId?: number
}

export default function BuyerOrderItem({
  productName,
  optionLabel,
  quantity,
  amount,
  image,
  dropId,
}: Props) {
  return (
    <div className={image ? 'buyer-item' : 'buyer-item no-image'}>
      {image && <img src={image} alt="" />}
      <div>
        {dropId ? (
          <Link className="text-link" to={`/drops/${dropId}`}>
            {productName}
          </Link>
        ) : (
          <strong>{productName}</strong>
        )}
        <p>
          {optionLabel} · {quantity}개
        </p>
      </div>
      <strong>{won(amount)}</strong>
    </div>
  )
}
