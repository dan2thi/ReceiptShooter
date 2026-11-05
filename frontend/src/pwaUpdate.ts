let waiting: ServiceWorker | null = null
let listener: ((available: boolean) => void) | null = null
let activating = false

function offerUpdate(worker: ServiceWorker | null) {
  if (worker && navigator.serviceWorker.controller) {
    waiting = worker
    listener?.(true)
  }
}

export function subscribeToUpdates(callback: (available: boolean) => void): () => void {
  listener = callback
  if (waiting) callback(true)
  return () => { if (listener === callback) listener = null }
}

export function applyUpdate() {
  if (!waiting) return
  activating = true
  waiting.postMessage({ type: 'SKIP_WAITING' })
}

export function registerPwa() {
  if (!('serviceWorker' in navigator)) return
  navigator.serviceWorker.addEventListener('controllerchange', () => {
    if (activating) window.location.reload()
  })
  void navigator.serviceWorker.register('/sw.js').then((registration) => {
    offerUpdate(registration.waiting)
    registration.addEventListener('updatefound', () => {
      const installing = registration.installing
      installing?.addEventListener('statechange', () => {
        if (installing.state === 'installed') offerUpdate(registration.waiting ?? installing)
      })
    })
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') void registration.update().catch(() => {})
    })
  }).catch(() => { /* Capture still works; the next online launch can retry registration. */ })
}
