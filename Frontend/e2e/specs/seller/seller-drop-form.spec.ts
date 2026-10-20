import { expect, getAuth, loginSeller, test } from '../../fixtures'

test.beforeEach(async ({ page }) => {
  await loginSeller(page)
})

declare const Buffer: {
  from(value: string, encoding: string): Uint8Array
  alloc(size: number): Uint8Array
}

const png = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lWQAAAAASUVORK5CYII=',
  'base64',
)

test('기존 DRAFT 수정 후 재진입해도 최신 값과 이미지를 유지한다', async ({
  page,
}) => {
  await page.goto('/#/seller/drops/401/edit')
  await expect(page.getByLabel('상품명')).toHaveValue('오버핏 니트 가디건')
  await page.getByLabel('상품명').fill('수정한 DROP')
  await page.getByLabel('가격', { exact: true }).fill('42000')
  await page.getByLabel('재고', { exact: true }).fill('7')
  const gallery = page.locator('.drop-image-card').first()
  await gallery.getByLabel('대체 설명').fill('수정한 대표 이미지')
  await page.getByRole('button', { name: '임시 저장' }).click()
  await expect(page.locator('.toast')).toHaveText('DROP 초안을 저장했어요.')
  await page.getByRole('link', { name: '← DROP STUDIO' }).click()
  await page
    .locator('.seller-drops-row')
    .filter({ hasText: '수정한 DROP' })
    .getByRole('link', { name: '수정', exact: true })
    .click()
  await expect(page.getByLabel('상품명')).toHaveValue('수정한 DROP')
  await expect(page.getByLabel('가격', { exact: true })).toHaveValue('42000')
  await expect(page.getByLabel('재고', { exact: true })).toHaveValue('7')
  await expect(gallery.getByLabel('대체 설명')).toHaveValue(
    '수정한 대표 이미지',
  )
  const request = page.waitForRequest(
    (request) =>
      request.method() === 'PATCH' &&
      /\/seller\/drops\/401$/.test(request.url()),
  )
  await page.getByRole('button', { name: '임시 저장' }).click()
  expect((await request).postDataJSON()).toMatchObject({
    name: '수정한 DROP',
    images: [{ altText: '수정한 대표 이미지' }],
    options: [{ unitPrice: 42000, totalQuantity: 7 }],
  })
  await expect(page.locator('.toast')).toHaveText('DROP 초안을 저장했어요.')
})

for (const publish of [false, true]) {
  test(`저장 후 상세 재조회가 실패해도 ${publish ? '공개를 진행한다' : '저장 성공과 최신 입력을 유지한다'}`, async ({
    page,
  }) => {
    await page.goto('/#/seller/drops/401/edit')
    await expect(page.getByLabel('상품명')).toHaveValue('오버핏 니트 가디건')
    let saved = false
    let failed = false
    await page.route('**/api/v1/seller/drops/401', (route) => {
      if (route.request().method() === 'PATCH') saved = true
      if (route.request().method() === 'GET' && saved && !failed) {
        failed = true
        return route.fulfill({ status: 500, json: { success: false } })
      }
      return route.fallback()
    })
    await page.getByLabel('상품명').fill('재조회 실패 후 저장한 DROP')
    await page
      .getByRole('button', {
        name: publish ? '공개' : '임시 저장',
        exact: true,
      })
      .click()
    await expect(page.locator('.toast')).toHaveText(
      publish ? 'DROP을 WISH 상태로 공개했어요.' : 'DROP 초안을 저장했어요.',
    )
    expect(failed).toBe(true)
    if (publish) {
      await expect(page).toHaveURL(/\/seller\/drops\/401$/)
      await expect(page.locator('.status-pill')).toHaveText('WISH')
    } else {
      await page.getByRole('link', { name: '← DROP STUDIO' }).click()
      await page
        .locator('.seller-drops-row')
        .filter({ hasText: '재조회 실패 후 저장한 DROP' })
        .getByRole('link', { name: '수정', exact: true })
        .click()
      await expect(page.getByLabel('상품명')).toHaveValue(
        '재조회 실패 후 저장한 DROP',
      )
    }
  })
}

