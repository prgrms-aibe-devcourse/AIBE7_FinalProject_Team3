import { useMemo, useState, type FormEvent } from 'react'
import { Link, NavLink, Navigate, Route, Routes, useLocation, useNavigate, useParams } from 'react-router-dom'
import logo from '../asset/Logo.png'
import heroImage from '../asset/background.png'
import { categories, dateLabel, drops, won, type Drop, type DropStatus } from './data'
import { findMatchingSku, optionCombinations, selectionLabel, type OptionGroup } from './domain'

type Order = {
  id: string
  drop: Drop
  optionLabel: string
  quantity: number
  total: number
}

type SharedProps = {
  authenticated: boolean
  wishes: Set<number>
  toggleWish: (id: number) => boolean
  orders: Order[]
  addOrder: (order: Order) => void
  notify: (message: string) => void
}

const minPrice = (drop: Drop) => Math.min(...drop.skus.map((sku) => sku.price))
const stock = (drop: Drop) => drop.skus.reduce((sum, sku) => sum + sku.stock, 0)
const daysUntil = (value: string) => Math.max(0, Math.ceil((new Date(value).getTime() - Date.now()) / 86_400_000))

export default function App() {
  const [authenticated, setAuthenticated] = useState(false)
  const [wishes, setWishes] = useState(() => new Set([101]))
  const [orders, setOrders] = useState<Order[]>([])
  const [toast, setToast] = useState('')

  const notify = (message: string) => {
    setToast(message)
    window.setTimeout(() => setToast(''), 2600)
  }

  const shared: SharedProps = {
    authenticated,
    wishes,
    orders,
    notify,
    toggleWish: (id) => {
      if (!authenticated) {
        notify('로그인 후 WISH를 이용할 수 있어요.')
        return false
      }
      setWishes((current) => {
        const next = new Set(current)
        next.has(id) ? next.delete(id) : next.add(id)
        return next
      })
      return true
    },
    addOrder: (order) => setOrders((current) => [order, ...current]),
  }

  return (
    <div className="app-shell">
      <Header authenticated={authenticated} />
      <main>
        <Routes>
          <Route path="/" element={<Navigate to="/wish" replace />} />
          <Route path="/wish" element={<CatalogPage status="WISH" {...shared} />} />
          <Route path="/grab" element={<CatalogPage status="GRAB" {...shared} />} />
          <Route path="/drops/:dropId" element={<DropDetail {...shared} />} />
          <Route path="/my" element={<MyPage {...shared} />} />
          <Route path="/login" element={<AuthPage mode="login" onLogin={() => setAuthenticated(true)} notify={notify} />} />
          <Route path="/signup" element={<AuthPage mode="signup" onLogin={() => setAuthenticated(true)} notify={notify} />} />
          <Route path="/seller" element={<SellerDashboard />} />
          <Route path="/seller/drops/new" element={<SellerDropForm notify={notify} />} />
          <Route path="*" element={<NotFound />} />
        </Routes>
      </main>
      <Footer />
      <div className={`toast ${toast ? 'is-visible' : ''}`} role="status" aria-live="polite">
        {toast}
      </div>
    </div>
  )
}

function Header({ authenticated }: { authenticated: boolean }) {
  const location = useLocation()
  const sellerMode = location.pathname.startsWith('/seller')

  return (
    <header className="site-header">
      <Link className="brand" to={sellerMode ? '/seller' : '/wish'} aria-label="GRAB 홈">
        <img src={logo} alt="" />
        <span>GRAB</span>
      </Link>
      <nav aria-label="주요 메뉴">
        {sellerMode ? (
          <NavLink to="/seller">DROP STUDIO</NavLink>
        ) : (
          <>
            <NavLink to="/wish">WISH</NavLink>
            <NavLink to="/grab">GRAB</NavLink>
          </>
        )}
      </nav>
      <div className="header-actions">
        <Link className="mode-button" to={sellerMode ? '/wish' : '/seller'}>
          {sellerMode ? '쇼퍼 전환' : '셀러 전환'} <span aria-hidden="true">↗</span>
        </Link>
        {!sellerMode && <Link className="account-button" to={authenticated ? '/my' : '/login'}>{authenticated ? 'MY' : '로그인'}</Link>}
      </div>
    </header>
  )
}

