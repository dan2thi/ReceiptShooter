import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { registerPwa } from './pwaUpdate'
import './index.css'

if (import.meta.env.PROD) registerPwa()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
