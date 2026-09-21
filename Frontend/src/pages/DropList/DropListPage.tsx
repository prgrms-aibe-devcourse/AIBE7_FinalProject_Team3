import { useState } from 'react'
import { Link } from 'react-router-dom'
import logo from '../../../asset/Logo.png'
import heroImage from '../../../asset/background.png'
import DropCard from '../../features/drop/DropCard'
import { categories, drops } from '../../features/drop/mockDrops'
import type { DropStatus } from '../../types/drop'
import type { SharedProps } from '../../types/store'

export default function DropListPage({ status, wishes, toggleWish, notify }: SharedProps & { status: DropStatus }) {
  const [category, setCategory] = useState('전체')
  const [query, setQuery] = useState('')
  const items = drops.filter(
    (drop) =>
      drop.status === status &&
      (category === '전체' || drop.category === category) &&
      `${drop.name} ${drop.brand}`.toLowerCase().includes(query.trim().toLowerCase()),
  )

  const onWish = (id: number) => {
    const selected = wishes.has(id)
    if (!toggleWish(id)) return
    notify(selected ? 'WISH를 취소했어요.' : 'WISH에 담았어요.')
  }

  return (
    <>
      {status === 'WISH' ? (
        <>
          <section className="hero">
            <img src={heroImage} alt="GRAB, Grab what you want" />
            <span>SELLER DROPS. CONSUMER GRABS.</span>
          </section>
          <section className="intro">
            <div>
              <p className="eyebrow">BE READY FOR THE DROP</p>
              <h1>발견한 취향, <em>놓치지 않도록.</em></h1>
              <p>마음에 드는 DROP을 WISH하고 판매 시작을 기다려보세요.</p>
            </div>
            <Link className="text-link" to="/my">나의 WISH ↗</Link>
          </section>
        </>
      ) : (
        <section className="grab-hero">
          <div>
            <p className="eyebrow">LIMITED TIME. LIMITED QUANTITY.</p>
            <h1>원하던 순간, 지금 GRAB.</h1>
            <p>작은 브랜드가 준비한 특별한 상품을 한정 수량으로 만나보세요.</p>
          </div>
          <img src={logo} alt="" />
        </section>
      )}

      <section className="catalog-controls" aria-label="상품 필터">
        <div className="chips">
          {categories.map((item) => (
            <button key={item} className={category === item ? 'active' : ''} onClick={() => setCategory(item)}>
              {item}
            </button>
          ))}
        </div>
        <label className="search-box">
          <span className="sr-only">상품 검색</span>
          <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="상품 또는 브랜드 검색" />
          <span aria-hidden="true">⌕</span>
        </label>
      </section>

      {items.length ? (
        <section className="product-grid" aria-label={`${status} 상품 목록`}>
          {items.map((drop) => (
            <DropCard key={drop.id} drop={drop} wished={wishes.has(drop.id)} onWish={onWish} />
          ))}
        </section>
      ) : (
        <div className="empty-state"><strong>조건에 맞는 상품이 없어요.</strong><p>검색어나 카테고리를 바꿔보세요.</p></div>
      )}

      <section className="seller-banner">
        <img src={logo} alt="" />
        <div><strong>하나의 상품, 하나의 DROP.</strong><p>관심을 모으고 한정판매로 연결하세요.</p></div>
        <Link className="secondary-button" to="/seller">셀러로 시작하기 ↗</Link>
      </section>
    </>
  )
}
