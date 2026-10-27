import { useState, type FormEvent } from 'react'
import type {
  DemandAnalysis,
  DemandCalculateRequest,
} from '../../../api/demand/demand'
import { count } from '../logic/demandFormat'
import {
  demandErrorMessage,
  initialAssumptionForm,
  planChange,
  signed,
  toCalculateRequest,
  type AssumptionForm,
  type ConversionBasisChoice,
} from '../logic/demandView'

const BASIS_OPTIONS: [ConversionBasisChoice, string][] = [
  ['AUTO', '자동 (신뢰도 높은 기준)'],
  ['OWN', '내 실적'],
  ['MARKET', '시장 평균'],
  ['INPUT', '직접 입력'],
]

// 가정을 바꿔 다시 계산한다. 결과는 저장하지 않고 카드에 잠시 보여 줄 뿐이다(DEMAND.md 2.2)
export default function DemandAssumptionForm({
  base,
  calculated,
  calculating,
  onCalculate,
  onReset,
}: {
  base: DemandAnalysis
  calculated: DemandAnalysis | null
  calculating: boolean
  onCalculate: (request: DemandCalculateRequest) => Promise<unknown>
  onReset: () => void
}) {
  const [form, setForm] = useState<AssumptionForm>(() =>
    initialAssumptionForm(base),
  )
  const [error, setError] = useState('')
  const change = calculated ? planChange(base, calculated) : null
  const update = (patch: Partial<AssumptionForm>) =>
    setForm((current) => ({ ...current, ...patch }))
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setError('')
    try {
      await onCalculate(toCalculateRequest(form))
    } catch (cause) {
      setError(demandErrorMessage(cause))
    }
  }
  const reset = () => {
    setForm(initialAssumptionForm(base))
    setError('')
    onReset()
  }
  return (
    <form
      className="demand-assumption-form"
      onSubmit={(event) => void submit(event)}
      noValidate
    >
      <fieldset disabled={calculating}>
        <legend>전환율 기준</legend>
        {BASIS_OPTIONS.map(([value, label]) => (
          <label key={value}>
            <input
              type="radio"
              name="conversionBasis"
              value={value}
              checked={form.basis === value}
              onChange={() => update({ basis: value })}
            />
            {label}
          </label>
        ))}
      </fieldset>
      <div className="demand-assumption-fields">
        <label>
          전환율 (%)
          <input
            type="text"
            inputMode="numeric"
            value={form.ratePercent}
            disabled={calculating || form.basis !== 'INPUT'}
            placeholder="1~100"
            onChange={(event) => update({ ratePercent: event.target.value })}
          />
        </label>
        <label>
          안전 재고율 (%)
          <input
            type="text"
            inputMode="numeric"
            value={form.safetyPercent}
            disabled={calculating}
            placeholder="0~100"
            onChange={(event) => update({ safetyPercent: event.target.value })}
          />
        </label>
      </div>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      <div className="demand-assumption-actions">
        <button
          type="submit"
          className="secondary-button"
          disabled={calculating}
        >
          {calculating ? '계산 중' : '다시 계산'}
        </button>
        {calculated && (
          <button
            type="button"
            className="secondary-button"
            disabled={calculating}
            onClick={reset}
          >
            기본 가정으로
          </button>
        )}
      </div>
      {change && (
        <p className="demand-change" role="status">
          기본 가정 대비 · 준비 권장 {count(base.plan?.recommended)} →{' '}
          {count(calculated?.plan?.recommended)}
          {change.recommended != null && ` (${signed(change.recommended)})`} ·
          예상 판매량 {count(base.plan?.expected)} →{' '}
          {count(calculated?.plan?.expected)}
          {change.expected != null && ` (${signed(change.expected)})`}
        </p>
      )}
      <small className="demand-hint">
        다시 계산한 결과는 저장하지 않아요. 화면을 나가면 기본 가정으로
        돌아가요.
      </small>
    </form>
  )
}
