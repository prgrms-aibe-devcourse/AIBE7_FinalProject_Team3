import { useState, type FormEvent } from 'react'
import { Link, Navigate, useLocation } from 'react-router-dom'
import symbol from '../../assets/images/grab-symbol.png'
import EmptyState from '../../components/EmptyState/EmptyState'
import AuthLoading from '../../features/auth/components/AuthLoading'
import {
  isAdmin,
  isSeller,
  type AuthStatus,
  type AuthUser,
} from '../../features/auth/logic/authUser'
import type { SellerDocumentType } from '../../api/seller/applications'
import {
  useMySellerApplication,
  useSubmitSellerApplication,
  useUploadSellerDocument,
} from '../../features/seller/logic/useSellerApplication'
import { applicationErrorMessage } from '../../features/seller/logic/sellerApplicationState'
import { dateLabel } from '../../utils/date'

type Props = {
  user: AuthUser | null
  authStatus: AuthStatus
  notify: (message: string) => void
}

const DOCUMENT_FIELDS: {
  type: SellerDocumentType
  label: string
  hint?: string
}[] = [
  { type: 'BUSINESS_LICENSE', label: '사업자등록증 사본' },
  {
    type: 'ID_CARD',
    label: '신분증 사본',
    hint: '주민등록번호 뒷자리를 가리고 촬영해주세요.',
  },
  { type: 'BANKBOOK', label: '통장 사본' },
  { type: 'MAIL_ORDER_BUSINESS_REPORT', label: '통신판매업신고증' },
]

function DocumentUploadField({
  type,
  label,
  hint,
  documentId,
  onUploaded,
  onCleared,
}: {
  type: SellerDocumentType
  label: string
  hint?: string
  documentId: string | undefined
  onUploaded: (type: SellerDocumentType, documentId: string) => void
  onCleared: (type: SellerDocumentType) => void
}) {
  const upload = useUploadSellerDocument()

  return (
    <label className="seller-document-field">
      {label}
      {hint && <small>{hint}</small>}
      <input
        type="file"
        accept="image/*"
        required={!documentId}
        onChange={(event) => {
          const file = event.target.files?.[0]
          if (!file) return
          // 재업로드가 실패해도 이전 documentId가 제출에 쓰이지 않도록 먼저 지운다.
          onCleared(type)
          upload.mutate(
            { documentType: type, file },
            { onSuccess: (data) => onUploaded(type, data.documentId) },
          )
        }}
      />
      {upload.isPending && (
        <small className="seller-document-status">업로드 중...</small>
      )}
      {documentId && !upload.isPending && (
        <small className="seller-document-status seller-document-status--done">
          ✓ 업로드 완료
        </small>
      )}
      {upload.isError && (
        <small role="alert">{applicationErrorMessage(upload.error)}</small>
      )}
    </label>
  )
}

