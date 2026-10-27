import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import {
  Link,
  Navigate,
  useLocation,
  useParams,
  useSearchParams,
} from 'react-router-dom'
import { getCategories } from '../../api/category/categories'
import EmptyState from '../../components/EmptyState/EmptyState'
import AuthLoading from '../../features/auth/components/AuthLoading'
import DemandReferenceCard from '../../features/demand/components/DemandReferenceCard'
import {
  apiErrorCode,
  isAdmin,
  isSeller,
  type AuthStatus,
  type AuthUser,
} from '../../features/auth/logic/authUser'
import { categoryKeys } from '../../features/drop/logic/queryKeys'
import SellerDropActions from '../../features/seller/components/SellerDropActions'
import { readSellerDropQuery } from '../../features/seller/logic/sellerDropQuery'
import {
  useSellerDropDetail,
  useSellerDropWishCount,
} from '../../features/seller/logic/useSellerDrops'
import { useSellerDropStocks } from '../../features/seller/logic/useSellerDropStocks'
import { sellerDropError } from '../../features/seller/logic/sellerDropErrors'
import { dateLabel } from '../../utils/date'
import { won } from '../../utils/price'

const MAX_TIMER_DELAY = 2_147_483_647

export default function SellerDropDetailPage({
  user,
  authStatus,
  notify,
}: {
  user: AuthUser | null
  authStatus: AuthStatus
  notify: (message: string) => void
}) {
  const { dropId } = useParams()
  const [params] = useSearchParams()
  const location = useLocation()
  const id = Number(dropId)
  const validId =
    /^\d+$/.test(dropId ?? '') && Number.isSafeInteger(id) && id > 0
  const enabled = validId && authStatus === 'authenticated' && isSeller(user)
  const query = useSellerDropDetail(id, enabled)
  const stocks = useSellerDropStocks(enabled && query.data ? id : null)
  const wishes = useSellerDropWishCount(id, enabled && !!query.data)
  const categories = useQuery({
    queryKey: categoryKeys.all,
    queryFn: getCategories,
    enabled,
  })
  // 수요 분석 카드는 판매 시작 전에만 보인다. 렌더마다 시각이 바뀌지 않게 진입 시각을 쓰고(SellerDropActions와 같음),
  // 화면을 열어 둔 채 판매 시작 시각이 되면 다시 판단해 카드를 내린다
  const [now, setNow] = useState(() => Date.now())
  const saleStartsAt = query.data?.saleStartsAt
  useEffect(() => {
    if (!saleStartsAt) return
    const startsAt = Date.parse(saleStartsAt)
    const delay = startsAt - Date.now()
    // setTimeout은 약 24.8일(2^31-1ms)을 넘는 지연을 바로 실행하므로 그보다 먼 일정은 다음 진입 때 판단한다
    if (delay <= 0 || delay > MAX_TIMER_DELAY) return
    // 타이머가 울린 시점은 판매 시작 시각이므로 그 시각으로 판단한다
    const timer = window.setTimeout(() => setNow(startsAt), delay)
    return () => window.clearTimeout(timer)
  }, [saleStartsAt])
  const { status, page } = readSellerDropQuery(params)
  const backTo = `/seller/drops?status=${status}&page=${page}`
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
  if (!validId)
    return (
      <EmptyState
        title="잘못된 DROP 번호예요."
        link={backTo}
        label="DROP 목록으로 돌아가기"
        variant="page"
      />
    )
  if (query.isPending)
    return (
      <div role="status">
        <EmptyState
          loading
          title="DROP 상세를 불러오는 중이에요."
          variant="page"
        />
      </div>
    )
  if (apiErrorCode(query.error) === 'DROP_ACCESS_DENIED')
    return (
      <EmptyState
        title="이 DROP을 관리할 권한이 없어요."
        link={backTo}
        label="DROP 목록으로 돌아가기"
        variant="page"
      />
    )
  if (apiErrorCode(query.error) === 'DROP_NOT_FOUND')
    return (
      <EmptyState
        title="DROP을 찾을 수 없어요."
        link={backTo}
        label="DROP 목록으로 돌아가기"
        variant="page"
      />
    )
  if (query.isError && !query.data)
    return (
      <div className="seller-drops-result" role="alert">
        <EmptyState
          title="DROP 상세를 불러오지 못했어요."
          description={sellerDropError(query.error).message}
          variant="block"
        />
        <button
          type="button"
          className="secondary-button"
          onClick={() => void query.refetch()}
        >
          다시 시도
        </button>
      </div>
    )
  const drop = query.data!
  const category =
    categories.data?.find((item) => item.categoryId === drop.categoryId)
      ?.name ??
    (drop.categoryId ? `카테고리 #${drop.categoryId}` : '카테고리 미정')
  const stockMap = new Map(
    stocks.data?.options?.map((option) => [option.optionId, option]),
  )
  const totals = stocks.data?.options?.reduce(
    (sum, option) => ({
      available: sum.available + (option.availableStock ?? 0),
      reserved: sum.reserved + (option.reservedStock ?? 0),
      sold: sum.sold + (option.soldStock ?? 0),
    }),
    { available: 0, reserved: 0, sold: 0 },
  )
  const optionLabel = (
    selections: { groupId?: number; valueId?: number }[] | undefined,
  ) =>
    (drop.optionGroups ?? [])
      .map(
        (group) =>
          group.values?.find(
            (value) =>
              value.valueId ===
              selections?.find(
                (selection) => selection.groupId === group.groupId,
              )?.valueId,
          )?.value,
      )
      .filter(Boolean)
      .join(' / ') || '기본 옵션'
  return (
    <section className="page-section seller-page seller-drop-detail-page">
      <Link className="text-link seller-drops-back" to={backTo}>
        ← DROP 관리
      </Link>
      <div className="seller-drops-heading">
        <div>
          <h1>{drop.name || '이름 없는 DROP'}</h1>
          <p>
            {category} ·{' '}
            {drop.saleStartsAt ? dateLabel(drop.saleStartsAt) : '시작 미정'} ~{' '}
            {drop.saleEndsAt ? dateLabel(drop.saleEndsAt) : '종료 미정'}
          </p>
        </div>
        <div className="detail-actions">
          <span className={`status-pill ${drop.status?.toLowerCase()}`}>
            {drop.status}
          </span>
          <SellerDropActions
            dropId={id}
            status={drop.status}
            saleStartsAt={drop.saleStartsAt}
            notify={notify}
          />
        </div>
      </div>
      {query.isError && (
        <p className="form-error" role="alert">
          최신 DROP 정보를 불러오지 못했어요.{' '}
          <button
            type="button"
            className="secondary-button"
            onClick={() => void query.refetch()}
          >
            다시 시도
          </button>
        </p>
      )}
      <div className="seller-drop-overview">
        <section className="panel seller-drop-product">
          <h2>상품 정보</h2>
          {drop.images?.[0]?.imageUrl ? (
            <img
              src={drop.images[0].imageUrl}
              alt={drop.images[0].altText ?? drop.name ?? ''}
            />
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
                {drop.saleStartsAt ? dateLabel(drop.saleStartsAt) : '미정'} ~{' '}
                {drop.saleEndsAt ? dateLabel(drop.saleEndsAt) : '미정'}
              </dd>
            </div>
            <div>
              <dt>배송비</dt>
              <dd>
                {drop.shipping?.shippingFee == null
                  ? '미정'
                  : won(drop.shipping.shippingFee)}
              </dd>
            </div>
            <div>
              <dt>배송 안내</dt>
              <dd>{drop.shipping?.shippingNotice || '미정'}</dd>
            </div>
            <div>
              <dt>활성 WISH</dt>
              <dd>
                {wishes.isPending ? (
                  '조회 중'
                ) : wishes.isError ? (
                  <button
                    type="button"
                    className="secondary-button"
                    onClick={() => void wishes.refetch()}
                  >
                    다시 시도
                  </button>
                ) : (
                  `${wishes.data}명`
                )}
              </dd>
            </div>
          </dl>
        </section>
        <section className="panel seller-drop-options">
          <div className="panel-heading">
            <div>
              <h2>옵션 운영</h2>
              {totals && !stocks.isError && (
                <p>
                  가용 {totals.available} · 예약 {totals.reserved} · 판매{' '}
                  {totals.sold}
                </p>
              )}
            </div>
          </div>
          {stocks.isPending ? (
            <div role="status">
              <EmptyState loading title="옵션 재고를 불러오는 중이에요." />
            </div>
          ) : stocks.isError ? (
            <div className="seller-drops-result" role="alert">
              <EmptyState title="옵션 재고를 불러오지 못했어요." />
              <button
                type="button"
                className="secondary-button"
                onClick={() => void stocks.refetch()}
              >
                다시 시도
              </button>
            </div>
          ) : !drop.options?.length ? (
            <EmptyState title="등록된 옵션 재고가 없어요." />
          ) : (
            <div className="option-stock">
              <table>
                <thead>
                  <tr>
                    {[
                      '옵션',
                      '단가',
                      '전체',
                      '가용',
                      '예약',
                      '판매',
                      '상태',
                    ].map((title) => (
                      <th key={title}>{title}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {drop.options.map((option) => {
                    const stock = stockMap.get(option.optionId)
                    return (
                      <tr key={option.optionId}>
                        <td>{optionLabel(option.selections)}</td>
                        <td>
                          {option.unitPrice == null
                            ? '미정'
                            : won(option.unitPrice)}
                        </td>
                        <td>{stock?.totalStock ?? '—'}</td>
                        <td>{stock?.availableStock ?? '—'}</td>
                        <td>{stock?.reservedStock ?? '—'}</td>
                        <td>{stock?.soldStock ?? '—'}</td>
                        <td>
                          {option.active === false
                            ? '비활성'
                            : stock?.availableStock === 0
                              ? '품절'
                              : stock
                                ? '판매중'
                                : '확인 필요'}
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
      {drop.status === 'WISH' &&
        !!drop.saleStartsAt &&
        Date.parse(drop.saleStartsAt) > now && (
          <DemandReferenceCard dropId={id} />
        )}
      <section className="panel seller-drop-related">
        <h2>관련 작업</h2>
        <div>
          <Link className="secondary-button" to={`/seller/orders?dropId=${id}`}>
            이 DROP의 주문 ↗
          </Link>
          <Link
            className="secondary-button"
            to={`/seller/inventory?dropId=${id}`}
          >
            옵션별 재고 ↗
          </Link>
          <Link className="secondary-button" to={`/seller/sales?dropId=${id}`}>
            판매 실적 ↗
          </Link>
          {drop.status !== 'DRAFT' && (
            <Link className="secondary-button" to={`/drops/${id}`}>
              소비자 화면 ↗
            </Link>
          )}
        </div>
      </section>
    </section>
  )
}
