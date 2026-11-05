// @vitest-environment node
import 'fake-indexeddb/auto'
import { deleteDB, openDB } from 'idb'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  addReceiptPhoto, createReceipt, deleteReceipt, deleteReceiptPhoto,
  getReceiptDetails, listReceipts, moveReceiptPhoto, lockReceiptForUpload,
  finishReceiptUpload, copyReceiptForEditing,
} from '../src/receiptStore'
import type { PhotoCapture } from '../src/receiptStore'

function capture(id: string, capturedAt = '2026-01-02T03:04:05.000Z', contents = id): PhotoCapture {
  return { id, capturedAt, image: new Blob([contents], { type: 'image/jpeg' }) }
}

async function photoContents(receiptId: string) {
  const details = await getReceiptDetails(receiptId)
  return Promise.all((details?.photos ?? []).map((photo) => photo.image.text()))
}

async function createLegacyDatabase(orphan = false) {
  const db = await openDB('receipt-shooter', 1, {
    upgrade(legacy) {
      legacy.createObjectStore('receipts', { keyPath: 'id' }).createIndex('by-capture-time', 'capturedAt')
      legacy.createObjectStore('images', { keyPath: 'id' })
    },
  })
  const tx = db.transaction(['receipts', 'images'], 'readwrite')
  await Promise.all([
    tx.objectStore('receipts').add({ id: 'legacy-one', capturedAt: '2026-01-02T03:04:05.000Z', status: 'pending' }),
    tx.objectStore('images').add({ id: 'legacy-one', image: new Blob(['first original'], { type: 'image/jpeg' }) }),
    tx.objectStore('receipts').add({ id: 'legacy-two', capturedAt: '2026-01-03T03:04:05.000Z', status: 'pending' }),
    tx.objectStore('images').add({ id: 'legacy-two', image: new Blob(['second original'], { type: 'image/png' }) }),
    ...(orphan ? [tx.objectStore('images').add({ id: 'orphan', image: new Blob(['orphan original']) })] : []),
    tx.done,
  ])
  db.close()
}

beforeEach(async () => { await deleteDB('receipt-shooter') })
afterEach(() => vi.restoreAllMocks())

