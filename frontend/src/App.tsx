import { useCallback, useEffect, useRef, useState } from 'react'
import type { ChangeEvent } from 'react'
import {
  addReceiptPhoto, createReceipt, deleteReceipt, deleteReceiptPhoto,
  getReceiptDetails, listReceipts, moveReceiptPhoto, copyReceiptForEditing,
  lockReceiptForUpload, finishReceiptUpload,
} from './receiptStore'
import type { PhotoCapture, Receipt, ReceiptDetails, ReceiptPhoto } from './receiptStore'
import { getRemotePhoto, getRemotePhotos, getUploadLimits, prepareUpload, uploadReceipt } from './receiptApi'
import type { RemotePhoto } from './receiptApi'
import { applyUpdate, subscribeToUpdates } from './pwaUpdate'
import { ApiError, currentSession } from './api'
import type { SessionUser } from './api'
import AccountPanel from './AccountPanel'

function CameraIcon() {
  return <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M4 7.5h3l1.5-2h7l1.5 2h3A1.5 1.5 0 0 1 21.5 9v9A1.5 1.5 0 0 1 20 19.5H4A1.5 1.5 0 0 1 2.5 18V9A1.5 1.5 0 0 1 4 7.5Z" stroke="currentColor" strokeWidth="1.7" strokeLinejoin="round"/><circle cx="12" cy="13" r="3" stroke="currentColor" strokeWidth="1.7"/></svg>
}

function ReceiptIcon() {
  return <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M5 3.5h14v17l-2.3-1.5-2.35 1.5L12 19l-2.35 1.5L7.3 19 5 20.5v-17Z" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round"/><path d="M8.5 8h7M8.5 11.5h7M8.5 15h4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round"/></svg>
}

function TrashIcon() {
  return <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M4.5 7h15M9 7V4.5h6V7m-8.5 0 .7 12h9.6l.7-12M10 10.5v5M14 10.5v5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"/></svg>
}

const dateFormatter = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })
const dayFormatter = new Intl.DateTimeFormat(undefined, { day: 'numeric' })
const monthFormatter = new Intl.DateTimeFormat(undefined, { month: 'short' })

interface UnsavedCapture {
  receiptId: string
  isNew: boolean
  photo: PhotoCapture
}

function storageMessage(error: unknown, action: 'save' | 'load' | 'delete' | 'reorder'): string {
  if (typeof error === 'object' && error !== null && 'name' in error && error.name === 'QuotaExceededError') {
    return action === 'save'
      ? 'Your device is out of space for this photo. Free up space, then retry saving it.'
      : 'Device storage is full. Free up space and try again.'
  }
  if (error instanceof Error && error.message.includes('Close other ReceiptShooter tabs')) return error.message
  if (action === 'save') return 'This photo could not be saved. Check your browser storage settings and try again.'
  if (action === 'delete') return 'The deletion could not be completed. Please try again.'
  if (action === 'reorder') return 'The photo order could not be changed. Please try again.'
  return 'Receipt storage could not be opened. Check your browser storage settings and try again.'
}

function Notices({ error, unsaved, busy, onRetry, onDiscard }: {
  error: string
  unsaved: UnsavedCapture | null
  busy: boolean
  onRetry: () => void
  onDiscard: () => void
}) {
  return <>
    {error && <div className="notice error-notice" role="alert"><strong>Something needs attention</strong><p>{error}</p></div>}
    {unsaved && !busy && <div className="notice retry-notice"><strong>Photo not saved yet</strong><p>Retry before closing this screen, or discard this photo.</p><div className="notice-actions"><button type="button" onClick={onRetry}>Retry save</button><button type="button" className="text-button" onClick={onDiscard}>Discard photo</button></div></div>}
  </>
}

function PhotoPreview({ photo, position, count, capturedAt }: {
  photo: ReceiptPhoto
  position: number
  count: number
  capturedAt: string
}) {
  const [imageUrl, setImageUrl] = useState('')
  useEffect(() => {
    const url = URL.createObjectURL(photo.image)
    setImageUrl(url)
    return () => URL.revokeObjectURL(url)
  }, [photo.image])

  return imageUrl && <img src={imageUrl} alt={'Receipt photo ' + position + ' of ' + count + ', captured ' + dateFormatter.format(new Date(capturedAt))} />
}

