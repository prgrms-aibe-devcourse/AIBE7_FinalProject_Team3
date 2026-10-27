import type { StockRecommendation } from '../../../api/demand/stockRecommendation'
import { won } from '../../../utils/price'
import { CONFIDENCE_LABEL, count, percent } from '../logic/demandFormat'
import {
  PRICE_BAND_LABEL,
  PRICE_POSITION_LABEL,
  STOCK_GAP_TEXT,
  toRecommendationInput,
} from '../logic/stockRecommendation'
import { useStockRecommendation } from '../logic/useStockRecommendation'

type Sales = NonNullable<StockRecommendation['own']>

function BasisLine({
  title,
  sales,
  sample,
  used,
}: {
  title: string
  sales: Sales
  sample: string
  used: boolean
}) {
  if (sales.confidence === 'NONE') return null
  return (
    <li className={used ? 'is-used' : undefined}>
      <strong>
        {title}
        {used && <span>추천에 사용</span>}
      </strong>
      <span>
        판매량 {sales.soldLow}~{sales.soldHigh}개 (중간 {sales.soldMedian}개) ·
        판매율 {percent(sales.sellThroughRate)} · 품절{' '}
        {percent(sales.soldOutRate)}
      </span>
      <small>
        {sample} · {CONFIDENCE_LABEL[sales.confidence!]}
      </small>
    </li>
  )
}

/*
    DROP 생성·DRAFT 편집 폼의 재고 추천·가격대 참고(DEMAND.md 5절, GR-168).
    입력·저장·공개를 막지 않고, "추천 수량 채우기"를 눌러야만 재고 칸이 바뀐다.
 */
export default function StockRecommendationPanel({
  values,
  onApply,
}: {
  values: Record<string, string>
  onApply: (recommendation: StockRecommendation, rowKeys: string[]) => void
}) {
  const input = toRecommendationInput(values)
  const query = useStockRecommendation(input?.request ?? null)
  const data = input ? query.data : undefined
  const recommendation = data?.recommendation
  const bands = (data?.priceReference?.bands ?? []).filter(
    (band) => band.sellThroughRate != null,
  )
  const position = data?.priceReference?.position
  const typicalLow = data?.priceReference?.typicalPriceLow
  const typicalHigh = data?.priceReference?.typicalPriceHigh
  const typicalRange = typicalLow != null && typicalHigh != null
  return (
    <section
      className="stock-recommendation"
      aria-labelledby="stock-recommendation-title"
    >
      <div className="stock-recommendation-heading">
        <h3 id="stock-recommendation-title">재고 추천</h3>
        <p>같은 카테고리·비슷한 가격대 종료 DROP의 판매 실적 기준 · 참고용</p>
      </div>
      {!input ? (
        <p className="stock-recommendation-hint">
          카테고리와 SKU 가격을 입력하면 비슷한 DROP의 판매 실적으로 재고를
          추천해요.
        </p>
      ) : query.isError && !data ? (
        <div className="stock-recommendation-hint" role="alert">
          추천을 불러오지 못했어요. 재고는 직접 입력해도 돼요.{' '}
          <button
            type="button"
            className="text-link"
            onClick={() => void query.refetch()}
          >
            다시 시도
          </button>
        </div>
      ) : !data ? (
        <p className="stock-recommendation-hint" role="status">
          비슷한 DROP의 판매 실적을 찾는 중이에요.
        </p>
      ) : (
        <>
          {recommendation?.recommended != null && (
            <>
              <dl className="stock-recommendation-metrics">
                <div>
                  <dt>예상 판매량</dt>
                  <dd>{count(recommendation.expected)}</dd>
                  <small>
                    범위 {recommendation.low}~{recommendation.high}개
                  </small>
                </div>
                <div className="is-primary">
                  <dt>준비 권장 수량</dt>
                  <dd>{count(recommendation.recommended)}</dd>
                  <small>
                    안전 재고 {percent(recommendation.safetyStockRate)} 포함
                  </small>
                </div>
              </dl>
              <button
                type="button"
                className="secondary-button"
                onClick={() => onApply(data, input.rowKeys)}
              >
                추천 수량 채우기
              </button>
            </>
          )}
          {data.dataGaps!.length > 0 && (
            <ul className="stock-recommendation-gaps">
              {data.dataGaps!.map((gap) => (
                <li key={gap}>{STOCK_GAP_TEXT[gap]}</li>
              ))}
            </ul>
          )}
          <ul className="stock-recommendation-bases">
            <BasisLine
              title="내 실적"
              sales={data.own!}
              used={data.basis === 'OWN'}
              sample={`내 종료 DROP ${data.own!.dropCount}개`}
            />
            <BasisLine
              title="시장 평균"
              sales={data.market!}
              used={data.basis === 'MARKET'}
              sample={
                data.market!.dropCountBand === '20_PLUS'
                  ? '다른 판매자 DROP 20개 이상'
                  : '다른 판매자 DROP 10~19개'
              }
            />
          </ul>
          {(position && PRICE_POSITION_LABEL[position]) ||
          typicalRange ||
          bands.length ? (
            <div className="stock-recommendation-price">
              <strong>
                가격대 참고
                {position && PRICE_POSITION_LABEL[position] && (
                  <span> · 입력 가격은 {PRICE_POSITION_LABEL[position]}</span>
                )}
              </strong>
              {typicalRange && (
                <p>
                  다른 판매자 DROP 가격은 보통 {won(typicalLow)}~
                  {won(typicalHigh)}
                  이에요.
                </p>
              )}
              {bands.length > 0 && (
                <ul>
                  {bands.map((band) => (
                    <li key={band.band}>
                      {PRICE_BAND_LABEL[band.band!]} 판매율{' '}
                      {percent(band.sellThroughRate)}
                    </li>
                  ))}
                </ul>
              )}
              <small>
                가격은 추천하지 않아요. 다른 판매자 실적은 구간으로만 보여 줘요.
              </small>
            </div>
          ) : null}
        </>
      )}
    </section>
  )
}
