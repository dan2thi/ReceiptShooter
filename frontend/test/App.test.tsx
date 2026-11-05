import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../src/App'
import * as store from '../src/receiptStore'
import * as api from '../src/receiptApi'
import * as authApi from '../src/api'
import type { PhotoCapture, Receipt, ReceiptDetails, ReceiptPhoto } from '../src/receiptStore'

vi.mock('../src/receiptStore', () => ({
  listReceipts: vi.fn(),
  createReceipt: vi.fn(),
  addReceiptPhoto: vi.fn(),
  getReceiptDetails: vi.fn(),
  deleteReceiptPhoto: vi.fn(),
  moveReceiptPhoto: vi.fn(),
  deleteReceipt: vi.fn(),
  lockReceiptForUpload: vi.fn(),
  finishReceiptUpload: vi.fn(),
  copyReceiptForEditing: vi.fn(),
}))

vi.mock('../src/receiptApi', () => ({
  getUploadLimits: vi.fn(), prepareUpload: vi.fn(), uploadReceipt: vi.fn(),
  getRemotePhotos: vi.fn(), getRemotePhoto: vi.fn(),
}))

vi.mock('../src/api', () => ({
  ApiError: class extends Error { constructor(public status: number, message: string) { super(message) } },
  currentSession: vi.fn(), login: vi.fn(), logout: vi.fn(), changePassword: vi.fn(),
  listUsers: vi.fn(), createUser: vi.fn(), setUserEnabled: vi.fn(), resetUserPassword: vi.fn(),
}))

const capturedAt = '2026-01-02T03:04:05.000Z'
const receipt: Receipt = { id: 'receipt-one', capturedAt, status: 'pending', photoCount: 1 }
const photo = (id: string, sequence: number): ReceiptPhoto => ({
  id, receiptId: receipt.id, sequence, capturedAt, image: new Blob([id], { type: 'image/jpeg' }),
})
const first = photo('photo-one', 1)
const second = photo('photo-two', 2)
const details: ReceiptDetails = { receipt: { ...receipt, photoCount: 2 }, photos: [first, second] }
const imageFile = () => new File(['photo bytes'], 'receipt.jpg', { type: 'image/jpeg' })

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(store.listReceipts).mockResolvedValue([])
  vi.mocked(authApi.currentSession).mockResolvedValue(null)
  vi.mocked(store.createReceipt).mockImplementation(async (id, capture) => ({
    receipt: { id, capturedAt: capture.capturedAt, status: 'pending', photoCount: 1 },
    photos: [{ ...capture, receiptId: id, sequence: 1 }],
  }))
  vi.mocked(store.deleteReceipt).mockResolvedValue(undefined)
  vi.mocked(api.getUploadLimits).mockResolvedValue({ maxPhotoBytes: 10000, maxTotalBytes: 20000, maxPhotos: 10, maxPixels: 100000, maxImageEdge: 32768 })
  vi.mocked(api.prepareUpload).mockResolvedValue([])
  Object.defineProperty(navigator, 'storage', { configurable: true, value: { persist: vi.fn().mockResolvedValue(true) } })
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.open = true } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.open = false; this.dispatchEvent(new Event('close')) } })
  URL.createObjectURL = vi.fn().mockReturnValue('blob:receipt')
  URL.revokeObjectURL = vi.fn()
})

afterEach(() => { cleanup(); vi.restoreAllMocks() })

