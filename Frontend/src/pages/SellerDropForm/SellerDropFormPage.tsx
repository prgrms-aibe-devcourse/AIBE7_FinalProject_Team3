import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  Link,
  Navigate,
  useLocation,
  useNavigate,
  useParams,
} from 'react-router-dom'
import { getCategories } from '../../api/category/categories'
import EmptyState from '../../components/EmptyState/EmptyState'
import AuthLoading from '../../features/auth/components/AuthLoading'
import {
  apiErrorCode,
  isAdmin,
  isSeller,
} from '../../features/auth/logic/authUser'
import useAuth from '../../features/auth/logic/useAuth'
import { categoryKeys } from '../../features/drop/logic/queryKeys'
import DropImageField from '../../features/seller/components/DropImageField'
import DropFieldErrors from '../../features/seller/components/DropFieldErrors'
import OptionGroupBuilder from '../../features/seller/OptionGroupBuilder'
import {
  rebuildSkuValues,
  toSellerDropRequest,
  validateDropPublish,
  type SellerDraft,
} from '../../features/seller/logic/sellerDropForm'
import {
  sellerDropError,
  type DropFormError,
} from '../../features/seller/logic/sellerDropErrors'
import {
  useSaveSellerDrop,
  useSellerDropForm,
} from '../../features/seller/logic/useSellerDropForm'

type Props = {
  notify: (message: string) => void
  onPublished: (dropId: number) => void
}

export default function SellerDropFormPage(props: Props) {
  const { dropId } = useParams()
  const location = useLocation()
  const { user, status } = useAuth()
  const id = Number(dropId)
  const validId =
    /^\d+$/.test(dropId ?? '') && Number.isSafeInteger(id) && id > 0
  const query = useSellerDropForm(
    id,
    !!dropId && validId && status === 'authenticated' && isSeller(user),
  )
  if (status === 'loading') return <AuthLoading />
  if (!user)
    return <Navigate to="/login" state={{ from: location.pathname }} replace />
  if (!isSeller(user))
    return (
      <EmptyState
        title="판매자 권한이 필요해요."
        link={isAdmin(user) ? '/my' : '/seller/apply'}
        label={isAdmin(user) ? '내 계정으로 돌아가기' : '판매자 신청 보기'}
        variant="page"
      />
    )
  if (dropId) {
    if (!validId)
      return (
        <EmptyState
          title="잘못된 DROP 번호예요."
          link="/seller/drops"
          label="DROP 목록으로 돌아가기"
          variant="page"
        />
      )
    if (query.isPending)
      return (
        <div role="status">
          <EmptyState title="DROP 정보를 불러오는 중이에요." variant="page" />
        </div>
      )
    if (apiErrorCode(query.error) === 'DROP_ACCESS_DENIED')
      return (
        <EmptyState
          title="이 DROP을 관리할 권한이 없어요."
          link="/seller/drops"
          label="DROP 목록으로 돌아가기"
          variant="page"
        />
      )
    if (apiErrorCode(query.error) === 'DROP_NOT_FOUND' || query.data === null)
      return (
        <EmptyState
          title="편집할 DRAFT를 찾을 수 없어요."
          link="/seller/drops"
          label="DROP 목록으로 돌아가기"
          variant="page"
        />
      )
    if (query.isError && !query.data)
      return (
        <div className="catalog-error" role="alert">
          <EmptyState title="DROP 정보를 불러오지 못했어요." variant="block" />
          <button
            type="button"
            className="secondary-button"
            onClick={() => void query.refetch()}
          >
            다시 시도
          </button>
        </div>
      )
  }
  return (
    <SellerDropForm
      key={dropId ?? 'new'}
      {...props}
      draft={query.data ?? undefined}
    />
  )
}

