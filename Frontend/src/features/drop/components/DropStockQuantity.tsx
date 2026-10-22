import { useState } from 'react'

export default function DropStockQuantity({
  stock,
}: {
  stock: number | undefined
}) {
  const [snapshot, setSnapshot] = useState({
    current: stock,
    previous: undefined as number | undefined,
  })
  if (stock !== snapshot.current) {
    setSnapshot({
      current: stock,
      previous: stock === undefined ? undefined : snapshot.current,
    })
  }
  const changing = snapshot.previous !== undefined
  return (
    <div className="detail-stock" data-low={stock !== undefined && stock <= 10}>
      <span className="detail-state-label">남은 수량</span>
      <strong className="detail-stock-value">
        {stock === undefined ? (
          '확인 중'
        ) : (
          <>
            <span
              key={stock}
              className="detail-stock-digits"
              data-changing={changing}
              onAnimationEnd={(event) => {
                if (event.animationName !== 'detail-stock-in') return
                setSnapshot((current) =>
                  current.current === stock
                    ? { current: stock, previous: undefined }
                    : current,
                )
              }}
            >
              {changing && (
                <span className="detail-stock-previous" aria-hidden="true">
                  {snapshot.previous}
                </span>
              )}
              <span className="detail-stock-current">{stock}</span>
            </span>
            <small>개</small>
          </>
        )}
      </strong>
    </div>
  )
}
