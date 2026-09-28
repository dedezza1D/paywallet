import { describe, expect, it } from 'vitest'
import { date, money, parseAmount } from './format'

describe('parseAmount', () => {
  it.each([
    ['10', 10],
    ['10.5', 10.5],
    ['10,50', 10.5],
    ['1.234,56', 1234.56],
    ['R$ 1.234,56', 1234.56],
    ['0,01', 0.01],
  ])('reads %s', (input, expected) => {
    expect(parseAmount(input)).toBe(expected)
  })

  it.each(['', '0', '-5', '1,234', '12.345', 'abc', '1e3'])('rejects %s', (input) => {
    expect(parseAmount(input)).toBeNull()
  })
})

describe('formatting', () => {
  it('formats reais', () => {
    expect(money(1234.5).replace(/\s/g, ' ')).toBe('R$ 1.234,50')
  })

  it('keeps plain dates on their calendar day in any time zone', () => {
    expect(date('2026-03-01')).toBe('01 Mar 2026')
  })
})