function CatalogPage({ status, wishes, toggleWish, notify }: SharedProps & { status: DropStatus }) {
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
            <ProductCard key={drop.id} drop={drop} wished={wishes.has(drop.id)} onWish={onWish} />
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

function ProductCard({ drop, wished, onWish }: { drop: Drop; wished: boolean; onWish: (id: number) => void }) {
  const available = stock(drop)
  return (
    <article className="product-card">
      <Link className="product-visual" to={`/drops/${drop.id}`}>
        <img src={drop.image} alt={drop.name} />
        <span className={`badge ${drop.status === 'WISH' ? 'lime' : ''}`}>
          {drop.status === 'WISH' ? `OPEN D−${daysUntil(drop.saleStartsAt)}` : available ? 'LIMITED DROP' : 'SOLD OUT'}
        </span>
        <span className="round-arrow" aria-hidden="true">↗</span>
      </Link>
      <p className="creator">{drop.brand}</p>
      <h2><Link to={`/drops/${drop.id}`}>{drop.name}</Link></h2>
      <p className="price">{won(minPrice(drop))}<small>부터</small></p>
      <p className="schedule">{dateLabel(drop.status === 'WISH' ? drop.saleStartsAt : drop.saleEndsAt)} {drop.status === 'WISH' ? '오픈' : '종료'}</p>
      <div className="card-bottom">
        <span>{drop.status === 'WISH' ? <><b>{drop.wishCount + (wished ? 1 : 0)}</b> WISH</> : <>남은 수량 <b>{available}개</b></>}</span>
        {drop.status === 'WISH' ? (
          <button className={`wish-button ${wished ? 'selected' : ''}`} onClick={() => onWish(drop.id)}>
            {wished ? '✓ WISHED' : '♡ WISH'}
          </button>
        ) : (
          <Link className="wish-button" to={`/drops/${drop.id}`}>GRAB ↗</Link>
        )}
      </div>
    </article>
  )
}

function DropDetail({ authenticated, wishes, toggleWish, addOrder, notify }: SharedProps) {
  const { dropId } = useParams()
  const navigate = useNavigate()
  const drop = drops.find((item) => item.id === Number(dropId))
  const [selected, setSelected] = useState<Record<string, string>>({})
  const [quantity, setQuantity] = useState(1)

  if (!drop) return <NotFound />

  const selectedSku = findMatchingSku(drop.skus, selected)
  const wished = wishes.has(drop.id)
  const orderable = drop.status === 'GRAB' && selectedSku && selectedSku.stock > 0
  const optionLabel = selectedSku ? selectionLabel(drop.optionGroups, selectedSku.selections) || '기본 옵션' : ''

  const order = (event: FormEvent) => {
    event.preventDefault()
    if (!selectedSku || !orderable) return
    if (!authenticated) {
      notify('로그인 후 주문할 수 있어요.')
      navigate('/login')
      return
    }
    addOrder({
      id: `GR-${Date.now().toString().slice(-8)}`,
      drop,
      optionLabel,
      quantity,
      total: selectedSku.price * quantity + drop.shippingFee,
    })
    notify('주문이 생성됐어요. 결제는 데모에서 생략합니다.')
    navigate('/my')
  }

  return (
    <>
      <div className="breadcrumbs"><Link to={drop.status === 'WISH' ? '/wish' : '/grab'}>← 목록으로</Link><span>/</span><span>{drop.category}</span></div>
      <section className="detail-layout">
        <div className="detail-media"><img src={drop.image} alt={drop.name} /><span className={`badge ${drop.status === 'WISH' ? 'lime' : ''}`}>{drop.status}</span></div>
        <div className="detail-summary">
          <div className="detail-status"><b>{drop.status === 'WISH' ? `오픈 D−${daysUntil(drop.saleStartsAt)}` : 'LIMITED DROP'}</b><span>{drop.status === 'WISH' ? `${drop.wishCount} WISH` : `재고 ${stock(drop)}개`}</span></div>
          <p className="creator">{drop.brand}</p>
          <h1>{drop.name}</h1>
          <p className="detail-description">{drop.description}</p>
          <p className="detail-price">{won(selectedSku?.price ?? minPrice(drop))}<small>{selectedSku ? '선택 옵션 가격' : '최저가'}</small></p>

          <dl className="meta-list">
            <div><dt>판매 기간</dt><dd>{dateLabel(drop.saleStartsAt)} – {dateLabel(drop.saleEndsAt)}</dd></div>
            <div><dt>배송</dt><dd>{won(drop.shippingFee)} · {drop.shippingNotice}</dd></div>
          </dl>

          {drop.status === 'WISH' ? (
            <div className="wish-panel">
              <strong>{drop.optionGroups.map((group) => group.name).join(' · ') || '기본'} 옵션으로 공개될 예정이에요.</strong>
              <p>WISH는 구매, 재고 예약 또는 구매 우선권을 보장하지 않습니다.</p>
              <button className="primary-button full" onClick={() => { if (toggleWish(drop.id)) notify(wished ? 'WISH를 취소했어요.' : 'WISH에 담았어요.') }}>
                {wished ? '✓ WISH 취소하기' : '♡ WISH 등록하기'}
              </button>
            </div>
          ) : (
            <form className="buy-form" onSubmit={order}>
              {drop.optionGroups.map((group) => (
                <label key={group.id}>
                  <span>{group.name}</span>
                  <select required value={selected[group.id] ?? ''} onChange={(event) => { setSelected((current) => ({ ...current, [group.id]: event.target.value })); setQuantity(1) }}>
                    <option value="">선택해주세요</option>
                    {group.values.map((value) => <option key={value.id} value={value.id}>{value.label}</option>)}
                  </select>
                </label>
              ))}
              {drop.optionGroups.length === 0 && <p className="default-option">별도 선택이 없는 기본 옵션 상품입니다.</p>}
              <label>
                <span>수량</span>
                <input type="number" min="1" max={Math.min(5, selectedSku?.stock ?? 1)} value={quantity} onChange={(event) => setQuantity(Math.max(1, Number(event.target.value)))} />
              </label>
              <div className={`sku-result ${selectedSku ? '' : 'muted'}`}>
                {selectedSku ? <><span>{optionLabel}</span><strong>{selectedSku.stock ? `재고 ${selectedSku.stock}개` : '품절'}</strong></> : <span>모든 옵션을 선택하면 재고와 가격을 확인할 수 있어요.</span>}
              </div>
              <div className="total-row"><span>상품 합계</span><strong>{won((selectedSku?.price ?? minPrice(drop)) * quantity)}</strong></div>
              <button className="primary-button full" disabled={!orderable}>{orderable ? (authenticated ? '지금 GRAB 하기 ↗' : '로그인 후 GRAB ↗') : selectedSku?.stock === 0 ? '선택 옵션 품절' : '옵션을 선택해주세요'}</button>
            </form>
          )}
        </div>
      </section>

      <section className="story-section">
        <p className="eyebrow">SMALL BRAND, SPECIAL FIND</p>
        <h2>작은 취향이 특별한 상품이 되는 순간.</h2>
        <p>{drop.description} GRAB은 브랜드의 이야기가 필요한 사람에게 정확히 닿도록 돕습니다.</p>
        <div className="creator-card"><img src={logo} alt="" /><div><small>MEET THE CREATOR</small><strong>{drop.brand}</strong><p>이번 DROP을 위해 준비한 한정 컬렉션입니다.</p></div></div>
      </section>
    </>
  )
}

function MyPage({ authenticated, wishes, orders, toggleWish, notify }: SharedProps) {
  if (!authenticated) return <Navigate to="/login" replace />
  const wishedDrops = drops.filter((drop) => wishes.has(drop.id))
  return (
    <section className="page-section">
      <p className="eyebrow">MY GRAB</p>
      <h1>나의 취향과 주문</h1>
      <div className="my-columns">
        <section className="panel">
          <div className="panel-heading"><h2>WISH</h2><span>{wishedDrops.length}</span></div>
          {wishedDrops.length ? wishedDrops.map((drop) => (
            <div className="compact-item" key={drop.id}>
              <img src={drop.image} alt="" />
              <div><Link to={`/drops/${drop.id}`}><strong>{drop.name}</strong></Link><small>{dateLabel(drop.saleStartsAt)} 오픈</small></div>
              <button onClick={() => { if (toggleWish(drop.id)) notify('WISH를 취소했어요.') }}>취소</button>
            </div>
          )) : <Empty title="아직 담은 WISH가 없어요." link="/wish" label="WISH 둘러보기" />}
        </section>
        <section className="panel">
          <div className="panel-heading"><h2>주문</h2><span>{orders.length}</span></div>
          {orders.length ? orders.map((order) => (
            <div className="compact-item order" key={order.id}>
              <img src={order.drop.image} alt="" />
              <div><small>{order.id} · 결제 대기</small><strong>{order.drop.name}</strong><small>{order.optionLabel} · {order.quantity}개</small></div>
              <b>{won(order.total)}</b>
            </div>
          )) : <Empty title="아직 주문이 없어요." link="/grab" label="GRAB 둘러보기" />}
        </section>
      </div>
    </section>
  )
}

function AuthPage({ mode, onLogin, notify }: { mode: 'login' | 'signup'; onLogin: () => void; notify: (message: string) => void }) {
  const navigate = useNavigate()
  const signup = mode === 'signup'

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    onLogin()
    notify(signup ? '가입이 완료됐어요.' : '로그인했어요.')
    navigate('/wish')
  }

  return (
    <section className="auth-page">
      <div className="auth-copy">
        <p className="eyebrow">WELCOME TO GRAB</p>
        <h1>{signup ? '취향을 발견할 준비가 됐나요?' : '다시 만나 반가워요.'}</h1>
        <p>좋아하는 상품을 WISH하고, 한정된 순간에 GRAB하세요.</p>
        <img src={logo} alt="" />
      </div>
      <form className="auth-form" onSubmit={submit}>
        <h2>{signup ? '회원가입' : '로그인'}</h2>
        {signup && <label>이름<input required autoComplete="name" placeholder="이름" /></label>}
        <label>이메일<input required type="email" autoComplete="email" placeholder="grab@example.com" /></label>
        <label>비밀번호<input required type="password" minLength={8} autoComplete={signup ? 'new-password' : 'current-password'} placeholder="8자 이상 입력" /></label>
        <button className="primary-button full">{signup ? 'GRAB 시작하기' : '로그인'}</button>
        <p>{signup ? '이미 계정이 있나요?' : '아직 계정이 없나요?'} <Link to={signup ? '/login' : '/signup'}>{signup ? '로그인' : '회원가입'}</Link></p>
        <small>현재 화면은 프론트엔드 초안으로, 입력한 정보는 저장하거나 전송하지 않습니다.</small>
      </form>
    </section>
  )
}

