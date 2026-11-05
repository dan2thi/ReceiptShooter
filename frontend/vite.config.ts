import { readFileSync } from 'node:fs'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'

export default defineConfig(({ command, isPreview }) => ({
  plugins: [
    react(),
    VitePWA({
      registerType: 'prompt',
      injectRegister: null,
      manifest: {
        name: 'ReceiptShooter',
        short_name: 'ReceiptShooter',
        description: 'Collect household receipts offline',
        theme_color: '#286c5d',
        background_color: '#f7f7f2',
        display: 'standalone',
        start_url: '/',
        scope: '/',
        icons: [
          { src: '/pwa-192x192.png', sizes: '192x192', type: 'image/png' },
          { src: '/pwa-512x512.png', sizes: '512x512', type: 'image/png' },
        ],
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,png,svg,ico,webmanifest}'],
        navigateFallbackDenylist: [/^\/api(?:\/|$)/, /^\/assets\//],
      },
    }),
  ],
  server: {
    host: '0.0.0.0',
    port: 5173,
    https:
      command === 'serve' && !isPreview
        ? {
            key: readFileSync(new URL('./certs/dev-key.pem', import.meta.url)),
            cert: readFileSync(new URL('./certs/dev.pem', import.meta.url)),
          }
        : undefined,
  },
  preview: {
    host: 'localhost',
  },
}))
