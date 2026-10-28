import { useState } from 'react'
import { Link } from 'react-router-dom'
import Modal from '../../../components/Modal/Modal'
import { isAdmin, isSeller, type AuthUser } from '../logic/authUser'
import NicknameForm from './NicknameForm'
import PasswordChangeForm from './PasswordChangeForm'
import ProfileImageForm from './ProfileImageForm'

type EditableRow = 'profileImage' | 'nickname' | 'password'

const MODAL_TITLES: Record<EditableRow, string> = {
  profileImage: '프로필 이미지 변경',
  nickname: '닉네임 변경',
  password: '비밀번호 변경',
}

type Props = {
  user: AuthUser
  notify: (message: string) => void
  loggingOut: boolean
  logoutError: string
  onLogout: () => void
}

// 마이페이지 계정 설정 섹션(GR-160). 행의 "변경"은 모달로 폼을 열어 페이지 배치를 밀지 않는다.
// 모달을 닫으면 폼이 언마운트되어 입력·오류도 함께 사라지고, 저장에 성공하면 닫는다.
export default function AccountSettings({
  user,
  notify,
  loggingOut,
  logoutError,
  onLogout,
}: Props) {
  const [openRow, setOpenRow] = useState<EditableRow | null>(null)
  const close = () => setOpenRow(null)

  const editButton = (row: EditableRow) => (
    <button
      type="button"
      className="secondary-button"
      aria-haspopup="dialog"
      onClick={() => setOpenRow(row)}
    >
      변경
    </button>
  )

  return (
    <section
      className="panel account-settings"
      aria-labelledby="settings-title"
    >
      <h2 id="settings-title">계정 설정</h2>
      <div
        className="settings-row"
        role="group"
        aria-labelledby="settings-profile"
      >
        <h3 id="settings-profile">프로필 이미지</h3>
        <p>JPG, PNG, WebP · 5MB 이하</p>
        {editButton('profileImage')}
      </div>
      <div
        className="settings-row"
        role="group"
        aria-labelledby="settings-nickname"
      >
        <h3 id="settings-nickname">닉네임</h3>
        <p>{user.nickname}</p>
        {editButton('nickname')}
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
      <Modal
        open={openRow !== null}
        title={openRow ? MODAL_TITLES[openRow] : ''}
        onClose={close}
      >
        {openRow === 'profileImage' && (
          <ProfileImageForm
            user={user}
            notify={notify}
            onSuccess={close}
            onCancel={close}
          />
        )}
        {openRow === 'nickname' && (
          <NicknameForm
            user={user}
            notify={notify}
            onSuccess={close}
            onCancel={close}
          />
        )}
        {openRow === 'password' && (
          <PasswordChangeForm
            notify={notify}
            onSuccess={close}
            onCancel={close}
          />
        )}
      </Modal>
    </section>
  )
}
