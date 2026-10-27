import type { Page } from '@playwright/test'
import { expect, loginSeller, test } from '../../fixtures'

test.beforeEach(async ({ page }) => {
  await loginSeller(page)
})

declare const Buffer: {
  from(value: string, encoding: string): Uint8Array
}

const png = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lWQAAAAASUVORK5CYII=',
  'base64',
)
const FIRST = '00000000-0000-4000-8000-000000000001'
const SECOND = '00000000-0000-4000-8000-000000000002'
const draft = {
  name: '블랙 니트 가디건',
  description: '도톰한 블랙 니트 가디건입니다.\n앞여밈 단추가 있어요.',
  categoryId: 1,
  images: [
    { imageId: FIRST, altText: '블랙 가디건 정면' },
    { imageId: SECOND, altText: '블랙 가디건 단추' },
  ],
}

async function uploadGallery(page: Page) {
  await page.goto('/#/seller/drops/new')
  const gallery = page.locator('.drop-image-card').first()
  await gallery.locator('input[type="file"]').setInputFiles([
    { name: 'first.png', mimeType: 'image/png', buffer: png },
    { name: 'second.png', mimeType: 'image/png', buffer: png },
  ])
  await expect(gallery.getByText('업로드 중...')).toHaveCount(0)
  return gallery
}

function routeDraft(
  page: Page,
  response: { status: number; json: unknown },
  requests: unknown[] = [],
) {
  return page.route('**/api/v1/seller/drop-drafts', (route) => {
    requests.push(route.request().postDataJSON())
    return route.fulfill(response)
  })
}

test('업로드한 GALLERY 이미지로 기본 정보와 대체 설명을 바로 작성하고 저장한다', async ({
  page,
}) => {
  await page.goto('/#/seller/drops/new')
  const button = page.getByRole('button', { name: '사진으로 작성' })
  await expect(button).toBeDisabled()
  const requests: unknown[] = []
  await routeDraft(
    page,
    { status: 200, json: { success: true, data: draft } },
    requests,
  )
  const gallery = await uploadGallery(page)
  await button.click()
  await expect(page.locator('.drop-ai-draft')).toContainText(
    'AI 초안으로 작성했어요.',
  )
  expect(requests).toEqual([
    {
      images: [FIRST, SECOND].map((imageId) => ({
        imageId,
        imageUrl: `https://storage.grab.test/images/${imageId}.png`,
      })),
    },
  ])
  await expect(page.getByLabel('상품명')).toHaveValue(draft.name)
  await expect(page.getByLabel('상품 설명')).toHaveValue(draft.description)
  await expect(page.getByLabel('카테고리')).toHaveValue('1')
  await expect(gallery.getByLabel('대체 설명').nth(1)).toHaveValue(
    '블랙 가디건 단추',
  )

  const save = page.waitForRequest(
    (request) =>
      request.method() === 'POST' && /\/seller\/drops$/.test(request.url()),
  )
  await page.getByRole('button', { name: '임시 저장' }).click()
  expect((await save).postDataJSON()).toMatchObject({
    name: draft.name,
    description: draft.description,
    categoryId: 1,
    images: [
      { imageId: FIRST, altText: '블랙 가디건 정면' },
      { imageId: SECOND, altText: '블랙 가디건 단추' },
    ],
  })
})

for (const accept of [false, true])
  test(`입력한 칸은 ${accept ? '덮어쓰기를 허락하면 바꾼다' : '확인을 거절하면 두고 빈 칸만 채운다'}`, async ({
    page,
  }) => {
    await routeDraft(page, {
      status: 200,
      json: { success: true, data: { ...draft, categoryId: null } },
    })
    await uploadGallery(page)
    await page.getByLabel('상품명').fill('직접 쓴 이름')
    page.once(
      'dialog',
      (dialog) => void (accept ? dialog.accept() : dialog.dismiss()),
    )
    await page.getByRole('button', { name: '사진으로 작성' }).click()
    await expect(page.getByLabel('상품 설명')).toHaveValue(draft.description)
    await expect(page.getByLabel('상품명')).toHaveValue(
      accept ? draft.name : '직접 쓴 이름',
    )
    await expect(page.getByLabel('카테고리')).toHaveValue('')
  })

test('상품을 알아볼 수 없는 사진이면 대체 설명만 채우고 이유를 안내한다', async ({
  page,
}) => {
  await routeDraft(page, {
    status: 200,
    json: {
      success: true,
      data: {
        name: null,
        description: null,
        categoryId: null,
        images: draft.images,
      },
    },
  })
  const gallery = await uploadGallery(page)
  await page.getByRole('button', { name: '사진으로 작성' }).click()
  await expect(page.locator('.drop-ai-draft')).toContainText(
    '상품을 알아보기 어려워',
  )
  await expect(page.getByLabel('상품명')).toHaveValue('')
  await expect(gallery.getByLabel('대체 설명').first()).toHaveValue(
    '블랙 가디건 정면',
  )
})

for (const [status, code, message] of [
  [429, 'AI_DRAFT_LIMIT_EXCEEDED', /내일 다시/],
  [503, 'AI_DRAFT_UNAVAILABLE', /직접 입력해주세요/],
  [502, 'AI_DRAFT_FAILED', /잠시 후 다시/],
] as const)
  test(`${code}는 폼을 그대로 두고 안내한다`, async ({ page }) => {
    await routeDraft(page, {
      status,
      json: { success: false, error: { code, message: 'error' } },
    })
    await uploadGallery(page)
    await page.getByLabel('상품명').fill('직접 쓴 이름')
    await page.getByRole('button', { name: '사진으로 작성' }).click()
    await expect(page.locator('.drop-ai-draft').getByRole('alert')).toHaveText(
      message,
    )
    await expect(page.getByLabel('상품명')).toHaveValue('직접 쓴 이름')
    await expect(page.getByRole('button', { name: '임시 저장' })).toBeEnabled()
  })
