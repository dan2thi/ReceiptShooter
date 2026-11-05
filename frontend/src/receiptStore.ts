import { openDB, unwrap, type DBSchema, type IDBPDatabase, type IDBPTransaction } from 'idb'

export interface Receipt {
  id: string
  capturedAt: string
  status: 'pending' | 'uploaded'
  photoCount: number
  uploadedAt?: string
  uploadLocked?: boolean
}

export interface PhotoCapture {
  id: string
  capturedAt: string
  image: Blob
  originalFilename?: string
}

export interface ReceiptPhoto extends PhotoCapture {
  receiptId: string
  sequence: number
}

export interface ReceiptDetails {
  receipt: Receipt
  photos: ReceiptPhoto[]
}

interface ReceiptDatabase extends DBSchema {
  receipts: {
    key: string
    value: Receipt
    indexes: { 'by-capture-time': string }
  }
  photos: {
    key: string
    value: ReceiptPhoto
    indexes: { 'by-receipt': string; 'by-receipt-sequence': [string, number] }
  }
  // Version 1 only; removed atomically after all its images have been migrated.
  images: {
    key: string
    value: { id: string; image: Blob }
  }
}

type WriteTransaction = IDBPTransaction<ReceiptDatabase, ['receipts', 'photos'], 'readwrite'>

const databaseName = 'receipt-shooter'
const databaseVersion = 2

function openReceiptDatabase(): Promise<IDBPDatabase<ReceiptDatabase>> {
  // An open can stall if another tab blocks an upgrade or storage is unavailable.
  return new Promise((resolve, reject) => {
    let settled = false
    const fail = (error: unknown) => {
      if (!settled) {
        settled = true
        clearTimeout(timeout)
        reject(error)
      }
    }
    const timeout = setTimeout(() => {
      fail(new Error('Opening receipt storage timed out.'))
    }, 10000)

    openDB<ReceiptDatabase>(databaseName, databaseVersion, {
      upgrade(db, oldVersion, _newVersion, transaction) {
        // openDB reports upgrade failures; also consume idb's transaction
        // promise so an intentional migration abort is not unhandled.
        void transaction.done.catch(() => {})
        if (oldVersion === 0) {
          const receipts = db.createObjectStore('receipts', { keyPath: 'id' })
          receipts.createIndex('by-capture-time', 'capturedAt')
        }

        const photos = db.createObjectStore('photos', { keyPath: 'id' })
        photos.createIndex('by-receipt', 'receiptId')
        photos.createIndex('by-receipt-sequence', ['receiptId', 'sequence'])

        if (oldVersion === 1) {
          // Native request callbacks keep every migration step in the upgrade
          // transaction, including on browsers that aggressively close idle ones.
          const nativeTransaction = unwrap(transaction)
          const legacyImages = nativeTransaction.objectStore('images')
          const receipts = nativeTransaction.objectStore('receipts')
          const nativePhotos = nativeTransaction.objectStore('photos')
          const countRequest = legacyImages.count()
          const cursorRequest = receipts.openCursor()
          let imageCount = 0
          let migratedCount = 0

          countRequest.onsuccess = () => { imageCount = countRequest.result }
          cursorRequest.onsuccess = () => {
            const cursor = cursorRequest.result
            if (!cursor) {
              // Refuse to discard orphaned images from an inconsistent old DB.
              if (migratedCount !== imageCount) {
                nativeTransaction.abort()
              } else {
                db.deleteObjectStore('images')
              }
              return
            }

            const receipt = cursor.value as Omit<Receipt, 'photoCount'>
            const imageRequest = legacyImages.get(cursor.primaryKey)
            imageRequest.onsuccess = () => {
              const legacy = imageRequest.result as { image?: Blob } | undefined
              if (!legacy || !(legacy.image instanceof Blob)) {
                nativeTransaction.abort()
                return
              }
              try {
                nativePhotos.add({
                  id: globalThis.crypto.randomUUID(),
                  receiptId: receipt.id,
                  sequence: 1,
                  capturedAt: receipt.capturedAt,
                  image: legacy.image,
                } satisfies ReceiptPhoto)
                cursor.update({ ...receipt, photoCount: 1 })
                migratedCount += 1
                cursor.continue()
              } catch {
                nativeTransaction.abort()
              }
            }
          }
        }
      },
      blocked() {
        fail(new Error('Close other ReceiptShooter tabs and try again.'))
      },
    }).then(
      (db) => {
        if (settled) {
          db.close()
          return
        }
        settled = true
        clearTimeout(timeout)
        resolve(db)
      },
      (error: unknown) => fail(error),
    )
  })
}

