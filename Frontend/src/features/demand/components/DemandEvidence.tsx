import type { DemandAnalysis } from '../../../api/demand/demand'
import { dateLabel } from '../../../utils/date'
import { CONFIDENCE_LABEL, count, percent } from '../logic/demandFormat'
import { marketSampleLabel, rateRange, SOURCE_LABEL } from '../logic/demandView'

type Conversion = {
  rate?: number
  rateLow?: number
  rateHigh?: number
  avgQuantityPerBuyer?: number
  confidence?: 'HIGH' | 'LOW' | 'NONE'
}

function ConversionCard({
  title,
  conversion,
  sample,
  used,
}: {
  title: string
  conversion?: Conversion
  sample: string | null
  used: boolean
}) {
  const available = conversion?.confidence && conversion.confidence !== 'NONE'
  return (
    <article className={used ? 'demand-basis is-used' : 'demand-basis'}>
      <h3>
        {title}
        {used && <span>계산에 사용</span>}
      </h3>
      {available ? (
        <>
          <strong>
            전환율 {percent(conversion.rate)}
            <small>
              {' '}
              ({rateRange(conversion.rateLow, conversion.rateHigh)})
            </small>
          </strong>
          <p>
            구매자당 {conversion.avgQuantityPerBuyer?.toFixed(1)}개 ·{' '}
            {CONFIDENCE_LABEL[conversion.confidence!]}
          </p>
        </>
      ) : (
        <p>{CONFIDENCE_LABEL.NONE} · 계산에 쓰지 않아요</p>
      )}
      {sample && <small>{sample}</small>}
    </article>
  )
}

// 수량을 계산한 근거: WISH 추이, 두 전환율 기준, 사용한 가정(DEMAND.md 1.1~1.5)
export default function DemandEvidence({
  analysis,
}: {
  analysis: DemandAnalysis
}) {
  const { demand, conversion, assumptions } = analysis
  const recentWishes = (demand?.dailyWishes ?? []).reduce(
    (sum, day) => sum + (day.count ?? 0),
    0,
  )
  const source = assumptions?.conversionRate?.source
  const own = conversion?.own
  const market = conversion?.market
  const marketSample = marketSampleLabel(
    market?.dropCountBand,
    market?.wishCount,
  )
  return (
    <div className="demand-evidence">
      <p className="demand-recent">
        최근 14일 WISH 등록 {count(recentWishes, '명')}
        {demand?.trendApproximate && <small> (근사치)</small>}
      </p>
      <div className="demand-bases">
        <ConversionCard
          title="내 실적"
          conversion={own}
          used={source === 'OWN'}
          sample={
            own?.dropCount
              ? `내 종료 DROP ${own.dropCount}개 · WISH ${count(own.wishCount, '명')}`
              : '같은 카테고리의 내 종료 DROP이 없어요'
          }
        />
        <ConversionCard
          title="시장 평균"
          conversion={market}
          used={source === 'MARKET'}
          sample={
            marketSample
              ? `${marketSample}${market?.asOf ? ` · ${dateLabel(market.asOf)} 기준` : ''}`
              : '판매자 5명·DROP 10개 미만이라 제공되지 않아요'
          }
        />
      </div>
      <dl className="demand-assumptions">
        {(
          [
            ['전환율', assumptions?.conversionRate, percent],
            [
              '구매자당 수량',
              assumptions?.avgQuantityPerBuyer,
              (value?: number) =>
                value == null ? '—' : `${value.toFixed(1)}개`,
            ],
            ['안전 재고율', assumptions?.safetyStockRate, percent],
          ] as const
        ).map(([label, assumption, format]) => (
          <div key={label}>
            <dt>{label}</dt>
            <dd>
              {assumption ? format(assumption.value) : '정하지 못함'}
              {assumption?.source && (
                <small> · {SOURCE_LABEL[assumption.source]}</small>
              )}
            </dd>
          </div>
        ))}
      </dl>
    </div>
  )
}
