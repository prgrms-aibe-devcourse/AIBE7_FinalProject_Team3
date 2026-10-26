import { useEffect, useId, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import Avatar from '../Avatar/Avatar'

type Props = {
  imageUrl: string | null
  name: string
  onLogout: () => Promise<void>
}

// 로그인 상태 헤더의 프로필 이미지 버튼과 펼침 메뉴(GR-132). 마이페이지 이동과 로그아웃을 둔다.
// 버튼이 펼침 상태(aria-expanded)를 알리는 공개(disclosure) 방식이고, 항목은 Tab으로 오간다.
// Esc·바깥 클릭·메뉴 밖으로 포커스 이동·화면 이동·항목 선택 시 닫는다.
export default function AccountMenu({ imageUrl, name, onLogout }: Props) {
  // 연 화면의 경로를 기억해 화면이 바뀌면(마이페이지 항목 선택 포함) 저절로 닫힌다
  const { pathname } = useLocation()
  const [openedAt, setOpenedAt] = useState<string | null>(null)
  const open = openedAt === pathname
  const setOpen = (value: boolean) => setOpenedAt(value ? pathname : null)
  const [loggingOut, setLoggingOut] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const menuId = useId()

  useEffect(() => {
    if (!open) return
    const closeOutside = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpenedAt(null)
    }
    document.addEventListener('pointerdown', closeOutside)
    return () => document.removeEventListener('pointerdown', closeOutside)
  }, [open])

  // 결과와 관계없이 시도가 끝나면 닫는다. 실패 안내는 onLogout(App)이 토스트로 한다.
  const logout = async () => {
    if (loggingOut) return
    setLoggingOut(true)
    try {
      await onLogout()
    } finally {
      setLoggingOut(false)
      setOpenedAt(null)
    }
  }

  return (
    <div
      ref={rootRef}
      className="account-menu"
      onKeyDown={(event) => {
        if (event.key !== 'Escape' || !open) return
        setOpen(false)
        buttonRef.current?.focus()
      }}
      onBlur={(event) => {
        // 포커스가 메뉴 밖의 다른 요소로 옮겨 가면 닫는다
        const next = event.relatedTarget as Node | null
        if (next && !rootRef.current?.contains(next)) setOpen(false)
      }}
    >
      <button
        ref={buttonRef}
        type="button"
        className="account-avatar"
        aria-label="계정 메뉴"
        aria-expanded={open}
        aria-controls={menuId}
        onClick={() => setOpen(!open)}
      >
        <Avatar src={imageUrl} name={name} size={36} />
      </button>
      {open && (
        <ul id={menuId} className="account-menu-list">
          <li>
            <Link to="/my" onClick={() => setOpen(false)}>
              마이페이지
            </Link>
          </li>
          <li>
            <button
              type="button"
              disabled={loggingOut}
              onClick={() => void logout()}
            >
              {loggingOut ? '로그아웃 중…' : '로그아웃'}
            </button>
          </li>
        </ul>
      )}
    </div>
  )
}