describe('receipt storage', () => {
  it('freezes a receipt for identical retries and atomically removes photos after acknowledgement', async () => {
    await createReceipt('receipt', capture('one'))
    await addReceiptPhoto('receipt', capture('two'))
    const snapshot = (await getReceiptDetails('receipt'))!
    const locked = await lockReceiptForUpload(snapshot)
    expect(locked.receipt.uploadLocked).toBe(true)
    await expect(addReceiptPhoto('receipt', capture('three'))).rejects.toThrow('locked')
    await expect(moveReceiptPhoto('receipt', 'two', -1)).rejects.toThrow('locked')
    await expect(deleteReceiptPhoto('receipt', 'two')).rejects.toThrow('locked')
    expect((await getReceiptDetails('receipt'))?.receipt.uploadLocked).toBe(true)
    await finishReceiptUpload(locked, '2026-01-02T04:00:00Z')
    expect(await getReceiptDetails('receipt')).toEqual({
      receipt: { id: 'receipt', capturedAt: snapshot.receipt.capturedAt, status: 'uploaded', photoCount: 2, uploadedAt: '2026-01-02T04:00:00Z' },
      photos: [],
    })
    expect((await listReceipts())[0].status).toBe('uploaded')
    const db = await openDB('receipt-shooter', 2)
    expect(await db.count('photos')).toBe(0)
    db.close()
  })

  it('retains every photo if local cleanup or status update fails', async () => {
    await createReceipt('receipt', capture('one'))
    await addReceiptPhoto('receipt', capture('two'))
    const locked = await lockReceiptForUpload((await getReceiptDetails('receipt'))!)
    const originalPut = IDBObjectStore.prototype.put
    vi.spyOn(IDBObjectStore.prototype, 'put').mockImplementation(function (this: IDBObjectStore, value, key) {
      if (this.name === 'receipts' && value.status === 'uploaded') throw new DOMException('Write failed', 'UnknownError')
      return originalPut.call(this, value, key)
    })
    await expect(finishReceiptUpload(locked, '2026-01-02T04:00:00Z')).rejects.toThrow()
    expect(await photoContents('receipt')).toEqual(['one', 'two'])
    expect((await getReceiptDetails('receipt'))?.receipt).toMatchObject({ status: 'pending', uploadLocked: true })
  })

  it('refuses to upload a stale snapshot if another window edited the receipt', async () => {
    await createReceipt('receipt', capture('one'))
    const snapshot = (await getReceiptDetails('receipt'))!
    await addReceiptPhoto('receipt', capture('two'))
    await expect(lockReceiptForUpload(snapshot)).rejects.toThrow('changed')
    expect(await photoContents('receipt')).toEqual(['one', 'two'])
    expect((await getReceiptDetails('receipt'))?.receipt.uploadLocked).toBeUndefined()
  })

  it('creates a separately identified editable copy without changing a locked original', async () => {
    await createReceipt('receipt', capture('one'))
    await addReceiptPhoto('receipt', capture('two'))
    await lockReceiptForUpload((await getReceiptDetails('receipt'))!)
    const copy = await copyReceiptForEditing('receipt')
    expect(copy.receipt.id).not.toBe('receipt')
    expect(copy.photos.map((item) => item.id)).not.toContain('one')
    expect(copy.photos.map((item) => item.id)).not.toContain('two')
    expect(await photoContents(copy.receipt.id)).toEqual(['one', 'two'])
    await deleteReceiptPhoto(copy.receipt.id, copy.photos[0].id)
    expect(await photoContents('receipt')).toEqual(['one', 'two'])
  })

  it('persists multiple photos and separate receipt metadata across reopened connections', async () => {
    const first = capture('photo-one', '2026-01-02T03:04:05.000Z', 'front')
    const second = capture('photo-two', '2026-01-02T03:05:05.000Z', 'back')
    await createReceipt('receipt-one', first)
    const saved = await addReceiptPhoto('receipt-one', second)

    expect(saved.receipt).toEqual({ id: 'receipt-one', capturedAt: first.capturedAt, status: 'pending', photoCount: 2 })
    expect(await listReceipts()).toEqual([saved.receipt])
    const reopened = await getReceiptDetails('receipt-one')
    expect(reopened?.photos.map(({ id, receiptId, sequence, capturedAt }) => ({ id, receiptId, sequence, capturedAt }))).toEqual([
      { id: 'photo-one', receiptId: 'receipt-one', sequence: 1, capturedAt: first.capturedAt },
      { id: 'photo-two', receiptId: 'receipt-one', sequence: 2, capturedAt: second.capturedAt },
    ])
    expect(reopened?.photos.every((photo) => photo.image instanceof Blob && photo.image.type === 'image/jpeg')).toBe(true)
    expect(await photoContents('receipt-one')).toEqual(['front', 'back'])
    expect('image' in saved.receipt).toBe(false)
  })

  it('lists one entry per receipt, newest first, without loading photos', async () => {
    await createReceipt('middle', capture('p1', '2026-01-02T00:00:00.000Z'))
    await createReceipt('newest', capture('p2', '2026-01-03T00:00:00.000Z'))
    await addReceiptPhoto('middle', capture('p3'))
    await createReceipt('oldest', capture('p4', '2026-01-01T00:00:00.000Z'))

    const receipts = await listReceipts()
    expect(receipts.map((receipt) => receipt.id)).toEqual(['newest', 'middle', 'oldest'])
    expect(receipts.map((receipt) => receipt.photoCount)).toEqual([1, 2, 1])
    expect(receipts.every((receipt) => !('image' in receipt))).toBe(true)
  })

  it('persists reordering, deletion and subsequent additions with contiguous sequences', async () => {
    await createReceipt('receipt', capture('one'))
    await addReceiptPhoto('receipt', capture('two'))
    await addReceiptPhoto('receipt', capture('three'))
    await moveReceiptPhoto('receipt', 'three', -1)
    await moveReceiptPhoto('receipt', 'three', -1)
    await moveReceiptPhoto('receipt', 'three', -1) // Already first.
    await deleteReceiptPhoto('receipt', 'one')
    await addReceiptPhoto('receipt', capture('four'))

    const reopened = await getReceiptDetails('receipt')
    expect(reopened?.receipt.photoCount).toBe(3)
    expect(reopened?.photos.map(({ id, sequence }) => [id, sequence])).toEqual([['three', 1], ['two', 2], ['four', 3]])
    expect(await photoContents('receipt')).toEqual(['three', 'two', 'four'])
  })

  it('rejects deletion of the last photo and photos belonging to another receipt', async () => {
    await createReceipt('first', capture('one'))
    await createReceipt('second', capture('two'))
    await expect(deleteReceiptPhoto('first', 'one')).rejects.toThrow('at least one photo')
    await expect(deleteReceiptPhoto('first', 'two')).rejects.toThrow('not found')
    expect(await photoContents('first')).toEqual(['one'])
    expect(await photoContents('second')).toEqual(['two'])
  })

  it('serializes concurrent additions and competing attempts to delete the last photos', async () => {
    await createReceipt('receipt', capture('one'))
    await Promise.all([
      addReceiptPhoto('receipt', capture('two')),
      addReceiptPhoto('receipt', capture('three')),
    ])
    const added = await getReceiptDetails('receipt')
    expect(added?.receipt.photoCount).toBe(3)
    expect(added?.photos.map((item) => item.sequence)).toEqual([1, 2, 3])
    expect(new Set(added?.photos.map((item) => item.id))).toEqual(new Set(['one', 'two', 'three']))

    await deleteReceiptPhoto('receipt', 'one')
    const attempts = await Promise.allSettled([
      deleteReceiptPhoto('receipt', 'two'),
      deleteReceiptPhoto('receipt', 'three'),
    ])
    expect(attempts.filter((result) => result.status === 'fulfilled')).toHaveLength(1)
    expect(attempts.filter((result) => result.status === 'rejected')).toHaveLength(1)
    const remaining = await getReceiptDetails('receipt')
    expect(remaining?.receipt.photoCount).toBe(1)
    expect(remaining?.photos.map((item) => item.sequence)).toEqual([1])
  })

  it('deletes a receipt and all of its photos without affecting other receipts', async () => {
    await createReceipt('first', capture('one'))
    await addReceiptPhoto('first', capture('two'))
    await createReceipt('second', capture('three'))
    await deleteReceipt('first')

    expect(await getReceiptDetails('first')).toBeUndefined()
    expect((await listReceipts()).map((receipt) => receipt.id)).toEqual(['second'])
    expect(await photoContents('second')).toEqual(['three'])
    const db = await openDB('receipt-shooter', 2)
    expect(await db.count('photos')).toBe(1)
    db.close()
  })

  it('rolls back a new photo if a receipt ID or photo ID is duplicated', async () => {
    await createReceipt('receipt', capture('original'))
    await expect(createReceipt('receipt', capture('extra'))).rejects.toThrow()
    await expect(addReceiptPhoto('receipt', capture('original', undefined, 'replacement'))).rejects.toThrow()
    expect(await photoContents('receipt')).toEqual(['original'])
    expect((await listReceipts())[0].photoCount).toBe(1)
    const db = await openDB('receipt-shooter', 2)
    expect(await db.count('photos')).toBe(1)
    db.close()
  })

  it('rolls back an added photo when updating receipt metadata fails', async () => {
    await createReceipt('receipt', capture('original'))
    const originalPut = IDBObjectStore.prototype.put
    vi.spyOn(IDBObjectStore.prototype, 'put').mockImplementation(function (this: IDBObjectStore, value, key) {
      if (this.name === 'receipts') throw new DOMException('Storage full', 'QuotaExceededError')
      return originalPut.call(this, value, key)
    })
    await expect(addReceiptPhoto('receipt', capture('new'))).rejects.toMatchObject({ name: 'QuotaExceededError' })
    expect(await photoContents('receipt')).toEqual(['original'])
    expect((await listReceipts())[0].photoCount).toBe(1)
  })

  it('rolls back a photo deletion and its sequence changes when the count update fails', async () => {
    await createReceipt('receipt', capture('one'))
    await addReceiptPhoto('receipt', capture('two'))
    await addReceiptPhoto('receipt', capture('three'))
    const originalPut = IDBObjectStore.prototype.put
    vi.spyOn(IDBObjectStore.prototype, 'put').mockImplementation(function (this: IDBObjectStore, value, key) {
      if (this.name === 'receipts') throw new DOMException('Storage full', 'QuotaExceededError')
      return originalPut.call(this, value, key)
    })
    await expect(deleteReceiptPhoto('receipt', 'one')).rejects.toThrow()
    const reopened = await getReceiptDetails('receipt')
    expect(reopened?.receipt.photoCount).toBe(3)
    expect(reopened?.photos.map(({ id, sequence }) => [id, sequence])).toEqual([['one', 1], ['two', 2], ['three', 3]])
  })

  it('rolls back both positions when reordering fails partway through', async () => {
    await createReceipt('receipt', capture('one'))
    await addReceiptPhoto('receipt', capture('two'))
    const originalPut = IDBObjectStore.prototype.put
    let photoWrites = 0
    vi.spyOn(IDBObjectStore.prototype, 'put').mockImplementation(function (this: IDBObjectStore, value, key) {
      if (this.name === 'photos' && ++photoWrites === 2) throw new DOMException('Storage full', 'QuotaExceededError')
      return originalPut.call(this, value, key)
    })
    await expect(moveReceiptPhoto('receipt', 'two', -1)).rejects.toThrow()
    const reopened = await getReceiptDetails('receipt')
    expect(reopened?.photos.map(({ id, sequence }) => [id, sequence])).toEqual([['one', 1], ['two', 2]])
  })

  it('rolls back all photo deletions if receipt deletion fails', async () => {
    await createReceipt('receipt', capture('one'))
    await addReceiptPhoto('receipt', capture('two'))
    const originalDelete = IDBObjectStore.prototype.delete
    vi.spyOn(IDBObjectStore.prototype, 'delete').mockImplementation(function (this: IDBObjectStore, key) {
      if (this.name === 'receipts') throw new DOMException('Deletion failed', 'UnknownError')
      return originalDelete.call(this, key)
    })
    await expect(deleteReceipt('receipt')).rejects.toThrow()
    expect(await photoContents('receipt')).toEqual(['one', 'two'])
    expect((await listReceipts())[0].photoCount).toBe(2)
  })

  it('migrates every version-1 receipt and image, preserving metadata and bytes', async () => {
    await createLegacyDatabase()
    const receipts = await listReceipts()
    expect(receipts).toEqual([
      { id: 'legacy-two', capturedAt: '2026-01-03T03:04:05.000Z', status: 'pending', photoCount: 1 },
      { id: 'legacy-one', capturedAt: '2026-01-02T03:04:05.000Z', status: 'pending', photoCount: 1 },
    ])
    const first = await getReceiptDetails('legacy-one')
    const second = await getReceiptDetails('legacy-two')
    expect(first?.photos[0]).toMatchObject({ receiptId: 'legacy-one', sequence: 1, capturedAt: receipts[1].capturedAt })
    expect(first?.photos[0].id).toMatch(/^[0-9a-f-]{36}$/i)
    expect(first?.photos[0].id).not.toBe(second?.photos[0].id)
    expect(first?.photos[0].image.type).toBe('image/jpeg')
    expect(second?.photos[0].image.type).toBe('image/png')
    expect(await photoContents('legacy-one')).toEqual(['first original'])
    expect(await photoContents('legacy-two')).toEqual(['second original'])
    const migratedPhotoId = first?.photos[0].id
    await addReceiptPhoto('legacy-one', capture('new-photo'))
    expect((await getReceiptDetails('legacy-one'))?.photos.map((photo) => photo.id)).toEqual([migratedPhotoId, 'new-photo'])
    const db = await openDB('receipt-shooter', 2)
    expect([...db.objectStoreNames]).toEqual(['photos', 'receipts'])
    db.close()
  })

  it('aborts migration without losing old data if an orphaned legacy image exists', async () => {
    await createLegacyDatabase(true)
    await expect(listReceipts()).rejects.toThrow()
    const db = await openDB('receipt-shooter', 1)
    expect([...db.objectStoreNames]).toEqual(['images', 'receipts'])
    expect(await db.count('receipts')).toBe(2)
    expect(await db.count('images')).toBe(3)
    expect(await (await db.get('images', 'legacy-one')).image.text()).toBe('first original')
    expect(await (await db.get('images', 'orphan')).image.text()).toBe('orphan original')
    db.close()
  })

  it('keeps the legacy database intact when a receipt has no matching image', async () => {
    await createLegacyDatabase()
    const legacy = await openDB('receipt-shooter', 1)
    await legacy.delete('images', 'legacy-two')
    legacy.close()
    await expect(listReceipts()).rejects.toThrow()

    const reopened = await openDB('receipt-shooter', 1)
    expect([...reopened.objectStoreNames]).toEqual(['images', 'receipts'])
    expect(await reopened.count('receipts')).toBe(2)
    expect(await (await reopened.get('images', 'legacy-one')).image.text()).toBe('first original')
    expect(await reopened.get('receipts', 'legacy-one')).not.toHaveProperty('photoCount')
    reopened.close()
  })
})
