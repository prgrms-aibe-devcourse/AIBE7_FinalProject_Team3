import { useState } from 'react'
import { Link, Navigate, useLocation, useParams } from 'react-router-dom'
import type {
  DemandAnalysis,
  DemandCalculateRequest,
  DemandDecisionRequest,
} from '../../api/demand/demand'
import EmptyState from '../../components/EmptyState/EmptyState'
import AuthLoading from '../../features/auth/components/AuthLoading'
import {
  apiErrorCode,
  isAdmin,
  isSeller,
  type AuthStatus,
  type AuthUser,
} from '../../features/auth/logic/authUser'
import DemandAssumptionForm from '../../features/demand/components/DemandAssumptionForm'
import DemandEvidence from '../../features/demand/components/DemandEvidence'
import DemandPlanHistory from '../../features/demand/components/DemandPlanHistory'
import DemandPlanOverview from '../../features/demand/components/DemandPlanOverview'
import {
  demandErrorMessage,
  visiblePlans,
} from '../../features/demand/logic/demandView'
import {
  useCalculateDemand,
  useDecideDemandPlan,
  useDemandAnalysis,
  useDemandPlans,
  useSaveDemandPlan,
} from '../../features/demand/logic/useDemand'
import { sellerDropError } from '../../features/seller/logic/sellerDropErrors'
import { useSellerDropDetail } from '../../features/seller/logic/useSellerDrops'
import { dateLabel } from '../../utils/date'

const DROPS_PATH = '/seller/drops'