function SellerDashboard() {
  const wishDrops = drops.filter((drop) => drop.status === 'WISH')
  const grabDrops = drops.filter((drop) => drop.status === 'GRAB')
  return (
    <section className="page-section seller-page">
      <div className="seller-title"><div><p className="eyebrow">CREATOR WORKSPACE</p><h1>다음 DROP을 준비하는 곳.</h1><p>관심부터 재고까지 한눈에 확인하세요.</p></div><Link className="primary-button" to="/seller/drops/new">＋ 새 DROP 만들기</Link></div>
      <div className="stats-grid">
        <article><span>전체 WISH</span><strong>{drops.reduce((sum, drop) => sum + drop.wishCount, 0)}</strong><small>공개 상품 누적</small></article>
        <article><span>판매 예정</span><strong>{wishDrops.length}<em>개</em></strong><small>WISH 상태</small></article>
        <article><span>판매 중</span><strong>{grabDrops.length}<em>개</em></strong><small>GRAB 상태</small></article>
        <article><span>가용 재고</span><strong>{grabDrops.reduce((sum, drop) => sum + stock(drop), 0)}<em>개</em></strong><small>모든 SKU 합계</small></article>
      </div>
      <section className="panel drop-table-panel">
        <div className="panel-heading"><div><h2>DROP 관리</h2><p>상태와 옵션별 재고를 확인합니다.</p></div><Link className="text-link" to="/seller/drops/new">새 상품 ↗</Link></div>
        <div className="drop-list">
          {drops.slice(0, 5).map((drop) => (
            <article key={drop.id}>
              <img src={drop.image} alt="" />
              <div className="drop-name"><strong>{drop.name}</strong><small>{drop.optionGroups.map((group) => group.name).join(' · ') || '기본 옵션'}</small></div>
              <span className={`status-pill ${drop.status.toLowerCase()}`}>{drop.status}</span>
              <div><small>WISH</small><b>{drop.wishCount}</b></div>
              <div><small>재고</small><b>{stock(drop)}</b></div>
              <Link to={`/drops/${drop.id}`} aria-label={`${drop.name} 보기`}>↗</Link>
            </article>
          ))}
        </div>
      </section>
    </section>
  )
}