async function writeReceipt<T>(operation: (transaction: WriteTransaction) => Promise<T>): Promise<T> {
  const db = await openReceiptDatabase()
  let transaction: WriteTransaction
  try {
    transaction = db.transaction(['receipts', 'photos'], 'readwrite')
  } catch (error) {
    db.close()
    throw error
  }

  try {
    const result = await operation(transaction)
    await transaction.done
    return result
  } catch (error) {
    try { transaction.abort() } catch { /* The transaction may already be finished. */ }
    await transaction.done.catch(() => {})
    throw error
  } finally {
    db.close()
  }
}

function orderedPhotos(photos: ReceiptPhoto[]): ReceiptPhoto[] {
  return photos.sort((a, b) => a.sequence - b.sequence)
}

async function pendingReceipt(transaction: WriteTransaction, receiptId: string): Promise<Receipt> {
  const receipt = await transaction.objectStore('receipts').get(receiptId)
  if (!receipt || receipt.status !== 'pending') throw new Error('Pending receipt not found.')
  if (receipt.uploadLocked) throw new Error('Receipt is locked for an upload retry.')
  return receipt
}

async function detailsInTransaction(transaction: WriteTransaction, receipt: Receipt): Promise<ReceiptDetails> {
  const photos = await transaction.objectStore('photos').index('by-receipt').getAll(receipt.id)
  return { receipt, photos: orderedPhotos(photos) }
}

export async function createReceipt(receiptId: string, capture: PhotoCapture): Promise<ReceiptDetails> {
  return writeReceipt(async (transaction) => {
    const receipt: Receipt = { id: receiptId, capturedAt: capture.capturedAt, status: 'pending', photoCount: 1 }
    await transaction.objectStore('photos').add({ ...capture, receiptId, sequence: 1 })
    await transaction.objectStore('receipts').add(receipt)
    return detailsInTransaction(transaction, receipt)
  })
}

export async function addReceiptPhoto(receiptId: string, capture: PhotoCapture): Promise<ReceiptDetails> {
  return writeReceipt(async (transaction) => {
    const receipt = await pendingReceipt(transaction, receiptId)
    await transaction.objectStore('photos').add({ ...capture, receiptId, sequence: receipt.photoCount + 1 })
    const updated = { ...receipt, photoCount: receipt.photoCount + 1 }
    await transaction.objectStore('receipts').put(updated)
    return detailsInTransaction(transaction, updated)
  })
}

export async function listReceipts(): Promise<Receipt[]> {
  const db = await openReceiptDatabase()
  try {
    const receipts = await db.getAllFromIndex('receipts', 'by-capture-time')
    return receipts.reverse()
  } finally {
    db.close()
  }
}

export async function getReceiptDetails(receiptId: string): Promise<ReceiptDetails | undefined> {
  const db = await openReceiptDatabase()
  try {
    const transaction = db.transaction(['receipts', 'photos'], 'readonly')
    const [receipt, photos] = await Promise.all([
      transaction.objectStore('receipts').get(receiptId),
      transaction.objectStore('photos').index('by-receipt').getAll(receiptId),
      transaction.done,
    ])
    return receipt ? { receipt, photos: orderedPhotos(photos) } : undefined
  } finally {
    db.close()
  }
}

function sameSnapshot(expected: ReceiptDetails, actual: ReceiptDetails): boolean {
  return expected.receipt.id === actual.receipt.id &&
    expected.receipt.capturedAt === actual.receipt.capturedAt &&
    expected.receipt.photoCount === actual.receipt.photoCount &&
    expected.photos.length === actual.photos.length &&
    expected.photos.every((photo, index) => {
      const other = actual.photos[index]
      return !!other && photo.id === other.id && photo.sequence === other.sequence &&
        photo.capturedAt === other.capturedAt && photo.originalFilename === other.originalFilename &&
        photo.image.size === other.image.size && photo.image.type === other.image.type
    })
}

// Freeze the exact upload before any request is sent. The flag survives reloads,
// preventing a changed retry from conflicting with an upload whose reply was lost.
export async function lockReceiptForUpload(expected: ReceiptDetails): Promise<ReceiptDetails> {
  return writeReceipt(async (transaction) => {
    const receipt = await transaction.objectStore('receipts').get(expected.receipt.id)
    if (!receipt || receipt.status !== 'pending') throw new Error('Pending receipt not found.')
    const actual = await detailsInTransaction(transaction, receipt)
    if (!actual.photos.length || actual.photos.length !== receipt.photoCount || !sameSnapshot(expected, actual)) {
      throw new Error('The receipt changed. Review it and try again.')
    }
    const locked = { ...receipt, uploadLocked: true }
    await transaction.objectStore('receipts').put(locked)
    return { receipt: locked, photos: actual.photos }
  })
}