function ReceiptDialog({ details, selectedId, busy, unsaved, error, secureCaptureAvailable, onSelect, onPhoto, onMove, onDelete, onDone, onRetry, onDiscard }: {
  details: ReceiptDetails
  selectedId: string
  busy: boolean
  unsaved: UnsavedCapture | null
  error: string
  secureCaptureAvailable: boolean
  onSelect: (id: string) => void
  onPhoto: (event: ChangeEvent<HTMLInputElement>) => void
  onMove: (id: string, direction: -1 | 1) => void
  onDelete: (id: string) => void
  onDone: () => void
  onRetry: () => void
  onDiscard: () => void
}) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const { receipt, photos } = details
  const index = Math.max(0, photos.findIndex((photo) => photo.id === selectedId))
  const photo = photos[index]
  const blocked = busy || !!unsaved
  const editBlocked = blocked || !!receipt.uploadLocked

  useEffect(() => { dialogRef.current?.showModal() }, [])

  return (
    <dialog ref={dialogRef} className="photo-dialog" aria-labelledby="photo-title"
      onClose={onDone} onCancel={(event) => { if (blocked) event.preventDefault() }}>
      <div className="photo-dialog-header">
        <div><p className="eyebrow">RECEIPT PHOTOS</p><h2 id="photo-title">{dateFormatter.format(new Date(receipt.capturedAt))}</h2></div>
        <button className="done-button" type="button" disabled={blocked} onClick={() => dialogRef.current?.close()}>Done</button>
      </div>
      <Notices error={error} unsaved={unsaved} busy={busy} onRetry={onRetry} onDiscard={onDiscard} />
      {receipt.uploadLocked && <p className="photo-help">This receipt is locked for an identical retry. To make changes, create an editable copy from the receipt list.</p>}
      {photo && <>
        <p className="photo-position" aria-live="polite">Photo {index + 1} of {photos.length}</p>
        <PhotoPreview key={photo.id} photo={photo} position={index + 1} count={photos.length} capturedAt={photo.capturedAt} />
        <div className="photo-controls" aria-label="Browse receipt photos">
          <button type="button" disabled={blocked || index === 0} onClick={() => onSelect(photos[index - 1].id)}>Previous photo</button>
          <button type="button" disabled={blocked || index === photos.length - 1} onClick={() => onSelect(photos[index + 1].id)}>Next photo</button>
        </div>
        <div className="photo-controls" aria-label="Edit receipt photo">
          <button type="button" disabled={editBlocked || index === 0} onClick={() => onMove(photo.id, -1)}>Move earlier</button>
          <button type="button" disabled={editBlocked || index === photos.length - 1} onClick={() => onMove(photo.id, 1)}>Move later</button>
          <button type="button" disabled={editBlocked || photos.length === 1} onClick={() => onDelete(photo.id)}>Delete photo</button>
        </div>
        {photos.length === 1 && <p className="photo-help">A receipt must keep at least one photo. To remove it, delete the receipt from the list.</p>}
      </>}
      <input ref={inputRef} className="visually-hidden" type="file" accept="image/*" capture="environment"
        onChange={onPhoto} aria-label="Choose another receipt photo" disabled={editBlocked || !secureCaptureAvailable} />
      <button className="capture-button add-photo-button" type="button" disabled={editBlocked || !secureCaptureAvailable} onClick={() => inputRef.current?.click()}>
        <CameraIcon /> {busy && unsaved ? 'Saving photo…' : 'Add Another Photo'}
      </button>
    </dialog>
  )
}

