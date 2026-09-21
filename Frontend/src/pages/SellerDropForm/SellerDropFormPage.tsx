import type { FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import OptionGroupBuilder from '../../features/seller/OptionGroupBuilder'

export default function SellerDropFormPage({
  notify,
}: {
  notify: (message: string) => void
}) {
  const navigate = useNavigate()

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    notify('DROP 초안을 저장했어요.')
    navigate('/seller')
  }

  return (
    <section className="page-section form-page">
      <div className="breadcrumbs">
        <Link to="/seller">← DROP STUDIO</Link>
        <span>/</span>
        <span>새 DROP</span>
      </div>
      <p className="eyebrow">ONE DROP, ONE PRODUCT</p>
      <h1>새 DROP 만들기</h1>
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
              <input required placeholder="상품 이름" />
            </label>
            <label>
              상품 설명
              <textarea
                required
                rows={4}
                placeholder="상품과 브랜드의 이야기를 적어주세요."
              />
            </label>
            <div className="form-row">
              <label>
                카테고리
                <select required defaultValue="">
                  <option value="" disabled>
                    선택
                  </option>
                  <option>패션</option>
                  <option>리빙</option>
                  <option>문구 · 굿즈</option>
                </select>
              </label>
              <label>
                대표 이미지
                <input type="file" accept="image/*" />
              </label>
            </div>
          </div>
        </section>
        <section className="form-card">
          <div className="section-number">02</div>
          <div className="form-content">
            <OptionGroupBuilder />
          </div>
        </section>
        <section className="form-card">
          <div className="section-number">03</div>
          <div className="form-content">
            <h2>판매 및 배송</h2>
            <div className="form-row">
              <label>
                판매 시작
                <input required type="datetime-local" />
              </label>
              <label>
                판매 종료
                <input required type="datetime-local" />
              </label>
            </div>
            <div className="form-row">
              <label>
                배송비
                <input
                  required
                  type="number"
                  min="0"
                  step="100"
                  defaultValue="3000"
                />
              </label>
              <label>
                배송 안내
                <input
                  required
                  defaultValue="결제 완료 후 3~5 영업일 이내 출고"
                />
              </label>
            </div>
          </div>
        </section>
        <div className="form-actions">
          <Link className="secondary-button" to="/seller">
            취소
          </Link>
          <button className="primary-button">임시 저장</button>
        </div>
      </form>
    </section>
  )
}
