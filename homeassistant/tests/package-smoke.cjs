// Exercise the staged executable JAR with a disposable H2 database. This does
// not substitute for MariaDB, a Linux container, Home Assistant or iPhone tests.
const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const fs = require('node:fs')
const net = require('node:net')
const os = require('node:os')
const path = require('node:path')
const { spawn } = require('node:child_process')
const { setTimeout: delay } = require('node:timers/promises')

const [jar, h2] = process.argv.slice(2).map((p) => p && path.resolve(p))
if (!jar || !h2 || !fs.existsSync(jar) || !fs.existsSync(h2)) {
  throw new Error('Usage: node package-smoke.cjs <staged JAR> <H2 JAR>')
}
const java = process.env.JAVA_HOME
  ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  : 'java'
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'receiptshooter-smoke-'))
const image = fs.readFileSync(path.join(__dirname, '../../frontend/public/pwa-192x192.png'))
const firstPassword = 'Initial-test-password-123'
const nextPassword = 'Changed-test-password-456'
let child
let base

async function freePort() {
  const server = net.createServer()
  await new Promise((resolve, reject) => server.once('error', reject).listen(0, '127.0.0.1', resolve))
  const port = server.address().port
  await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()))
  return port
}

async function start(port) {
  const env = {
    ...process.env,
    DB_URL: `jdbc:h2:file:${path.join(root, 'testdb').replaceAll('\\', '/')};MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0`,
    DB_USERNAME: 'sa', DB_PASSWORD: 'isolated-test-database-password',
    ADMIN_USERNAME: 'smoke_admin', ADMIN_INITIAL_PASSWORD: firstPassword,
    SPRING_DATASOURCE_DRIVER_CLASS_NAME: 'org.h2.Driver',
    SERVER_ADDRESS: '127.0.0.1', SERVER_PORT: String(port),
    SESSION_TIMEOUT_SECONDS: '600', ACCOUNT_RECOVERY: 'false',
  }
  child = spawn(java, [`-Dloader.path=${h2}`, '-cp', jar, 'org.springframework.boot.loader.launch.PropertiesLauncher'], {
    env, stdio: 'ignore', windowsHide: true,
  })
  let failed
  child.once('error', (error) => { failed = error })
  base = `http://127.0.0.1:${port}`
  for (let attempt = 0; attempt < 200; attempt++) {
    if (failed) throw failed
    if (child.exitCode !== null) throw new Error(`Packaged application exited during startup (${child.exitCode}).`)
    try { if ((await fetch(`${base}/api/health`)).status === 200) return } catch { /* starting */ }
    await delay(250)
  }
  throw new Error('Packaged application did not become healthy in 50 seconds.')
}

async function stop() {
  if (!child || child.exitCode !== null) return
  const stopped = new Promise((resolve) => child.once('exit', resolve))
  child.kill('SIGTERM')
  await Promise.race([stopped, delay(10000).then(() => { throw new Error('The test application did not stop.') })])
}

function cookieFrom(response) {
  const header = response.headers.getSetCookie().find((value) => value.startsWith('RECEIPT_SESSION='))
  assert.ok(header, 'Missing session cookie')
  assert.match(header, /;\s*secure/i)
  assert.match(header, /;\s*httponly/i)
  assert.match(header, /;\s*samesite=lax/i)
  assert.match(header, /;\s*max-age=600/i)
  return header.split(';', 1)[0]
}

async function getCsrf(cookie) {
  const response = await fetch(`${base}/api/auth/csrf`, { headers: cookie ? { cookie } : {} })
  assert.equal(response.status, 200)
  return { csrf: await response.json(), cookie: cookie || cookieFrom(response) }
}

async function login(password) {
  const { csrf, cookie } = await getCsrf()
  const response = await fetch(`${base}/api/auth/login`, {
    method: 'POST', headers: { cookie, [csrf.headerName]: csrf.token },
    body: new URLSearchParams({ username: 'smoke_admin', password }),
  })
  assert.equal(response.status, 204)
  return cookieFrom(response)
}