function RemotePhotoPreview({ receiptId, photo, index, count, onExpired }: {
  receiptId: string; photo: RemotePhoto; index: number; count: number; onExpired: () => void
}) {
  const [imageUrl, setImageUrl] = useState('')
  const [error, setError] = useState('')
  useEffect(() => {
    const controller = new AbortController()
    let url = ''
    setImageUrl('')
    setError('')
    void getRemotePhoto(receiptId, photo.photoId, controller.signal).then((blob) => {
      if (controller.signal.aborted) return
      url = URL.createObjectURL(blob)
      setImageUrl(url)
    }).catch((cause: unknown) => {
      if (!controller.signal.aborted) {
        if (cause instanceof ApiError && cause.status === 401) onExpired()
        else setError(cause instanceof Error ? cause.message : 'Photo could not be loaded.')
      }
    })
    return () => { controller.abort(); if (url) URL.revokeObjectURL(url) }
  }, [receiptId, photo.photoId, onExpired])
  if (error) return <p className="notice error-notice" role="alert">{error}</p>
  return imageUrl ? <img src={imageUrl} alt={`Receipt photo ${index + 1} of ${count}`} /> : <p className="photo-position">Loading photo…</p>
}

function RemoteReceiptDialog({ receipt, photos, onDone, onExpired }: {
  receipt: Receipt; photos: RemotePhoto[]; onDone: () => void; onExpired: () => void
}) {
  const ref = useRef<HTMLDialogElement>(null)
  const [index, setIndex] = useState(0)
  useEffect(() => { ref.current?.showModal() }, [])
  return <dialog ref={ref} className="photo-dialog" aria-labelledby="remote-photo-title" onClose={onDone}>
    <div className="photo-dialog-header"><div><p className="eyebrow">UPLOADED RECEIPT</p><h2 id="remote-photo-title">{dateFormatter.format(new Date(receipt.capturedAt))}</h2></div>
      <button className="done-button" type="button" onClick={() => ref.current?.close()}>Done</button></div>
    <p className="photo-position" aria-live="polite">Photo {index + 1} of {photos.length}</p>
    <RemotePhotoPreview receiptId={receipt.id} photo={photos[index]} index={index} count={photos.length} onExpired={onExpired} />
    <div className="photo-controls" aria-label="Browse receipt photos">
      <button type="button" disabled={index === 0} onClick={() => setIndex(index - 1)}>Previous photo</button>
      <button type="button" disabled={index === photos.length - 1} onClick={() => setIndex(index + 1)}>Next photo</button>
    </div>
    <p className="photo-help">Uploaded photos are loaded from your server when viewed.</p>
  </dialog>
}

