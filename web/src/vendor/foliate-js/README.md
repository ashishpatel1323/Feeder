# foliate-js (vendored)

Copied from https://github.com/johnfactotum/foliate-js at commit
78914aef4466eb960965702401634c2cb348e9b1 (MIT, see LICENSE). zip.js is BSD-3-Clause, fflate MIT.

Only the modules Feeder's Ebooks tab uses are here. Update by copying the same files from a newer
commit; don't edit them, except `pdf.js` (below).

PDF: `vendor/pdfjs/pdf.mjs` is bundled; PDF.js's worker, fonts, character maps and CSS (same
PDF.js release, Apache-2.0) live in `web/public/pdfjs/` and are copied into the Android reader's
assets. `pdf.js` is upstream's adapter with one change: it finds those files through
`globalThis.FEEDER_PDFJS_BASE`. Source maps are left out.
