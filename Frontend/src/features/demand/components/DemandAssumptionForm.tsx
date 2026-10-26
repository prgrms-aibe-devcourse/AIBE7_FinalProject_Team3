import { useState, type FormEvent } from 'react'
import type {
  DemandAnalysis,
  DemandCalculateRequest,
} from '../../../api/demand/demand'
import {
  count,
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

export default function DemandAssumptionForm({
  base,
  calculated,
  calculating,
  saving,
  onCalculate,
  onSave,
}: {
  base: DemandAnalysis
  calculated: DemandAnalysis | null
  calculating: boolean
  saving: boolean
  onCalculate: (request: DemandCalculateRequest) => Promise<unknown>
  onSave: (request: DemandCalculateRequest) => Promise<unknown>
}) {
  const [form, setForm] = useState<AssumptionForm>(() =>
    initialAssumptionForm(base),
  )
  const [error, setError] = useState('')
  const change = calculated ? planChange(base, calculated) : null
  const busy = calculating || saving
  const update = (patch: Partial<AssumptionForm>) =>
    setForm((current) => ({ ...current, ...patch }))
  const run = async (action: typeof onCalculate) => {
    setError('')
    try {
      await action(toCalculateRequest(form))
    } catch (cause) {
      setError(demandErrorMessage(cause))
    }
  }
  const submit = (event: FormEvent) => {
    event.preventDefault()
    void run(onCalculate)
  }
  return (
    <form className="demand-assumption-form" onSubmit={submit} noValidate>
      <fieldset disabled={busy}>
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
            disabled={busy || form.basis !== 'INPUT'}
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
            disabled={busy}
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
        <button type="submit" className="secondary-button" disabled={busy}>
          {calculating ? '계산 중' : '다시 계산'}
        </button>
        <button
          type="button"
          className="primary-button"
          disabled={busy}
          onClick={() => void run(onSave)}
        >
          {saving ? '저장 중' : '이 가정으로 계획 저장'}
        </button>
      </div>
      {change && (
        <p className="demand-change" role="status">
          기본 제안 대비 · 준비 권장 {count(base.plan?.recommended)} →{' '}
          {count(calculated?.plan?.recommended)}
          {change.recommended != null && ` (${signed(change.recommended)})`} ·
          예상 판매량 {count(base.plan?.expected)} →{' '}
          {count(calculated?.plan?.expected)}
          {change.expected != null && ` (${signed(change.expected)})`}
        </p>
      )}
      <small className="demand-hint">
        다시 계산은 저장하지 않아요. 저장하면 서버가 저장 시점 데이터로 다시
        계산해 기록해요.
      </small>
    </form>
  )
}
