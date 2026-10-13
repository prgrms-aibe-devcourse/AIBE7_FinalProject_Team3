import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import EmptyState from '../../components/EmptyState/EmptyState'
import DropImageField from '../../features/seller/components/DropImageField'
import OptionGroupBuilder from '../../features/seller/OptionGroupBuilder'
import {
  defaultGroups,
  existingSellerDraft,
  type SellerDraft,
} from '../../features/seller/mock/mockSellerDrafts'

type Props = {
  drafts: Record<string, SellerDraft>
  editableIds: string[]
  onSave: (draft: SellerDraft) => void
  notify: (message: string) => void
}

export default function SellerDropFormPage(props: Props) {
  const { dropId } = useParams()
  const draft = dropId
    ? (props.drafts[dropId] ?? existingSellerDraft(dropId))
    : undefined
  if (dropId && (!draft || !props.editableIds.includes(dropId)))
    return (
      <EmptyState
        title="편집할 DRAFT를 찾을 수 없어요."
        link="/seller"
        label="DROP 목록으로 돌아가기"
        variant="page"
      />
    )
  return <SellerDropForm key={dropId ?? 'new'} {...props} draft={draft} />
}

function SellerDropForm({
  draft,
  onSave,
  notify,
}: Omit<Props, 'drafts' | 'editableIds'> & { draft?: SellerDraft }) {
  const navigate = useNavigate()
  const [groups, setGroups] = useState(draft?.groups ?? defaultGroups)
  const [gallery, setGallery] = useState(draft?.gallery ?? [])
  const [detail, setDetail] = useState(draft?.detail ?? [])
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState('')
  const values = draft?.values ?? {}

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const submitter = (event.nativeEvent as SubmitEvent)
      .submitter as HTMLButtonElement | null
    const publish = submitter?.value === 'publish'
    if (uploading) {
      setError('이미지를 불러오는 동안에는 저장할 수 없어요.')
      return
    }
    if (
      gallery.some((image) => image.failed) ||
      detail.some((image) => image.failed)
    ) {
      setError('업로드에 실패한 이미지를 다시 시도하거나 삭제해주세요.')
      return
    }
    if (publish && !gallery.length) {
      setError('공개하려면 GALLERY 이미지를 1장 이상 추가해주세요.')
      return
    }
    const form = new FormData(event.currentTarget)
    const fields = Object.fromEntries(
      [...form.entries()].filter(([, value]) => typeof value === 'string'),
    ) as Record<string, string>
    if (
      publish &&
      new Date(fields.saleStartsAt).getTime() >=
        new Date(fields.saleEndsAt).getTime()
    ) {
      setError('판매 종료는 시작보다 늦어야 해요.')
      return
    }
    const next: SellerDraft = {
      id: draft?.id ?? crypto.randomUUID(),
      values: fields,
      groups,
      gallery,
      detail,
    }
    onSave(next)
    setError('')
    notify(
      publish
        ? 'Mock 공개 조건을 확인했어요. 실제 DROP은 공개되지 않았습니다.'
        : 'Mock DROP 초안을 저장했어요.',
    )
    if (!publish) navigate(`/seller/drops/${next.id}/edit`)
  }

  return (
    <section className="page-section form-page">
      <div className="breadcrumbs">
        <Link to="/seller">← DROP STUDIO</Link>
        <span>/</span>
        <span>{draft ? 'DRAFT 편집' : '새 DROP'}</span>
      </div>
      <p className="eyebrow">ONE DROP, ONE PRODUCT</p>
      <h1>{draft ? 'DRAFT 편집' : '새 DROP 만들기'}</h1>
      <p className="page-description">
        상품 특성에 맞게 옵션 그룹과 선택값을 자유롭게 구성하세요.
      </p>
      <form onSubmit={submit}>
        <section className="form-card">
          <div className="section-number">01</div>
          <div className="form-content">
            <h2>기본 정보</h2>
            <label>
              상품명
              <input
                required
                name="name"
                defaultValue={values.name ?? ''}
                placeholder="상품 이름"
              />
            </label>
            <label>
              상품 설명
              <textarea
                required
                name="description"
                defaultValue={values.description ?? ''}
                rows={4}
                placeholder="상품과 브랜드의 이야기를 적어주세요."
              />
            </label>
            <div className="form-row">
              <label>
                카테고리
                <select
                  required
                  name="category"
                  defaultValue={values.category ?? ''}
                >
                  <option value="" disabled>
                    선택
                  </option>
                  <option>패션</option>
                  <option>리빙</option>
                  <option>문구 · 굿즈</option>
                </select>
              </label>
            </div>
          </div>
        </section>
        <div className="drop-image-layout">
          <DropImageField
            kind="GALLERY"
            images={gallery}
            totalCount={gallery.length + detail.length}
            onChange={setGallery}
            onUploading={setUploading}
          />
          <DropImageField
            kind="DETAIL"
            images={detail}
            totalCount={gallery.length + detail.length}
            onChange={setDetail}
            onUploading={setUploading}
          />
        </div>
        <p className="form-image-hint">
          두 용도 합계 10장 · 파일당 5MB · JPEG/PNG/WebP
        </p>
        <section className="form-card">
          <div className="section-number">04</div>
          <div className="form-content">
            <OptionGroupBuilder
              groups={groups}
              setGroups={setGroups}
              values={values}
            />
          </div>
        </section>
        <section className="form-card">
          <div className="section-number">05</div>
          <div className="form-content">
            <h2>판매 및 배송</h2>
            <div className="form-row">
              <label>
                판매 시작
                <input
                  required
                  name="saleStartsAt"
                  defaultValue={values.saleStartsAt ?? ''}
                  type="datetime-local"
                />
              </label>
              <label>
                판매 종료
                <input
                  required
                  name="saleEndsAt"
                  defaultValue={values.saleEndsAt ?? ''}
                  type="datetime-local"
                />
              </label>
            </div>
            <div className="form-row">
              <label>
                배송비
                <input
                  required
                  name="shippingFee"
                  type="number"
                  min="0"
                  step="100"
                  defaultValue={values.shippingFee ?? '3000'}
                />
              </label>
              <label>
                배송 안내
                <input
                  required
                  name="shippingNotice"
                  defaultValue={
                    values.shippingNotice ?? '결제 완료 후 3~5 영업일 이내 출고'
                  }
                />
              </label>
            </div>
          </div>
        </section>
        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
        <div className="form-actions">
          <Link className="secondary-button" to="/seller">
            취소
          </Link>
          <button
            type="submit"
            name="intent"
            value="draft"
            formNoValidate
            className="secondary-button"
            disabled={uploading}
          >
            임시 저장
          </button>
          <button
            type="submit"
            name="intent"
            value="publish"
            className="primary-button"
            disabled={uploading}
          >
            공개 조건 확인
          </button>
        </div>
      </form>
    </section>
  )
}
