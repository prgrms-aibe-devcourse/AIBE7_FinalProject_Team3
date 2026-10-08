import { useState, type FormEvent } from 'react'
import { validateShipment } from './shipment'

type Shipment = { carrier: string; trackingNumber: string }

type Props = {
  initial: Shipment
  submitLabel: string
  // 검증을 통과한 값만 앞뒤 공백을 지워 넘긴다
  onSubmit: (shipment: Shipment) => void
  onCancel: () => void
}

export default function ShipmentForm({
  initial,
  submitLabel,
  onSubmit,
  onCancel,
}: Props) {
  const [carrier, setCarrier] = useState(initial.carrier)
  const [trackingNumber, setTrackingNumber] = useState(initial.trackingNumber)
  const [error, setError] = useState<string | null>(null)

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const message = validateShipment(carrier, trackingNumber)
    if (message) {
      setError(message)
      return
    }
    onSubmit({ carrier: carrier.trim(), trackingNumber: trackingNumber.trim() })
  }

  return (
    <form className="shipment-form" onSubmit={submit}>
      <label>
        택배사
        <input
          value={carrier}
          maxLength={50}
          placeholder="CJ대한통운"
          onChange={(event) => setCarrier(event.target.value)}
        />
      </label>
      <label>
        송장번호
        <input
          value={trackingNumber}
          maxLength={100}
          placeholder="123456789012"
          onChange={(event) => setTrackingNumber(event.target.value)}
        />
      </label>
      <div className="shipment-actions">
        <button className="primary-button" type="submit">
          {submitLabel}
        </button>
        <button className="text-link" type="button" onClick={onCancel}>
          취소
        </button>
      </div>
      {error && (
        <p className="shipment-error" role="alert">
          {error}
        </p>
      )}
    </form>
  )
}
