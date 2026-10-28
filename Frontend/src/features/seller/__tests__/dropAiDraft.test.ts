import { AxiosError, AxiosHeaders } from 'axios'
import { expect, test } from 'vitest'
import {
  applyDropAiDraft,
  draftOverwrites,
  draftSourceImages,
  dropAiDraftErrorMessage,
} from '../logic/dropAiDraft'

const image = (id: string, extra = {}) => ({
  id,
  url: `https://storage.grab.test/images/${id}.png`,
  altText: '',
  ...extra,
})

const draft = {
  name: '블랙 니트 가디건',
  description: '도톰한 니트입니다.',
  categoryId: 1,
  images: [
    { imageId: 'a', altText: '정면' },
    { imageId: 'z', altText: '삭제된 이미지' },
  ],
}

test('업로드가 끝난 GALLERY 이미지만 앞에서부터 3장 보낸다', () => {
  expect(
    draftSourceImages([
      image('a', { uploading: true }),
      image('b'),
      image('c', { failed: true }),
      { id: 'd', url: 'blob:http://localhost/d', altText: '' },
      image('e'),
      image('f'),
      image('g'),
    ]),
  ).toEqual(
    ['b', 'e', 'f'].map((id) => ({
      imageId: id,
      imageUrl: `https://storage.grab.test/images/${id}.png`,
    })),
  )
})

test('빈 폼에는 초안을 모두 채우고 대체 설명은 이미지 ID로 맞춘다', () => {
  const target = {
    values: { name: '', shippingFee: '3000' },
    gallery: [image('b'), image('a')],
  }
  expect(draftOverwrites(target, draft, [1, 2])).toBe(false)
  expect(applyDropAiDraft(target, draft, [1, 2], false)).toEqual({
    values: {
      name: '블랙 니트 가디건',
      description: '도톰한 니트입니다.',
      categoryId: '1',
      shippingFee: '3000',
    },
    gallery: [image('b'), image('a', { altText: '정면' })],
    filled: 4,
  })
})

test('입력한 칸은 덮어쓰기를 허락할 때만 바꾸고, 목록에 없는 카테고리와 null 항목은 채우지 않는다', () => {
  const target = {
    values: { name: '직접 쓴 이름', categoryId: '2' },
    gallery: [image('a', { altText: '직접 쓴 설명' })],
  }
  const partial = { ...draft, description: null, categoryId: 9 }
  expect(draftOverwrites(target, partial, [1, 2])).toBe(true)
  expect(applyDropAiDraft(target, partial, [1, 2], false)).toEqual({
    values: { name: '직접 쓴 이름', categoryId: '2' },
    gallery: [image('a', { altText: '직접 쓴 설명' })],
    filled: 0,
  })
  expect(applyDropAiDraft(target, partial, [1, 2], true)).toEqual({
    values: { name: '블랙 니트 가디건', categoryId: '2' },
    gallery: [image('a', { altText: '정면' })],
    filled: 2,
  })
})

test('오류 코드별로 다음 행동을 안내한다', () => {
  const error = (code: string) =>
    new AxiosError('error', code, undefined, undefined, {
      status: 400,
      statusText: '',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: { success: false, error: { code } },
    })
  expect(dropAiDraftErrorMessage(error('AI_DRAFT_LIMIT_EXCEEDED'))).toMatch(
    /내일/,
  )
  expect(dropAiDraftErrorMessage(error('AI_DRAFT_UNAVAILABLE'))).toMatch(
    /직접 입력/,
  )
  expect(dropAiDraftErrorMessage(error('VALIDATION_FAILED'))).toMatch(
    /다시 올려/,
  )
  expect(dropAiDraftErrorMessage(new Error('network'))).toMatch(/잠시 후/)
})
