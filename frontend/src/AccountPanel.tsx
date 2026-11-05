import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import {
  ApiError, changePassword, createUser, listUsers, login, logout, resetUserPassword, setUserEnabled,
} from './api'
import type { ManagedUser, SessionUser } from './api'

function message(error: unknown): string { return error instanceof Error ? error.message : 'The request could not be completed.' }

function ManageUsers({ current, onClose, onSessionEnd }: {
  current: SessionUser; onClose: () => void; onSessionEnd: () => void
}) {
  const [users, setUsers] = useState<ManagedUser[]>([])
  const [username, setUsername] = useState('')
  const [temporaryPassword, setTemporaryPassword] = useState('')
  const [role, setRole] = useState<'ADMIN' | 'USER'>('USER')
  const [resetId, setResetId] = useState<number | null>(null)
  const [resetPassword, setResetPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  async function refresh() { setUsers(await listUsers()) }
  useEffect(() => { void refresh().catch((cause: unknown) => setError(message(cause))) }, [])

  async function create(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError(''); setNotice('')
    try {
      await createUser(username, temporaryPassword, role)
      setUsername(''); setTemporaryPassword(''); setRole('USER'); await refresh()
      setNotice('User created. They must change their temporary password at sign-in.')
    } catch (cause) { setError(message(cause)) } finally { setBusy(false) }
  }

  async function toggle(user: ManagedUser) {
    if (!window.confirm(`${user.enabled ? 'Disable' : 'Enable'} ${user.username}?`)) return
    setBusy(true); setError(''); setNotice('')
    try {
      await setUserEnabled(user.id, !user.enabled)
      if (user.username === current.username && user.enabled) { onSessionEnd(); return }
      await refresh(); setNotice('Account status updated.')
    } catch (cause) { setError(message(cause)) } finally { setBusy(false) }
  }

  async function reset(event: FormEvent, user: ManagedUser) {
    event.preventDefault(); setBusy(true); setError(''); setNotice('')
    try {
      await resetUserPassword(user.id, resetPassword)
      setResetPassword(''); setResetId(null)
      if (user.username === current.username) { onSessionEnd(); return }
      await refresh(); setNotice('Temporary password set. Existing sessions were revoked.')
    } catch (cause) { setError(message(cause)) } finally { setBusy(false) }
  }

  return <section className="user-management" aria-labelledby="manage-users-title">
    <div className="section-heading"><h3 id="manage-users-title">Manage Users</h3><button type="button" className="view-button" onClick={onClose}>Close</button></div>
    {error && <p className="notice error-notice" role="alert">{error}</p>}
    {notice && <p role="status">{notice}</p>}
    <ul className="managed-users">
      {users.map((user) => <li key={user.id}>
        <strong>{user.username}</strong> <span>{user.role} · {user.enabled ? 'Enabled' : 'Disabled'}</span>
        <div className="notice-actions">
          <button type="button" disabled={busy} onClick={() => void toggle(user)}>{user.enabled ? 'Disable' : 'Enable'} {user.username}</button>
          <button type="button" disabled={busy} onClick={() => { setResetId(user.id); setResetPassword('') }}>Reset password for {user.username}</button>
        </div>
        {resetId === user.id && <form onSubmit={(event) => void reset(event, user)} className="account-form">
          <label htmlFor={`reset-${user.id}`}>New temporary password for {user.username}</label>
          <input id={`reset-${user.id}`} type="password" autoComplete="new-password" minLength={12} required value={resetPassword} onChange={(event) => setResetPassword(event.target.value)} />
          <button type="submit" disabled={busy}>Set temporary password</button>
        </form>}
      </li>)}
    </ul>
    <h3>Add a user</h3>
    <form onSubmit={(event) => void create(event)} className="account-form">
      <label htmlFor="new-username">Username</label><input id="new-username" autoCapitalize="none" autoComplete="off" required value={username} onChange={(event) => setUsername(event.target.value)} />
      <label htmlFor="new-user-password">Temporary password</label><input id="new-user-password" type="password" autoComplete="new-password" minLength={12} required value={temporaryPassword} onChange={(event) => setTemporaryPassword(event.target.value)} />
      <label htmlFor="new-user-role">Role</label><select id="new-user-role" value={role} onChange={(event) => setRole(event.target.value as 'ADMIN' | 'USER')}><option value="USER">User</option><option value="ADMIN">Administrator</option></select>
      <button type="submit" disabled={busy}>Create user</button>
    </form>
  </section>
}

export default function AccountPanel({ session, onSession, initialMessage }: {
  session: SessionUser | null; onSession: (user: SessionUser | null) => void; initialMessage: string
}) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [showPasswordForm, setShowPasswordForm] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [managing, setManaging] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  async function signIn(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError(''); setNotice('')
    try { onSession(await login(username, password)); setPassword('') }
    catch (cause) { setError(message(cause)); setPassword('') }
    finally { setBusy(false) }
  }
  async function signOut() {
    setBusy(true); setError('')
    try { await logout(); onSession(null); setManaging(false); setSettingsOpen(false); setNotice('Signed out.') }
    catch (cause) { if (cause instanceof ApiError && cause.status === 401) onSession(null); setError(message(cause)) }
    finally { setBusy(false) }
  }
  async function updatePassword(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError(''); setNotice('')
    try {
      await changePassword(currentPassword, newPassword)
      setCurrentPassword(''); setNewPassword(''); setShowPasswordForm(false); onSession(null)
      setNotice('Password changed. Sign in again with your new password.')
    } catch (cause) { setError(message(cause)) } finally { setBusy(false) }
  }
  const endSession = () => { setManaging(false); setSettingsOpen(false); onSession(null); setNotice('Account changed. Sign in again.') }

  return <section className="upload-section account-section" aria-labelledby="account-title">
    <div><p className="eyebrow">PRIVATE SERVER</p><h2 id="account-title">{session ? 'Your account' : 'Sign in'}</h2>
      <p>{session ? `Signed in as ${session.username}.` : 'Sign in to upload or view server photos. Offline capture does not require sign-in.'}</p></div>
    {(error || initialMessage) && <p className="notice error-notice" role="alert">{error || initialMessage}</p>}
    {notice && <p role="status">{notice}</p>}
    {!session ? <form className="account-form" onSubmit={(event) => void signIn(event)}>
      <label htmlFor="login-username">Username</label><input id="login-username" autoCapitalize="none" autoComplete="username" required value={username} onChange={(event) => setUsername(event.target.value)} />
      <label htmlFor="login-password">Password</label><input id="login-password" type="password" autoComplete="current-password" required value={password} onChange={(event) => setPassword(event.target.value)} />
      <button type="submit" disabled={busy}>Sign in</button>
    </form> : <>
      {session.mustChangePassword && <p className="notice">Change your temporary password before uploading or managing users.</p>}
      {!session.mustChangePassword && <div className="notice-actions">
        <button type="button" disabled={busy} onClick={() => setShowPasswordForm(!showPasswordForm)}>Change password</button>
        {session.role === 'ADMIN' && <button type="button" disabled={busy} onClick={() => { setSettingsOpen(!settingsOpen); setManaging(false) }}>Settings</button>}
        <button type="button" disabled={busy} onClick={() => void signOut()}>Logout</button>
      </div>}
      {(showPasswordForm || session.mustChangePassword) && <form className="account-form" onSubmit={(event) => void updatePassword(event)}>
        <label htmlFor="current-password">Current password</label><input id="current-password" type="password" autoComplete="current-password" required value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} />
        <label htmlFor="change-password">New password</label><input id="change-password" type="password" autoComplete="new-password" minLength={12} required value={newPassword} onChange={(event) => setNewPassword(event.target.value)} />
        <p>Use at least 12 characters and at most 72 UTF-8 bytes.</p><button type="submit" disabled={busy}>Save password</button>
        {session.mustChangePassword && <button type="button" disabled={busy} onClick={() => void signOut()}>Logout</button>}
      </form>}
      {settingsOpen && session.role === 'ADMIN' && !session.mustChangePassword && <div className="notice-actions"><button type="button" onClick={() => setManaging(!managing)}>Manage Users</button></div>}
      {managing && session.role === 'ADMIN' && !session.mustChangePassword && <ManageUsers current={session} onClose={() => setManaging(false)} onSessionEnd={endSession} />}
    </>}
    <p>JPEG and PNG are supported. HEIC/HEIF photos remain on this device until a supported copy is available.</p>
  </section>
}
