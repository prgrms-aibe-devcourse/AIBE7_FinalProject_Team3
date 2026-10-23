import { useEffect, useId, useRef, type ReactNode } from 'react'

type Props = {
  open: boolean
  title: string
  onClose: () => void
  children: ReactNode
}

// 브라우저 <dialog>의 showModal()로 연다. 포커스 가두기·Esc·뒤 페이지 비활성화는 브라우저가 처리한다.
// 닫히면 연 요소로 포커스를 돌려준다. 내용은 열려 있을 때만 그려 닫을 때 입력·오류가 함께 사라진다.
// 열림 애니메이션은 CSS가 맡는다(DESIGN.md 모달 예외, prefers-reduced-motion이면 생략).
export default function Modal({ open, title, onClose, children }: Props) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const titleId = useId()

  useEffect(() => {
    const dialog = dialogRef.current
    if (!dialog || !open) return
    const opener =
      document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null
    dialog.showModal()
    // 기본으로는 첫 버튼(닫기)에 포커스가 가므로 입력이 있으면 첫 입력으로 옮긴다
    dialog.querySelector<HTMLElement>('input, select, textarea')?.focus()
    return () => {
      if (dialog.open) dialog.close()
      opener?.focus()
    }
  }, [open])

  return (
    <dialog
      ref={dialogRef}
      className="modal"
      aria-labelledby={titleId}
      // Esc는 cancel 이벤트로 온다. 브라우저가 닫기 전에 상태로 닫아 열림 상태를 React가 쥔다.
      onCancel={(event) => {
        event.preventDefault()
        onClose()
      }}
      // 패널 바깥(배경)을 누르면 이벤트 대상이 dialog 자신이다
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      {open && (
        <div className="modal-panel">
          <div className="modal-header">
            <h2 id={titleId}>{title}</h2>
            <button
              type="button"
              className="modal-close"
              aria-label="닫기"
              onClick={onClose}
            >
              <svg
                width="18"
                height="18"
                viewBox="0 0 18 18"
                aria-hidden="true"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
              >
                <path d="M3 3l12 12M15 3L3 15" />
              </svg>
            </button>
          </div>
          {children}
        </div>
      )}
    </dialog>
  )
}
