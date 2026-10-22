export default function DropStockQuantity({
  stock,
}: {
  stock: number | undefined
}) {
  return (
    <div className="detail-stock" data-low={stock !== undefined && stock <= 10}>
      <span className="detail-state-label">남은 수량</span>
      <strong className="detail-stock-value">
        {stock === undefined ? (
          '확인 중'
        ) : (
          <>
            {stock}
            <small>개</small>
          </>
        )}
      </strong>
    </div>
  )
}