describe('offline receipt collection', () => {
  it('saves the first photo before showing the receipt and makes upload a manual action', async () => {
    let completeSave!: (value: ReceiptDetails) => void
    vi.mocked(store.createReceipt).mockImplementationOnce(() => new Promise((resolve) => { completeSave = resolve }))
    render(<App />)
    await screen.findByText('No receipts yet')
    expect(screen.queryByRole('button', { name: /Upload receipt/ })).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Choose first receipt photo'), { target: { files: [imageFile()] } })
    expect(screen.getByRole('button', { name: 'Saving photo…' })).toBeDisabled()
    expect(screen.queryByText('Pending upload')).not.toBeInTheDocument()

    const [receiptId, capture] = vi.mocked(store.createReceipt).mock.calls[0]
    expect(receiptId).toMatch(/^[0-9a-f-]{36}$/i)
    expect(capture.id).toMatch(/^[0-9a-f-]{36}$/i)
    expect(capture.id).not.toBe(receiptId)
    expect(capture.image).toBeInstanceOf(Blob)
    expect(Number.isNaN(Date.parse(capture.capturedAt))).toBe(false)
    completeSave({
      receipt: { id: receiptId, capturedAt: capture.capturedAt, status: 'pending', photoCount: 1 },
      photos: [{ ...capture, receiptId, sequence: 1 }],
    })
    expect(await screen.findByText('Pending upload')).toBeInTheDocument()
    expect(screen.getByText('1 photo')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Delete photo' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Add Another Photo' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: 'Done' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Upload receipt/ })).toBeEnabled()
    expect(api.uploadReceipt).not.toHaveBeenCalled()
  })

  it('waits for acknowledgement before cleanup and prevents repeated presses', async () => {
    vi.mocked(authApi.currentSession).mockResolvedValue({ username: 'tester', role: 'USER', mustChangePassword: false })
    vi.mocked(store.listReceipts).mockResolvedValue([details.receipt])
    vi.mocked(store.getReceiptDetails).mockResolvedValue(details)
    const locked = { ...details, receipt: { ...details.receipt, uploadLocked: true } }
    vi.mocked(store.lockReceiptForUpload).mockResolvedValue(locked)
    vi.mocked(store.finishReceiptUpload).mockResolvedValue({ ...details.receipt, status: 'uploaded', uploadedAt: capturedAt })
    let acknowledge!: (value: api.UploadAcknowledgement) => void
    vi.mocked(api.uploadReceipt).mockImplementation(() => new Promise((resolve) => { acknowledge = resolve }))
    render(<App />)
    const button = await screen.findByRole('button', { name: /Upload receipt/ })
    expect(api.getUploadLimits).not.toHaveBeenCalled()
    await screen.findByText('Signed in as tester.')
    fireEvent.click(button)
    await waitFor(() => expect(api.uploadReceipt).toHaveBeenCalledTimes(1))
    expect(screen.getByRole('button', { name: /Upload receipt/ })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: /Upload receipt/ }))
    expect(api.uploadReceipt).toHaveBeenCalledTimes(1)
    expect(store.finishReceiptUpload).not.toHaveBeenCalled()
    acknowledge({ receiptId: receipt.id, photoCount: 2, status: 'STORED', uploadedAt: capturedAt })
    await screen.findByText('Uploaded')
    expect(store.finishReceiptUpload).toHaveBeenCalledWith(locked, capturedAt)
  })

  it('keeps local photos after a failed upload and offers a manual identical retry', async () => {
    vi.mocked(authApi.currentSession).mockResolvedValue({ username: 'tester', role: 'USER', mustChangePassword: false })
    vi.mocked(store.listReceipts).mockResolvedValue([details.receipt])
    vi.mocked(store.getReceiptDetails).mockResolvedValue(details)
    const locked = { ...details, receipt: { ...details.receipt, uploadLocked: true } }
    vi.mocked(store.lockReceiptForUpload).mockResolvedValue(locked)
    vi.mocked(api.uploadReceipt).mockRejectedValueOnce(new Error('The server could not be reached. Your photos remain on this device.'))
      .mockResolvedValueOnce({ receiptId: receipt.id, photoCount: 2, status: 'STORED', uploadedAt: capturedAt })
    vi.mocked(store.finishReceiptUpload).mockResolvedValue({ ...details.receipt, status: 'uploaded', uploadedAt: capturedAt })
    render(<App />)
    await screen.findByText('Signed in as tester.')
    fireEvent.click(await screen.findByRole('button', { name: /Upload receipt/ }))
    expect(await screen.findByText(/could not be reached/)).toBeInTheDocument()
    expect(store.finishReceiptUpload).not.toHaveBeenCalled()
    expect(screen.getByText('Retry upload')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Upload receipt/ }))
    await screen.findByText('Uploaded')
    expect(api.uploadReceipt).toHaveBeenCalledTimes(2)
    expect(vi.mocked(api.uploadReceipt).mock.calls[0][0].photos.map((item) => item.id)).toEqual(['photo-one', 'photo-two'])
    expect(vi.mocked(api.uploadReceipt).mock.calls[1][0]).toEqual(locked)
  })

  it('keeps capture available when the session cannot be checked offline', async () => {
    vi.mocked(authApi.currentSession).mockRejectedValue(new Error('Offline'))
    render(<App />)
    await screen.findByText('No receipts yet')
    expect(await screen.findByText(/Server unavailable/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Choose first receipt photo'), { target: { files: [imageFile()] } })
    expect(await screen.findByText('Pending upload')).toBeInTheDocument()
  })

  it('retains pending photos and asks for sign-in when the session expires', async () => {
    vi.mocked(authApi.currentSession).mockResolvedValue({ username: 'tester', role: 'USER', mustChangePassword: false })
    vi.mocked(store.listReceipts).mockResolvedValue([details.receipt])
    vi.mocked(store.getReceiptDetails).mockResolvedValue(details)
    vi.mocked(api.getUploadLimits).mockRejectedValue(new authApi.ApiError(401, 'Your session expired. Sign in again.'))
    render(<App />)
    await screen.findByText('Signed in as tester.')
    fireEvent.click(await screen.findByRole('button', { name: /Upload receipt/ }))
    expect(await screen.findByText(/session expired/)).toBeInTheDocument()
    expect(store.finishReceiptUpload).not.toHaveBeenCalled()
    expect(store.lockReceiptForUpload).not.toHaveBeenCalled()
    expect(screen.getByText('Pending upload')).toBeInTheDocument()
  })

  it('keeps the locked originals for retry if authentication expires during upload', async () => {
    vi.mocked(authApi.currentSession).mockResolvedValue({ username: 'tester', role: 'USER', mustChangePassword: false })
    vi.mocked(store.listReceipts).mockResolvedValue([details.receipt])
    vi.mocked(store.getReceiptDetails).mockResolvedValue(details)
    vi.mocked(store.lockReceiptForUpload).mockResolvedValue({ ...details, receipt: { ...details.receipt, uploadLocked: true } })
    vi.mocked(api.uploadReceipt).mockRejectedValue(new authApi.ApiError(401, 'Your session expired. Sign in again.'))
    render(<App />)
    await screen.findByText('Signed in as tester.')
    fireEvent.click(await screen.findByRole('button', { name: /Upload receipt/ }))
    expect(await screen.findByText(/session expired/)).toBeInTheDocument()
    expect(store.finishReceiptUpload).not.toHaveBeenCalled()
    expect(screen.getByText('Retry upload')).toBeInTheDocument()
  })

  it('restores an existing session on an ordinary app restart', async () => {
    vi.mocked(authApi.currentSession).mockResolvedValue({ username: 'tester', role: 'USER', mustChangePassword: false })
    const first = render(<App />)
    await screen.findByText('Signed in as tester.')
    first.unmount()
    render(<App />)
    expect(await screen.findByText('Signed in as tester.')).toBeInTheDocument()
    expect(authApi.currentSession).toHaveBeenCalledTimes(2)
  })

  it('allows repeated additions to the same receipt during capture', async () => {
    let current!: ReceiptDetails
    vi.mocked(store.createReceipt).mockImplementation(async (id, capture) => {
      current = { receipt: { id, capturedAt: capture.capturedAt, status: 'pending', photoCount: 1 }, photos: [{ ...capture, receiptId: id, sequence: 1 }] }
      return current
    })
    vi.mocked(store.addReceiptPhoto).mockImplementation(async (id, capture) => {
      const photos = [...current.photos, { ...capture, receiptId: id, sequence: current.photos.length + 1 }]
      current = { receipt: { ...current.receipt, photoCount: photos.length }, photos }
      return current
    })
    render(<App />)
    await screen.findByText('No receipts yet')
    fireEvent.change(screen.getByLabelText('Choose first receipt photo'), { target: { files: [imageFile()] } })
    await screen.findByRole('button', { name: 'Add Another Photo' })
    fireEvent.change(screen.getByLabelText('Choose another receipt photo'), { target: { files: [imageFile()] } })
    await screen.findByText('Photo 2 of 2')
    fireEvent.change(screen.getByLabelText('Choose another receipt photo'), { target: { files: [imageFile()] } })
    await screen.findByText('Photo 3 of 3')

    expect(vi.mocked(store.addReceiptPhoto).mock.calls.map(([id]) => id)).toEqual([current.receipt.id, current.receipt.id])
    expect(new Set(current.photos.map((item) => item.id)).size).toBe(3)
    fireEvent.click(screen.getByRole('button', { name: 'Done' }))
    expect(screen.getByText('3 photos')).toBeInTheDocument()
    expect(screen.getByText('1 receipt')).toBeInTheDocument()
  })

  it('retains an unsaved first photo and its IDs for retry after a quota error', async () => {
    vi.mocked(store.createReceipt).mockRejectedValueOnce(new DOMException('Full', 'QuotaExceededError'))
    render(<App />)
    await screen.findByText('No receipts yet')
    fireEvent.change(screen.getByLabelText('Choose first receipt photo'), { target: { files: [imageFile()] } })

    expect(await screen.findByText(/out of space/)).toBeInTheDocument()
    expect(screen.getByText('Photo not saved yet')).toBeInTheDocument()
    expect(screen.queryByText('Pending upload')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'New Receipt' })).toBeDisabled()
    expect(window.dispatchEvent(new Event('beforeunload', { cancelable: true }))).toBe(false)
    const [firstId, firstCapture]: [string, PhotoCapture] = vi.mocked(store.createReceipt).mock.calls[0]
    fireEvent.click(screen.getByRole('button', { name: 'Retry save' }))
    expect(await screen.findByText('Pending upload')).toBeInTheDocument()
    expect(vi.mocked(store.createReceipt).mock.calls[1]).toEqual([firstId, firstCapture])
  })

  it('keeps a failed additional photo available for retry inside the receipt', async () => {
    vi.mocked(store.listReceipts).mockResolvedValue([receipt])
    vi.mocked(store.getReceiptDetails).mockResolvedValue({ receipt, photos: [first] })
    vi.mocked(store.addReceiptPhoto).mockRejectedValueOnce(new DOMException('Full', 'QuotaExceededError'))
      .mockImplementationOnce(async (_id, capture) => ({
        receipt: { ...receipt, photoCount: 2 },
        photos: [first, { ...capture, receiptId: receipt.id, sequence: 2 }],
      }))
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: /View receipt/ }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Choose another receipt photo'), { target: { files: [imageFile()] } })

    expect(await within(dialog).findByText(/out of space/)).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Done' })).toBeDisabled()
    expect(within(dialog).getByText('Photo 1 of 1')).toBeInTheDocument()
    const attempted = vi.mocked(store.addReceiptPhoto).mock.calls[0]
    fireEvent.click(within(dialog).getByRole('button', { name: 'Retry save' }))
    expect(await within(dialog).findByText('Photo 2 of 2')).toBeInTheDocument()
    expect(vi.mocked(store.addReceiptPhoto).mock.calls[1]).toEqual(attempted)
    expect(within(dialog).getByRole('button', { name: 'Done' })).toBeEnabled()
  })

  it('opens pending receipts, navigates, reorders, and confirms individual deletion', async () => {
    vi.mocked(store.listReceipts).mockResolvedValue([details.receipt])
    vi.mocked(store.getReceiptDetails).mockResolvedValue(details)
    const reordered = { receipt: details.receipt, photos: [{ ...second, sequence: 1 }, { ...first, sequence: 2 }] }
    vi.mocked(store.moveReceiptPhoto).mockResolvedValue(reordered)
    vi.mocked(store.deleteReceiptPhoto).mockResolvedValue({ receipt, photos: [{ ...first, sequence: 1 }] })
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: /View receipt/ }))
    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByRole('img', { name: /Receipt photo 1 of 2/ })).toHaveAttribute('src', 'blob:receipt')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Next photo' }))
    expect(await within(dialog).findByRole('img', { name: /Receipt photo 2 of 2/ })).toBeInTheDocument()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:receipt')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Move earlier' }))
    await waitFor(() => expect(store.moveReceiptPhoto).toHaveBeenCalledWith(receipt.id, second.id, -1))
    expect(await within(dialog).findByText('Photo 1 of 2')).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete photo' }))
    expect(store.deleteReceiptPhoto).not.toHaveBeenCalled()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete photo' }))
    await waitFor(() => expect(store.deleteReceiptPhoto).toHaveBeenCalledWith(receipt.id, second.id))
    expect(await within(dialog).findByText('Photo 1 of 1')).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Delete photo' })).toBeDisabled()
    expect(confirm).toHaveBeenCalledTimes(2)
    fireEvent.click(within(dialog).getByRole('button', { name: 'Done' }))
    expect(screen.getByText('1 photo')).toBeInTheDocument()
    expect(URL.revokeObjectURL).toHaveBeenCalled()
  })

  it('requires confirmation and keeps a receipt visible if deletion fails', async () => {
    vi.mocked(store.listReceipts).mockResolvedValue([receipt])
    vi.mocked(store.deleteReceipt).mockRejectedValueOnce(new Error('Failed')).mockResolvedValueOnce(undefined)
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValue(true)
    render(<App />)
    const button = await screen.findByRole('button', { name: /Delete receipt/ })
    fireEvent.click(button)
    expect(store.deleteReceipt).not.toHaveBeenCalled()
    fireEvent.click(button)
    expect(await screen.findByText(/deletion could not be completed/)).toBeInTheDocument()
    expect(screen.getByText('Pending upload')).toBeInTheDocument()
    fireEvent.click(button)
    await waitFor(() => expect(screen.queryByText('Pending upload')).not.toBeInTheDocument())
    expect(confirm).toHaveBeenCalledTimes(3)
    expect(store.deleteReceipt).toHaveBeenCalledTimes(2)
  })

  it('offers a retry when the database cannot be opened', async () => {
    vi.mocked(store.listReceipts).mockRejectedValueOnce(new Error('Unavailable')).mockResolvedValueOnce([])
    render(<App />)
    expect(await screen.findByText(/storage could not be opened/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'New Receipt' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Retry storage' }))
    expect(await screen.findByText('No receipts yet')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'New Receipt' })).toBeEnabled()
  })
})
