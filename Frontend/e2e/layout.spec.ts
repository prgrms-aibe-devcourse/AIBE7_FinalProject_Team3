import { expect, login, test, type Page } from './fixtures'
import type { Locator } from '@playwright/test'

// 페이지 전체에 가로 스크롤이 생기지 않는다. 표처럼 의도적으로 안쪽에서 스크롤하는 영역은 해당하지 않는다.
async function expectNoHorizontalOverflow(page: Page) {
  const { scrollWidth, clientWidth } = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }))
  expect(scrollWidth, '가로 넘침').toBeLessThanOrEqual(clientWidth)
}

// 핵심 버튼이 보이고 화면 좌우 밖으로 잘리지 않는다
async function expectReachable(page: Page, locator: Locator) {
  await expect(locator).toBeVisible()
  const box = await locator.boundingBox()
  const width = page.viewportSize()!.width
  expect(box!.x, '왼쪽 잘림').toBeGreaterThanOrEqual(0)
  expect(box!.x + box!.width, '오른쪽 잘림').toBeLessThanOrEqual(width)
}

const pages: {
  name: string
  path: string
  actions: (page: Page) => Locator[]
}[] = [
  {
    name: 'WISH 목록',
    path: '/#/wish',
    actions: (page) => [
      page.getByRole('link', { name: 'WISH', exact: true }),
      page.getByRole('link', { name: 'GRAB', exact: true }),
      page.getByRole('link', { name: '로그인' }),
      page.getByRole('button', { name: '♡ WISH' }).first(),
    ],
  },
  {
    name: 'GRAB 목록',
    path: '/#/grab',
    actions: (page) => [page.getByRole('link', { name: 'GRAB ↗' }).first()],
  },
  {
    name: 'WISH 상세',
    path: '/#/drops/102',
    actions: (page) => [page.getByRole('button', { name: '♡ WISH 등록하기' })],
  },
  {
    name: 'GRAB 상세',
    path: '/#/drops/201',
    actions: (page) => [
      page.getByLabel('착용감'),
      page.getByLabel('수량'),
      page.getByRole('button', { name: '옵션을 선택해주세요' }),
    ],
  },
  {
    name: '로그인',
    path: '/#/login',
    actions: (page) => [page.getByRole('button', { name: '로그인' })],
  },
  {
    name: '판매자 주문',
    path: '/#/seller/orders',
    actions: (page) => [
      page.getByRole('button', { name: '결제 완료 3' }),
      page.getByRole('button', { name: '배송 완료 1' }),
      page.getByRole('button', { name: '배송 준비', exact: true }).first(),
      page.getByRole('button', { name: 'ORD-20260918-000500 주문 상세' }),
    ],
  },
  {
    name: '판매자 대시보드',
    path: '/#/seller',
    actions: (page) => [page.getByRole('link', { name: '＋ 새 DROP 만들기' })],
  },
  {
    name: '판매자 DROP 상세',
    path: '/#/seller/drops/201',
    actions: (page) => [page.getByRole('link', { name: '소비자 화면 ↗' })],
  },
  {
    name: 'DROP 등록',
    path: '/#/seller/drops/new',
    actions: (page) => [
      page.getByRole('button', { name: '＋ 그룹 추가' }),
      page.getByRole('button', { name: '임시 저장' }),
    ],
  },
]

for (const { name, path, actions } of pages) {
  test(`${name}: 가로 넘침 없이 핵심 버튼에 닿는다`, async ({ page }) => {
    await page.goto(path)
    for (const action of actions(page)) await expectReachable(page, action)
    await expectNoHorizontalOverflow(page)
  })
}

test('MY: 가로 넘침 없이 핵심 버튼에 닿는다', async ({ page }) => {
  await login(page)
  await page.getByRole('link', { name: 'MY', exact: true }).click()
  await expectReachable(page, page.getByRole('button', { name: '취소' }))
  await expectNoHorizontalOverflow(page)
})

// 입력란별 outline 재정의가 전역 :focus-visible 표시를 가리지 않는다
test('키보드 포커스가 입력란에 보인다', async ({ page }) => {
  const expectFocusRing = async (focus: Locator, ring: Locator = focus) => {
    await focus.focus()
    await expect(ring).toHaveCSS('outline-style', 'solid')
  }

  await page.goto('/#/wish')
  await expectFocusRing(
    page.getByPlaceholder('상품명 검색'),
    page.locator('.search-box'),
  )
  await expectFocusRing(page.getByLabel('정렬', { exact: true }))
  await page.goto('/#/grab')
  await expectFocusRing(page.getByLabel('품절 제외'))

  await page.goto('/#/drops/201')
  await expectFocusRing(page.getByLabel('수량'))

  await page.goto('/#/seller/drops/new')
  await expectFocusRing(page.getByLabel('상품명'))
  await expectFocusRing(page.getByLabel('상품 설명'))
  await expectFocusRing(page.getByLabel('카테고리'))
})
