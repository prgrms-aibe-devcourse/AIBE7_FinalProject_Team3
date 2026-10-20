import {
  useEffect,
  useRef,
  useState,
  type ChangeEvent,
  type Dispatch,
  type SetStateAction,
} from 'react'
import { uploadDropImage } from '../../../api/seller/images'
import { imageError, moveImage, type ImageKind } from '../logic/dropImages'
import type { DraftImage } from '../logic/sellerDropForm'

type Props = {
  kind: ImageKind
  images: DraftImage[]
  onChange: Dispatch<SetStateAction<DraftImage[]>>
}

type UploadTask = { file: File; preview: string; controller: AbortController }

export default function DropImageField({ kind, images, onChange }: Props) {
  const [error, setError] = useState('')
  const tasks = useRef(new Map<string, UploadTask>())
  const uploading = images.some((image) => image.uploading)

  useEffect(() => {
    const active = tasks.current
    return () => {
      for (const task of active.values()) {
        task.controller.abort()
        URL.revokeObjectURL(task.preview)
      }
      active.clear()
    }
  }, [])

  const upload = async (id: string, task: UploadTask) => {
    try {
      const result = await uploadDropImage(task.file, task.controller.signal)
      if (tasks.current.get(id) !== task || task.controller.signal.aborted)
        return
      onChange((current) =>
        current.map((image) =>
          image.id === id
            ? {
                ...image,
                id: result.imageId,
                url: result.imageUrl,
                uploading: false,
                failed: false,
              }
            : image,
        ),
      )
      tasks.current.delete(id)
      URL.revokeObjectURL(task.preview)
    } catch {
      if (tasks.current.get(id) !== task || task.controller.signal.aborted)
        return
      onChange((current) =>
        current.map((image) =>
          image.id === id
            ? { ...image, uploading: false, failed: true }
            : image,
        ),
      )
    }
  }

  const add = (event: ChangeEvent<HTMLInputElement>) => {
    const files = [...(event.target.files ?? [])]
    event.target.value = ''
    const added: DraftImage[] = []
    setError('')
    for (const file of files) {
      const message = imageError(file, images.length + added.length, kind)
      if (message) {
        setError(message)
        break
      }
      const id = crypto.randomUUID()
      const task = {
        file,
        preview: URL.createObjectURL(file),
        controller: new AbortController(),
      }
      tasks.current.set(id, task)
      added.push({ id, url: task.preview, altText: '', uploading: true })
    }
    onChange((current) => [...current, ...added])
    for (const image of added)
      void upload(image.id, tasks.current.get(image.id)!)
  }

  const remove = (image: DraftImage) => {
    const task = tasks.current.get(image.id)
    if (task) {
      task.controller.abort()
      URL.revokeObjectURL(task.preview)
      tasks.current.delete(image.id)
    }
    onChange((current) => current.filter((item) => item.id !== image.id))
  }

  const retry = (image: DraftImage) => {
    const previous = tasks.current.get(image.id)
    if (!previous || image.uploading) return
    const task = { ...previous, controller: new AbortController() }
    tasks.current.set(image.id, task)
    onChange((current) =>
      current.map((item) =>
        item.id === image.id
          ? { ...item, uploading: true, failed: false }
          : item,
      ),
    )
    void upload(image.id, task)
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
            disabled={uploading}
            onChange={add}
          />
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
                    onChange={(event) => {
                      const altText = event.target.value
                      onChange((current) =>
                        current.map((item) =>
                          item.id === image.id ? { ...item, altText } : item,
                        ),
                      )
                    }}
                  />
                </label>
                {image.uploading && (
                  <p className="form-image-hint" role="status">
                    업로드 중...
                  </p>
                )}
                {image.failed && (
                  <p className="form-error" role="alert">
                    이미지 업로드에 실패했어요. 다시 시도하거나 삭제해주세요.
                  </p>
                )}
                <div className="drop-image-actions">
                  <button
                    type="button"
                    className="secondary-button"
                    disabled={index === 0}
                    onClick={() =>
                      onChange((current) => moveImage(current, index, -1))
                    }
                  >
                    위로
                  </button>
                  <button
                    type="button"
                    className="secondary-button"
                    disabled={index === images.length - 1}
                    onClick={() =>
                      onChange((current) => moveImage(current, index, 1))
                    }
                  >
                    아래로
                  </button>
                  {image.failed && (
                    <button
                      type="button"
                      className="secondary-button"
                      onClick={() => retry(image)}
                    >
                      다시 시도
                    </button>
                  )}
                  <button
                    type="button"
                    className="secondary-button"
                    onClick={() => remove(image)}
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
