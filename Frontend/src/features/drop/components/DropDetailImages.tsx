import { useState } from 'react'
import type { DetailImage } from '../mock/mockDetailImages'

function DetailPicture({ image }: { image: DetailImage }) {
  const [failed, setFailed] = useState(false)

  return failed ? (
    <div
      className="detail-picture-fallback"
      role="img"
      aria-label={image.altText}
    >
      {image.altText} 이미지 준비 중
    </div>
  ) : (
    <img
      src={image.imageUrl}
      alt={image.altText}
      loading="lazy"
      onError={() => setFailed(true)}
    />
  )
}

export default function DropDetailImages({
  images,
}: {
  images: DetailImage[]
}) {
  return images.length ? (
    <div className="detail-pictures">
      {images.map((image) => (
        <DetailPicture key={image.imageUrl} image={image} />
      ))}
    </div>
  ) : null
}
