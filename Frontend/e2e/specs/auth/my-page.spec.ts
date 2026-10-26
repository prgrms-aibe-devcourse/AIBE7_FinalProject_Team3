import { expect, login, test, type Page } from '../../fixtures'

// 마이페이지 계정 카드·주문 행(GR-160). 닉네임·비밀번호 모달은 my-account.spec.ts에서 확인한다.

const orders = (page: Page) => page.locator('.compact-item.order')
const stat = (page: Page, name: '주문' | 'WISH') =>
  page.locator('.account-stats div').filter({ hasText: name }).locator('dd')

async function openMyPage(page: Page) {
  await login(page)
  await page.goto('/#/my')
  await expect(orders(page)).toHaveCount(8)
}

test('계정 카드에 기본 프로필·역할 배지·주문/WISH 개수를 보여 준다', async ({
  page,
}) => {
  await openMyPage(page)
  const card = page.getByRole('region', { name: '내 계정' })
  // 프로필 이미지가 없으면 닉네임 첫 글자를 표시한다
  await expect(card.locator('.avatar')).toHaveText('드')
  await expect(card.locator('.avatar img')).toHaveCount(0)
  await expect(card.locator('.account-heading strong')).toHaveText('드롭헌터')
  await expect(card.locator('.account-badge')).toHaveText(['일반 회원'])
  await expect(stat(page, '주문')).toHaveText('8')
  await expect(stat(page, 'WISH')).toHaveText('1')
  // 계정 카드에는 설정 이동 버튼을 두지 않는다
  await expect(card.getByRole('button')).toHaveCount(0)
})

test('주문·WISH 조회에 실패하면 계정 카드의 개수를 비워 둔다', async ({
  page,
}) => {
  await page.route('**/api/v1/orders?*', (route) =>
    route.fulfill({
      status: 500,
      json: {
        success: false,
        data: null,
        error: { code: 'INTERNAL_SERVER_ERROR', message: '실패' },
      },
    }),
  )
  await login(page)
  await page.goto('/#/my')
  await expect(page.getByText('주문 목록을 불러오지 못했어요.')).toBeVisible()
  await expect(stat(page, '주문')).toHaveText('')
  await expect(stat(page, 'WISH')).toHaveText('1')
})

test('주문 행은 상태 배지를 두고 결제 대기만 결제하기로 이동한다', async ({
  page,
}) => {
  await openMyPage(page)
  await expect(orders(page).locator('.order-status')).toHaveText([
    '결제 대기',
    '결제 대기',
    '결제 완료',
    '배송 준비',
    '배송 중',
    '배송 완료',
    '취소 완료',
    '주문 만료',
  ])
  await expect(orders(page).locator('.order-status.pending')).toHaveCount(2)
  await expect(
    orders(page).getByRole('link', { name: /결제하기$/ }),
  ).toHaveCount(2)
  await expect(
    orders(page).getByRole('link', { name: /주문 상세$/ }),
  ).toHaveCount(6)

  await page.getByRole('link', { name: 'ORD-20261001-000001 결제하기' }).click()
  await expect(page).toHaveURL(/#\/checkout\/[^/]+$/)
  await expect(page.getByRole('button', { name: '결제하기' })).toBeVisible()
})

test('일반 회원은 계정 설정에서 판매자 신청으로 이동한다', async ({ page }) => {
  await openMyPage(page)
  const seller = page.getByRole('group', { name: '판매자' })
  await expect(seller).toContainText('신청 후 운영자 승인이 필요해요')
  await seller.getByRole('link', { name: '판매자 신청 ↗' }).click()
  await expect(page).toHaveURL(/#\/seller\/apply$/)
})
