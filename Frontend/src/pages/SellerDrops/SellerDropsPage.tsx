import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import EmptyState from '../../components/EmptyState/EmptyState'
import {
  readSellerDropQuery,
  sellerDropStatuses,
  type SellerMockDrop,
} from '../../features/seller/mock/mockSellerDrops'
import { dateLabel } from '../../utils/date'
import { won } from '../../utils/price'

const PAGE_SIZE = 4

export default function SellerDropsPage({
  drops,
}: {
  drops: SellerMockDrop[]
}) {
  const [params, setParams] = useSearchParams()
  const [result, setResult] = useState<'READY' | 'LOADING' | 'ERROR' | 'EMPTY'>(
    'READY',
  )
  const { status, page } = readSellerDropQuery(params)
  const filtered = drops.filter(
    (drop) => status === 'ALL' || drop.status === status,
  )
  const visible = filtered.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE)
  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE))
  const setQuery = (nextStatus: string, nextPage: number) =>
    setParams({ status: nextStatus, page: String(nextPage) })

  return (
    <section className="page-section seller-page seller-drops-page">
      <div className="seller-drops-heading">
        <div>
          <p className="eyebrow">DROP STUDIO</p>
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
          <label className="seller-drops-demo">
            목록 결과 예시
            <select
              value={result}
              onChange={(event) =>
                setResult(event.target.value as typeof result)
              }
            >
              <option value="READY">조회 성공</option>
              <option value="LOADING">불러오는 중</option>
              <option value="ERROR">조회 실패</option>
              <option value="EMPTY">빈 결과</option>
            </select>
          </label>
        </div>
        {result === 'LOADING' ? (
          <EmptyState title="DROP 목록을 불러오는 중이에요." />
        ) : result === 'ERROR' ? (
          <div className="seller-drops-result">
            <EmptyState title="DROP 목록을 불러오지 못했어요." />
            <button
              type="button"
              className="secondary-button"
              onClick={() => setResult('READY')}
            >
              다시 시도
            </button>
          </div>
        ) : result === 'EMPTY' || filtered.length === 0 ? (
          <EmptyState
            title="해당 상태의 DROP이 없어요."
            link="/seller/drops/new"
            label="새 DROP 만들기"
          />
        ) : visible.length === 0 ? (
          <div className="seller-drops-result">
            <EmptyState title="해당 페이지에 DROP이 없어요." />
            <button
              type="button"
              className="secondary-button"
              onClick={() => setQuery(status, 0)}
            >
              첫 페이지
            </button>
          </div>
        ) : (
          <>
            <div className="seller-drops-list">
              {visible.map((drop) => {
                const minPrice = drop.skus
                  .filter((sku) => sku.active !== false)
                  .reduce<number | null>(
                    (min, sku) =>
                      min === null ? sku.price : Math.min(min, sku.price),
                    null,
                  )
                return (
                  <article className="seller-drops-row" key={drop.id}>
                    {drop.image ? (
                      <img src={drop.image} alt="" />
                    ) : (
                      <div className="seller-drops-placeholder">
                        이미지 준비 중
                      </div>
                    )}
                    <div className="seller-drops-name">
                      <Link
                        to={`/seller/drops/${drop.id}?status=${status}&page=${page}`}
                      >
                        {drop.name || '이름 없는 DROP'}
                      </Link>
                      <small>{drop.category || '카테고리 미정'}</small>
                    </div>
                    <span
                      className={`status-pill ${drop.status.toLowerCase()}`}
                    >
                      {drop.status}
                    </span>
                    <div className="seller-drops-meta">
                      <small>판매 일정</small>
                      <span>
                        {drop.saleStartsAt
                          ? dateLabel(drop.saleStartsAt)
                          : '미정'}{' '}
                        ~{' '}
                        {drop.saleEndsAt ? dateLabel(drop.saleEndsAt) : '미정'}
                      </span>
                    </div>
                    <div className="seller-drops-meta">
                      <small>최저 가격 · 재고</small>
                      <span>
                        {minPrice === null ? '미정' : won(minPrice)} ·{' '}
                        {drop.skus.reduce((sum, sku) => sum + sku.stock, 0)}개
                      </span>
                    </div>
                    <Link
                      className="text-link"
                      to={`/seller/drops/${drop.id}?status=${status}&page=${page}`}
                    >
                      보기 ↗
                    </Link>
                  </article>
                )
              })}
            </div>
            <div className="seller-drops-pagination">
              <span>총 {filtered.length}개</span>
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
                  {page + 1} / {totalPages}
                </span>
                <button
                  type="button"
                  className="secondary-button"
                  disabled={page + 1 >= totalPages}
                  onClick={() => setQuery(status, page + 1)}
                >
                  다음
                </button>
              </div>
            </div>
          </>
        )}
      </section>
      <small className="seller-drops-note">
        목록과 상태는 Mock 데이터입니다.
      </small>
    </section>
  )
}
