import * as session from './session'

export const API_BASE = '/api'

/** An RFC 7807 problem from the API, or a network failure (status 0). */
export class ApiError extends Error {
  readonly status: number
  readonly fieldErrors: Record<string, string>
  readonly retryAfterSeconds: number | null

  constructor(status: number, message: string, fieldErrors: Record<string, string> = {}, retryAfter: number | null = null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.fieldErrors = fieldErrors
    this.retryAfterSeconds = retryAfter
  }

  get isWrongPin() {
    return this.status === 403 && /transaction pin/i.test(this.message)
  }
}

export type RequestOptions = {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  /** Sends the access token; on by default. */
  auth?: boolean
  pin?: string
  idempotencyKey?: string
  headers?: Record<string, string>
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, auth = true, pin, idempotencyKey } = options
  const headers: Record<string, string> = { Accept: 'application/json', ...options.headers }
  const isForm = body instanceof FormData
  if (body !== undefined && !isForm) headers['Content-Type'] = 'application/json'
  if (pin) headers['X-Transaction-Pin'] = pin
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey

  const send = async () => {
    if (auth) {
      const token = await session.accessToken()
      if (token) headers.Authorization = `Bearer ${token}`
    }
    try {
      return await fetch(API_BASE + path, {
        method,
        headers,
        body: body === undefined ? undefined : isForm ? body : JSON.stringify(body),
      })
    } catch {
      throw new ApiError(0, 'Could not reach PayWallet. Check your connection and try again.')
    }
  }

  let response = await send()
  // The access token may have expired between the check and the call; one rotation and retry is enough.
  if (response.status === 401 && auth && session.refreshToken()) {
    if (await session.refresh()) response = await send()
  }
  if (response.status === 401 && auth) session.end()
  if (!response.ok) throw await toError(response)
  if (response.status === 204 || response.headers.get('Content-Length') === '0') return undefined as T
  const text = await response.text()
  return (text ? JSON.parse(text) : undefined) as T
}

async function toError(response: Response): Promise<ApiError> {
  let problem: { detail?: string; title?: string; errors?: Record<string, string> } = {}
  try {
    problem = await response.json()
  } catch {
    // Not a problem document (e.g. a proxy error page).
  }
  const retryAfter = Number(response.headers.get('Retry-After')) || null
  const message = problem.detail && problem.detail !== 'Invalid request'
    ? problem.detail
    : problem.errors
      ? Object.entries(problem.errors).map(([field, error]) => `${field} ${error}`).join('; ')
      : problem.title ?? fallbackMessage(response.status)
  return new ApiError(response.status, message, problem.errors ?? {}, retryAfter)
}

function fallbackMessage(status: number): string {
  if (status === 429) return 'Too many attempts. Please wait a moment and try again.'
  if (status >= 500) return 'PayWallet is having trouble right now. Please try again.'
  return 'Something went wrong. Please try again.'
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 429 && error.retryAfterSeconds) {
      const minutes = Math.ceil(error.retryAfterSeconds / 60)
      return `${error.message} Try again in ${minutes > 1 ? `${minutes} minutes` : 'a minute'}.`
    }
    return error.message
  }
  return error instanceof Error ? error.message : 'Something went wrong.'
}

export const newIdempotencyKey = () => crypto.randomUUID()
