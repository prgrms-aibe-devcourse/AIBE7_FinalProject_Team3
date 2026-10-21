import { Link, useSearchParams } from 'react-router-dom'
import logo from '../../assets/images/grab-symbol.png'
import LocalSignupForm from '../../features/auth/components/LocalSignupForm'
import SocialSignupForm from '../../features/auth/components/SocialSignupForm'
import type { AuthUser } from '../../features/auth/logic/authUser'

type Props = {
  refreshAuth: () => Promise<AuthUser | null>
  notify: (message: string) => void
}

// 공통 배치만 맡고 폼은 type 쿼리로 고른다. location.state는 새로고침하면 사라지므로 쓰지 않는다(GR-31 M00-01).
export default function SignupPage({ refreshAuth, notify }: Props) {
  const [searchParams] = useSearchParams()
  const social = searchParams.get('type') === 'social'

  return (
    <section className="auth-page signup-page">
      <div className="auth-copy">
        <p className="eyebrow">WELCOME TO GRAB</p>
        <h1>취향을 발견할 준비가 됐나요?</h1>
        <p>이메일을 확인한 뒤 닉네임과 비밀번호를 정합니다.</p>
        <img src={logo} alt="" />
      </div>
      <div className="auth-form">
        <div className="auth-stack">
          <div className="auth-heading">
            <p className="eyebrow">START YOUR COLLECTION</p>
            <h2>{social ? '소셜 계정으로 가입' : 'LOCAL 회원가입'}</h2>
          </div>
          {social ? (
            <SocialSignupForm refreshAuth={refreshAuth} notify={notify} />
          ) : (
            <LocalSignupForm notify={notify} />
          )}
          <p>
            이미 계정이 있나요?{' '}
            <Link className="text-link" to="/login">
              로그인
            </Link>
          </p>
        </div>
      </div>
    </section>
  )
}
