import {
  accountMenuButton,
  expect,
  getAuth,
  goToMyPage,
  login,
  loginSeller,
  test,
  toast,
  type Page,
} from '../../fixtures'

// 마이페이지 프로필 이미지 등록·변경·삭제와 헤더 계정 메뉴(GR-132).
// 발급·등록·삭제는 installAuthApi, Storage 업로드는 installDropImageUploads가 처리한다.
// 오류는 failAccount('profileImageUploadUrl' | 'profileImage' | 'profileImageDelete', ...)로 바꾸고,
// 요청 순서·CSRF는 accountRequests로 확인한다.

// tsconfig에 Node 타입이 없어 seller-drop-form.spec.ts처럼 필요한 Buffer 메서드만 선언한다
declare const Buffer: {
  from(value: string, encoding: string): Uint8Array
  alloc(size: number): Uint8Array
}

const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lWQAAAAASUVORK5CYII=',
  'base64',
)
const UPLOAD_FAILED = '이미지를 올리지 못했어요. 잠시 뒤 다시 시도해주세요.'
const REGISTER_FAILED = '이미지를 등록하지 못했어요. 다시 시도해주세요.'
const NETWORK = '연결에 실패했어요. 잠시 뒤 다시 시도해주세요.'
const IMAGE_1 = 'https://storage.grab.test/images/profile/'

const card = (page: Page) => page.getByRole('region', { name: '내 계정' })
// 헤더 프로필 이미지는 계정 메뉴 버튼 안에 있다
const headerLink = accountMenuButton
const menuItem = (page: Page, name: '마이페이지' | '로그아웃') =>
  page
    .locator('.site-header')
    .getByRole(name === '마이페이지' ? 'link' : 'button', { name, exact: true })
const editButton = (page: Page) =>
  page
    .getByRole('group', { name: '프로필 이미지' })
    .getByRole('button', { name: '변경' })
const dialog = (page: Page) =>
  page.getByRole('dialog', { name: '프로필 이미지 변경' })
const chooseButton = (page: Page) =>
  dialog(page).getByRole('button', { name: '이미지 선택' })
const deleteButton = (page: Page) =>
  dialog(page).getByRole('button', { name: /^삭제/ })
const saveButton = (page: Page) =>
  dialog(page).getByRole('button', { name: /^저장/ })
const fileInput = (page: Page) => dialog(page).locator('input[type="file"]')
const preview = (page: Page) =>
  dialog(page).getByRole('img', { name: '새 프로필 이미지 미리보기' })
const current = (page: Page) =>
  dialog(page).getByRole('img', { name: '현재 프로필 이미지' })

const pick = (page: Page, name = 'profile.png', type = 'image/png') =>
  fileInput(page).setInputFiles({ name, mimeType: type, buffer: PNG })

const apis = (page: Page) =>
  getAuth(page).state.accountRequests.map((request) => request.api)

async function openModal(page: Page, imageUrl: string | null = null) {
  getAuth(page).state.profileImageUrl = imageUrl
  await login(page)
  await page.goto('/#/my')
  await editButton(page).click()
  await expect(dialog(page)).toBeVisible()
}

// 다음 요청 하나를 release 전까지 붙잡아 처리 중 화면을 확인한다
async function holdNext(page: Page, pattern: string) {
  let release: () => void = () => undefined
  const released = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.route(
    pattern,
    async (route) => {
      await released
      // 붙잡은 사이 브라우저가 요청을 취소했으면 넘길 곳이 없다
      await route.fallback().catch(() => undefined)
    },
    { times: 1 },
  )
  return release
}

