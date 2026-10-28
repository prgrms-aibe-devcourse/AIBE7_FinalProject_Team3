import type { SimilarSales } from '../../../api/demand/similarSales'
import { dateLabel } from '../../../utils/date'
import type { DraftGroup } from '../../seller/logic/sellerDropForm'
import {
  basisLine,
  othersSentence,
  ownSentence,
  salesFacts,
  toSimilarSalesRequest,
} from '../logic/similarSales'
import {
  useSimilarSales,
  useSimilarSalesForForm,
} from '../logic/useSimilarSales'

function SimilarSalesBody({ data }: { data: SimilarSales }) {
  const own = data.own
  const others = data.others!
  const othersShown = others.dropCountBand !== 'NONE'
  return (
    <>
      <p className="similar-sales-basis">{basisLine(data)}</p>
      <ul className="similar-sales-sides">
        {own && (
          <li className={data.basis === 'OWN' ? 'is-used' : undefined}>
            <strong>
              내 지난 DROP
              <span>{data.basis === 'OWN' ? '기준' : '참고'}</span>
              {data.reason === 'OWN_FEW' && <small>표본이 적어 참고용</small>}
            </strong>
            <p>{ownSentence(own)}</p>
            <small>{salesFacts(own)}</small>
          </li>
        )}
        <li className={data.basis === 'OTHERS' ? 'is-used' : undefined}>
          <strong>
            다른 판매자
            <span>{data.basis === 'OTHERS' ? '기준' : '참고'}</span>
          </strong>
          {othersShown ? (
            <>
              <p>{othersSentence(others)}</p>
              <small>
                {salesFacts(others)}
                {others.asOf && ` · ${dateLabel(others.asOf)} 기준`}
              </small>
            </>
          ) : (
            <p>
              비교할 판매자·DROP 수가 적어 다른 판매자 자료는 보여 주지 않아요.
            </p>
          )}
        </li>
      </ul>
      <small>
        같은 카테고리에서 판매 기간 만료·매진으로 끝난 DROP만 비교해요. 다른
        판매자 실적은 구간·반올림 값이에요.
      </small>
    </>
  )
}

/*
    WISH 단계 비슷한 DROP 실적(DEMAND.md 7.4·7.6). 수요 분석 참고 카드 안에 둔다.
    수요 분석과 따로 조회해 이쪽이 실패해도 수요 분석은 그대로 보인다.
 */
export function SimilarSalesSection({ dropId }: { dropId: number }) {
  const query = useSimilarSales(dropId)
  return (
    <section className="similar-sales" aria-labelledby="similar-sales-title">
      <h3 id="similar-sales-title">비슷한 DROP 실적</h3>
      {query.isPending ? (
        <p className="similar-sales-hint" role="status">
          비슷한 DROP의 판매 결과를 찾는 중이에요.
        </p>
      ) : query.isError ? (
        <div className="similar-sales-hint" role="alert">
          비슷한 DROP 실적을 불러오지 못했어요.{' '}
          <button
            type="button"
            className="text-link"
            onClick={() => void query.refetch()}
          >
            다시 시도
          </button>
        </div>
      ) : (
        <SimilarSalesBody data={query.data} />
      )}
    </section>
  )
}

// 생성 단계(DEMAND.md 7.5·7.6). DROP 폼의 재고 추천 패널 아래에 둔다
export function SimilarSalesFormPanel({
  values,
  groups,
}: {
  values: Record<string, string>
  groups: DraftGroup[]
}) {
  const request = toSimilarSalesRequest(values, groups)
  const query = useSimilarSalesForForm(request)
  const data = request ? query.data : undefined
  return (
    <section
      className="similar-sales is-form"
      aria-labelledby="similar-sales-form-title"
    >
      <div>
        <h3 id="similar-sales-form-title">비슷한 DROP 실적</h3>
        <p className="similar-sales-hint">
          상품명·설명이 비슷한 종료 DROP의 매진 결과 · 참고용
        </p>
      </div>
      {!request ? (
        <p className="similar-sales-hint">
          카테고리와 SKU 가격을 입력하면 비슷한 DROP의 판매 결과를 보여 줘요.
        </p>
      ) : query.isError && !data ? (
        <div className="similar-sales-hint" role="alert">
          비슷한 DROP 실적을 불러오지 못했어요.{' '}
          <button
            type="button"
            className="text-link"
            onClick={() => void query.refetch()}
          >
            다시 시도
          </button>
        </div>
      ) : !data ? (
        <p className="similar-sales-hint" role="status">
          비슷한 DROP의 판매 결과를 찾는 중이에요.
        </p>
      ) : (
        <SimilarSalesBody data={data} />
      )}
    </section>
  )
}
