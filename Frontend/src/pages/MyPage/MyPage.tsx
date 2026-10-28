import { useState } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { getMyWishes } from '../../api/wish/wishes'
import Avatar from '../../components/Avatar/Avatar'
import EmptyState from '../../components/EmptyState/EmptyState'
import AccountSettings from '../../features/auth/components/AccountSettings'
import AuthLoading from '../../features/auth/components/AuthLoading'
import WishRelatedDrops from '../../features/drop/components/WishRelatedDrops'
import {
  isAdmin,
  isSeller,
  type AuthUser,
} from '../../features/auth/logic/authUser'
import { wishKeys } from '../../features/wish/logic/queryKeys'
import { buyerOrderStatusText } from '../../features/order/logic/buyerOrderView'
import { useMyOrders } from '../../features/order/logic/useBuyerOrders'
import type { SharedProps } from '../../types/store'
import { dateLabel } from '../../utils/date'
import { won } from '../../utils/price'

const WISH_PAGE_SIZE = 20
// 판매 종료·취소된 WISH는 비슷한 굿즈를 펼쳐 볼 수 있다(DROP.md 1.4)
const CLOSED_WISH_STATUSES = new Set(['ENDED', 'CANCELED'])
const ORDER_PAGE_SIZE = 20

const WISH_STATUS_TEXT: Record<string, string> = {
  WISH: 'WISH',
  GRAB: 'GRAB',
  ENDED: '판매 종료',
  CANCELED: '판매 취소',
  DRAFT: '준비 중',
}

type Props = SharedProps & {
  user: AuthUser | null
  onLogout: () => Promise<void>
}

