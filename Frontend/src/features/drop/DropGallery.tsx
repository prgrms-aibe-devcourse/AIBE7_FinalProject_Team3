import { useState } from 'react'
import type { Drop } from '../../types/drop'
import { galleryImages } from './mock/mockDetailImages'

export default function DropGallery({ drop }: { drop: Drop }) {
  const images = galleryImages(drop)
  const [selected, setSelected] = useState(0)
  const [failedImage, setFailedImage] = useState<string | null>(null)
  const image = images[selected]

  return (
    <div
      className="detail-gallery"
      role="group"
      aria-label={`${drop.name} 상품 갤러리`}
    >
      <div className="detail-media">
        {image.imageUrl && failedImage !== image.imageUrl ? (
          <img
            src={image.imageUrl}
            alt={image.altText}
            onError={() => setFailedImage(image.imageUrl)}
          />
        ) : (
          <span role="img" aria-label={`${image.altText} 이미지 없음`}>
            상품 이미지 준비 중
          </span>
        )}
        <span className={`badge ${drop.status === 'WISH' ? 'lime' : ''}`}>
          {drop.status}
        </span>
      </div>
      {images.length > 1 && (
        <div className="detail-thumbnails" aria-label="갤러리 이미지 선택">
          {images.map((item, index) => (
            <button
              key={item.imageUrl}
              type="button"
              className={selected === index ? 'selected' : ''}
              aria-label={`${index + 1}번 이미지: ${item.altText}`}
              aria-pressed={selected === index}
              onClick={() => setSelected(index)}
            >
              {item.imageUrl && failedImage !== item.imageUrl ? (
                <img
                  src={item.imageUrl}
                  alt=""
                  onError={() => setFailedImage(item.imageUrl)}
                />
              ) : (
                <span aria-hidden="true">이미지 없음</span>
              )}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
