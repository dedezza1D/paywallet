import { describe, expect, it } from 'vitest'
import { formatCpf, isValidCpf } from './validation'

describe('CPF', () => {
  it.each(['529.982.247-25', '52998224725', '111.444.777-35'])('accepts %s', (cpf) => {
    expect(isValidCpf(cpf)).toBe(true)
  })

  it.each(['529.982.247-24', '111.111.111-11', '1234567890', ''])('rejects %s', (cpf) => {
    expect(isValidCpf(cpf)).toBe(false)
  })

  it('masks while typing', () => {
    expect(formatCpf('529')).toBe('529')
    expect(formatCpf('5299822')).toBe('529.982.2')
    expect(formatCpf('52998224725')).toBe('529.982.247-25')
  })
})