export default function MyPage({
  user,
  onLogout,
  authStatus,
  toggleWish,
  notify,
}: Props) {
  const navigate = useNavigate()
  const location = useLocation()
  const [page, setPage] = useState(0)
  const [relatedOpen, setRelatedOpen] = useState<number | null>(null)
  const [orderPage, setOrderPage] = useState(0)
  const [loggingOut, setLoggingOut] = useState(false)
  const [logoutError, setLogoutError] = useState('')

  const wishesQuery = useQuery({
    queryKey: wishKeys.list(page, WISH_PAGE_SIZE),
    queryFn: () => getMyWishes({ page, size: WISH_PAGE_SIZE }),
    enabled: authStatus === 'authenticated',
  })
  const wishItems = (wishesQuery.data?.content ?? []).flatMap((item) =>
    item.dropId === undefined ? [] : [{ ...item, dropId: item.dropId }],
  )
  const totalWishes = wishesQuery.data?.totalElements ?? 0
  const totalPages = wishesQuery.data?.totalPages ?? 0
  const ordersQuery = useMyOrders(
    { page: orderPage, size: ORDER_PAGE_SIZE },
    authStatus === 'authenticated',
  )
  const orderItems = ordersQuery.data?.content ?? []
  // 조회 중·실패면 개수를 비워 두고 각 영역의 상태 표시를 따른다(GR-160).
  const orderCount = ordersQuery.isSuccess
    ? (ordersQuery.data?.totalElements ?? 0)
    : null
  const wishCount = wishesQuery.isSuccess ? totalWishes : null
  const totalOrderPages = ordersQuery.data?.totalPages ?? 0

  if (authStatus === 'loading') return <AuthLoading />
  // 로그아웃 완료 후의 이동은 아래 처리에 맡겨 로그인 가드와 경합하지 않게 한다.
  if (!user && loggingOut) return <AuthLoading />
  if (!user)
    return <Navigate to="/login" state={{ from: location.pathname }} replace />

  const logout = async () => {
    if (loggingOut) return
    setLoggingOut(true)
    setLogoutError('')
    try {
      await onLogout()
      navigate('/wish')
    } catch {
      setLogoutError('로그아웃하지 못했어요. 잠시 뒤 다시 시도해주세요.')
      setLoggingOut(false)
    }
  }

  return (
    <section className="page-section">
      <h1>My Page</h1>
      <section className="panel account-panel" aria-labelledby="account-title">
        <div className="account-heading">
          <Avatar src={user.profileImageUrl} name={user.nickname} />
          <div className="account-identity">
            <h2 id="account-title" className="sr-only">
              내 계정
            </h2>
            <strong>{user.nickname}</strong>
            <p>{user.email}</p>
            <div className="account-badges">
              <span className="account-badge">
                {isAdmin(user)
                  ? '운영자'
                  : isSeller(user)
                    ? '승인된 판매자'
                    : '일반 회원'}
              </span>
            </div>
          </div>
          <dl className="account-stats">
            <div>
              <dt>주문</dt>
              <dd>{orderCount}</dd>
            </div>
            <div>
              <dt>WISH</dt>
              <dd>{wishCount}</dd>
            </div>
          </dl>
        </div>
      </section>
      <div className="my-columns">
        <section className="panel order-panel">
          <div className="panel-heading">
            <h2>주문</h2>
            <span>{ordersQuery.data?.totalElements ?? 0}</span>
          </div>
          {ordersQuery.isPending ? (
            <div role="status" aria-live="polite">
              <EmptyState loading title="주문 목록을 불러오는 중이에요." />
            </div>
          ) : ordersQuery.isError ? (
            <div className="catalog-error" role="alert">
              <EmptyState
                title="주문 목록을 불러오지 못했어요."
                description="잠시 후 다시 시도해주세요."
              />
              <button
                type="button"
                className="secondary-button"
                onClick={() => void ordersQuery.refetch()}
              >
                다시 시도
              </button>
            </div>
          ) : orderItems.length ? (
            <>
              {orderItems.map((order) => {
                const pending = order.status === 'PAYMENT_PENDING'
                return (
                  <div className="compact-item order" key={order.id}>
                    <span
                      className={
                        pending ? 'order-status pending' : 'order-status'
                      }
                    >
                      {buyerOrderStatusText[order.status]}
                    </span>
                    <div>
                      <strong>{order.productName}</strong>
                      <small>
                        {order.orderNumber} · 주문일{' '}
                        {dateLabel(order.orderedAt)}
                      </small>
                    </div>
                    <b>{won(order.totalAmount)}</b>
                    {/* 행마다 같은 문구라 주문번호를 붙여 링크를 구분한다(보이는 문구는 이름에 포함). */}
                    {pending ? (
                      <Link
                        className="primary-button"
                        to={`/checkout/${order.id}`}
                        aria-label={`${order.orderNumber} 결제하기`}
                      >
                        결제하기
                      </Link>
                    ) : (
                      <Link
                        className="secondary-button"
                        to={`/my/orders/${order.id}`}
                        aria-label={`${order.orderNumber} 주문 상세`}
                      >
                        주문 상세
                      </Link>
                    )}
                  </div>
                )
              })}
              {totalOrderPages > 1 && (
                <div className="catalog-pagination">
                  <button
                    type="button"
                    className="secondary-button"
                    disabled={orderPage === 0}
                    onClick={() =>
                      setOrderPage((current) => Math.max(0, current - 1))
                    }
                  >
                    이전
                  </button>
                  <span aria-live="polite">
                    {orderPage + 1} / {totalOrderPages} 페이지
                  </span>
                  <button
                    type="button"
                    className="secondary-button"
                    disabled={!ordersQuery.data?.hasNext}
                    onClick={() => setOrderPage((current) => current + 1)}
                  >
                    다음
                  </button>
                </div>
              )}
            </>
          ) : (
            <EmptyState
              title="아직 주문이 없어요."
              link="/grab"
              label="GRAB 둘러보기"
            />
          )}
        </section>
        <section className="panel wish-panel">
          <div className="panel-heading">
            <h2>WISH</h2>
            <span>{totalWishes}</span>
          </div>
          {wishesQuery.isPending ? (
            <div role="status" aria-live="polite">
              <EmptyState loading title="WISH 목록을 불러오는 중이에요." />
            </div>
          ) : wishesQuery.isError ? (
            <div className="catalog-error" role="alert">
              <EmptyState
                title="WISH 목록을 불러오지 못했어요."
                description="잠시 후 다시 시도해주세요."
              />
              <button
                type="button"
                className="secondary-button"
                onClick={() => void wishesQuery.refetch()}
              >
                다시 시도
              </button>
            </div>
          ) : wishItems.length ? (
            <>
              {wishItems.map((item) => (
                <div className="compact-item" key={item.dropId}>
                  {item.thumbnailUrl ? (
                    <img src={item.thumbnailUrl} alt="" />
                  ) : (
                    <span role="img" aria-label={`${item.name} 이미지 없음`}>
                      이미지 준비 중
                    </span>
                  )}
                  <div>
                    <Link to={`/drops/${item.dropId}`}>
                      <strong>{item.name}</strong>
                    </Link>
                    <small>
                      {item.minPrice != null
                        ? `${won(item.minPrice)}부터`
                        : '가격 미정'}{' '}
                      · {WISH_STATUS_TEXT[item.status ?? ''] ?? item.status}
                    </small>
                    <small>
                      오픈 일정 ·{' '}
                      {item.saleStartsAt
                        ? dateLabel(item.saleStartsAt)
                        : '미정'}
                    </small>
                    {CLOSED_WISH_STATUSES.has(item.status ?? '') && (
                      <button
                        type="button"
                        className="wish-related-toggle"
                        aria-expanded={relatedOpen === item.dropId}
                        aria-controls={`wish-related-${item.dropId}`}
                        onClick={() =>
                          setRelatedOpen((current) =>
                            current === item.dropId ? null : item.dropId,
                          )
                        }
                      >
                        {relatedOpen === item.dropId
                          ? '비슷한 굿즈 접기'
                          : '비슷한 굿즈 보기'}
                      </button>
                    )}
                  </div>
                  <button
                    type="button"
                    onClick={() => {
                      void toggleWish(item.dropId)
                    }}
                  >
                    취소
                  </button>
                  {relatedOpen === item.dropId && (
                    <WishRelatedDrops
                      dropId={item.dropId}
                      id={`wish-related-${item.dropId}`}
                    />
                  )}
                </div>
              ))}
              {totalPages > 1 && (
                <div className="catalog-pagination">
                  <button
                    type="button"
                    className="secondary-button"
                    disabled={page === 0}
                    onClick={() =>
                      setPage((current) => Math.max(0, current - 1))
                    }
                  >
                    이전
                  </button>
                  <span aria-live="polite">
                    {page + 1} / {totalPages} 페이지
                  </span>
                  <button
                    type="button"
                    className="secondary-button"
                    disabled={!wishesQuery.data?.hasNext}
                    onClick={() => setPage((current) => current + 1)}
                  >
                    다음
                  </button>
                </div>
              )}
            </>
          ) : (
            <EmptyState
              title="아직 담은 WISH가 없어요."
              link="/wish"
              label="WISH 둘러보기"
            />
          )}
        </section>
      </div>
      <AccountSettings
        user={user}
        notify={notify}
        loggingOut={loggingOut}
        logoutError={logoutError}
        onLogout={() => void logout()}
      />
    </section>
  )
}
