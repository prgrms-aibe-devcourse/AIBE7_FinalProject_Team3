import { expect, test } from '../../fixtures'

declare const Buffer: {
  from(value: string, encoding: string): Uint8Array
  alloc(size: number): Uint8Array
}

const png = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lWQAAAAASUVORK5CYII=',
  'base64',
)

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
  await expect(page.locator('form > [role="alert"]')).toHaveText(
    /업로드에 실패한 이미지/,
  )
  await gallery.getByRole('button', { name: '다시 시도' }).click()
  await expect(gallery.getByRole('alert')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '임시 저장' })).toBeEnabled()
  expect(attempts).toBe(2)
  await page.getByRole('button', { name: '임시 저장' }).click()
  await expect(page).toHaveURL(/\/edit$/)
})

test('기존 DRAFT는 GALLERY 예시로 열리고 공개 DROP 편집은 막는다', async ({
  page,
}) => {
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
  await page.getByLabel('카테고리').selectOption('패션')
  await page.getByLabel('판매 시작').fill('2026-10-20T10:00')
  await page.getByLabel('판매 종료').fill('2026-10-21T10:00')
  await page.getByRole('button', { name: '공개 조건 확인' }).click()
  await expect(page.getByRole('alert')).toHaveText(/GALLERY 이미지를 1장/)
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