export default function App() {
  const inputRef = useRef<HTMLInputElement>(null)
  const uploadGuard = useRef(false)
  const secureCaptureAvailable = typeof globalThis.crypto?.randomUUID === 'function'
  const [receipts, setReceipts] = useState<Receipt[]>([])
  const [loading, setLoading] = useState(true)
  const [storageReady, setStorageReady] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [unsaved, setUnsaved] = useState<UnsavedCapture | null>(null)
  const [storageProtected, setStorageProtected] = useState<boolean | null>(null)
  const [activeReceipt, setActiveReceipt] = useState<ReceiptDetails | null>(null)
  const [selectedPhotoId, setSelectedPhotoId] = useState('')
  const [session, setSession] = useState<SessionUser | null>(null)
  const [sessionMessage, setSessionMessage] = useState('')
  const [uploadStatus, setUploadStatus] = useState('')
  const [updateAvailable, setUpdateAvailable] = useState(false)
  const [remoteReceipt, setRemoteReceipt] = useState<{ receipt: Receipt; photos: RemotePhoto[] } | null>(null)

  const loadReceipts = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      setReceipts(await listReceipts())
      setStorageReady(true)
    } catch (cause) {
      setStorageReady(false)
      setError(storageMessage(cause, 'load'))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void loadReceipts() }, [loadReceipts])
  useEffect(() => subscribeToUpdates(setUpdateAvailable), [])
  useEffect(() => {
    let active = true
    void currentSession().then((user) => { if (active) setSession(user) })
      .catch(() => { if (active) setSessionMessage('Server unavailable. You can still capture receipts offline.') })
    return () => { active = false }
  }, [])

  const sessionExpired = useCallback(() => {
    setSession(null)
    setRemoteReceipt(null)
    setError('Your session expired. Sign in again; pending photos remain on this device.')
  }, [])

  useEffect(() => {
    if (!unsaved) return
    const warnBeforeLeaving = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', warnBeforeLeaving)
    return () => window.removeEventListener('beforeunload', warnBeforeLeaving)
  }, [unsaved])

  function updateReceipt(details: ReceiptDetails) {
    setActiveReceipt(details)
    updateReceiptList(details.receipt)
  }

  function updateReceiptList(updated: Receipt) {
    setReceipts((current) => [updated, ...current.filter((receipt) => receipt.id !== updated.id)]
      .sort((a, b) => b.capturedAt.localeCompare(a.capturedAt)))
  }

  async function saveCapture(capture: UnsavedCapture) {
    setBusy(true)
    setError('')
    try {
      const details = capture.isNew
        ? await createReceipt(capture.receiptId, capture.photo)
        : await addReceiptPhoto(capture.receiptId, capture.photo)
      updateReceipt(details)
      setSelectedPhotoId(capture.photo.id)
      setUnsaved(null)
      if (navigator.storage?.persist) {
        void Promise.resolve().then(() => navigator.storage.persist()).then(setStorageProtected).catch(() => setStorageProtected(false))
      } else {
        setStorageProtected(false)
      }
    } catch (cause) {
      setError(storageMessage(cause, 'save'))
    } finally {
      setBusy(false)
    }
  }

  function handlePhoto(event: ChangeEvent<HTMLInputElement>, receiptId?: string) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    if (!file.size || (file.type && !file.type.startsWith('image/'))) {
      setError('Please choose a non-empty image file.')
      return
    }
    if (!globalThis.crypto?.randomUUID) {
      setError('A secure connection is needed to save photos. Open the app using HTTPS or localhost.')
      return
    }
    const capture: UnsavedCapture = {
      receiptId: receiptId ?? globalThis.crypto.randomUUID(),
      isNew: !receiptId,
      photo: {
        id: globalThis.crypto.randomUUID(),
        capturedAt: new Date().toISOString(),
        image: file.slice(0, file.size, file.type),
        originalFilename: file.name,
      },
    }
    setUnsaved(capture)
    void saveCapture(capture)
  }

  async function viewReceipt(receipt: Receipt) {
    if (receipt.status === 'uploaded' && !session) {
      setError('Sign in to view uploaded photos from the server.')
      return
    }
    setBusy(true)
    setError('')
    try {
      if (receipt.status === 'uploaded') {
        const photos = await getRemotePhotos(receipt.id)
        if (photos.length !== receipt.photoCount || !photos.length) throw new Error('The server returned incomplete receipt details.')
        setRemoteReceipt({ receipt, photos })
        return
      }
      const details = await getReceiptDetails(receipt.id)
      if (!details?.photos.length) throw new Error('Receipt photos not found')
      updateReceipt(details)
      setSelectedPhotoId(details.photos[0].id)
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 401) setSession(null)
      setError(receipt.status === 'uploaded' && cause instanceof Error ? cause.message : 'This receipt could not be opened. Please try again.')
    } finally {
      setBusy(false)
    }
  }

  async function removeReceipt(receipt: Receipt) {
    const message = receipt.status === 'uploaded'
      ? 'Remove this receipt from this device? The copy on the server will remain.'
      : 'Delete this receipt? All of its photos will be removed from this device.'
    if (!window.confirm(message)) return
    setBusy(true)
    setError('')
    try {
      await deleteReceipt(receipt.id)
      setReceipts((current) => current.filter((item) => item.id !== receipt.id))
    } catch (cause) {
      setError(storageMessage(cause, 'delete'))
    } finally {
      setBusy(false)
    }
  }

  async function removePhoto(photoId: string) {
    if (!activeReceipt || !window.confirm('Delete this photo from the receipt?')) return
    const previousIndex = activeReceipt.photos.findIndex((photo) => photo.id === photoId)
    setBusy(true)
    setError('')
    try {
      const details = await deleteReceiptPhoto(activeReceipt.receipt.id, photoId)
      updateReceipt(details)
      setSelectedPhotoId(details.photos[Math.min(previousIndex, details.photos.length - 1)].id)
    } catch (cause) {
      setError(storageMessage(cause, 'delete'))
    } finally {
      setBusy(false)
    }
  }

  async function movePhoto(photoId: string, direction: -1 | 1) {
    if (!activeReceipt) return
    setBusy(true)
    setError('')
    try {
      updateReceipt(await moveReceiptPhoto(activeReceipt.receipt.id, photoId, direction))
    } catch (cause) {
      setError(storageMessage(cause, 'reorder'))
    } finally {
      setBusy(false)
    }
  }

  async function upload(receipt: Receipt) {
    if (uploadGuard.current || busy || unsaved) return
    if (!session) { setError('Sign in before uploading. Your photos remain on this device.'); return }
    if (session.mustChangePassword) { setError('Change your temporary password before uploading.'); return }
    uploadGuard.current = true
    setBusy(true)
    setError('')
    setUploadStatus('Checking receipt and server limits…')
    let acknowledged = false
    try {
      const details = await getReceiptDetails(receipt.id)
      if (!details || details.receipt.status !== 'pending') throw new Error('This pending receipt could not be opened.')
      const limits = await getUploadLimits()
      const prepared = await prepareUpload(details, limits)
      const locked = await lockReceiptForUpload(details)
      updateReceiptList(locked.receipt)
      setUploadStatus('Uploading receipt…')
      const response = await uploadReceipt(locked, prepared)
      acknowledged = true
      setUploadStatus('Confirming local storage…')
      const uploaded = await finishReceiptUpload(locked, response.uploadedAt)
      updateReceiptList(uploaded)
      setUploadStatus('Receipt uploaded. The server confirmed all photos.')
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 401) setSession(null)
      setUploadStatus('')
      setError(acknowledged
        ? 'The server confirmed this receipt, but local cleanup could not finish. Your photos were kept; retry the upload.'
        : cause instanceof Error ? cause.message : 'Upload failed. Your photos were kept; try again.')
    } finally {
      uploadGuard.current = false
      setBusy(false)
    }
  }

  async function makeEditableCopy(receipt: Receipt) {
    if (!window.confirm('Create a new editable receipt with new IDs? The original may already be on the server, so uploading both could create duplicates.')) return
    setBusy(true)
    setError('')
    try {
      const copy = await copyReceiptForEditing(receipt.id)
      updateReceipt(copy)
      setSelectedPhotoId(copy.photos[0].id)
    } catch {
      setError('The editable copy could not be saved. The original photos were kept.')
    } finally {
      setBusy(false)
    }
  }

  const retrySave = () => { if (unsaved) void saveCapture(unsaved) }
  const discardPhoto = () => { setUnsaved(null); setError('') }
  const controlsDisabled = !storageReady || busy || !!unsaved || !secureCaptureAvailable

  return (
    <main className="app-shell">
      <header className="brand"><span className="brand-mark"><ReceiptIcon /></span><span>ReceiptShooter</span></header>

      <section className="intro" aria-labelledby="page-title">
        <p className="eyebrow">YOUR HOUSEHOLD, ORGANIZED</p>
        <h1 id="page-title">Keep the receipt.<br /><span>Skip the clutter.</span></h1>
        <p className="intro-copy">Capture receipts as you go. They stay on this device, ready for later.</p>
        <input ref={inputRef} className="visually-hidden" type="file" accept="image/*" capture="environment"
          onChange={(event) => handlePhoto(event)} aria-label="Choose first receipt photo" disabled={controlsDisabled} />
        <button className="capture-button" type="button" onClick={() => inputRef.current?.click()} disabled={controlsDisabled}>
          <CameraIcon /> {busy && unsaved && !activeReceipt ? 'Saving photo…' : 'New Receipt'}
        </button>
        <p className="capture-note"><span className="status-dot" /> Works offline · Saved on this device</p>
      </section>

      {!secureCaptureAvailable && <div className="notice error-notice" role="alert"><strong>Secure connection needed</strong><p>Open the app using trusted HTTPS or localhost to capture receipts.</p></div>}
      {!activeReceipt && <Notices error={error} unsaved={unsaved} busy={busy} onRetry={retrySave} onDiscard={discardPhoto} />}
      {!storageReady && !loading && <button className="retry-storage" type="button" onClick={() => void loadReceipts()}>Retry storage</button>}
      {updateAvailable && <div className="notice"><strong>Update available</strong><p>Finish saving or uploading before updating.</p><div className="notice-actions"><button type="button" disabled={busy || !!unsaved || !!activeReceipt || !!remoteReceipt} onClick={() => { setBusy(true); applyUpdate() }}>Update app</button></div></div>}

      <AccountPanel session={session} onSession={(user) => { setSession(user); setSessionMessage(''); setError('') }} initialMessage={sessionMessage} />
      <p className="upload-status" role="status" aria-live="polite">{uploadStatus}</p>

      <section className="receipts-section" aria-labelledby="receipts-title">
        <div className="section-heading"><div><p className="eyebrow">ON THIS DEVICE</p><h2 id="receipts-title">Your receipts</h2></div><span className="receipt-count">{receipts.length} {receipts.length === 1 ? 'receipt' : 'receipts'}</span></div>
        {loading ? <p className="loading">Loading receipts…</p> : storageReady && receipts.length === 0 ? (
          <div className="empty-state"><span className="empty-icon"><ReceiptIcon /></span><h3>No receipts yet</h3><p>Your captured receipts will appear here.</p></div>
        ) : (
          <ul className="receipt-list">
            {receipts.map((receipt) => <li className="receipt-row" key={receipt.id}>
              <div className="date-tile" aria-hidden="true"><span>{monthFormatter.format(new Date(receipt.capturedAt))}</span><strong>{dayFormatter.format(new Date(receipt.capturedAt))}</strong></div>
              <div className="receipt-details"><time dateTime={receipt.capturedAt}>{dateFormatter.format(new Date(receipt.capturedAt))}</time><span className="photo-count">{receipt.photoCount} {receipt.photoCount === 1 ? 'photo' : 'photos'}</span><span className={receipt.status === 'uploaded' ? 'uploaded-badge' : 'pending-badge'}><span />{receipt.status === 'uploaded' ? 'Uploaded' : receipt.uploadLocked ? 'Retry upload' : 'Pending upload'}</span></div>
              <div className="receipt-actions">
                <button className="view-button" type="button" onClick={() => void viewReceipt(receipt)} disabled={busy || !!unsaved} aria-label={'View receipt from ' + dateFormatter.format(new Date(receipt.capturedAt))}>View</button>
                {receipt.status === 'pending' && <button className="upload-button" type="button" onClick={() => void upload(receipt)} disabled={busy || !!unsaved} aria-label={'Upload receipt from ' + dateFormatter.format(new Date(receipt.capturedAt))}>{receipt.uploadLocked ? 'Retry' : 'Upload'}</button>}
                {receipt.uploadLocked && <button className="view-button" type="button" onClick={() => void makeEditableCopy(receipt)} disabled={busy || !!unsaved} aria-label={'Copy receipt from ' + dateFormatter.format(new Date(receipt.capturedAt)) + ' to edit'}>Copy to edit</button>}
                <button className="delete-button" type="button" onClick={() => void removeReceipt(receipt)} disabled={busy || !!unsaved} aria-label={'Delete receipt from ' + dateFormatter.format(new Date(receipt.capturedAt))}><TrashIcon /></button>
              </div>
            </li>)}
          </ul>
        )}
      </section>

      <p className="storage-note">Pending photos remain on this device until the server confirms the complete receipt. Browser storage can be cleared or evicted; keep paper receipts.{storageProtected === false ? ' Persistent storage was not granted.' : ''}</p>
      {activeReceipt && <ReceiptDialog details={activeReceipt} selectedId={selectedPhotoId} busy={busy} unsaved={unsaved} error={error}
        secureCaptureAvailable={secureCaptureAvailable} onSelect={setSelectedPhotoId}
        onPhoto={(event) => handlePhoto(event, activeReceipt.receipt.id)}
        onMove={(id, direction) => void movePhoto(id, direction)} onDelete={(id) => void removePhoto(id)}
        onDone={() => { setActiveReceipt(null); setError('') }} onRetry={retrySave} onDiscard={discardPhoto} />}
      {remoteReceipt && <RemoteReceiptDialog receipt={remoteReceipt.receipt} photos={remoteReceipt.photos} onDone={() => setRemoteReceipt(null)} onExpired={sessionExpired} />}
    </main>
  )
}
