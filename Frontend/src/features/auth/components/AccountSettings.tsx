import { useState, type Ref } from 'react'
import { Link } from 'react-router-dom'
import { isAdmin, isSeller, type AuthUser } from '../logic/authUser'
import NicknameForm from './NicknameForm'
import PasswordChangeForm from './PasswordChangeForm'

type EditableRow = 'nickname' | 'password'

type Props = {
  user: AuthUser
  notify: (message: string) => void
  loggingOut: boolean
  logoutError: string
  onLogout: () => void
  titleRef?: Ref<HTMLHeadingElement>
}

// 마이페이지 계정 설정 섹션(GR-160). 행마다 "변경"으로 그 행 안에서 폼을 펼치고 한 번에 한 행만 연다(M00-01).
// 다른 행을 열면 이전 폼이 언마운트되어 입력·오류도 함께 사라진다.
// 제목은 계정 카드의 "계정 설정" 버튼이 포커스를 옮기는 대상이다.
export default function AccountSettings({
  user,
  notify,
  loggingOut,
  logoutError,
  onLogout,
  titleRef,
}: Props) {
  const [openRow, setOpenRow] = useState<EditableRow | null>(null)
  const toggle = (row: EditableRow) =>
    setOpenRow((current) => (current === row ? null : row))

  const editButton = (row: EditableRow) => (
    <button
      type="button"
      className="secondary-button"
      aria-expanded={openRow === row}
      aria-controls={`account-settings-${row}`}
      onClick={() => toggle(row)}
    >
      {openRow === row ? '닫기' : '변경'}
    </button>
  )

  return (
    <section
      className="panel account-settings"
      aria-labelledby="settings-title"
    >
      <h2 id="settings-title" ref={titleRef} tabIndex={-1}>
        계정 설정
      </h2>
      <div
        className="settings-row"
        role="group"
        aria-labelledby="settings-profile"
      >
        <h3 id="settings-profile">프로필 이미지</h3>
        <p>프로필 이미지 관리는 API 연동 시 제공됩니다.</p>
      </div>
      <div
        className="settings-row"
        role="group"
        aria-labelledby="settings-nickname"
      >
        <h3 id="settings-nickname">닉네임</h3>
        <p>{user.nickname}</p>
        {editButton('nickname')}
        <div id="account-settings-nickname" className="settings-form">
          {openRow === 'nickname' && (
            <NicknameForm user={user} notify={notify} />
          )}
        </div>
      </div>
      {user.provider === 'LOCAL' && (
        <div
          className="settings-row"
          role="group"
          aria-labelledby="settings-password"
        >
          <h3 id="settings-password">비밀번호</h3>
          <p>현재 비밀번호를 확인한 뒤 변경해요.</p>
          {editButton('password')}
          <div id="account-settings-password" className="settings-form">
            {openRow === 'password' && <PasswordChangeForm notify={notify} />}
          </div>
        </div>
      )}
      <div
        className="settings-row"
        role="group"
        aria-labelledby="settings-seller"
      >
        <h3 id="settings-seller">판매자</h3>
        {isAdmin(user) ? (
          <>
            <p>판매자 신청을 심사할 수 있어요.</p>
            <Link className="secondary-button" to="/admin/seller-applications">
              판매자 신청 심사 ↗
            </Link>
          </>
        ) : isSeller(user) ? (
          <>
            <p>승인된 판매자예요.</p>
            <Link className="secondary-button" to="/seller">
              판매자 작업 공간 ↗
            </Link>
          </>
        ) : (
          <>
            <p>판매자로 활동하려면 신청 후 운영자 승인이 필요해요.</p>
            <Link className="secondary-button" to="/seller/apply">
              판매자 신청 ↗
            </Link>
          </>
        )}
      </div>
      <div
        className="settings-row"
        role="group"
        aria-labelledby="settings-logout"
      >
        <h3 id="settings-logout">로그아웃</h3>
        <p>이 기기에서 로그아웃해요.</p>
        <button
          type="button"
          className="text-link"
          disabled={loggingOut}
          onClick={onLogout}
        >
          {loggingOut ? '로그아웃 중…' : '로그아웃'}
        </button>
        {logoutError && (
          <div className="settings-form">
            <p className="form-error" role="alert">
              {logoutError}
            </p>
          </div>
        )}
      </div>
    </section>
  )
}