type DraftGroup = OptionGroup & { rawValues: string }

function SellerDropForm({ notify }: { notify: (message: string) => void }) {
  const navigate = useNavigate()
  const [groups, setGroups] = useState<DraftGroup[]>([
    { id: 'group-1', name: '소재', rawValues: '코튼, 린넨', values: [{ id: 'value-1', label: '코튼' }, { id: 'value-2', label: '린넨' }] },
  ])

  const parsedGroups = useMemo<OptionGroup[]>(() => groups.filter((group) => group.name.trim() && group.values.length), [groups])
  const combinations = optionCombinations(parsedGroups)

  const updateGroup = (index: number, field: 'name' | 'rawValues', value: string) => {
    setGroups((current) => current.map((group, itemIndex) => {
      if (itemIndex !== index) return group
      if (field === 'name') return { ...group, name: value }
      return {
        ...group,
        rawValues: value,
        values: value.split(',').map((item) => item.trim()).filter(Boolean).map((label, valueIndex) => ({ id: `${group.id}-value-${valueIndex}`, label })),
      }
    }))
  }

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!parsedGroups.length || !combinations.length) return
    notify('DROP 초안을 저장했어요.')
    navigate('/seller')
  }

  return (
    <section className="page-section form-page">
      <div className="breadcrumbs"><Link to="/seller">← DROP STUDIO</Link><span>/</span><span>새 DROP</span></div>
      <p className="eyebrow">ONE DROP, ONE PRODUCT</p>
      <h1>새 DROP 만들기</h1>
      <p className="page-description">상품 특성에 맞게 옵션 그룹과 선택값을 자유롭게 구성하세요.</p>
      <form onSubmit={submit}>
        <section className="form-card">
          <div className="section-number">01</div><div className="form-content"><h2>기본 정보</h2>
            <label>상품명<input required placeholder="상품 이름" /></label>
            <label>상품 설명<textarea required rows={4} placeholder="상품과 브랜드의 이야기를 적어주세요." /></label>
            <div className="form-row"><label>카테고리<select required defaultValue=""><option value="" disabled>선택</option><option>패션</option><option>리빙</option><option>문구 · 굿즈</option></select></label><label>대표 이미지<input type="file" accept="image/*" /></label></div>
          </div>
        </section>
        <section className="form-card">
          <div className="section-number">02</div><div className="form-content"><div className="form-title-row"><div><h2>옵션 구성</h2><p>색상·사이즈에 한정하지 않고 필요한 기준을 직접 만듭니다.</p></div><button type="button" className="secondary-button" onClick={() => setGroups((current) => [...current, { id: `group-${Date.now()}`, name: '', rawValues: '', values: [] }])}>＋ 그룹 추가</button></div>
            <div className="option-builder">
              {groups.map((group, index) => (
                <div className="option-group" key={group.id}>
                  <span>그룹 {index + 1}</span>
                  <label>그룹명<input required value={group.name} onChange={(event) => updateGroup(index, 'name', event.target.value)} placeholder="예: 소재, 길이, 포장" /></label>
                  <label>선택값<input required value={group.rawValues} onChange={(event) => updateGroup(index, 'rawValues', event.target.value)} placeholder="쉼표로 구분: 코튼, 린넨" /></label>
                  {groups.length > 1 && <button type="button" className="remove-button" onClick={() => setGroups((current) => current.filter((_, itemIndex) => itemIndex !== index))}>삭제</button>}
                </div>
              ))}
            </div>
            <div className="sku-table"><div className="sku-heading"><strong>판매 조합(SKU)</strong><span>{combinations.length}개 조합</span></div>
              {combinations.slice(0, 12).map((combination, index) => (
                <div className="sku-row" key={JSON.stringify(combination)}><span>{selectionLabel(parsedGroups, combination) || '선택값을 입력해주세요'}</span><label>가격<input required type="number" min="0" step="100" defaultValue="29000" /></label><label>재고<input required type="number" min="0" defaultValue="10" /></label></div>
              ))}
              {combinations.length > 12 && <p className="form-hint">초안에서는 처음 12개 조합만 표시합니다. 옵션 값을 줄여주세요.</p>}
            </div>
          </div>
        </section>
        <section className="form-card">
          <div className="section-number">03</div><div className="form-content"><h2>판매 및 배송</h2><div className="form-row"><label>판매 시작<input required type="datetime-local" /></label><label>판매 종료<input required type="datetime-local" /></label></div><div className="form-row"><label>배송비<input required type="number" min="0" step="100" defaultValue="3000" /></label><label>배송 안내<input required defaultValue="결제 완료 후 3~5 영업일 이내 출고" /></label></div></div>
        </section>
        <div className="form-actions"><Link className="secondary-button" to="/seller">취소</Link><button className="primary-button">임시 저장</button></div>
      </form>
    </section>
  )
}

function Empty({ title, link, label }: { title: string; link: string; label: string }) {
  return <div className="empty-state compact"><strong>{title}</strong><Link className="secondary-button" to={link}>{label}</Link></div>
}

function NotFound() {
  return <div className="empty-state page"><strong>페이지를 찾을 수 없어요.</strong><Link className="primary-button" to="/wish">홈으로 돌아가기</Link></div>
}

function Footer() {
  return <footer><Link className="brand" to="/wish"><img src={logo} alt="" /><span>GRAB</span></Link><p>A small drop, a big tomorrow.</p><small>© 2026 GRAB · MVP FRONTEND DRAFT</small></footer>
}
