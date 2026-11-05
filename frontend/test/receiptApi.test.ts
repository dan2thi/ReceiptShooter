// @vitest-environment node
import { afterEach, describe, expect, it, vi } from 'vitest'
import { getUploadLimits, prepareUpload, uploadReceipt } from '../src/receiptApi'
import type { UploadLimits } from '../src/receiptApi'
import type { ReceiptDetails } from '../src/receiptStore'

const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64')
const jpeg = Uint8Array.from([0xff, 0xd8, 0xff, 0xc0, 0, 11, 8, 0, 2, 0, 3, 1, 1, 0x11, 0, 0xff, 0xd9])
const limits: UploadLimits = { maxPhotoBytes: 10000, maxTotalBytes: 20000, maxPhotos: 5, maxPixels: 1000, maxImageEdge: 32768 }
const time = '2026-01-02T03:04:05.000Z'
const receiptId = '11111111-1111-4111-8111-111111111111'
const firstId = '22222222-2222-4222-8222-222222222222'
const secondId = '33333333-3333-4333-8333-333333333333'

function receipt(): ReceiptDetails {
  return {
    receipt: { id: receiptId, capturedAt: time, status: 'pending', photoCount: 2 },
    photos: [
      { id: firstId, receiptId, sequence: 1, capturedAt: time, image: new Blob([png], { type: 'image/png' }), originalFilename: 'front.png' },
      { id: secondId, receiptId, sequence: 2, capturedAt: time, image: new Blob([jpeg], { type: 'image/jpeg' }), originalFilename: 'back.jpg' },
    ],
  }
}

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('receipt API client', () => {
  it.each([200, 201])('sends exact multipart metadata and original bytes and accepts HTTP %i', async (status) => {
    const details = receipt()
    const fetchMock = vi.fn().mockImplementation(async (path: string) => path === '/api/auth/csrf'
      ? new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'csrf-test' }), { status: 200 })
      : new Response(JSON.stringify({ receiptId, uploadedAt: time, photoCount: 2, status: 'STORED' }), { status }))
    vi.stubGlobal('fetch', fetchMock)
    const prepared = await prepareUpload(details, limits)
    expect(await uploadReceipt(details, prepared)).toMatchObject({ receiptId, photoCount: 2 })
    const [path, options] = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(path).toBe('/api/receipts')
    expect((options.headers as Headers).get('X-CSRF-TOKEN')).toBe('csrf-test')
    expect((options.headers as Headers).has('Authorization')).toBe(false)
    expect(options.credentials).toBe('same-origin')
    expect(options.cache).toBe('no-store')
    const form = options.body as FormData
    expect((form.get('metadata') as Blob).type).toBe('application/json')
    expect(JSON.parse(await (form.get('metadata') as Blob).text())).toEqual({
      receiptId, capturedAt: time, photos: [
        { id: firstId, sequence: 1, capturedAt: time, contentType: 'image/png', size: png.length },
        { id: secondId, sequence: 2, capturedAt: time, contentType: 'image/jpeg', size: jpeg.length },
      ],
    })
    expect((form.get(`photo-${firstId}`) as File).name).toBe('front.png')
    expect((form.get(`photo-${secondId}`) as File).type).toBe('image/jpeg')
    expect(new Uint8Array(await (form.get(`photo-${firstId}`) as Blob).arrayBuffer())).toEqual(new Uint8Array(png))
    expect(new Uint8Array(await (form.get(`photo-${secondId}`) as Blob).arrayBuffer())).toEqual(jpeg)
  })

  it('reads the authenticated limits from a relative no-store endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(limits), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    expect(await getUploadLimits()).toEqual(limits)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/upload-limits')
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ cache: 'no-store', credentials: 'same-origin' })
  })

  it.each([
    [{ receiptId: 'other', uploadedAt: time, photoCount: 2, status: 'STORED' }],
    [{ receiptId, uploadedAt: time, photoCount: 1, status: 'STORED' }],
    [{ receiptId, uploadedAt: time, photoCount: 2, status: 'PENDING' }],
    [{ receiptId, uploadedAt: 'invalid', photoCount: 2, status: 'STORED' }],
  ])('rejects an acknowledgement that does not confirm the entire receipt', async (acknowledgement) => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (path: string) => path === '/api/auth/csrf'
      ? new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'csrf-test' }), { status: 200 })
      : new Response(JSON.stringify(acknowledgement), { status: 201 })))
    const details = receipt()
    await expect(uploadReceipt(details, await prepareUpload(details, limits))).rejects.toThrow('acknowledgement')
  })

  it.each([[400, 'rejected'], [401, 'session'], [409, 'conflicts'], [413, 'limit'], [500, 'server']])
  ('explains HTTP %i failures', async (status, message) => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (path: string) => path === '/api/auth/csrf'
      ? new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'csrf-test' }), { status: 200 })
      : new Response('', { status })))
    const details = receipt()
    await expect(uploadReceipt(details, await prepareUpload(details, limits))).rejects.toThrow(message)
  })

  it('explains unreachable servers', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('network')))
    await expect(getUploadLimits()).rejects.toThrow('could not be reached')
  })

  it('rejects HEIC, damaged headers, photo count, bytes and dimensions before upload', async () => {
    const details = receipt()
    const heic = receipt()
    heic.photos[0].image = new Blob(['heic'], { type: 'image/heic' })
    await expect(prepareUpload(heic, limits)).rejects.toThrow('HEIC/HEIF')
    const damaged = receipt()
    damaged.photos[1].image = new Blob([new Uint8Array([0xff, 0xd8, 0xff])], { type: 'image/jpeg' })
    await expect(prepareUpload(damaged, limits)).rejects.toThrow()
    await expect(prepareUpload(details, { ...limits, maxPhotos: 1 })).rejects.toThrow('photos')
    await expect(prepareUpload(details, { ...limits, maxPhotoBytes: 10 })).rejects.toThrow('per-photo')
    await expect(prepareUpload(details, { ...limits, maxTotalBytes: png.length })).rejects.toThrow('total')
    await expect(prepareUpload(details, { ...limits, maxPixels: 1 })).rejects.toThrow('dimension')
    await expect(prepareUpload(details, { ...limits, maxImageEdge: 2 })).rejects.toThrow('dimension')
  })
})