test('이미지를 고르면 미리보기만 보이고, 저장하면 발급 → Storage → 등록 후 카드·헤더에 표시된다', async ({
  page,
}) => {
  await openModal(page)
  await expect(chooseButton(page)).toBeFocused()
  await expect(current(page)).toHaveText('드')
  await expect(deleteButton(page)).toHaveCount(0)
  await expect(saveButton(page)).toBeDisabled()

  const storage: string[] = []
  page.on('request', (request) => {
    if (request.url().includes('storage.grab.test/upload/'))
      storage.push(request.method())
  })
  await pick(page)
  await expect(preview(page).locator('img')).toHaveAttribute('src', /^blob:/)
  await expect(saveButton(page)).toBeEnabled()
  // 파일 선택만으로는 요청하지 않는다
  expect(apis(page)).toEqual([])
  expect(storage).toEqual([])

  await saveButton(page).click()
  await expect(dialog(page)).toBeHidden()
  await expect(toast(page)).toHaveText('프로필 이미지를 변경했어요.')
  expect(apis(page)).toEqual(['profileImageUploadUrl', 'profileImage'])
  expect(storage).toEqual(['PUT'])
  const [issue, register] = getAuth(page).state.accountRequests
  expect(issue.body).toEqual({
    fileName: 'profile.png',
    contentType: 'image/png',
    fileSize: PNG.length,
  })
  // 발급 응답의 imageUrl을 가공하지 않고 등록한다
  const imageUrl = getAuth(page).state.profileImageUrl
  expect(imageUrl).toMatch(new RegExp(`^${IMAGE_1}.+\\.png$`))
  expect(register.body).toEqual({ imageUrl })
  for (const request of getAuth(page).state.accountRequests) {
    expect(request.xsrf).toBe('e2e-csrf')
  }

  await expect(editButton(page)).toBeFocused()
  await expect(card(page).locator('.avatar img')).toHaveAttribute(
    'src',
    imageUrl!,
  )
  await expect(headerLink(page).locator('.avatar img')).toHaveAttribute(
    'src',
    imageUrl!,
  )

  await page.reload()
  await expect(card(page).locator('.avatar img')).toHaveAttribute(
    'src',
    imageUrl!,
  )
  await expect(headerLink(page).locator('.avatar img')).toHaveAttribute(
    'src',
    imageUrl!,
  )
})

test('등록된 이미지를 다른 이미지로 바꾼다', async ({ page }) => {
  const before = `${IMAGE_1}before.png`
  await openModal(page, before)
  await expect(current(page).locator('img')).toHaveAttribute('src', before)
  await pick(page, 'next.webp', 'image/webp')
  await saveButton(page).click()
  await expect(dialog(page)).toBeHidden()

  const after = getAuth(page).state.profileImageUrl
  expect(after).not.toBe(before)
  await expect(card(page).locator('.avatar img')).toHaveAttribute('src', after!)
  await expect(headerLink(page).locator('.avatar img')).toHaveAttribute(
    'src',
    after!,
  )
})

test('삭제는 확인 없이 바로 지우고 기본 표시로 돌아간다', async ({ page }) => {
  await openModal(page, `${IMAGE_1}before.png`)
  await deleteButton(page).click()
  await expect(dialog(page)).toBeHidden()
  await expect(toast(page)).toHaveText('프로필 이미지를 삭제했어요.')
  expect(apis(page)).toEqual(['profileImageDelete'])
  expect(getAuth(page).state.accountRequests[0].xsrf).toBe('e2e-csrf')

  await expect(card(page).locator('.avatar')).toHaveText('드')
  await expect(card(page).locator('.avatar img')).toHaveCount(0)
  await expect(headerLink(page).locator('.avatar')).toHaveText('드')

  await page.reload()
  await expect(card(page).locator('.avatar img')).toHaveCount(0)
  await editButton(page).click()
  // 이미지가 없으면 삭제 버튼을 두지 않는다
  await expect(deleteButton(page)).toHaveCount(0)
})

test('새 파일을 고른 뒤 삭제하면 현재 등록된 이미지를 지운다', async ({
  page,
}) => {
  await openModal(page, `${IMAGE_1}before.png`)
  await pick(page)
  await deleteButton(page).click()
  await expect(dialog(page)).toBeHidden()
  expect(apis(page)).toEqual(['profileImageDelete'])
  await expect(card(page).locator('.avatar img')).toHaveCount(0)
})

test('취소·Esc로 닫으면 고른 파일을 버리고 요청하지 않는다', async ({
  page,
}) => {
  await openModal(page)
  await pick(page)
  await dialog(page).getByRole('button', { name: '취소' }).click()
  await expect(dialog(page)).toBeHidden()
  await expect(editButton(page)).toBeFocused()

  await editButton(page).click()
  await expect(preview(page)).toHaveCount(0)
  await expect(current(page)).toBeVisible()
  await pick(page)
  await page.keyboard.press('Escape')
  await expect(dialog(page)).toBeHidden()
  expect(apis(page)).toEqual([])
  await expect(card(page).locator('.avatar img')).toHaveCount(0)
})