export default function SellerDemandPage({
  user,
  authStatus,
  notify,
}: {
  user: AuthUser | null
  authStatus: AuthStatus
  notify: (message: string) => void
}) {
  const { dropId } = useParams()
  const location = useLocation()
  const id = Number(dropId)
  const validId =
    /^\d+$/.test(dropId ?? '') && Number.isSafeInteger(id) && id > 0
  const enabled = validId && authStatus === 'authenticated' && isSeller(user)
  const drop = useSellerDropDetail(id, enabled)
  const ready = enabled && !!drop.data
  const analysis = useDemandAnalysis(id, ready)
  const [page, setPage] = useState(0)
  const plans = useDemandPlans(id, page, ready)
  const calculate = useCalculateDemand(id)
  const save = useSaveDemandPlan(id)
  const decide = useDecideDemandPlan(id)
  const [calculated, setCalculated] = useState<DemandAnalysis | null>(null)
  const [openPlanId, setOpenPlanId] = useState<string | null>(null)

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
        link={DROPS_PATH}
        label="DROP 목록으로 돌아가기"
        variant="page"
      />
    )
  if (drop.isPending)
    return (
      <div role="status">
        <EmptyState
          loading
          title="수요 분석을 불러오는 중이에요."
          variant="page"
        />
      </div>
    )
  const dropErrorCode = apiErrorCode(drop.error)
  if (
    dropErrorCode === 'DROP_ACCESS_DENIED' ||
    dropErrorCode === 'DROP_NOT_FOUND'
  )
    return (
      <EmptyState
        title={
          dropErrorCode === 'DROP_ACCESS_DENIED'
            ? '이 DROP을 관리할 권한이 없어요.'
            : 'DROP을 찾을 수 없어요.'
        }
        link={DROPS_PATH}
        label="DROP 목록으로 돌아가기"
        variant="page"
      />
    )
  if (!drop.data)
    return (
      <div className="seller-drops-result" role="alert">
        <EmptyState
          title="DROP 정보를 불러오지 못했어요."
          description={sellerDropError(drop.error).message}
          variant="block"
        />
        <button
          type="button"
          className="secondary-button"
          onClick={() => void drop.refetch()}
        >
          다시 시도
        </button>
      </div>
    )

  const unavailable =
    apiErrorCode(analysis.error) === 'DEMAND_ANALYSIS_UNAVAILABLE'
  const current = calculated ?? analysis.data
  const onCalculate = async (request: DemandCalculateRequest) =>
    setCalculated(await calculate.mutateAsync(request))
  const onSave = async (request: DemandCalculateRequest) => {
    const plan = await save.mutateAsync(request)
    setPage(0)
    setOpenPlanId(plan.planId!)
    notify('판매 계획을 저장했어요.')
  }
  const onDecide = async (planId: string, request: DemandDecisionRequest) => {
    await decide.mutateAsync({ planId, request })
    notify(
      request.status === 'REJECTED'
        ? '계획을 거절했어요. 거절한 계획은 목록에 표시하지 않아요.'
        : '계획 결정을 기록했어요.',
    )
  }

  return (
    <section className="page-section seller-page seller-demand-page">
      <Link className="text-link seller-drops-back" to={`/seller/drops/${id}`}>
        ← DROP 상세
      </Link>
      <div className="seller-drops-heading">
        <div>
          <h1>수요 분석 제안서</h1>
          <p>
            {drop.data.name || '이름 없는 DROP'}
            {current?.calculatedAt &&
              ` · ${dateLabel(current.calculatedAt)} 계산`}
          </p>
        </div>
        <span className={`status-pill ${drop.data.status?.toLowerCase()}`}>
          {drop.data.status}
        </span>
      </div>

      {analysis.isPending ? (
        <div role="status">
          <EmptyState
            loading
            title="수요 데이터를 계산하는 중이에요."
            variant="block"
          />
        </div>
      ) : unavailable ? (
        <EmptyState
          title="판매 시작 전 WISH DROP만 분석할 수 있어요."
          description="저장한 계획 이력은 아래에서 계속 볼 수 있어요."
          variant="block"
        />
      ) : analysis.isError || !current ? (
        <div className="seller-drops-result" role="alert">
          <EmptyState
            title="수요 분석을 불러오지 못했어요."
            description={demandErrorMessage(analysis.error)}
            variant="block"
          />
          <button
            type="button"
            className="secondary-button"
            onClick={() => void analysis.refetch()}
          >
            다시 시도
          </button>
        </div>
      ) : (
        <>
          <section className="panel demand-section">
            <div className="panel-heading">
              <div>
                <h2>판매 계획</h2>
                <p>
                  {calculated
                    ? '바꾼 가정으로 다시 계산한 결과예요. 아직 저장하지 않았어요.'
                    : 'WISH 반응으로 계산한 참고용 제안이에요. 재고는 바뀌지 않아요.'}
                </p>
              </div>
            </div>
            <DemandPlanOverview analysis={current} />
          </section>
          <div className="demand-columns">
            <section className="panel demand-section">
              <div className="panel-heading">
                <h2>근거 데이터</h2>
              </div>
              <DemandEvidence analysis={current} />
            </section>
            <section className="panel demand-section demand-assumption-panel">
              <div className="panel-heading">
                <h2>가정 수정</h2>
              </div>
              <DemandAssumptionForm
                base={analysis.data!}
                calculated={calculated}
                calculating={calculate.isPending}
                saving={save.isPending}
                onCalculate={onCalculate}
                onSave={onSave}
              />
            </section>
          </div>
          {!!current.notes?.length && (
            <ul className="demand-notes">
              {current.notes.map((note) => (
                <li key={note}>{note}</li>
              ))}
            </ul>
          )}
        </>
      )}

      <section className="panel demand-section">
        <div className="panel-heading">
          <div>
            <h2>저장한 계획</h2>
            <p>
              최근 저장순 · 결정은 계획마다 한 번만 기록돼요. 거절한 계획은
              표시하지 않아요.
            </p>
          </div>
        </div>
        {plans.isPending ? (
          <div role="status">
            <EmptyState loading title="저장한 계획을 불러오는 중이에요." />
          </div>
        ) : plans.isError ? (
          <div className="seller-drops-result" role="alert">
            <EmptyState
              title="저장한 계획을 불러오지 못했어요."
              description={demandErrorMessage(plans.error)}
            />
            <button
              type="button"
              className="secondary-button"
              onClick={() => void plans.refetch()}
            >
              다시 시도
            </button>
          </div>
        ) : (
          <>
            <DemandPlanHistory
              plans={visiblePlans(plans.data.content)}
              openPlanId={openPlanId}
              decidable={!unavailable && !analysis.isError}
              onToggle={(planId) =>
                setOpenPlanId((current) => (current === planId ? null : planId))
              }
              onDecide={onDecide}
            />
            {(plans.data.totalPages ?? 0) > 1 && (
              <div className="seller-drops-pagination">
                <span>
                  {page + 1} / {plans.data.totalPages} 페이지
                </span>
                <div>
                  <button
                    type="button"
                    className="secondary-button"
                    disabled={page === 0}
                    onClick={() => setPage((value) => value - 1)}
                  >
                    이전
                  </button>
                  <button
                    type="button"
                    className="secondary-button"
                    disabled={!plans.data.hasNext}
                    onClick={() => setPage((value) => value + 1)}
                  >
                    다음
                  </button>
                </div>
              </div>
            )}
          </>
        )}
      </section>
    </section>
  )
}
