import { Link, Navigate, useLocation, useSearchParams } from 'react-router-dom'
import EmptyState from '../../components/EmptyState/EmptyState'
import AuthLoading from '../../features/auth/components/AuthLoading'
import {
  isAdmin,
  isSeller,
  type AuthStatus,
  type AuthUser,
} from '../../features/auth/logic/authUser'
import {
  readSellerDropQuery,
  sellerDropStatuses,
} from '../../features/seller/logic/sellerDropQuery'
import { useSellerDrops } from '../../features/seller/logic/useSellerDrops'
import { sellerDropError } from '../../features/seller/logic/sellerDropErrors'
import SellerDropActions from '../../features/seller/components/SellerDropActions'
import { dateLabel } from '../../utils/date'
import { won } from '../../utils/price'

export default function SellerDropsPage({
  user,
  authStatus,
  notify,
}: {
  user: AuthUser | null
  authStatus: AuthStatus
  notify: (message: string) => void
}) {
  const [params, setParams] = useSearchParams()
  const location = useLocation()
  const { status, page } = readSellerDropQuery(params)
  const query = useSellerDrops(
    { status: status === 'ALL' ? undefined : status, page, size: 20 },
    authStatus === 'authenticated' && isSeller(user),
  )
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
  const setQuery = (nextStatus: string, nextPage: number) =>
    setParams({ status: nextStatus, page: String(nextPage) })
  const data = query.data
  return (
    <section className="page-section seller-page seller-drops-page">
      <div className="seller-drops-heading">
        <div>
          <h1>DROP 관리</h1>
          <p>상태별로 상품을 살펴보고 공개를 관리합니다.</p>
        </div>
        <Link className="primary-button" to="/seller/drops/new">
          ＋ 새 DROP
        </Link>
      </div>
      <section className="panel seller-drops-panel">
        <div className="seller-drops-controls">
          <div className="chips" aria-label="DROP 상태 필터">
            {sellerDropStatuses.map((item) => (
              <button
                key={item}
                type="button"
                aria-pressed={status === item}
                className={status === item ? 'active' : undefined}
                onClick={() => setQuery(item, 0)}
              >
                {item === 'ALL' ? '전체' : item}
              </button>
            ))}
          </div>
        </div>
        {query.isPending ? (
          <div role="status">
            <EmptyState title="DROP 목록을 불러오는 중이에요." />
          </div>
        ) : query.isError ? (
          <div className="seller-drops-result" role="alert">
            <EmptyState
              title="DROP 목록을 불러오지 못했어요."
              description={sellerDropError(query.error).message}
            />
            <button
              type="button"
              className="secondary-button"
              onClick={() => void query.refetch()}
            >
              다시 시도
            </button>
          </div>
        ) : !data?.content?.length ? (
          <div className="seller-drops-result">
            <EmptyState
              title={
                page
                  ? '해당 페이지에 DROP이 없어요.'
                  : '해당 상태의 DROP이 없어요.'
              }
              link={page ? undefined : '/seller/drops/new'}
              label={page ? undefined : '새 DROP 만들기'}
            />
            {page > 0 && (
              <button
                type="button"
                className="secondary-button"
                onClick={() => setQuery(status, 0)}
              >
                첫 페이지
              </button>
            )}
          </div>
        ) : (
          <>
            <div className="seller-drops-list">
              {data.content.map((drop) => (
                <article className="seller-drops-row" key={drop.dropId}>
                  {drop.thumbnailUrl ? (
                    <img src={drop.thumbnailUrl} alt="" />
                  ) : (
                    <div className="seller-drops-placeholder">
                      이미지 준비 중
                    </div>
                  )}
                  <div className="seller-drops-name">
                    <Link
                      to={`/seller/drops/${drop.dropId}?status=${status}&page=${page}`}
                    >
                      {drop.name || '이름 없는 DROP'}
                    </Link>
                    <small>DROP #{drop.dropId}</small>
                  </div>
                  <span className={`status-pill ${drop.status?.toLowerCase()}`}>
                    {drop.status}
                  </span>
                  <div className="seller-drops-meta">
                    <small>등록일</small>
                    <span>
                      {drop.createdAt ? dateLabel(drop.createdAt) : '미정'}
                    </span>
                  </div>
                  <div className="seller-drops-meta">
                    <small>최저 가격</small>
                    <span>
                      {drop.minPrice == null ? '미정' : won(drop.minPrice)}
                    </span>
                  </div>
                  <Link
                    className="text-link"
                    to={`/seller/drops/${drop.dropId}?status=${status}&page=${page}`}
                  >
                    보기 ↗
                  </Link>
                  <SellerDropActions
                    dropId={drop.dropId!}
                    status={drop.status}
                    detailSearch={`?status=${status}&page=${page}`}
                    notify={notify}
                  />
                </article>
              ))}
            </div>
            <div className="seller-drops-pagination">
              <span>총 {data.totalElements}개</span>
              <div>
                <button
                  type="button"
                  className="secondary-button"
                  disabled={page === 0}
                  onClick={() => setQuery(status, page - 1)}
                >
                  이전
                </button>
                <span>
                  {page + 1} / {data.totalPages}
                </span>
                <button
                  type="button"
                  className="secondary-button"
                  disabled={!data.hasNext}
                  onClick={() => setQuery(status, page + 1)}
                >
                  다음
                </button>
              </div>
            </div>
          </>
        )}
      </section>
    </section>
  )
}