test('허용하지 않는 형식·크기·파일명은 요청 없이 안내한다', async ({
  page,
}) => {
  await openModal(page)
  const alert = dialog(page).getByRole('alert')

  await pick(page, 'profile.gif', 'image/gif')
  await expect(alert).toHaveText('JPG, PNG, WebP 이미지만 올릴 수 있어요.')
  await expect(preview(page)).toHaveCount(0)
  await expect(saveButton(page)).toBeDisabled()

  await fileInput(page).setInputFiles({
    name: 'large.png',
    mimeType: 'image/png',
    buffer: Buffer.alloc(5 * 1024 * 1024 + 1),
  })
  await expect(alert).toHaveText('5MB 이하의 이미지를 올려주세요.')

  await fileInput(page).setInputFiles({
    name: 'empty.png',
    mimeType: 'image/png',
    buffer: Buffer.alloc(0),
  })
  await expect(alert).toHaveText('5MB 이하의 이미지를 올려주세요.')

  await pick(page, `${'a'.repeat(252)}.png`)
  await expect(alert).toHaveText('파일명은 255자 이하로 지정해주세요.')
  await expect(saveButton(page)).toBeDisabled()
  expect(apis(page)).toEqual([])

  // 올바른 파일을 다시 고르면 안내를 지우고 저장할 수 있다
  await pick(page)
  await expect(alert).toHaveCount(0)
  await expect(saveButton(page)).toBeEnabled()
})

test('발급·Storage·등록 실패는 모달에 안내하고 기존 이미지를 유지한다', async ({
  page,
}) => {
  const before = `${IMAGE_1}before.png`
  await openModal(page, before)
  const alert = dialog(page).getByRole('alert')
  const auth = getAuth(page)

  auth.failAccount('profileImageUploadUrl', {
    status: 502,
    code: 'EXTERNAL_SERVICE_ERROR',
  })
  await pick(page)
  await saveButton(page).click()
  await expect(alert).toHaveText(UPLOAD_FAILED)

  // Storage 실패면 등록하지 않는다
  await page.route(
    'https://storage.grab.test/upload/**',
    (route) =>
      route.fulfill({
        status: 500,
        headers: { 'Access-Control-Allow-Origin': '*' },
        body: '',
      }),
    { times: 1 },
  )
  auth.state.accountRequests.length = 0
  await saveButton(page).click()
  await expect(alert).toHaveText(UPLOAD_FAILED)
  expect(apis(page)).toEqual(['profileImageUploadUrl'])

  auth.failAccount('profileImage', {
    status: 400,
    code: 'VALIDATION_FAILED',
    fieldErrors: [{ field: 'imageUrl', reason: '형식' }],
  })
  await saveButton(page).click()
  await expect(alert).toHaveText(REGISTER_FAILED)

  auth.failAccount('profileImage', 'network')
  await saveButton(page).click()
  await expect(alert).toHaveText(NETWORK)

  // 실패해도 모달은 열린 채이고 계정 카드·헤더는 기존 이미지다
  await expect(dialog(page)).toBeVisible()
  expect(auth.state.profileImageUrl).toBe(before)
  await expect(card(page).locator('.avatar img')).toHaveAttribute('src', before)
  await expect(headerLink(page).locator('.avatar img')).toHaveAttribute(
    'src',
    before,
  )

  // 다시 시도하면 성공한다
  await saveButton(page).click()
  await expect(dialog(page)).toBeHidden()
})

test('삭제 실패는 모달에 안내하고 기존 이미지를 유지한다', async ({ page }) => {
  const before = `${IMAGE_1}before.png`
  await openModal(page, before)
  getAuth(page).failAccount('profileImageDelete', {
    status: 500,
    code: 'INTERNAL_SERVER_ERROR',
  })
  await deleteButton(page).click()
  await expect(dialog(page).getByRole('alert')).toHaveText(NETWORK)
  await expect(dialog(page)).toBeVisible()
  await expect(card(page).locator('.avatar img')).toHaveAttribute('src', before)
})

