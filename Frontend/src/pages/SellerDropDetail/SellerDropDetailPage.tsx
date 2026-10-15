import { useState, type FormEvent } from 'react'
import {
  Link,
  Navigate,
  useLocation,
  useParams,
  useSearchParams,
} from 'react-router-dom'
import EmptyState from '../../components/EmptyState/EmptyState'
import AuthLoading from '../../features/auth/components/AuthLoading'
import {
  isAdmin,
  isSeller,
  type AuthStatus,
  type AuthUser,
} from '../../features/auth/logic/authUser'
import { endedDrops } from '../../features/drop/mock/mockEndedDrops'
import { sellerOrders } from '../../features/order/mock/mockSellerOrders'
import {
  inventoryTotals,
  optionLabel,
  stockBreakdown,
} from '../../features/seller/logic/inventory'
import {
  otherSellerDrop,
  readSellerDropQuery,
  transitionSellerDrop,
  type SellerMockDrop,
} from '../../features/seller/mock/mockSellerDrops'
import { dateLabel } from '../../utils/date'
import { won } from '../../utils/price'

type Props = {
  user: AuthUser | null
  authStatus: AuthStatus
  drops: SellerMockDrop[]
  ownerEmail: string
  onChange: (drops: SellerMockDrop[]) => void
  notify: (message: string) => void
}

