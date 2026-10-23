import { useState } from 'react'

type Props = {
  src: string | null
  name: string
  size?: number
  // 옆에 이름 글자가 없어 이미지 자체를 읽어야 할 때만 준다(프로필 이미지 모달, GR-132).
  label?: string
}

// 프로필 이미지. 없거나 불러오지 못하면 이름 첫 글자를 라임 원에 표시한다(GR-160 M00-02).
// 이름은 옆에 글자로 함께 보여 주므로 기본은 장식으로 숨긴다. 링크·버튼 안에서는 그쪽에 접근 가능한 이름을 둔다.
// label을 주면 기본 표시일 때도 같은 이름으로 읽히도록 감싼 요소를 이미지로 표시한다.
export default function Avatar({ src, name, size = 72, label }: Props) {
  // 실패한 주소를 기억해 같은 주소만 기본 표시로 바꾼다. 주소가 바뀌면 다시 불러온다.
  const [failedSrc, setFailedSrc] = useState<string | null>(null)
  const showImage = src !== null && src !== failedSrc
  const initial = Array.from(name.trim())[0] ?? ''

  return (
    <span
      className="avatar"
      {...(label
        ? { role: 'img', 'aria-label': label }
        : { 'aria-hidden': true })}
      style={{ width: size, height: size, fontSize: Math.round(size * 0.4) }}
    >
      {showImage ? (
        <img src={src} alt="" onError={() => setFailedSrc(src)} />
      ) : (
        initial
      )}
    </span>
  )
}