export default function SellerApplyPage({ user, authStatus, notify }: Props) {
  const location = useLocation()
  const {
    data: application,
    isLoading,
    isError,
  } = useMySellerApplication(authStatus === 'authenticated' && !isAdmin(user))
  const submit = useSubmitSellerApplication()
  const [documentIds, setDocumentIds] = useState<
    Partial<Record<SellerDocumentType, string>>
  >({})
  const [formError, setFormError] = useState('')

  if (authStatus === 'loading') return <AuthLoading />
  if (!user)
    return <Navigate to="/login" state={{ from: location.pathname }} replace />
  if (isAdmin(user))
    return (
      <EmptyState
        title="접근 권한이 없어요."
        link="/my"
        label="내 계정으로 돌아가기"
        variant="page"
      />
    )

  const approved = isSeller(user) || application?.status === 'APPROVED'
  const allDocumentsUploaded = DOCUMENT_FIELDS.every(
    (field) => documentIds[field.type],
  )

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!allDocumentsUploaded) {
      setFormError('서류 4종을 모두 업로드해주세요.')
      return
    }
    const form = new FormData(event.currentTarget)
    setFormError('')
    submit.mutate(
      {
        brandName: String(form.get('brandName') ?? ''),
        contactEmail: String(form.get('contactEmail') ?? ''),
        businessRegistrationNumber: String(
          form.get('businessRegistrationNumber') ?? '',
        ),
        description: String(form.get('description') ?? ''),
        documentIds: documentIds as Record<SellerDocumentType, string>,
      },
      {
        onSuccess: () => {
          notify('판매자 신청이 접수됐어요.')
          // 이후 반려되어 폼이 다시 보일 때 이전 서류 ID가 남지 않게 한다.
          setDocumentIds({})
        },
        onError: (error) => setFormError(applicationErrorMessage(error)),
      },
    )
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
          <h2>
            {isLoading
              ? '판매자 신청'
              : application || approved
                ? '판매자 신청 상태'
                : '판매자 신청'}
          </h2>
          {isLoading ? (
            <p>불러오는 중...</p>
          ) : isError ? (
            <p role="alert">
              신청 정보를 불러오지 못했어요. 다시 시도해주세요.
            </p>
          ) : application || approved ? (
            <div className="seller-application-status">
              <span className="status-pill">
                {approved ? 'APPROVED' : application?.status}
              </span>
              <strong>{application?.brandName ?? '승인된 판매자'}</strong>
              {application && (
                <>
                  <p>{application.contactEmail}</p>
                  <small>신청일 {dateLabel(application.appliedAt ?? '')}</small>
                </>
              )}
              {approved ? (
                isSeller(user) ? (
                  <Link className="primary-button" to="/seller">
                    판매자 작업 공간 ↗
                  </Link>
                ) : (
                  <p className="seller-application-note">
                    승인 상태 예시입니다. 판매자 권한은 계정 역할을 다시
                    확인해야 합니다.
                  </p>
                )
              ) : application?.status === 'PENDING' ? (
                <p className="seller-application-note">
                  심사를 기다리고 있어요.
                </p>
              ) : (
                <>
                  <p className="seller-application-note">
                    신청이 반려됐어요. 서류와 정보를 확인한 뒤 다시
                    신청해주세요.
                  </p>
                  <form className="seller-apply-form" onSubmit={handleSubmit}>
                    {renderFields()}
                  </form>
                </>
              )}
            </div>
          ) : (
            <form className="seller-apply-form" onSubmit={handleSubmit}>
              {renderFields()}
            </form>
          )}
        </section>
      </div>
    </section>
  )

  function renderFields() {
    return (
      <>
        <label>
          브랜드명
          <input required name="brandName" maxLength={100} />
        </label>
        <label>
          연락 이메일
          <input required name="contactEmail" type="email" />
        </label>
        <label>
          사업자등록번호
          <input
            required
            name="businessRegistrationNumber"
            pattern="\d{10}"
            maxLength={10}
            placeholder="하이픈 없이 10자리"
          />
        </label>
        <label>
          브랜드 소개
          <textarea name="description" rows={5} />
        </label>
        {DOCUMENT_FIELDS.map((field) => (
          <DocumentUploadField
            key={field.type}
            type={field.type}
            label={field.label}
            hint={field.hint}
            documentId={documentIds[field.type]}
            onUploaded={(type, documentId) =>
              setDocumentIds((current) => ({ ...current, [type]: documentId }))
            }
            onCleared={(type) =>
              setDocumentIds((current) => ({ ...current, [type]: undefined }))
            }
          />
        ))}
        {formError && (
          <p className="form-error" role="alert">
            {formError}
          </p>
        )}
        <button
          type="submit"
          className="primary-button"
          disabled={!allDocumentsUploaded || submit.isPending}
        >
          {submit.isPending ? '신청 중...' : '판매자 신청하기'}
        </button>
      </>
    )
  }
}
