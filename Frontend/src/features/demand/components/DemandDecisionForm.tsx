import { useRef, useState, type FormEvent } from 'react'
import type {
  DemandDecisionRequest,
  DemandPlan,
} from '../../../api/demand/demand'
import { demandErrorMessage, toDecisionRequest } from '../logic/demandView'

type Intent = 'MODIFIED' | 'REJECTED' | null

// 결정은 계획당 한 번이며 판매 수량을 바꾸지 않는다(DEMAND.md 2.6)
export default function DemandDecisionForm({
  plan,
  onDecide,
}: {
  plan: DemandPlan
  onDecide: (request: DemandDecisionRequest) => Promise<unknown>
}) {
  const options = plan.analysis?.plan?.options ?? []
  const [intent, setIntent] = useState<Intent>(null)
  const [reason, setReason] = useState('')
  const [quantities, setQuantities] = useState<Record<number, string>>(() =>
    Object.fromEntries(
      options.map((option) => [
        option.optionId!,
        String(option.recommendedQuantity ?? option.currentQuantity ?? 0),
      ]),
    ),
  )
  const [error, setError] = useState('')
  const [pending, setPending] = useState(false)
  const locked = useRef(false)
  const decide = async (status: 'ACCEPTED' | 'MODIFIED' | 'REJECTED') => {
    if (locked.current) return
    setError('')
    let request: DemandDecisionRequest
    try {
      request = toDecisionRequest(status, reason, options, quantities)
    } catch (cause) {
      setError(demandErrorMessage(cause))
      return
    }
    locked.current = true
    setPending(true)
    try {
      await onDecide(request)
    } catch (cause) {
      setError(demandErrorMessage(cause))
    } finally {
      locked.current = false
      setPending(false)
    }
  }
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (intent) void decide(intent)
  }
  // 수량 계산을 못 한 계획은 수락할 숫자가 없어 수정(현재 수량에서 시작)·거절만 둔다
  const acceptable = plan.analysis?.plan?.recommended != null
  return (
    <div className="demand-decision">
      <div className="demand-decision-actions">
        {acceptable && (
          <button
            type="button"
            className="primary-button"
            disabled={pending}
            onClick={() => void decide('ACCEPTED')}
          >
            수락
          </button>
        )}
        {options.length > 0 && (
          <button
            type="button"
            className="secondary-button"
            aria-pressed={intent === 'MODIFIED'}
            disabled={pending}
            onClick={() => setIntent('MODIFIED')}
          >
            수정 후 확정
          </button>
        )}
        <button
          type="button"
          className="secondary-button"
          aria-pressed={intent === 'REJECTED'}
          disabled={pending}
          onClick={() => setIntent('REJECTED')}
        >
          거절
        </button>
      </div>
      {intent && (
        <form className="demand-decision-form" onSubmit={submit} noValidate>
          {intent === 'MODIFIED' && (
            <fieldset disabled={pending}>
              <legend>옵션별 확정 수량</legend>
              {options.map((option) => (
                <label key={option.optionId}>
                  {option.name || '기본 옵션'}
                  <input
                    type="text"
                    inputMode="numeric"
                    value={quantities[option.optionId!] ?? ''}
                    onChange={(event) =>
                      setQuantities((current) => ({
                        ...current,
                        [option.optionId!]: event.target.value,
                      }))
                    }
                  />
                </label>
              ))}
            </fieldset>
          )}
          <label>
            사유 (선택)
            <textarea
              maxLength={500}
              value={reason}
              disabled={pending}
              onChange={(event) => setReason(event.target.value)}
            />
          </label>
          <div className="demand-decision-actions">
            <button type="submit" className="primary-button" disabled={pending}>
              {intent === 'MODIFIED' ? '수정 수량 확정' : '거절 확정'}
            </button>
            <button
              type="button"
              className="text-link"
              disabled={pending}
              onClick={() => {
                setIntent(null)
                setError('')
              }}
            >
              닫기
            </button>
          </div>
        </form>
      )}
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      <small className="demand-hint">
        결정은 계획마다 한 번만 기록되고, DROP의 판매 수량은 바뀌지 않아요.
      </small>
    </div>
  )
}
