import { expect, test } from './fixtures'

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
  await gallery.getByLabel('다음 이미지 업로드 실패 예시').check()
  await gallery
    .locator('input[type="file"]')
    .setInputFiles({ name: 'valid.png', mimeType: 'image/png', buffer: png })
  await expect(gallery.getByText('Mock 업로드에 실패했어요.')).toBeVisible()
  await page.getByRole('button', { name: '임시 저장' }).click()
  await expect(page.getByRole('alert')).toHaveText(/업로드에 실패한 이미지/)
  await gallery.getByRole('button', { name: '다시 시도' }).click()
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

test('GALLERY와 DETAIL 합계 10장을 넘기지 않는다', async ({ page }) => {
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
    Array.from({ length: 10 }, (_, index) => ({
      name: `${index}.png`,
      mimeType: 'image/png',
      buffer: png,
    })),
  )
  await expect(gallery.getByLabel('대체 설명')).toHaveCount(10)
  await detail
    .locator('input[type="file"]')
    .setInputFiles({ name: 'extra.png', mimeType: 'image/png', buffer: png })
  await expect(detail.getByRole('alert')).toHaveText(/합계 10장/)
  await expect(detail.getByLabel('대체 설명')).toHaveCount(0)
})
