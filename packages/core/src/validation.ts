export const onlyDigits = (value: string) => value.replace(/\D/g, '')

/** CPF with valid check digits (the API only checks the length). */
export function isValidCpf(input: string): boolean {
  const cpf = onlyDigits(input)
  if (cpf.length !== 11 || /^(\d)\1{10}$/.test(cpf)) return false
  const digit = (length: number) => {
    let sum = 0
    for (let i = 0; i < length; i++) sum += Number(cpf[i]) * (length + 1 - i)
    const rest = (sum * 10) % 11
    return rest === 10 ? 0 : rest
  }
  return digit(9) === Number(cpf[9]) && digit(10) === Number(cpf[10])
}

export function formatCpf(input: string): string {
  const d = onlyDigits(input).slice(0, 11)
  return d
    .replace(/^(\d{3})(\d)/, '$1.$2')
    .replace(/^(\d{3})\.(\d{3})(\d)/, '$1.$2.$3')
    .replace(/\.(\d{3})(\d{1,2})$/, '.$1-$2')
}

export const isEmail = (value: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value.trim())

export function passwordProblem(password: string): string | undefined {
  if (password.length < 12) return 'Use at least 12 characters.'
  if (password.length > 72) return 'Use at most 72 characters.'
  return undefined
}
