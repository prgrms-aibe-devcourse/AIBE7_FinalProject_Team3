import {
  useEffect,
  useRef,
  useState,
  type ChangeEvent,
  type FormEvent,
} from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  registerProfileImage,
  removeProfileImage,
  uploadProfileImage,
} from '../../../api/auth/me'
import Avatar from '../../../components/Avatar/Avatar'
import {
  PROFILE_IMAGE_TYPES,
  profileImageError,
  profileImageFailure,
} from '../logic/accountErrors'
import type { AuthUser, AuthUserSource } from '../logic/authUser'
import { authKeys } from '../logic/queryKeys'

type Props = {
  user: AuthUser
  notify: (message: string) => void
  onSuccess?: () => void
  onCancel?: () => void
}

type Selected = { file: File; previewUrl: string }

// 마이페이지 프로필 이미지 등록·변경·삭제(IMAGE_UPLOAD 1.2, MEMBER_AUTH 1.12·1.13, GR-132).
// 파일을 고르면 미리보기만 보여 주고, 저장을 눌러야 발급 → Storage 업로드 → 등록한다(M00-02).
// 성공하면 응답 값으로 users/me 캐시의 profileImageUrl만 바꿔 계정 카드·헤더도 함께 바뀐다.
// 실패하면 모달 안 한 곳에 문구를 보이고 기존 이미지는 그대로 둔다(M00-05).
export default function ProfileImageForm({
  user,
  notify,
  onSuccess,
  onCancel,
}: Props) {
  const queryClient = useQueryClient()
  const inputRef = useRef<HTMLInputElement>(null)
  // 업로드 취소와 "모달이 닫혔는지" 표시를 겸한다. 닫히면 abort된다.
  const controllerRef = useRef(new AbortController())
  const [selected, setSelected] = useState<Selected | null>(null)
  const [error, setError] = useState('')
  const save = useMutation({
    mutationFn: async ({ file, signal }: { file: File; signal: AbortSignal }) =>
      // 업로드가 실패하면 예외로 끝나 등록하지 않는다. 등록은 취소하지 않고 서버 결과를 따른다.
      registerProfileImage(await uploadProfileImage(file, signal)),
  })
  const remove = useMutation({ mutationFn: removeProfileImage })
  const busy = save.isPending || remove.isPending

  // 모달을 닫으면(언마운트) 진행 중인 발급·Storage 업로드를 멈춘다
  useEffect(() => {
    const controller = new AbortController()
    controllerRef.current = controller
    return () => controller.abort()
  }, [])

  // 미리보기 주소는 파일을 바꾸거나 모달을 닫을 때 해제한다
  useEffect(() => {
    if (!selected) return
    return () => URL.revokeObjectURL(selected.previewUrl)
  }, [selected])

  // 캐시는 users/me 응답 원본이다. 다시 조회하지 않고 profileImageUrl만 바꾼다(삭제는 null).
  const updateCache = (profileImageUrl: string | null) =>
    queryClient.setQueryData<AuthUserSource | null>(authKeys.me(), (old) =>
      old ? { ...old, profileImageUrl } : old,
    )

  // 닫힌 뒤 끝난 요청은 캐시·안내만 반영하고, 다시 연 모달을 닫지 않도록 onSuccess는 부르지 않는다
  const finish = (message: string) => {
    notify(message)
    if (!controllerRef.current.signal.aborted) onSuccess?.()
  }

  const fail = (failure: unknown) => {
    const { signal } = controllerRef.current
    if (signal.aborted) return
    setError(profileImageFailure(failure))
  }

  const choose = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    // 같은 파일을 다시 골라도 change가 오도록 비운다
    event.target.value = ''
    if (!file) return
    const invalid = profileImageError(file)
    setError(invalid)
    setSelected(
      invalid ? null : { file, previewUrl: URL.createObjectURL(file) },
    )
  }

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (busy || !selected) return
    setError('')
    const { signal } = controllerRef.current
    try {
      const image = await save.mutateAsync({ file: selected.file, signal })
      updateCache(image.profileImageUrl)
      finish('프로필 이미지를 변경했어요.')
    } catch (saveError) {
      fail(saveError)
    }
  }

  const removeImage = async () => {
    if (busy) return
    setError('')
    try {
      await remove.mutateAsync()
      updateCache(null)
      finish('프로필 이미지를 삭제했어요.')
    } catch (removeError) {
      fail(removeError)
    }
  }

  return (
    <form
      className="account-edit"
      noValidate
      onSubmit={(event) => void submit(event)}
    >
      <div className="profile-image-field">
        <Avatar
          src={selected?.previewUrl ?? user.profileImageUrl}
          name={user.nickname}
          size={96}
          label={selected ? '새 프로필 이미지 미리보기' : '현재 프로필 이미지'}
        />
        <div className="profile-image-buttons">
          {/* 모달을 열면 이 버튼에 포커스한다. 파일 입력은 숨기고 이 버튼으로 연다. */}
          <button
            type="button"
            className="secondary-button"
            data-autofocus
            disabled={busy}
            onClick={() => inputRef.current?.click()}
          >
            이미지 선택
          </button>
          {user.profileImageUrl && (
            <button
              type="button"
              className="secondary-button"
              disabled={busy}
              onClick={() => void removeImage()}
            >
              {remove.isPending ? '삭제 중…' : '삭제'}
            </button>
          )}
          <input
            ref={inputRef}
            type="file"
            accept={PROFILE_IMAGE_TYPES.join(',')}
            hidden
            onChange={choose}
          />
        </div>
      </div>
      <p className="profile-image-hint">JPG, PNG, WebP · 5MB 이하</p>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      <div className="account-edit-actions">
        {onCancel && (
          <button type="button" className="secondary-button" onClick={onCancel}>
            취소
          </button>
        )}
        <button
          type="submit"
          className="primary-button"
          disabled={busy || !selected}
        >
          {save.isPending ? '저장 중…' : '저장'}
        </button>
      </div>
    </form>
  )
}
