import {
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent,
  type TouchEvent,
} from 'react'
import type { Drop } from '../../types/drop'
import DropCard from './DropCard'

type Props = {
  drops: Drop[]
  mode: 'WISH' | 'GRAB'
  wishes: Set<number>
  onWish: (id: number) => void
  label?: string
}

const SWIPE_THRESHOLD = 40

// 기존 목록 그리드와 같은 기준(PC 4 · 태블릿 3 · 모바일 2)을 사용한다.
const visibleCount = () => {
  if (typeof window === 'undefined') return 4
  if (window.innerWidth <= 600) return 2
  if (window.innerWidth <= 850) return 3
  return 4
}

export default function PopularRail({
  drops,
  mode,
  wishes,
  onWish,
  label,
}: Props) {
  const [index, setIndex] = useState(0)
  const [visible, setVisible] = useState(visibleCount)
  const touchStart = useRef<number | null>(null)

  useEffect(() => {
    const onResize = () => setVisible(visibleCount())
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [])

  const maxIndex = Math.max(0, drops.length - visible)
  const safeIndex = Math.min(index, maxIndex)

  const move = (delta: number) =>
    setIndex(Math.min(Math.max(safeIndex + delta, 0), maxIndex))

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'ArrowLeft') {
      event.preventDefault()
      move(-1)
    } else if (event.key === 'ArrowRight') {
      event.preventDefault()
      move(1)
    }
  }

  const onTouchStart = (event: TouchEvent<HTMLDivElement>) => {
    touchStart.current = event.touches[0]?.clientX ?? null
  }

  const onTouchEnd = (event: TouchEvent<HTMLDivElement>) => {
    if (touchStart.current === null) return
    const end = event.changedTouches[0]?.clientX ?? touchStart.current
    const delta = end - touchStart.current
    touchStart.current = null
    if (Math.abs(delta) < SWIPE_THRESHOLD) return
    move(delta < 0 ? 1 : -1)
  }

  const railStyle = {
    '--rail-index': String(safeIndex),
    '--rail-visible': String(visible),
  } as CSSProperties

  return (
    <div
      className="popular-rail"
      role="group"
      aria-roledescription="carousel"
      aria-label={label}
      tabIndex={0}
      onKeyDown={onKeyDown}
    >
      <div
        className="popular-viewport"
        onTouchStart={onTouchStart}
        onTouchEnd={onTouchEnd}
      >
        <div className="popular-track" style={railStyle}>
          {drops.map((drop, itemIndex) => {
            const inView =
              itemIndex >= safeIndex && itemIndex < safeIndex + visible
            return (
              <div className="popular-item" key={drop.id} inert={!inView}>
                <DropCard
                  drop={drop}
                  mode={mode}
                  wished={wishes.has(drop.id)}
                  onWish={onWish}
                />
              </div>
            )
          })}
        </div>
      </div>
      {maxIndex > 0 && (
        <div className="popular-info">
          <button
            type="button"
            className="secondary-button"
            onClick={() => move(-1)}
            disabled={safeIndex === 0}
          >
            〈 이전
          </button>
          <span className="popular-position" aria-live="polite">
            {safeIndex + 1} / {drops.length}
          </span>
          <button
            type="button"
            className="secondary-button"
            onClick={() => move(1)}
            disabled={safeIndex >= maxIndex}
          >
            다음 〉
          </button>
          <span className="popular-hint">터치·키보드로 이동</span>
        </div>
      )}
    </div>
  )
}
