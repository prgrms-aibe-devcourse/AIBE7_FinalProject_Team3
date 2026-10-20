import { useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import type { DropStatus } from '../../../types/drop'
import { useSellerDropAction } from '../logic/useSellerDrops'
import { sellerDropError, type DropFormError } from '../logic/sellerDropErrors'
import DropFieldErrors from './DropFieldErrors'

export default function SellerDropActions({
  dropId,
  status,
  saleStartsAt,
  notify,
  detailSearch = '',
}: {
  dropId: number
  status?: DropStatus
  saleStartsAt?: string
  notify: (message: string) => void
  detailSearch?: string
}) {
  const action = useSellerDropAction()
  const locked = useRef(false)
  const [intent, setIntent] = useState<'PUBLISH' | 'CANCEL' | null>(null)
  const [confirmed, setConfirmed] = useState(false)
  const [reason, setReason] = useState('')
  const [error, setError] = useState<DropFormError | null>(null)
  const [now] = useState(() => Date.now())
  const cancelable =
    status === 'WISH' && !!saleStartsAt && Date.parse(saleStartsAt) > now
  const run = async (kind: 'PUBLISH' | 'CANCEL') => {
    if (locked.current) return
    locked.current = true
    setError(null)
    try {
      await action.mutateAsync({
        dropId,
        reason: kind === 'CANCEL' ? reason : undefined,
      })
      notify(
        kind === 'PUBLISH'
          ? 'DROP을 WISH 상태로 공개했어요.'
          : 'DROP 출시를 취소했어요.',
      )
      setIntent(null)
      setConfirmed(false)
      setReason('')
    } catch (cause) {
      setError(sellerDropError(cause))
    } finally {
      locked.current = false
    }
  }
  const cancel = (event: FormEvent) => {
    event.preventDefault()
    if (!reason.trim() || reason.length > 500) {
      setError({ message: '취소 사유를 1~500자로 입력해주세요.', fields: [] })
      return
    }
    if (!confirmed) setConfirmed(true)
    else void run('CANCEL')
  }
  return (
    <div className="seller-drops-actions">
      {status === 'DRAFT' && (
        <>
          <Link
            className="secondary-button"
            to={`/seller/drops/${dropId}/edit`}
          >
            수정
          </Link>
          <button
            type="button"
            className="secondary-button"
            disabled={action.isPending}
            onClick={() => {
              if (intent === 'PUBLISH') void run('PUBLISH')
              else {
                setIntent('PUBLISH')
                setError(null)
              }
            }}
          >
            {intent === 'PUBLISH' ? '공개 확정' : '공개 확인'}
          </button>
          {intent === 'PUBLISH' && (
            <small>한 번 더 누르면 이 DROP을 공개합니다.</small>
          )}
        </>
      )}
      {cancelable && (
        <button
          type="button"
          className="secondary-button"
          disabled={action.isPending}
          onClick={() => {
            setIntent('CANCEL')
            setConfirmed(false)
            setError(null)
          }}
        >
          출시 취소
        </button>
      )}
      {status === 'WISH' && !saleStartsAt && (
        <Link
          className="secondary-button"
          to={`/seller/drops/${dropId}${detailSearch}`}
        >
          상태 관리
        </Link>
      )}
      {intent === 'CANCEL' && cancelable && (
        <form onSubmit={cancel}>
          <label>
            취소 사유
            <input
              required
              maxLength={500}
              disabled={action.isPending}
              value={reason}
              onChange={(event) => {
                setReason(event.target.value)
                setConfirmed(false)
              }}
            />
          </label>
          <button
            type="submit"
            className="secondary-button"
            disabled={action.isPending}
          >
            {confirmed ? '취소 확정' : '취소 사유 확인'}
          </button>
          <DropFieldErrors field="reason" errors={error?.fields ?? []} />
        </form>
      )}
      {error && (
        <p className="form-error" role="alert">
          {[
            error.message,
            ...error.fields
              .filter((field) => field.field !== 'reason')
              .map((field) => field.reason),
          ].join(' ')}
        </p>
      )}
    </div>
  )
}
