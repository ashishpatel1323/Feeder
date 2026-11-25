import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './styles.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)

// In development the worker only serves images saved for offline reading (see sw.js)
if ('serviceWorker' in navigator) {
  const script = import.meta.env.PROD ? '/sw.js' : '/sw.js?dev=1'
  window.addEventListener('load', () => navigator.serviceWorker.register(script).catch(() => undefined))
  // The worker asks this window for the pages of ZIM books (offline websites) kept on the device
  void import('./lib/zim/library').then((m) => m.serveZimToServiceWorker())
}
