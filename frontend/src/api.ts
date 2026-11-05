export interface SessionUser {
  username: string
  role: 'ADMIN' | 'USER'
  mustChangePassword: boolean
}

export interface ManagedUser extends SessionUser {
  id: number
  enabled: boolean
}

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message) }
}

async function send(path: string, options: RequestInit = {}): Promise<Response> {
  try { return await fetch(path, { ...options, credentials: 'same-origin', cache: 'no-store' }) }
  catch { throw new Error('The server could not be reached. Your photos remain on this device; try again when it is available.') }
}

async function checked(response: Response): Promise<Response> {
  if (response.ok) return response
  let detail = ''
  try {
    const body = await response.json() as { error?: unknown }
    if (typeof body.error === 'string' && body.error.length < 250) detail = body.error
  } catch { /* Some responses intentionally have no body. */ }
  const messages: Record<number, string> = {
    400: 'The server rejected the request. Check the information and try again.',
    401: 'Your session has expired or the credentials were incorrect. Sign in again.',
    403: 'The request was not permitted. Check your session and permissions.',
    409: 'This request conflicts with existing data. Your local photos were kept.',
    413: 'The receipt exceeds a server upload or image size limit. Your photos were kept.',
    429: 'Too many sign-in attempts. Please wait before trying again.',
    500: 'The server could not complete the request. Your photos were kept; retry when it is available.',
    503: 'The server is busy. Your photos were kept; try again shortly.',
  }
  if (detail === 'PASSWORD_CHANGE_REQUIRED') detail = 'Change your temporary password before continuing.'
  throw new ApiError(response.status, detail || messages[response.status] || `The server returned HTTP ${response.status}.`)
}

export async function apiRequest(path: string, options: RequestInit = {}): Promise<Response> {
  const method = (options.method ?? 'GET').toUpperCase()
  const headers = new Headers(options.headers)
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    const response = await checked(await send('/api/auth/csrf'))
    const csrf = await response.json() as { headerName?: unknown; token?: unknown }
    if (csrf.headerName !== 'X-CSRF-TOKEN' || typeof csrf.token !== 'string' || !csrf.token) {
      throw new Error('The server could not provide a valid security token.')
    }
    headers.set(csrf.headerName, csrf.token)
  }
  return checked(await send(path, { ...options, headers }))
}

export async function currentSession(): Promise<SessionUser | null> {
  try { return await (await apiRequest('/api/auth/session')).json() as SessionUser }
  catch (error) { if (error instanceof ApiError && error.status === 401) return null; throw error }
}

export async function login(username: string, password: string): Promise<SessionUser> {
  await apiRequest('/api/auth/login', { method: 'POST', body: new URLSearchParams({ username, password }) })
  const user = await currentSession()
  if (!user) throw new Error('Sign-in could not be completed. Please try again.')
  return user
}

export async function logout(): Promise<void> { await apiRequest('/api/auth/logout', { method: 'POST' }) }

export async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
  await jsonRequest('/api/auth/password', 'POST', { currentPassword, newPassword })
}

async function jsonRequest(path: string, method: string, body: unknown): Promise<Response> {
  return apiRequest(path, { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
}

export async function listUsers(): Promise<ManagedUser[]> {
  return await (await apiRequest('/api/admin/users')).json() as ManagedUser[]
}
export async function createUser(username: string, temporaryPassword: string, role: 'ADMIN' | 'USER'): Promise<void> {
  await jsonRequest('/api/admin/users', 'POST', { username, temporaryPassword, role })
}
export async function setUserEnabled(id: number, enabled: boolean): Promise<void> {
  await jsonRequest(`/api/admin/users/${id}/enabled`, 'PUT', { enabled })
}
export async function resetUserPassword(id: number, temporaryPassword: string): Promise<void> {
  await jsonRequest(`/api/admin/users/${id}/password`, 'POST', { temporaryPassword })
}