export default function SellerDropDetailPage({
  user,
  authStatus,
  drops,
  ownerEmail,
  onChange,
  notify,
}: Props) {
  const { dropId } = useParams()
  const [params] = useSearchParams()
  const location = useLocation()
  const { status, page } = readSellerDropQuery(params)
  const backTo = `/seller/drops?status=${status}&page=${page}`
  const [result, setResult] = useState<'SUCCESS' | 'FAILURE' | 'CONFLICT'>(
    'SUCCESS',
  )
  const [detailResult, setDetailResult] = useState<
    'READY' | 'LOADING' | 'ERROR'
  >('READY')
  const [stockResult, setStockResult] = useState<'READY' | 'LOADING' | 'ERROR'>(
    'READY',
  )
  const [confirming, setConfirming] = useState<'PUBLISH' | 'CANCEL' | null>(
    null,
  )
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const [now] = useState(() => Date.now())
  const drop =
    drops.find((item) => item.id === dropId) ??
    (dropId === '999' ? otherSellerDrop : undefined)

  if (authStatus === 'loading') return <AuthLoading />
  if (!user)
    return (
      <Navigate
        to="/login"
        state={{ from: location.pathname + location.search }}
        replace
      />
    )
  if (!isSeller(user))
    return (
      <EmptyState
        title="판매자 권한이 필요해요."
        link={isAdmin(user) ? '/my' : '/seller/apply'}
        label={isAdmin(user) ? '내 계정으로 돌아가기' : '판매자 신청 보기'}
        variant="page"
      />
    )

  if (!drop)
    return (
      <EmptyState
        title={
          dropId && !/^\d+$|^[0-9a-f-]{36}$/i.test(dropId)
            ? '잘못된 DROP 번호예요.'
            : 'DROP을 찾을 수 없어요.'
        }
        link={backTo}
        label="DROP 목록으로 돌아가기"
        variant="page"
      />
    )
  if (drop.ownerEmail !== ownerEmail)
    return (
      <EmptyState
        title="이 DROP을 관리할 권한이 없어요."
        link={backTo}
        label="DROP 목록으로 돌아가기"
        variant="page"
      />
    )

  const cancelable =
    drop.status === 'WISH' &&
    !!drop.saleStartsAt &&
    new Date(drop.saleStartsAt).getTime() > now
  const orders = sellerOrders.filter(
    (order) => order.dropId === Number(drop.id),
  )
  const sales = endedDrops.find((item) => String(item.dropId) === drop.id)
  const salesAmount =
    sales?.salesAmount ??
    orders.reduce((sum, order) => sum + order.totalAmount, 0)
  const totals = inventoryTotals(drop)

  const transition = (action: 'PUBLISH' | 'CANCEL') => {
    if (action === 'CANCEL' && (!reason.trim() || reason.length > 500)) {
      setError('취소 사유를 1~500자로 입력해주세요.')
      return
    }
    if (confirming !== action) {
      setConfirming(action)
      setError('')
      return
    }
    if (result !== 'SUCCESS') {
      setError(
        result === 'CONFLICT'
          ? '상태가 바뀌었습니다. DROP을 다시 확인해주세요.'
          : '상태를 변경하지 못했어요. 다시 시도해주세요.',
      )
      setConfirming(null)
      return
    }
    const next = transitionSellerDrop(drops, drop.id, action, reason)
    if (next.error) {
      setError(next.error)
      setConfirming(null)
      return
    }
    onChange(next.drops)
    notify(
      action === 'PUBLISH'
        ? 'Mock DROP을 WISH 상태로 공개했어요.'
        : 'Mock DROP 출시를 취소했어요.',
    )
    setConfirming(null)
    setReason('')
    setError('')
  }

  const cancel = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    transition('CANCEL')
  }

  return (
    <section className="page-section seller-page seller-drop-detail-page">
      <Link className="text-link seller-drops-back" to={backTo}>
        ← DROP 관리
      </Link>
      <div className="seller-drops-heading">
        <div>
          <p className="eyebrow">DROP DETAIL</p>
          <h1>{drop.name || '이름 없는 DROP'}</h1>
          <p>
            {drop.category || '카테고리 미정'} ·{' '}
            {drop.saleStartsAt ? dateLabel(drop.saleStartsAt) : '시작 미정'} ~{' '}
            {drop.saleEndsAt ? dateLabel(drop.saleEndsAt) : '종료 미정'}
          </p>
        </div>
        <div className="detail-actions">
          <span className={`status-pill ${drop.status.toLowerCase()}`}>
            {drop.status}
          </span>
          {detailResult === 'READY' && drop.status === 'DRAFT' && (
            <Link
              className="secondary-button"
              to={`/seller/drops/${drop.id}/edit`}
            >
              수정
            </Link>
          )}
          {detailResult === 'READY' && drop.status === 'DRAFT' && (
            <button
              type="button"
              className="primary-button"
              onClick={() => transition('PUBLISH')}
            >
              {confirming === 'PUBLISH' ? 'Mock 공개 확인' : '공개 확인'}
            </button>
          )}
          {detailResult === 'READY' && cancelable && (
            <button
              type="button"
              className="secondary-button"
              onClick={() => {
                setConfirming('CANCEL')
                document.getElementById('seller-cancel-reason')?.focus()
              }}
            >
              출시 취소
            </button>
          )}
        </div>
      </div>
      <label className="seller-drops-demo">
        상세 결과 예시
        <select
          value={detailResult}
          onChange={(event) =>
            setDetailResult(event.target.value as typeof detailResult)
          }
        >
          <option value="READY">조회 성공</option>
          <option value="LOADING">불러오는 중</option>
          <option value="ERROR">조회 실패</option>
        </select>
      </label>
      {detailResult === 'LOADING' ? (
        <EmptyState title="DROP 상세를 불러오는 중이에요." variant="block" />
      ) : detailResult === 'ERROR' ? (
        <div className="seller-drops-result">
          <EmptyState title="DROP 상세를 불러오지 못했어요." variant="block" />
          <button
            type="button"
            className="secondary-button"
            onClick={() => setDetailResult('READY')}
          >
            다시 시도
          </button>
        </div>
      ) : (
        <>
          {(drop.status === 'DRAFT' || cancelable) && (
            <section className="panel seller-drop-review">
              <h2>상태 변경 예시</h2>
              <label className="seller-drops-demo">
                변경 결과 예시
                <select
                  value={result}
                  onChange={(event) => {
                    setResult(event.target.value as typeof result)
                    setConfirming(null)
                    setError('')
                  }}
                >
                  <option value="SUCCESS">처리 성공</option>
                  <option value="FAILURE">요청 실패</option>
                  <option value="CONFLICT">상태 충돌</option>
                </select>
              </label>
              {cancelable && (
                <form onSubmit={cancel}>
                  <label>
                    출시 취소 사유 (필수)
                    <textarea
                      id="seller-cancel-reason"
                      maxLength={500}
                      required
                      value={reason}
                      onChange={(event) => {
                        setReason(event.target.value)
                        setConfirming(null)
                      }}
                    />
                  </label>
                  <button type="submit" className="secondary-button">
                    {confirming === 'CANCEL'
                      ? 'Mock 취소 확인'
                      : '취소 사유 확인'}
                  </button>
                </form>
              )}
              {confirming && (
                <p>
                  이 DROP을 {confirming === 'PUBLISH' ? '공개' : '출시 취소'}
                  하는 Mock 결과를 표시할까요?
                </p>
              )}
              {error && (
                <p className="form-error" role="alert">
                  {error}
                </p>
              )}
            </section>
          )}
          <div className="seller-drop-overview">
            <section className="panel seller-drop-product">
              <h2>상품 정보</h2>
              {drop.image ? (
                <img src={drop.image} alt={drop.name} />
              ) : (
                <div className="seller-drop-image-empty">
                  GALLERY 이미지 준비 중
                </div>
              )}
              <p>{drop.description || '상품 설명을 준비 중입니다.'}</p>
              <dl>
                <div>
                  <dt>판매 일정</dt>
                  <dd>
                    {drop.saleStartsAt ? dateLabel(drop.saleStartsAt) : '미정'}{' '}
                    ~ {drop.saleEndsAt ? dateLabel(drop.saleEndsAt) : '미정'}
                  </dd>
                </div>
                <div>
                  <dt>배송비</dt>
                  <dd>{drop.shippingFee ? won(drop.shippingFee) : '무료'}</dd>
                </div>
                <div>
                  <dt>배송 안내</dt>
                  <dd>{drop.shippingNotice || '미정'}</dd>
                </div>
                <div>
                  <dt>활성 WISH</dt>
                  <dd>{drop.wishCount}명</dd>
                </div>
              </dl>
            </section>
            <section className="panel seller-drop-options">
              <div className="panel-heading">
                <div>
                  <h2>옵션 운영</h2>
                  <p>
                    가용 {totals.available} · 예약 {totals.reserved} · 판매{' '}
                    {totals.sold}
                  </p>
                </div>
              </div>
              <label className="seller-drops-demo">
                재고 결과 예시
                <select
                  value={stockResult}
                  onChange={(event) =>
                    setStockResult(event.target.value as typeof stockResult)
                  }
                >
                  <option value="READY">조회 성공</option>
                  <option value="LOADING">불러오는 중</option>
                  <option value="ERROR">조회 실패</option>
                </select>
              </label>
              {stockResult === 'LOADING' ? (
                <EmptyState title="옵션 재고를 불러오는 중이에요." />
              ) : stockResult === 'ERROR' ? (
                <div className="seller-drops-result">
                  <EmptyState title="옵션 재고를 불러오지 못했어요." />
                  <button
                    type="button"
                    className="secondary-button"
                    onClick={() => setStockResult('READY')}
                  >
                    다시 시도
                  </button>
                </div>
              ) : drop.skus.length === 0 ? (
                <EmptyState title="등록된 옵션 재고가 없어요." />
              ) : (
                <div className="option-stock">
                  <table>
                    <thead>
                      <tr>
                        <th>옵션</th>
                        <th>단가</th>
                        <th>전체</th>
                        <th>가용</th>
                        <th>예약</th>
                        <th>판매</th>
                        <th>상태</th>
                      </tr>
                    </thead>
                    <tbody>
                      {drop.skus.map((sku) => {
                        const item = stockBreakdown(sku)
                        return (
                          <tr key={sku.id}>
                            <td>{optionLabel(drop, sku)}</td>
                            <td>{won(sku.price)}</td>
                            <td>{item.total}</td>
                            <td>{item.available}</td>
                            <td>{item.reserved}</td>
                            <td>{item.sold}</td>
                            <td>
                              {sku.active === false
                                ? '비활성'
                                : item.available === 0
                                  ? '품절'
                                  : '판매중'}
                            </td>
                          </tr>
                        )
                      })}
                    </tbody>
                  </table>
                </div>
              )}
            </section>
          </div>
          <section className="panel seller-drop-related">
            <h2>관련 작업</h2>
            <p>
              주문 {orders.length}건 · 실적 {won(salesAmount)}
            </p>
            <div>
              <Link
                className="secondary-button"
                to={`/seller/orders?dropId=${drop.id}`}
              >
                이 DROP의 주문 ↗
              </Link>
              <Link
                className="secondary-button"
                to={`/seller/inventory?dropId=${drop.id}`}
              >
                옵션별 재고 ↗
              </Link>
              <Link
                className="secondary-button"
                to={`/seller/sales?dropId=${drop.id}`}
              >
                판매 실적 ↗
              </Link>
              {drop.status !== 'DRAFT' && (
                <Link className="secondary-button" to={`/drops/${drop.id}`}>
                  소비자 화면 ↗
                </Link>
              )}
            </div>
          </section>
          <small className="seller-drops-note">
            상태와 재고는 Mock 예시입니다.
          </small>
        </>
      )}
    </section>
  )
}