function SellerDropForm({
  draft,
  notify,
  onPublished,
}: Props & { draft?: SellerDraft }) {
  const navigate = useNavigate()
  const location = useLocation()
  const [groups, setGroups] = useState(draft?.groups ?? [])
  const [values, setValues] = useState(
    draft?.values ??
      rebuildSkuValues([], {
        shippingFee: '3000',
        shippingNotice: '결제 완료 후 3~5 영업일 이내 출고',
      }),
  )
  const [gallery, setGallery] = useState(draft?.gallery ?? [])
  const [detail, setDetail] = useState(draft?.detail ?? [])
  const [error, setError] = useState<DropFormError | null>(
    location.state?.dropFormError ?? null,
  )
  const save = useSaveSellerDrop(draft ? Number(draft.id) : null)
  const submitting = useRef(false)
  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])
  const categories = useQuery({
    queryKey: categoryKeys.all,
    queryFn: getCategories,
  })
  const uploading = [...gallery, ...detail].some((image) => image.uploading)
  const fields = error?.fields ?? []

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (submitting.current || uploading) return
    const publish =
      (event.nativeEvent as SubmitEvent).submitter?.getAttribute('value') ===
      'publish'
    const entries = Object.fromEntries(
      [...new FormData(event.currentTarget).entries()].filter(
        ([, value]) => typeof value === 'string',
      ),
    ) as Record<string, string>
    submitting.current = true
    setError(null)
    try {
      const input = toSellerDropRequest({
        values: { ...values, ...entries },
        groups,
        gallery,
        detail,
      })
      if (publish) {
        const errors = validateDropPublish(input)
        if (errors.length) {
          setError({
            message: '공개에 필요한 항목을 확인해주세요.',
            fields: errors,
          })
          return
        }
      }
      const id = await save.mutateAsync({ input, publish })
      if (!mounted.current) return
      notify(
        publish ? 'DROP을 WISH 상태로 공개했어요.' : 'DROP 초안을 저장했어요.',
      )
      if (publish) onPublished(id)
      else navigate(`/seller/drops/${id}/edit`, { replace: true, state: null })
    } catch (cause) {
      if (!mounted.current) return
      const next = sellerDropError(cause)
      const savedId = save.getSavedId()
      if (!draft && savedId)
        navigate(`/seller/drops/${savedId}/edit`, {
          replace: true,
          state: { dropFormError: next },
        })
      else setError(next)
    } finally {
      submitting.current = false
    }
  }

  return (
    <section className="page-section form-page">
      <div className="breadcrumbs">
        <Link to="/seller/drops">← DROP STUDIO</Link>
        <span>/</span>
        <span>{draft ? 'DRAFT 편집' : '새 DROP'}</span>
      </div>
      <h1>{draft ? 'DRAFT 편집' : '새 DROP 만들기'}</h1>
      <p className="page-description">
        상품 특성에 맞게 옵션 그룹과 선택값을 자유롭게 구성하세요.
      </p>
      <form onSubmit={(event) => void submit(event)} noValidate>
        <fieldset className="drop-form-fields" disabled={save.isPending}>
          <section className="form-card">
            <div className="section-number">01</div>
            <div className="form-content">
              <h2>기본 정보</h2>
              <label>
                상품명
                <input
                  required
                  name="name"
                  maxLength={200}
                  defaultValue={values.name ?? ''}
                  placeholder="상품 이름"
                />
                <DropFieldErrors field="name" errors={fields} />
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
                <DropFieldErrors field="description" errors={fields} />
              </label>
              <div className="form-row">
                <label>
                  카테고리
                  <select
                    required
                    name="categoryId"
                    value={values.categoryId ?? ''}
                    onChange={(event) => {
                      const categoryId = event.target.value
                      setValues((current) => ({ ...current, categoryId }))
                    }}
                    disabled={categories.isPending || categories.isError}
                  >
                    <option value="">선택</option>
                    {(categories.data ?? []).map((category) => (
                      <option
                        key={category.categoryId}
                        value={category.categoryId}
                      >
                        {category.name}
                      </option>
                    ))}
                  </select>
                  <DropFieldErrors field="categoryId" errors={fields} />
                </label>
              </div>
              {categories.isPending && (
                <p role="status" className="form-image-hint">
                  카테고리를 불러오는 중이에요.
                </p>
              )}
              {categories.isError && (
                <div>
                  <p className="form-error" role="alert">
                    카테고리를 불러오지 못했어요.
                  </p>
                  <button
                    type="button"
                    className="secondary-button"
                    onClick={() => void categories.refetch()}
                  >
                    다시 시도
                  </button>
                </div>
              )}
            </div>
          </section>
          <div className="drop-image-layout">
            <div>
              <DropImageField
                kind="GALLERY"
                images={gallery}
                onChange={setGallery}
              />
              <DropFieldErrors field="images" errors={fields} />
            </div>
            <div>
              <DropImageField
                kind="DETAIL"
                images={detail}
                onChange={setDetail}
              />
              <DropFieldErrors field="detailImages" errors={fields} />
            </div>
          </div>
          <p className="form-image-hint">
            GALLERY 최대 5장 · DETAIL 최대 15장 · 파일당 5MB · JPEG/PNG/WebP
          </p>
          <section className="form-card">
            <div className="section-number">04</div>
            <div className="form-content">
              <OptionGroupBuilder
                groups={groups}
                values={values}
                setValues={setValues}
                errors={fields}
                onGroupsChange={(next) => {
                  setGroups(next)
                  setValues((current) => rebuildSkuValues(next, current))
                }}
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
                    step="any"
                  />
                  <DropFieldErrors field="saleStartsAt" errors={fields} />
                </label>
                <label>
                  판매 종료
                  <input
                    required
                    name="saleEndsAt"
                    defaultValue={values.saleEndsAt ?? ''}
                    type="datetime-local"
                    step="any"
                  />
                  <DropFieldErrors field="saleEndsAt" errors={fields} />
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
                    step="1"
                    defaultValue={values.shippingFee ?? ''}
                  />
                  <DropFieldErrors
                    field="shipping.shippingFee"
                    errors={fields}
                  />
                </label>
                <label>
                  배송 안내
                  <input
                    required
                    name="shippingNotice"
                    defaultValue={values.shippingNotice ?? ''}
                  />
                  <DropFieldErrors
                    field="shipping.shippingNotice"
                    errors={fields}
                  />
                </label>
              </div>
            </div>
          </section>
          {error && (
            <p className="form-error drop-submit-error" role="alert">
              {error.message}
            </p>
          )}
          <div className="form-actions">
            <Link className="secondary-button" to="/seller/drops">
              취소
            </Link>
            <button
              type="submit"
              name="intent"
              value="draft"
              formNoValidate
              className="secondary-button"
              disabled={uploading || save.isPending}
            >
              임시 저장
            </button>
            <button
              type="submit"
              name="intent"
              value="publish"
              className="primary-button"
              disabled={
                uploading ||
                save.isPending ||
                categories.isPending ||
                categories.isError
              }
            >
              공개
            </button>
          </div>
        </fieldset>
      </form>
    </section>
  )
}