test('GALLERY·DETAIL 이미지를 편집하고 저장 후 재진입 시 순서와 설명을 유지한다', async ({
  page,
}) => {
  await page.goto('/#/seller/drops/new')
  const gallery = page
    .getByRole('heading', { name: 'GALLERY 이미지' })
    .locator('..')
    .locator('..')
  const detail = page
    .getByRole('heading', { name: 'DETAIL 이미지' })
    .locator('..')
    .locator('..')
  await gallery.locator('input[type="file"]').setInputFiles([
    { name: 'first.png', mimeType: 'image/png', buffer: png },
    { name: 'second.png', mimeType: 'image/png', buffer: png },
  ])
  await detail
    .locator('input[type="file"]')
    .setInputFiles({ name: 'detail.png', mimeType: 'image/png', buffer: png })
  await gallery.getByLabel('대체 설명').nth(0).fill('첫 이미지')
  await gallery.getByLabel('대체 설명').nth(1).fill('둘째 이미지')
  await gallery.getByRole('button', { name: '아래로' }).first().click()
  await detail.getByLabel('대체 설명').fill('상세 이미지')
  await page.getByLabel('가격').first().fill('35000')
  await page.getByLabel('재고', { exact: true }).first().fill('5')
  await page.getByRole('button', { name: '임시 저장' }).click()
  await expect(page).toHaveURL(/#\/seller\/drops\/.+\/edit$/)
  await expect(gallery.getByLabel('대체 설명').nth(0)).toHaveValue(
    '둘째 이미지',
  )
  await expect(detail.getByLabel('대체 설명')).toHaveValue('상세 이미지')
  await expect(page.getByLabel('가격').first()).toHaveValue('35000')
  await page.getByRole('link', { name: '← DROP STUDIO' }).click()
  await page.goBack()
  await expect(gallery.getByLabel('대체 설명').nth(0)).toHaveValue(
    '둘째 이미지',
  )
  await expect(detail.getByLabel('대체 설명')).toHaveValue('상세 이미지')
  await expect(page.getByLabel('가격').first()).toHaveValue('35000')
})

test('이미지 검증과 업로드 실패 재시도를 안내한다', async ({ page }) => {
  await page.goto('/#/seller/drops/new')
  const gallery = page
    .getByRole('heading', { name: 'GALLERY 이미지' })
    .locator('..')
    .locator('..')
  await gallery
    .locator('input[type="file"]')
    .setInputFiles({ name: 'bad.gif', mimeType: 'image/gif', buffer: png })
  await expect(gallery.getByRole('alert')).toHaveText(/JPEG/)
  await gallery.locator('input[type="file"]').setInputFiles({
    name: 'large.png',
    mimeType: 'image/png',
    buffer: Buffer.alloc(5 * 1024 * 1024 + 1),
  })
  await expect(gallery.getByRole('alert')).toHaveText(/5MB/)
  let attempts = 0
  await page.route('https://storage.grab.test/upload/**', (route) => {
    if (++attempts > 1) return route.fallback()
    return route.fulfill({
      status: 500,
      headers: { 'Access-Control-Allow-Origin': '*' },
      json: {},
    })
  })
  await gallery
    .locator('input[type="file"]')
    .setInputFiles({ name: 'valid.png', mimeType: 'image/png', buffer: png })
  await expect(gallery.getByRole('alert')).toContainText('업로드에 실패했어요.')
  await page.getByRole('button', { name: '임시 저장' }).click()
  await expect(gallery.locator('..').getByRole('alert').last()).toHaveText(
    /업로드를 완료해주세요/,
  )
  await gallery.getByRole('button', { name: '다시 시도' }).click()
  await expect(gallery.getByRole('alert')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '임시 저장' })).toBeEnabled()
  expect(attempts).toBe(2)
  await page.getByRole('button', { name: '임시 저장' }).click()
  await expect(page).toHaveURL(/\/edit$/)
})

test('서버의 DRAFT를 조회하고 공개 DROP 편집은 막는다', async ({ page }) => {
  await page.goto('/#/seller/drops/401/edit')
  await expect(page.getByRole('heading', { name: 'DRAFT 편집' })).toBeVisible()
  await expect(page.getByRole('img', { name: '' }).first()).toBeVisible()
  await page.goto('/#/seller/drops/101/edit')
  await expect(page.getByText('편집할 DRAFT를 찾을 수 없어요.')).toBeVisible()
})