async function upload(cookie, metadata) {
  const { csrf } = await getCsrf(cookie)
  const form = new FormData()
  form.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }), 'metadata.json')
  for (const photo of metadata.photos) {
    form.append(`photo-${photo.id}`, new Blob([image], { type: 'image/png' }), `photo-${photo.sequence}.png`)
  }
  return fetch(`${base}/api/receipts`, { method: 'POST', headers: { cookie, [csrf.headerName]: csrf.token }, body: form })
}

async function run() {
  const port = await freePort()
  await start(port)
  for (const url of ['/', '/manifest.webmanifest', '/sw.js', '/api/health']) {
    assert.equal((await fetch(`${base}${url}`)).status, 200, `Missing ${url}`)
  }
  assert.equal((await fetch(`${base}/api/receipts`)).status, 401)
  assert.equal((await fetch(`${base}/api/auth/login`, { method: 'POST' })).status, 403)
  let cookie = await login(firstPassword)
  let response = await fetch(`${base}/api/auth/session`, { headers: { cookie } })
  assert.equal((await response.json()).mustChangePassword, true)
  let { csrf } = await getCsrf(cookie)
  response = await fetch(`${base}/api/auth/password`, {
    method: 'POST', headers: { cookie, [csrf.headerName]: csrf.token, 'content-type': 'application/json' },
    body: JSON.stringify({ currentPassword: firstPassword, newPassword: nextPassword }),
  })
  assert.equal(response.status, 204)
  cookie = await login(nextPassword)

  for (const count of [1, 2]) {
    const capturedAt = new Date().toISOString()
    const metadata = { receiptId: crypto.randomUUID(), capturedAt,
      photos: Array.from({ length: count }, (_, i) => ({ id: crypto.randomUUID(), sequence: i + 1,
        capturedAt, contentType: 'image/png', size: image.length })) }
    response = await upload(cookie, metadata)
    assert.equal(response.status, 201)
    const ack = await response.json()
    assert.equal(ack.receiptId, metadata.receiptId)
    assert.equal(ack.photoCount, count)
    assert.equal(ack.status, 'STORED')
    response = await upload(cookie, metadata)
    assert.equal(response.status, 200)
    response = await fetch(`${base}/api/receipts/${metadata.receiptId}`, { headers: { cookie } })
    const details = await response.json()
    assert.deepEqual(details.photos.map((photo) => photo.photoId), metadata.photos.map((photo) => photo.id))
    assert.deepEqual(details.photos.map((photo) => photo.sequence), metadata.photos.map((photo) => photo.sequence))
    response = await fetch(`${base}/api/receipts/${metadata.receiptId}/photos/${metadata.photos[0].id}`, { headers: { cookie } })
    assert.equal(response.headers.get('content-type'), 'image/png')
    assert.deepEqual(Buffer.from(await response.arrayBuffer()), image)
  }

  await stop()
  await start(port)
  response = await fetch(`${base}/api/auth/session`, { headers: { cookie } })
  assert.equal(response.status, 200, 'Session did not survive restart')
  response = await fetch(`${base}/api/receipts`, { headers: { cookie } })
  assert.equal(response.status, 200)
  assert.equal((await response.json()).length, 2)
  console.log('Packaged JAR smoke checks passed with an isolated H2 database.')
}

run().catch((error) => { console.error(error.stack); process.exitCode = 1 }).finally(async () => {
  try { await stop() } catch { process.exitCode = 1 }
  const resolvedRoot = fs.realpathSync(root)
  const resolvedTemp = fs.realpathSync(os.tmpdir()) + path.sep
  if (!resolvedRoot.startsWith(resolvedTemp) || !path.basename(resolvedRoot).startsWith('receiptshooter-smoke-')) {
    throw new Error('Refusing to remove an unexpected temporary path.')
  }
  fs.rmSync(resolvedRoot, { recursive: true, force: true })
})
