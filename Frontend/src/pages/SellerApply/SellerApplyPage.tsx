import { useState, type FormEvent } from 'react'
import { Link, Navigate, useLocation } from 'react-router-dom'
import symbol from '../../assets/images/grab-symbol.png'
import EmptyState from '../../components/EmptyState/EmptyState'
import type { MockAccount } from '../../features/auth/mock/mockAuth'
import type {
  SellerApplication,
  SellerApplicationInput,
  SellerApplicationStatus,
} from '../../features/seller/mock/mockSellerApplications'
import { dateLabel } from '../../utils/date'

type Props = {
  account: MockAccount | null
  application: SellerApplication | undefined
  onApply: (input: SellerApplicationInput) => void
  notify: (message: string) => void
}

type Example =
  'CURRENT' | 'NONE' | SellerApplicationStatus | 'ERROR' | 'FORBIDDEN'

export default function SellerApplyPage({
  account,
  application,
  onApply,
  notify,
}: Props) {
  const location = useLocation()
  const [example, setExample] = useState<Example>('CURRENT')
  const [result, setResult] = useState<'SUCCESS' | 'DUPLICATE' | 'ERROR'>(
    'SUCCESS',
  )
  const [error, setError] = useState('')

  if (!account)
    return <Navigate to="/login" state={{ from: location.pathname }} replace />
  if (account.role === 'ADMIN')
    return (
      <EmptyState
        title="접근 권한이 없어요."
        link="/my"
        label="내 계정으로 돌아가기"
        variant="page"
      />
    )

  const current =
    example === 'CURRENT'
      ? application
      : example === 'NONE' || example === 'ERROR' || example === 'FORBIDDEN'
        ? undefined
        : {
            brandName: '길동상점',
            contactEmail: 'seller@example.com',
            appliedAt: '2026-09-18T14:00:00+09:00',
            status: example,
            rejectedReason:
              example === 'REJECTED' ? '사업자 정보 확인이 필요합니다.' : null,
          }
  const approved = account.role === 'SELLER' || current?.status === 'APPROVED'

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (application || account.role === 'SELLER') {
      setError('이미 신청했거나 승인된 계정입니다.')
      return
    }
    if (result !== 'SUCCESS') {
      setError(
        result === 'DUPLICATE'
          ? '이미 접수된 신청이 있습니다.'
          : '신청을 접수하지 못했어요. 다시 시도해주세요.',
      )
      return
    }
    const form = new FormData(event.currentTarget)
    onApply({
      brandName: String(form.get('brandName') ?? ''),
      contactEmail: String(form.get('contactEmail') ?? ''),
      description: String(form.get('description') ?? ''),
    })
    setError('')
    setExample('CURRENT')
    notify('Mock 판매자 신청이 접수됐어요. 실제 신청은 진행되지 않았습니다.')
  }

  return (
    <section className="page-section seller-application-page">
      <div className="seller-apply-layout">
        <div className="seller-apply-intro">
          <img className="seller-apply-symbol" src={symbol} alt="" />
          <p className="eyebrow">BECOME A SELLER</p>
          <h1>나만의 DROP을 열어보세요</h1>
          <p>신청 후 운영자 승인을 받으면 판매자 기능을 사용할 수 있습니다.</p>
        </div>
        <section className="panel seller-apply-panel">
          <h2>{current || approved ? '판매자 신청 상태' : '판매자 신청'}</h2>
          {example === 'ERROR' ? (
            <p role="alert">
              신청 정보를 불러오지 못했어요. 다시 시도해주세요.
            </p>
          ) : example === 'FORBIDDEN' ? (
            <p role="alert">신청 정보를 볼 권한이 없습니다.</p>
          ) : current || approved ? (
            <div className="seller-application-status">
              <span className="status-pill">
                {approved ? 'APPROVED' : current?.status}
              </span>
              <strong>{current?.brandName ?? '승인된 판매자'}</strong>
              {current && (
                <>
                  <p>{current.contactEmail}</p>
                  <small>신청일 {dateLabel(current.appliedAt)}</small>
                </>
              )}
              {approved ? (
                account.role === 'SELLER' ? (
                  <Link className="primary-button" to="/seller">
                    판매자 작업 공간 ↗
                  </Link>
                ) : (
                  <p className="seller-application-note">
                    승인 상태 예시입니다. 판매자 권한은 계정 역할을 다시
                    확인해야 합니다.
                  </p>
                )
              ) : current?.status === 'PENDING' ? (
                <p className="seller-application-note">
                  심사를 기다리고 있어요.
                </p>
              ) : (
                <p className="seller-application-note">
                  반려 사유: {current?.rejectedReason}
                  <br />
                  재신청 절차는 API 계약이 추가되면 안내합니다.
                </p>
              )}
            </div>
          ) : (
            <form className="seller-apply-form" onSubmit={submit}>
              <label>
                브랜드명
                <input required name="brandName" maxLength={100} />
              </label>
              <label>
                연락 이메일
                <input required name="contactEmail" type="email" />
              </label>
              <label>
                브랜드 소개
                <textarea required name="description" rows={5} />
              </label>
              <label className="demo-select">
                신청 결과 예시
                <select
                  value={result}
                  onChange={(event) => {
                    setResult(event.target.value as typeof result)
                    setError('')
                  }}
                >
                  <option value="SUCCESS">접수 성공</option>
                  <option value="DUPLICATE">중복 신청</option>
                  <option value="ERROR">제출 실패</option>
                </select>
              </label>
              {error && (
                <p className="form-error" role="alert">
                  {error}
                </p>
              )}
              <button type="submit" className="primary-button">
                Mock 신청 결과 보기
              </button>
            </form>
          )}
        </section>
      </div>
      <label className="seller-application-demo">
        신청 상태 예시
        <select
          value={example}
          onChange={(event) => {
            setExample(event.target.value as Example)
            setError('')
          }}
        >
          <option value="CURRENT">현재 Mock 상태</option>
          <option value="NONE">미신청</option>
          <option value="PENDING">심사 대기</option>
          <option value="APPROVED">승인</option>
          <option value="REJECTED">반려</option>
          <option value="ERROR">조회 실패</option>
          <option value="FORBIDDEN">권한 부족</option>
        </select>
      </label>
      <small>상태 전환과 신청 결과는 이 화면의 Mock 예시입니다.</small>
    </section>
  )
}
