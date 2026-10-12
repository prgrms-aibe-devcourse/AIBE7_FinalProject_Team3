import { describe, expect, it } from 'vitest'
import { validateShipment } from '../shipment'

describe('validateShipment', () => {
  it('정상 입력은 통과한다', () => {
    expect(validateShipment('CJ대한통운', '123456789012')).toBeNull()
  })

  it('공백만 있으면 거부한다', () => {
    expect(validateShipment('   ', '123')).toBe('택배사를 입력하세요.')
    expect(validateShipment('CJ대한통운', '  ')).toBe('송장번호를 입력하세요.')
  })

  it('길이 제한을 넘기면 거부한다', () => {
    expect(validateShipment('가'.repeat(51), '123')).toBe(
      '택배사는 50자 이하여야 합니다.',
    )
    expect(validateShipment('CJ대한통운', '1'.repeat(101))).toBe(
      '송장번호는 100자 이하여야 합니다.',
    )
  })

  it('앞뒤 공백은 길이 계산에서 빼고 본다', () => {
    expect(validateShipment(` ${'가'.repeat(50)} `, ' 123 ')).toBeNull()
  })
})
