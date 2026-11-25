/** Loads foliate-js, telling its PDF adapter where PDF.js's files are (public/pdfjs). */
export function loadFoliate() {
  ;(globalThis as { FEEDER_PDFJS_BASE?: string }).FEEDER_PDFJS_BASE ??= '/pdfjs/'
  return import('../../vendor/foliate-js/view.js')
}