// Cleanup and status update are one IndexedDB commit; any mismatch or write
// failure leaves all local originals in place for the same idempotent retry.
export async function finishReceiptUpload(expected: ReceiptDetails, uploadedAt: string): Promise<Receipt> {
  return writeReceipt(async (transaction) => {
    const receipts = transaction.objectStore('receipts')
    const receipt = await receipts.get(expected.receipt.id)
    if (receipt?.status === 'uploaded') return receipt
    if (!receipt || !receipt.uploadLocked) throw new Error('Upload receipt no longer exists.')
    const actual = await detailsInTransaction(transaction, receipt)
    if (!sameSnapshot(expected, actual)) throw new Error('The receipt changed during upload.')
    const photos = transaction.objectStore('photos')
    for (const photo of actual.photos) await photos.delete(photo.id)
    const uploaded: Receipt = {
      id: receipt.id, capturedAt: receipt.capturedAt, photoCount: receipt.photoCount,
      status: 'uploaded', uploadedAt,
    }
    await receipts.put(uploaded)
    return uploaded
  })
}

// A copy has fresh IDs so it cannot conflict with an earlier ambiguous upload.
export async function copyReceiptForEditing(receiptId: string): Promise<ReceiptDetails> {
  return writeReceipt(async (transaction) => {
    const source = await transaction.objectStore('receipts').get(receiptId)
    if (!source || source.status !== 'pending') throw new Error('Pending receipt not found.')
    const original = await detailsInTransaction(transaction, source)
    if (!original.photos.length || original.photos.length !== source.photoCount) throw new Error('Receipt photos not found.')
    const id = globalThis.crypto.randomUUID()
    const copy: Receipt = { id, capturedAt: source.capturedAt, status: 'pending', photoCount: source.photoCount }
    for (const photo of original.photos) {
      await transaction.objectStore('photos').add({ ...photo, id: globalThis.crypto.randomUUID(), receiptId: id })
    }
    await transaction.objectStore('receipts').add(copy)
    return detailsInTransaction(transaction, copy)
  })
}

export async function deleteReceiptPhoto(receiptId: string, photoId: string): Promise<ReceiptDetails> {
  return writeReceipt(async (transaction) => {
    const receipt = await pendingReceipt(transaction, receiptId)
    const photos = transaction.objectStore('photos')
    const photo = await photos.get(photoId)
    if (!photo || photo.receiptId !== receiptId) throw new Error('Receipt photo not found.')
    const allPhotos = orderedPhotos(await photos.index('by-receipt').getAll(receiptId))
    if (allPhotos.length <= 1) throw new Error('A receipt must keep at least one photo.')
    await photos.delete(photoId)
    for (const later of allPhotos) {
      if (later.sequence > photo.sequence) await photos.put({ ...later, sequence: later.sequence - 1 })
    }
    const updated = { ...receipt, photoCount: allPhotos.length - 1 }
    await transaction.objectStore('receipts').put(updated)
    return detailsInTransaction(transaction, updated)
  })
}

export async function moveReceiptPhoto(receiptId: string, photoId: string, direction: -1 | 1): Promise<ReceiptDetails> {
  return writeReceipt(async (transaction) => {
    const receipt = await pendingReceipt(transaction, receiptId)
    const photos = transaction.objectStore('photos')
    const photo = await photos.get(photoId)
    if (!photo || photo.receiptId !== receiptId) throw new Error('Receipt photo not found.')
    const neighbor = await photos.index('by-receipt-sequence').get([receiptId, photo.sequence + direction])
    if (neighbor) {
      await photos.put({ ...photo, sequence: neighbor.sequence })
      await photos.put({ ...neighbor, sequence: photo.sequence })
    }
    return detailsInTransaction(transaction, receipt)
  })
}

export async function deleteReceipt(receiptId: string): Promise<void> {
  return writeReceipt(async (transaction) => {
    const photos = transaction.objectStore('photos')
    const photoIds = await photos.index('by-receipt').getAllKeys(receiptId)
    for (const photoId of photoIds) await photos.delete(photoId)
    await transaction.objectStore('receipts').delete(receiptId)
  })
}