test('저장 중에는 선택·삭제·저장을 막고 중복 요청하지 않는다', async ({
  page,
}) => {
  await openModal(page, `${IMAGE_1}before.png`)
  await pick(page)
  const release = await holdNext(page, '**/api/v1/users/me/profile-image')
  await saveButton(page).click()

  await expect(saveButton(page)).toHaveText('저장 중…')
  await expect(saveButton(page)).toBeDisabled()
  await expect(chooseButton(page)).toBeDisabled()
  await expect(deleteButton(page)).toBeDisabled()
  await saveButton(page).click({ force: true })

  release()
  await expect(dialog(page)).toBeHidden()
  expect(apis(page)).toEqual(['profileImageUploadUrl', 'profileImage'])
})

test('삭제 중에는 버튼을 막고 중복 요청하지 않는다', async ({ page }) => {
  await openModal(page, `${IMAGE_1}before.png`)
  const release = await holdNext(page, '**/api/v1/users/me/profile-image')
  await deleteButton(page).click()

  await expect(deleteButton(page)).toHaveText('삭제 중…')
  await expect(deleteButton(page)).toBeDisabled()
  await expect(chooseButton(page)).toBeDisabled()
  await deleteButton(page).click({ force: true })

  release()
  await expect(dialog(page)).toBeHidden()
  expect(apis(page)).toEqual(['profileImageDelete'])
})

test('업로드 중 모달을 닫으면 업로드를 멈추고 등록하지 않는다', async ({
  page,
}) => {
  await openModal(page)
  await pick(page)
  const release = await holdNext(page, 'https://storage.grab.test/upload/**')
  const uploading = page.waitForRequest((request) =>
    request.url().includes('storage.grab.test/upload/'),
  )
  await saveButton(page).click()
  await uploading
  const aborted = page.waitForEvent('requestfailed', (request) =>
    request.url().includes('storage.grab.test/upload/'),
  )
  await page.keyboard.press('Escape')
  await expect(dialog(page)).toBeHidden()
  // Storage 업로드가 취소로 끝났으므로 등록은 시작될 수 없다
  await aborted
  release()

  expect(apis(page)).toEqual(['profileImageUploadUrl'])
  await expect(card(page).locator('.avatar img')).toHaveCount(0)
})

test('등록 요청 뒤 모달을 닫으면 서버 결과를 반영한다', async ({ page }) => {
  await openModal(page)
  await pick(page)
  const release = await holdNext(page, '**/api/v1/users/me/profile-image')
  const registering = page.waitForRequest(
    (request) =>
      request.method() === 'PUT' &&
      request.url().endsWith('/api/v1/users/me/profile-image'),
  )
  await saveButton(page).click()
  await registering
  await page.keyboard.press('Escape')
  await expect(dialog(page)).toBeHidden()
  release()

  await expect(toast(page)).toHaveText('프로필 이미지를 변경했어요.')
  await expect(card(page).locator('.avatar img')).toHaveAttribute(
    'src',
    getAuth(page).state.profileImageUrl!,
  )
})

for (const provider of ['KAKAO', 'GOOGLE'] as const) {
  test(`${provider} 계정도 프로필 이미지를 등록한다`, async ({ page }) => {
    getAuth(page).setProvider(provider)
    await openModal(page)
    await pick(page)
    await saveButton(page).click()
    await expect(dialog(page)).toBeHidden()
    await expect(card(page).locator('.avatar img')).toHaveCount(1)
  })
}

test('키보드만으로 열고 선택·저장·삭제한다', async ({ page }) => {
  await login(page)
  await page.goto('/#/my')
  await editButton(page).focus()
  await page.keyboard.press('Enter')
  await expect(chooseButton(page)).toBeFocused()

  // 파일 대화상자는 브라우저가 열므로 filechooser로 대신 고른다
  const chooser = page.waitForEvent('filechooser')
  await page.keyboard.press('Enter')
  await (
    await chooser
  ).setFiles({ name: 'profile.png', mimeType: 'image/png', buffer: PNG })
  await expect(preview(page)).toBeVisible()
  await saveButton(page).focus()
  await page.keyboard.press('Enter')
  await expect(dialog(page)).toBeHidden()
  await expect(editButton(page)).toBeFocused()

  await page.keyboard.press('Enter')
  await expect(chooseButton(page)).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(deleteButton(page)).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(dialog(page)).toBeHidden()
  await expect(card(page).locator('.avatar img')).toHaveCount(0)
})

