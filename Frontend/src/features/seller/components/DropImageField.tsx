import { useState, type ChangeEvent } from 'react'
import {
  imageError,
  moveImage,
  type DraftImage,
} from '../mock/mockSellerDrafts'

type Props = {
  kind: 'GALLERY' | 'DETAIL'
  images: DraftImage[]
  totalCount: number
  onChange: (images: DraftImage[]) => void
  onUploading: (uploading: boolean) => void
}

const readImage = (file: File) =>
  new Promise<string>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = () => reject(new Error('이미지를 읽지 못했어요.'))
    reader.readAsDataURL(file)
  })

export default function DropImageField({
  kind,
  images,
  totalCount,
  onChange,
  onUploading,
}: Props) {
  const [error, setError] = useState('')
  const [failExample, setFailExample] = useState(false)

  const add = async (event: ChangeEvent<HTMLInputElement>) => {
    const files = [...(event.target.files ?? [])]
    let next = [...images]
    setError('')
    onUploading(true)
    try {
      for (const file of files) {
        const message = imageError(
          file,
          totalCount + next.length - images.length,
        )
        if (message) {
          setError(message)
          break
        }
        next = [
          ...next,
          {
            id: crypto.randomUUID(),
            url: await readImage(file),
            altText: '',
            failed: failExample,
          },
        ]
      }
      onChange(next)
    } catch {
      setError('이미지를 읽지 못했어요. 다시 선택해주세요.')
    } finally {
      onUploading(false)
      event.target.value = ''
    }
  }

  return (
    <section className="form-card drop-image-card">
      <div className="section-number">{kind === 'GALLERY' ? '02' : '03'}</div>
      <div className="form-content">
        <h2>{kind} 이미지</h2>
        <p className="form-image-hint">
          {kind === 'GALLERY'
            ? '상단 갤러리 · 첫 장이 썸네일 · 공개 시 필수'
            : '하단 상세 설명 · 선택'}
        </p>
        <label className="drop-image-picker">
          이미지 추가
          <input
            type="file"
            accept="image/jpeg,image/png,image/webp"
            multiple
            onChange={add}
          />
        </label>
        <label className="drop-image-demo">
          <input
            type="checkbox"
            checked={failExample}
            onChange={(event) => setFailExample(event.target.checked)}
          />
          다음 이미지 업로드 실패 예시
        </label>
        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
        {images.length === 0 && (
          <p className="form-image-hint">아직 이미지가 없어요.</p>
        )}
        <div className="drop-image-list">
          {images.map((image, index) => (
            <div className="drop-image-item" key={image.id}>
              <img src={image.url} alt="" />
              <div className="drop-image-fields">
                <strong>
                  {index + 1}{' '}
                  {kind === 'GALLERY' && index === 0 ? '대표 이미지' : '이미지'}
                </strong>
                <label>
                  대체 설명
                  <input
                    maxLength={300}
                    value={image.altText}
                    onChange={(event) =>
                      onChange(
                        images.map((item) =>
                          item.id === image.id
                            ? { ...item, altText: event.target.value }
                            : item,
                        ),
                      )
                    }
                  />
                </label>
                {image.failed && (
                  <p className="form-error">Mock 업로드에 실패했어요.</p>
                )}
                <div className="drop-image-actions">
                  <button
                    type="button"
                    className="secondary-button"
                    disabled={index === 0}
                    onClick={() => onChange(moveImage(images, index, -1))}
                  >
                    위로
                  </button>
                  <button
                    type="button"
                    className="secondary-button"
                    disabled={index === images.length - 1}
                    onClick={() => onChange(moveImage(images, index, 1))}
                  >
                    아래로
                  </button>
                  {image.failed && (
                    <button
                      type="button"
                      className="secondary-button"
                      onClick={() =>
                        onChange(
                          images.map((item) =>
                            item.id === image.id
                              ? { ...item, failed: false }
                              : item,
                          ),
                        )
                      }
                    >
                      다시 시도
                    </button>
                  )}
                  <button
                    type="button"
                    className="secondary-button"
                    onClick={() =>
                      onChange(images.filter((item) => item.id !== image.id))
                    }
                  >
                    삭제
                  </button>
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}
