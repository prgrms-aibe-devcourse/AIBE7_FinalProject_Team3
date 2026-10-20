import type { DropFieldError } from '../logic/sellerDropErrors'

export default function DropFieldErrors({
  field,
  errors,
}: {
  field: string
  errors: DropFieldError[]
}) {
  const matching = errors.filter(
    (error) =>
      error.field === field ||
      error.field.startsWith(`${field}.`) ||
      error.field.startsWith(`${field}[`),
  )
  if (!matching.length) return null
  return (
    <p className="form-error" role="alert">
      {matching.map((error) => error.reason).join(' ')}
    </p>
  )
}
