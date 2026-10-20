import {
  deliveryStages,
  deliveryStageText,
  type DeliveryStage,
} from '../logic/deliveryStage'

type Props = {
  stage: DeliveryStage | null
  delivered: boolean
}

// GR-114: 집화완료 → 간선상차 → 간선하차 → 배송출발 → 배송완료 자동 진행을 보여준다.
export default function DeliveryStageTimeline({ stage, delivered }: Props) {
  const currentIndex = stage ? deliveryStages.indexOf(stage) : -1
  return (
    <ol className="delivery-timeline">
      {deliveryStages.map((item, index) => {
        const done = delivered || index < currentIndex
        const current = !delivered && index === currentIndex
        return (
          <li
            key={item}
            className={done ? 'done' : current ? 'current' : ''}
            aria-current={current ? 'step' : undefined}
          >
            <span className="delivery-timeline-dot" aria-hidden="true" />
            {deliveryStageText[item]}
          </li>
        )
      })}
      <li className={delivered ? 'done current' : ''}>
        <span className="delivery-timeline-dot" aria-hidden="true" />
        배송완료
      </li>
    </ol>
  )
}
