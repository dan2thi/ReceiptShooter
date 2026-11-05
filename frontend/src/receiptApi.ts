import type { ReceiptDetails, ReceiptPhoto } from './receiptStore'
import { apiRequest } from './api'

export interface UploadLimits {
  maxPhotoBytes: number
  maxTotalBytes: number
  maxPhotos: number
  maxPixels: number
  maxImageEdge: number
}

export interface RemotePhoto {
  photoId: string
  sequence: number
  capturedAt: string | null
  contentType: string
}

interface PreparedPhoto {
  photo: ReceiptPhoto
  contentType: 'image/jpeg' | 'image/png'
  filename: string
}

export interface UploadAcknowledgement {
  receiptId: string
  uploadedAt: string
  photoCount: number
  status: 'STORED'
}

export async function getUploadLimits(): Promise<UploadLimits> {
  const response = await apiRequest('/api/upload-limits')
  let limits: UploadLimits
  try { limits = await response.json() as UploadLimits } catch { throw new Error('The server returned invalid upload limits.') }
  if (!limits || ![limits.maxPhotoBytes, limits.maxTotalBytes, limits.maxPhotos, limits.maxPixels, limits.maxImageEdge]
    .every((value) => Number.isSafeInteger(value) && value > 0)) {
    throw new Error('The server returned invalid upload limits.')
  }
  return limits
}

function imageInfo(bytes: Uint8Array): { contentType: 'image/jpeg' | 'image/png'; width: number; height: number } {
  const png = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]
  if (bytes.length >= 24 && png.every((value, index) => bytes[index] === value) &&
      bytes[12] === 0x49 && bytes[13] === 0x48 && bytes[14] === 0x44 && bytes[15] === 0x52) {
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
    return { contentType: 'image/png', width: view.getUint32(16), height: view.getUint32(20) }
  }
  if (bytes.length >= 4 && bytes[0] === 0xff && bytes[1] === 0xd8) {
    let offset = 2
    while (offset + 3 < bytes.length) {
      if (bytes[offset++] !== 0xff) break
      while (offset < bytes.length && bytes[offset] === 0xff) offset++
      const marker = bytes[offset++]
      if (marker === 0xd9 || marker === 0xda) break
      if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd8)) continue
      if (offset + 1 >= bytes.length) break
      const length = (bytes[offset] << 8) | bytes[offset + 1]
      if (length < 2 || offset + length > bytes.length) break
      if ([0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf].includes(marker)) {
        if (length < 7) break
        return { contentType: 'image/jpeg', height: (bytes[offset + 3] << 8) | bytes[offset + 4],
          width: (bytes[offset + 5] << 8) | bytes[offset + 6] }
      }
      offset += length
    }
    throw new Error('A JPEG photo is incomplete or its dimensions could not be read.')
  }
  throw new Error('Only JPEG and PNG photos can be uploaded. HEIC/HEIF is not supported; choose or capture a JPEG instead.')
}

function filenameFor(photo: ReceiptPhoto, contentType: string): string {
  const name = photo.originalFilename?.replace(/\\/g, '/').split('/').pop()?.replace(/[\u0000-\u001f\u007f-\u009f]/g, '').slice(0, 255)
  return name && name !== '.' && name !== '..' ? name : `photo-${photo.id}.${contentType === 'image/png' ? 'png' : 'jpg'}`
}

export async function prepareUpload(details: ReceiptDetails, limits: UploadLimits): Promise<PreparedPhoto[]> {
  if (!details.photos.length || details.photos.length !== details.receipt.photoCount) throw new Error('The receipt photos are incomplete on this device.')
  if (details.photos.length > limits.maxPhotos) throw new Error(`This receipt exceeds the server limit of ${limits.maxPhotos} photos.`)
  let total = 0
  const prepared: PreparedPhoto[] = []
  for (const photo of details.photos) {
    if (!photo.image.size) throw new Error('A photo is empty and cannot be uploaded.')
    if (photo.image.size > limits.maxPhotoBytes) throw new Error('A photo exceeds the server’s per-photo size limit.')
    total += photo.image.size
    if (total > limits.maxTotalBytes) throw new Error('The receipt exceeds the server’s total photo size limit.')
    const info = imageInfo(new Uint8Array(await photo.image.arrayBuffer()))
    if (!info.width || !info.height || info.width > limits.maxImageEdge || info.height > limits.maxImageEdge ||
        info.width * info.height > limits.maxPixels) {
      throw new Error('A photo exceeds the server’s image dimension limit.')
    }
    prepared.push({ photo, contentType: info.contentType, filename: filenameFor(photo, info.contentType) })
  }
  return prepared
}

export async function uploadReceipt(details: ReceiptDetails, prepared: PreparedPhoto[]): Promise<UploadAcknowledgement> {
  const metadata = {
    receiptId: details.receipt.id,
    capturedAt: details.receipt.capturedAt,
    photos: prepared.map(({ photo, contentType }) => ({
      id: photo.id, sequence: photo.sequence, capturedAt: photo.capturedAt, contentType, size: photo.image.size,
    })),
  }
  const body = new FormData()
  body.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }), 'metadata.json')
  for (const { photo, contentType, filename } of prepared) {
    body.append(`photo-${photo.id}`, photo.image.slice(0, photo.image.size, contentType), filename)
  }
  const response = await apiRequest('/api/receipts', { method: 'POST', body })
  if (response.status !== 200 && response.status !== 201) throw new Error('The server returned an unexpected acknowledgement. Your photos were kept.')
  let acknowledgement: UploadAcknowledgement
  try { acknowledgement = await response.json() as UploadAcknowledgement } catch { throw new Error('The server acknowledgement was invalid. Your photos were kept.') }
  if (!acknowledgement || acknowledgement.receiptId !== details.receipt.id ||
      acknowledgement.photoCount !== details.photos.length || acknowledgement.status !== 'STORED' ||
      typeof acknowledgement.uploadedAt !== 'string' || !Number.isFinite(Date.parse(acknowledgement.uploadedAt))) {
    throw new Error('The server acknowledgement did not match this receipt. Your photos were kept.')
  }
  return acknowledgement
}

export async function getRemotePhotos(receiptId: string): Promise<RemotePhoto[]> {
  const response = await apiRequest(`/api/receipts/${encodeURIComponent(receiptId)}`)
  const result = await response.json() as { receipt?: { receiptId?: string }; photos?: RemotePhoto[] }
  if (result.receipt?.receiptId !== receiptId || !Array.isArray(result.photos) ||
      !result.photos.every((photo) => typeof photo.photoId === 'string' && Number.isInteger(photo.sequence))) {
    throw new Error('The server returned invalid receipt details.')
  }
  return result.photos.sort((a, b) => a.sequence - b.sequence)
}

export async function getRemotePhoto(receiptId: string, photoId: string, signal: AbortSignal): Promise<Blob> {
  const response = await apiRequest(`/api/receipts/${encodeURIComponent(receiptId)}/photos/${encodeURIComponent(photoId)}`, { signal })
  return response.blob()
}