test('이미지를 불러오지 못하면 카드·헤더가 기본 표시로 바뀐다', async ({
  page,
}) => {
  await page.route(`${IMAGE_1}**`, (route) => route.abort())
  getAuth(page).state.profileImageUrl = `${IMAGE_1}broken.png`
  await login(page)
  await page.goto('/#/my')
  await expect(card(page).locator('.avatar')).toHaveText('드')
  await expect(card(page).locator('.avatar img')).toHaveCount(0)
  await expect(headerLink(page).locator('.avatar')).toHaveText('드')
})

test('헤더는 비로그인 시 로그인, 로그인 시 계정 메뉴 버튼, 판매자 작업 공간에서는 숨긴다', async ({
  page,
}) => {
  await page.goto('/')
  const header = page.locator('.site-header')
  await expect(
    header.getByRole('link', { name: '로그인', exact: true }),
  ).toBeVisible()
  await expect(headerLink(page)).toHaveCount(0)

  await loginSeller(page)
  await expect(headerLink(page).locator('.avatar')).toHaveText('드')
  await goToMyPage(page)
  await expect(page).toHaveURL(/#\/my$/)

  await page.goto('/#/seller')
  await expect(
    header.getByRole('link', { name: 'DASHBOARD', exact: true }),
  ).toBeVisible()
  await expect(headerLink(page)).toHaveCount(0)
})

test('계정 메뉴는 누르면 펼치고 Esc·바깥 클릭·화면 이동으로 닫는다', async ({
  page,
}) => {
  await login(page)
  const button = headerLink(page)
  await expect(button).toHaveAttribute('aria-expanded', 'false')
  await expect(menuItem(page, '마이페이지')).toHaveCount(0)

  await button.click()
  await expect(button).toHaveAttribute('aria-expanded', 'true')
  await expect(menuItem(page, '마이페이지')).toBeVisible()
  await expect(menuItem(page, '로그아웃')).toBeVisible()
  await button.click()
  await expect(menuItem(page, '마이페이지')).toHaveCount(0)

  // 키보드: Enter로 열고 Tab으로 항목을 오가며 Esc는 버튼으로 포커스를 돌려준다
  await button.focus()
  await page.keyboard.press('Enter')
  await page.keyboard.press('Tab')
  await expect(menuItem(page, '마이페이지')).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(menuItem(page, '로그아웃')).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(menuItem(page, '로그아웃')).toHaveCount(0)
  await expect(button).toBeFocused()

  await button.click()
  await page.locator('main').click({ position: { x: 5, y: 5 } })
  await expect(menuItem(page, '마이페이지')).toHaveCount(0)

  await button.click()
  await menuItem(page, '마이페이지').click()
  await expect(page).toHaveURL(/#\/my$/)
  await expect(menuItem(page, '마이페이지')).toHaveCount(0)
})

test('계정 메뉴의 로그아웃은 성공하면 WISH로 가고, 실패하면 안내 후 로그인 상태를 유지한다', async ({
  page,
}) => {
  await login(page)
  await page.goto('/#/grab')
  getAuth(page).failLogout()
  await headerLink(page).click()
  await menuItem(page, '로그아웃').click()
  await expect(toast(page)).toHaveText(
    '로그아웃하지 못했어요. 잠시 뒤 다시 시도해주세요.',
  )
  // 시도가 끝나면 메뉴는 닫히고 로그인 상태는 그대로다
  await expect(menuItem(page, '로그아웃')).toHaveCount(0)
  await expect(headerLink(page)).toBeVisible()
  expect(getAuth(page).state.loggedIn).toBe(true)

  getAuth(page).state.logoutFails = false
  await headerLink(page).click()
  await menuItem(page, '로그아웃').click()
  await expect(page).toHaveURL(/#\/wish$/)
  await expect(
    page.locator('.site-header').getByRole('link', { name: '로그인' }),
  ).toBeVisible()
  expect(getAuth(page).state.loggedIn).toBe(false)
})

test('서명 업로드 URL을 화면 주소·브라우저 저장소에 남기지 않는다', async ({
  page,
}) => {
  await openModal(page)
  await pick(page)
  await saveButton(page).click()
  await expect(dialog(page)).toBeHidden()

  const storage = await page.evaluate(() =>
    JSON.stringify({ ...localStorage, ...sessionStorage }),
  )
  expect(storage).not.toContain('token=fixture')
  expect(storage).not.toContain('storage.grab.test/upload')
  expect(page.url()).not.toContain('token=')
  await expect(page.locator('body')).not.toContainText('token=fixture')
})
