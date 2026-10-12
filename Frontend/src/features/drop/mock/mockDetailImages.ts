import type { Drop } from '../../../types/drop'

export type DetailImage = { imageUrl: string; altText: string }

const extraGalleryImages: Record<number, DetailImage[]> = {
  102: [
    {
      imageUrl:
        'https://images.unsplash.com/photo-1577937927133-66ef06acdf18?auto=format&fit=crop&w=1200&q=85',
      altText: '데일리 머그의 손잡이와 표면 질감',
    },
  ],
  201: [
    {
      imageUrl:
        'https://images.unsplash.com/photo-1503341455253-b2e723bb3dbb?auto=format&fit=crop&w=1200&q=85',
      altText: '뮤직 클럽 티셔츠의 뒷면',
    },
  ],
}

const mockDetailImages: Record<number, DetailImage[]> = {
  102: [
    {
      imageUrl:
        'https://images.unsplash.com/photo-1514228742587-6b1558fcca3d?auto=format&fit=crop&w=1200&q=85',
      altText: '데일리 머그의 전체 형태와 유약 색상',
    },
    {
      imageUrl:
        'https://images.unsplash.com/photo-1577937927133-66ef06acdf18?auto=format&fit=crop&w=1200&q=85',
      altText: '데일리 머그의 손잡이와 표면 질감',
    },
  ],
  201: [
    {
      imageUrl:
        'https://images.unsplash.com/photo-1529139574466-a303027c1d8b?auto=format&fit=crop&w=1200&q=85',
      altText: '뮤직 클럽 티셔츠의 프린트와 소재',
    },
  ],
}

export function galleryImages(drop: Drop): DetailImage[] {
  return [
    { imageUrl: drop.image, altText: `${drop.name} 대표 이미지` },
    ...(extraGalleryImages[drop.id] ?? []),
  ]
}

export function detailImages(dropId: number): DetailImage[] {
  return mockDetailImages[dropId] ?? []
}