test('DETAIL만 있는 초안은 저장할 수 있지만 공개 조건은 통과하지 못한다', async ({
  page,
}) => {
  await page.goto('/#/seller/drops/new')
  const detail = page
    .getByRole('heading', { name: 'DETAIL 이미지' })
    .locator('..')
    .locator('..')
  await detail
    .locator('input[type="file"]')
    .setInputFiles({ name: 'detail.png', mimeType: 'image/png', buffer: png })
  await page.getByLabel('상품명').fill('테스트 DROP')
  await page.getByLabel('상품 설명').fill('테스트 상품 설명')
  await page.getByLabel('카테고리').selectOption({ label: '패션' })
  await page.getByLabel('판매 시작').fill('2026-10-20T10:00')
  await page.getByLabel('판매 종료').fill('2026-10-21T10:00')
  await page.getByRole('button', { name: '공개', exact: true }).click()
  await expect(
    page.getByText('공개하려면 GALLERY 이미지를 1장 이상 추가해주세요.'),
  ).toBeVisible()
  await page.getByRole('button', { name: '임시 저장' }).click()
  await expect(page).toHaveURL(/\/edit$/)
})

test('GALLERY 5장·DETAIL 15장을 각각 제한한다', async ({ page }) => {
  await page.goto('/#/seller/drops/new')
  const gallery = page
    .getByRole('heading', { name: 'GALLERY 이미지' })
    .locator('..')
    .locator('..')
  const detail = page
    .getByRole('heading', { name: 'DETAIL 이미지' })
    .locator('..')
    .locator('..')
  await gallery.locator('input[type="file"]').setInputFiles(
    Array.from({ length: 6 }, (_, index) => ({
      name: `${index}.png`,
      mimeType: 'image/png',
      buffer: png,
    })),
  )
  await expect(gallery.getByLabel('대체 설명')).toHaveCount(5)
  await expect(gallery.getByRole('alert')).toHaveText(/GALLERY.*5장/)
  await detail.locator('input[type="file"]').setInputFiles(
    Array.from({ length: 16 }, (_, index) => ({
      name: `detail-${index}.png`,
      mimeType: 'image/png',
      buffer: png,
    })),
  )
  await expect(detail.getByRole('alert')).toHaveText(/DETAIL.*15장/)
  await expect(detail.getByLabel('대체 설명')).toHaveCount(15)
  await expect(page.getByRole('button', { name: '임시 저장' })).toBeEnabled()
})

test('두 용도의 업로드가 모두 끝나야 저장할 수 있고 입력한 설명을 보존한다', async ({
  page,
}) => {
  const releases: Array<() => void> = []
  await page.route('https://storage.grab.test/upload/**', async (route) => {
    await new Promise<void>((resolve) => releases.push(resolve))
    await route.fulfill({
      status: 200,
      headers: { 'Access-Control-Allow-Origin': '*' },
      json: {},
    })
  })
  await page.goto('/#/seller/drops/new')
  const gallery = page.locator('.drop-image-card').nth(0)
  const detail = page.locator('.drop-image-card').nth(1)
  await gallery
    .locator('input[type="file"]')
    .setInputFiles({ name: 'gallery.png', mimeType: 'image/png', buffer: png })
  await detail
    .locator('input[type="file"]')
    .setInputFiles({ name: 'detail.png', mimeType: 'image/png', buffer: png })
  await expect.poll(() => releases.length).toBe(2)
  await gallery.getByLabel('대체 설명').fill('업로드 중 입력한 설명')
  const save = page.getByRole('button', { name: '임시 저장' })
  await expect(save).toBeDisabled()
  releases[0]()
  await expect(page.getByText('업로드 중...', { exact: true })).toHaveCount(1)
  await expect(save).toBeDisabled()
  releases[1]()
  await expect(save).toBeEnabled()
  await expect(gallery.getByLabel('대체 설명')).toHaveValue(
    '업로드 중 입력한 설명',
  )
  await expect(gallery.locator('img')).toHaveAttribute(
    'src',
    /^https:\/\/storage.grab.test\/images\//,
  )
})

