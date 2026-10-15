import EmptyState from '../../../components/EmptyState/EmptyState'

// 인증 상태(users/me)를 확인하는 동안 보호 화면은 리다이렉트하지 않고 로딩을 보여 준다(M00-08).
export default function AuthLoading() {
  return (
    <div role="status" aria-live="polite">
      <EmptyState title="로그인 상태를 확인하는 중이에요." variant="page" />
    </div>
  )
}
