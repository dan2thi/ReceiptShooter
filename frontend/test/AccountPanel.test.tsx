import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AccountPanel from '../src/AccountPanel'
import * as api from '../src/api'

vi.mock('../src/api', () => ({
  ApiError: class extends Error { constructor(public status: number, message: string) { super(message) } },
  login: vi.fn(), logout: vi.fn(), changePassword: vi.fn(), listUsers: vi.fn(),
  createUser: vi.fn(), setUserEnabled: vi.fn(), resetUserPassword: vi.fn(),
}))

beforeEach(() => { vi.resetAllMocks() })
afterEach(() => { cleanup(); vi.restoreAllMocks() })

describe('account management', () => {
  it('shows a generic sign-in failure and clears the password field', async () => {
    const onSession = vi.fn()
    vi.mocked(api.login).mockRejectedValue(new api.ApiError(401, 'The credentials were incorrect. Sign in again.'))
    render(<AccountPanel session={null} onSession={onSession} initialMessage="" />)
    fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'member' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'wrong-password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByText(/credentials were incorrect/)).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toHaveValue('')
    expect(onSession).not.toHaveBeenCalled()
  })

  it('requires a temporary password to be changed before showing administration controls', async () => {
    const onSession = vi.fn()
    vi.mocked(api.changePassword).mockResolvedValue()
    render(<AccountPanel session={{ username: 'admin', role: 'ADMIN', mustChangePassword: true }} onSession={onSession} initialMessage="" />)
    expect(screen.getByText(/Change your temporary password before/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Settings' })).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Current password'), { target: { value: 'temporary-password' } })
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'new-password-123' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save password' }))
    await waitFor(() => expect(api.changePassword).toHaveBeenCalledWith('temporary-password', 'new-password-123'))
    expect(onSession).toHaveBeenCalledWith(null)
  })

  it('lets an administrator create, reset and disable household accounts', async () => {
    vi.mocked(api.listUsers).mockResolvedValue([
      { id: 1, username: 'admin', role: 'ADMIN', enabled: true, mustChangePassword: false },
      { id: 2, username: 'member', role: 'USER', enabled: true, mustChangePassword: false },
    ])
    vi.mocked(api.createUser).mockResolvedValue()
    vi.mocked(api.resetUserPassword).mockResolvedValue()
    vi.mocked(api.setUserEnabled).mockResolvedValue()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(<AccountPanel session={{ username: 'admin', role: 'ADMIN', mustChangePassword: false }} onSession={vi.fn()} initialMessage="" />)
    fireEvent.click(screen.getByRole('button', { name: 'Settings' }))
    fireEvent.click(screen.getByRole('button', { name: 'Manage Users' }))
    expect(await screen.findByText('member')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'new-member' } })
    fireEvent.change(screen.getByLabelText('Temporary password'), { target: { value: 'temporary-password' } })
    fireEvent.change(screen.getByLabelText('Role'), { target: { value: 'ADMIN' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create user' }))
    await waitFor(() => expect(api.createUser).toHaveBeenCalledWith('new-member', 'temporary-password', 'ADMIN'))
    fireEvent.click(screen.getByRole('button', { name: 'Reset password for member' }))
    fireEvent.change(screen.getByLabelText('New temporary password for member'), { target: { value: 'replacement-password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Set temporary password' }))
    await waitFor(() => expect(api.resetUserPassword).toHaveBeenCalledWith(2, 'replacement-password'))
    fireEvent.click(screen.getByRole('button', { name: 'Disable member' }))
    await waitFor(() => expect(api.setUserEnabled).toHaveBeenCalledWith(2, false))
  })

  it('does not show user management to a regular user', () => {
    render(<AccountPanel session={{ username: 'member', role: 'USER', mustChangePassword: false }} onSession={vi.fn()} initialMessage="" />)
    expect(screen.queryByRole('button', { name: 'Settings' })).not.toBeInTheDocument()
  })
})