test('업로드 중 삭제한 이미지는 요청을 취소하고 다시 추가하지 않는다', async ({
  page,
}) => {
  let release = () => {}
  let started = false
  await page.route('https://storage.grab.test/upload/**', async (route) => {
    started = true
    await new Promise<void>((resolve) => {
      release = resolve
    })
    await route.fulfill({
      status: 200,
      headers: { 'Access-Control-Allow-Origin': '*' },
      json: {},
    })
  })
  await page.goto('/#/seller/drops/new')
  const gallery = page.locator('.drop-image-card').first()
  await gallery
    .locator('input[type="file"]')
    .setInputFiles({ name: 'gallery.png', mimeType: 'image/png', buffer: png })
  await expect.poll(() => started).toBe(true)
  const canceled = page.waitForEvent('requestfailed', {
    predicate: (request) => request.url().includes('storage.grab.test/upload/'),
  })
  await gallery.getByRole('button', { name: '삭제' }).click()
  await canceled
  release()
  await expect(gallery.locator('.drop-image-item')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '임시 저장' })).toBeEnabled()
})

test('화면 이탈은 진행 중 업로드를 취소한다', async ({ page }) => {
  let release = () => {}
  let started = false
  await page.route('https://storage.grab.test/upload/**', async (route) => {
    started = true
    await new Promise<void>((resolve) => {
      release = resolve
    })
    await route.fulfill({
      status: 200,
      headers: { 'Access-Control-Allow-Origin': '*' },
      json: {},
    })
  })
  await page.goto('/#/seller/drops/new')
  await page
    .locator('.drop-image-card')
    .first()
    .locator('input[type="file"]')
    .setInputFiles({ name: 'gallery.png', mimeType: 'image/png', buffer: png })
  await expect.poll(() => started).toBe(true)
  const canceled = page.waitForEvent('requestfailed', {
    predicate: (request) => request.url().includes('storage.grab.test/upload/'),
  })
  await page.getByRole('link', { name: '← DROP STUDIO' }).click()
  await canceled
  release()
  await expect(page.locator('.drop-image-card')).toHaveCount(0)
})

test('비회원·일반 회원은 편집 상세 API를 호출하지 않는다', async ({ page }) => {
  let details = 0
  page.on('request', (request) => {
    if (/\/seller\/drops\/401$/.test(request.url())) details++
  })
  await page.goto('/#/my')
  await page.getByRole('button', { name: '로그아웃', exact: true }).click()
  await expect(
    page.getByRole('link', { name: '로그인', exact: true }),
  ).toBeVisible()
  await page.goto('/#/seller/drops/401/edit')
  await expect(page).toHaveURL(/#\/login$/)
  getAuth(page).setRoles(['USER'])
  await page.getByLabel('이메일').fill('grab@example.com')
  await page.getByLabel('비밀번호').fill('password1234')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByText('판매자 권한이 필요해요.')).toBeVisible()
  expect(details).toBe(0)
})

test('카테고리 조회가 늦어도 기존 선택을 복원하고 변경값을 저장한다', async ({
  page,
}) => {
  let release = () => {}
  const ready = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.route('**/api/v1/categories', async (route) => {
    await ready
    await route.fallback()
  })
  await page.goto('/#/seller/drops/401/edit')
  await page.reload()
  const category = page.getByLabel('카테고리')
  await expect(category).toBeDisabled()
  release()
  await expect(category).toBeEnabled()
  await expect(category).toHaveValue('1')
  await category.selectOption('2')
  const request = page.waitForRequest(
    (request) =>
      request.method() === 'PATCH' &&
      /\/seller\/drops\/401$/.test(request.url()),
  )
  await page.getByRole('button', { name: '임시 저장' }).click()
  expect((await request).postDataJSON().categoryId).toBe(2)
  await expect(page.locator('.toast')).toHaveText('DROP 초안을 저장했어요.')
  await page.reload()
  await expect(category).toHaveValue('2')
})

