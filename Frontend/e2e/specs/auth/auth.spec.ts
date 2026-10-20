import { expect, getAuth, login, loginAdmin, test } from '../../fixtures'

test('로그인 오류와 원래 화면 복귀', async ({ page }) => {
  await page.goto('/#/my')
  await expect(page).toHaveURL(/#\/login$/)
  await page.getByLabel('이메일').fill('grab@example.com')
  await page.getByLabel('비밀번호').fill('wrongpassword')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveText(
    '이메일 또는 비밀번호를 확인해주세요.',
  )
  await page.getByLabel('비밀번호').fill('password1234')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).toHaveURL(/#\/my$/)
  await expect(page.getByText('드롭헌터')).toBeVisible()
})

test('비활성 계정은 안내 문구를 보여 준다', async ({ page }) => {
  getAuth(page).failLogin('disabled')
  await page.goto('/#/login')
  await page.getByLabel('이메일').fill('grab@example.com')
  await page.getByLabel('비밀번호').fill('password1234')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveText('사용할 수 없는 계정이에요.')
})

test('새로고침 후 로그인 상태와 사용자 정보를 복구한다', async ({ page }) => {
  await login(page)
  await page.getByRole('link', { name: 'MY', exact: true }).click()
  await expect(page).toHaveURL(/#\/my$/)
  await page.reload()
  await expect(page).toHaveURL(/#\/my$/)
  await expect(page.getByText('드롭헌터')).toBeVisible()
  await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible()
})

test('만료된 Access Token은 한 번의 재발급 후 재시도된다', async ({ page }) => {
  await login(page)
  await page.getByRole('link', { name: 'MY', exact: true }).click()
  const before = getAuth(page).state.refreshCount
  getAuth(page).expireAccess()
  await page.reload()
  await expect(page.getByText('드롭헌터')).toBeVisible()
  expect(getAuth(page).state.refreshCount).toBe(before + 1)
})

test('재발급 실패 시 비로그인 상태가 된다', async ({ page }) => {
  await login(page)
  await page.getByRole('link', { name: 'MY', exact: true }).click()
  await expect(page).toHaveURL(/#\/my$/)
  getAuth(page).invalidateSession()
  await page.reload()
  await expect(page).toHaveURL(/#\/login$/)
})

test('로그아웃 204는 상태와 캐시를 정리한다', async ({ page }) => {
  await login(page)
  await page.getByRole('link', { name: 'MY', exact: true }).click()
  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page).toHaveURL(/#\/wish$/)
  await expect(page.getByRole('link', { name: '로그인' })).toBeVisible()
  await page.goto('/#/my')
  await expect(page).toHaveURL(/#\/login$/)
})

test('로그아웃 500은 상태를 유지하고 실패를 안내한다', async ({ page }) => {
  await login(page)
  getAuth(page).failLogout()
  await page.getByRole('link', { name: 'MY', exact: true }).click()
  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page.getByRole('alert')).toHaveText(
    '로그아웃하지 못했어요. 잠시 뒤 다시 시도해주세요.',
  )
  await expect(page).toHaveURL(/#\/my$/)
  await expect(page.getByText('드롭헌터')).toBeVisible()
})

test('계정 유형에 따라 마이페이지 링크가 달라진다', async ({ page }) => {
  getAuth(page).setRoles(['USER', 'SELLER'])
  await login(page)
  await page.getByRole('link', { name: 'MY', exact: true }).click()
  await expect(page.getByText('LOCAL 계정 · 승인된 판매자')).toBeVisible()
  await expect(
    page.getByRole('link', { name: '판매자 작업 공간 ↗' }),
  ).toBeVisible()
})

test('운영자 계정은 심사 링크를 본다', async ({ page }) => {
  await loginAdmin(page)
  await page.getByRole('link', { name: 'MY', exact: true }).click()
  await expect(page.getByText('LOCAL 계정 · 운영자')).toBeVisible()
  await expect(
    page.getByRole('link', { name: '판매자 신청 심사 ↗' }),
  ).toBeVisible()
})

test('요청에 CSRF 헤더·쿠키가 실리고 토큰은 저장소에 없다', async ({
  page,
}) => {
  const apiRequests: { path: string; headers: Record<string, string> }[] = []
  page.on('request', (request) => {
    if (request.url().includes('/api/v1/')) {
      apiRequests.push({
        path: new URL(request.url()).pathname,
        headers: request.headers(),
      })
    }
  })
  await login(page)
  const loginRequest = apiRequests.find(
    (request) => request.path === '/api/v1/auth/login',
  )
  expect(loginRequest?.headers['x-xsrf-token']).toBeTruthy()
  expect(loginRequest?.headers['cookie']).toContain('XSRF-TOKEN=')

  const storage = await page.evaluate(() => ({
    local: Object.keys(localStorage),
    session: Object.keys(sessionStorage),
    cookie: document.cookie,
  }))
  expect(storage.local).toHaveLength(0)
  expect(storage.session).toHaveLength(0)
  expect(storage.cookie).not.toContain('access_token')
  expect(storage.cookie).not.toContain('refresh_token')
})