test('카테고리 조회 실패를 재시도하고 서버 필드 오류가 입력 가까이 표시된다', async ({
  page,
}) => {
  let failing = true
  await page.route('**/api/v1/categories', (route) =>
    failing
      ? route.fulfill({ status: 500, json: { success: false } })
      : route.fallback(),
  )
  await page.goto('/#/seller/drops/401/edit')
  await expect(page.getByText('카테고리를 불러오지 못했어요.')).toBeVisible()
  failing = false
  await page.getByRole('button', { name: '다시 시도' }).click()
  await expect(page.getByLabel('카테고리')).toBeEnabled()
  await page.route('**/api/v1/seller/drops/401', (route) => {
    if (route.request().method() !== 'PATCH') return route.fallback()
    return route.fulfill({
      status: 400,
      json: {
        success: false,
        error: {
          code: 'VALIDATION_FAILED',
          fieldErrors: [
            { field: 'name', reason: '상품명을 다시 확인해주세요.' },
            {
              field: 'images[0].altText',
              reason: '이미지 설명을 확인해주세요.',
            },
          ],
        },
      },
    })
  })
  await page.getByLabel('상품명').fill('입력 유지 테스트')
  await page.getByRole('button', { name: '임시 저장' }).click()
  await expect(
    page.getByLabel('상품명').locator('..').getByRole('alert'),
  ).toHaveText('상품명을 다시 확인해주세요.')
  await expect(page.getByText('이미지 설명을 확인해주세요.')).toBeVisible()
  await expect(page.getByLabel('상품명')).toHaveValue('입력 유지 테스트')
})

test('빈 DRAFT 생성 중 중복 제출을 막고 새로고침 후에도 서버 값이 남는다', async ({
  page,
}) => {
  let creates = 0
  let release = () => {}
  await page.route('**/api/v1/seller/drops', async (route) => {
    if (route.request().method() !== 'POST') return route.fallback()
    creates++
    await new Promise<void>((resolve) => {
      release = resolve
    })
    return route.fallback()
  })
  await page.goto('/#/seller/drops/new')
  await page.getByLabel('상품명').fill('비어 있어도 저장하는 DRAFT')
  await page.locator('form').evaluate((element) => {
    const form = element as HTMLFormElement
    const button = form.querySelector<HTMLButtonElement>(
      'button[value="draft"]',
    )!
    form.requestSubmit(button)
    form.requestSubmit(button)
  })
  await expect.poll(() => creates).toBe(1)
  await expect(page.getByLabel('상품명')).toBeDisabled()
  release()
  await expect(page).toHaveURL(/\/drops\/1001\/edit$/)
  await page.reload()
  await expect(page.getByLabel('상품명')).toHaveValue(
    '비어 있어도 저장하는 DRAFT',
  )
  expect(creates).toBe(1)
})

test('생성 후 공개 실패는 동일 ID로 재시도하고 성공 후 판매자 상세로 이동한다', async ({
  page,
}) => {
  let creates = 0
  let updates = 0
  let publishes = 0
  page.on('request', (request) => {
    if (request.method() === 'POST' && /\/seller\/drops$/.test(request.url()))
      creates++
    if (request.method() === 'PATCH') updates++
  })
  await page.route('**/api/v1/seller/drops/*/publish', (route) => {
    if (++publishes > 1) return route.fallback()
    return route.fulfill({
      status: 400,
      json: {
        success: false,
        error: {
          code: 'VALIDATION_FAILED',
          fieldErrors: [
            { field: 'options[0]', reason: '판매 옵션을 다시 확인해주세요.' },
          ],
        },
      },
    })
  })
  await page.goto('/#/seller/drops/new')
  await page.getByLabel('상품명').fill('실제 공개 흐름')
  await page.getByLabel('상품 설명').fill('저장 후 공개합니다.')
  await page.getByLabel('카테고리').selectOption({ label: '패션' })
  await page.getByLabel('판매 시작').fill('2026-10-20T10:00')
  await page.getByLabel('판매 종료').fill('2026-10-21T10:00')
  await page.getByLabel('가격', { exact: true }).fill('35000')
  await page.getByLabel('재고', { exact: true }).fill('7')
  await page
    .locator('.drop-image-card')
    .first()
    .locator('input[type="file"]')
    .setInputFiles({ name: 'gallery.png', mimeType: 'image/png', buffer: png })
  await page.getByRole('button', { name: '공개', exact: true }).click()
  await expect(page).toHaveURL(/\/drops\/1001\/edit$/)
  await expect(page.getByText('판매 옵션을 다시 확인해주세요.')).toBeVisible()
  await expect(page.getByLabel('상품명')).toHaveValue('실제 공개 흐름')
  await expect(page.getByLabel('재고', { exact: true })).toHaveValue('7')
  await page.getByRole('button', { name: '공개', exact: true }).click()
  await expect(page).toHaveURL(/\/seller\/drops\/1001$/)
  await expect(page.locator('.status-pill')).toHaveText('WISH')
  expect({ creates, updates, publishes }).toEqual({
    creates: 1,
    updates: 1,
    publishes: 2,
  })
})

test('한 용도 삭제 후 수정·재조회해도 다른 용도 이미지가 남는다', async ({
  page,
}) => {
  await page.goto('/#/seller/drops/401/edit')
  const gallery = page.locator('.drop-image-card').first()
  const detail = page.locator('.drop-image-card').nth(1)
  await detail
    .locator('input[type="file"]')
    .setInputFiles({ name: 'detail.png', mimeType: 'image/png', buffer: png })
  await detail.getByLabel('대체 설명').fill('남겨둘 상세 설명')
  await gallery.getByRole('button', { name: '삭제', exact: true }).click()
  await page.getByRole('button', { name: '임시 저장' }).click()
  await expect(page.locator('.toast')).toHaveText('DROP 초안을 저장했어요.')
  await page.reload()
  await expect(gallery.locator('.drop-image-item')).toHaveCount(0)
  await expect(detail.getByLabel('대체 설명')).toHaveValue('남겨둘 상세 설명')
})

test('빈 SKU 행을 제외한 요청 순서로 서버 오류를 표시한다', async ({
  page,
}) => {
  await page.goto('/#/seller/drops/new')
  await page.getByRole('button', { name: '＋ 그룹 추가' }).click()
  await page.getByLabel('그룹명').fill('색상')
  await page.getByLabel('선택값', { exact: true }).fill('빨강, 파랑, 초록')
  await page.getByLabel('가격', { exact: true }).nth(1).fill('0')
  await page.getByLabel('재고', { exact: true }).nth(1).fill('5')
  let submitted = 0
  await page.route('**/api/v1/seller/drops', (route) => {
    if (route.request().method() !== 'POST') return route.fallback()
    const options = route.request().postDataJSON().options
    submitted = options.length
    return route.fulfill({
      status: 400,
      json: {
        success: false,
        error: {
          code: 'VALIDATION_FAILED',
          fieldErrors: [
            {
              field: `options[${options.length - 1}].unitPrice`,
              reason: '파랑 가격을 확인해주세요.',
            },
          ],
        },
      },
    })
  })
  for (const count of [1, 2]) {
    if (count === 2) {
      await page.getByLabel('가격', { exact: true }).nth(0).fill('1000')
      await page.getByLabel('재고', { exact: true }).nth(0).fill('3')
    }
    await page.getByRole('button', { name: '임시 저장' }).click()
    const rows = page.locator('.sku-row').locator('..')
    await expect(rows.nth(1).getByRole('alert')).toHaveText(
      '파랑 가격을 확인해주세요.',
    )
    await expect(rows.nth(0).getByRole('alert')).toHaveCount(0)
    await expect(rows.nth(2).getByRole('alert')).toHaveCount(0)
    expect(submitted).toBe(count)
  }
})

test('12개를 넘는 옵션 조합도 잘라내지 않고 저장한다', async ({ page }) => {
  await page.goto('/#/seller/drops/new')
  await page.getByRole('button', { name: '＋ 그룹 추가' }).click()
  await page.getByLabel('그룹명').fill('버전')
  await page
    .getByLabel('선택값', { exact: true })
    .fill(Array.from({ length: 13 }, (_, index) => `옵션${index}`).join(', '))
  await expect(page.locator('.sku-row')).toHaveCount(13)
  for (let index = 0; index < 13; index++) {
    await page.getByLabel('가격', { exact: true }).nth(index).fill('1000')
    await page.getByLabel('재고', { exact: true }).nth(index).fill('1')
  }
  const request = page.waitForRequest(
    (request) =>
      request.method() === 'POST' && /\/seller\/drops$/.test(request.url()),
  )
  await page.getByRole('button', { name: '임시 저장' }).click()
  expect((await request).postDataJSON().options).toHaveLength(13)
  await expect(page).toHaveURL(/\/edit$/)
  await expect(page.locator('.sku-row')).toHaveCount(13)
})
