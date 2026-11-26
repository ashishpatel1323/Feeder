// Copies the MCP server (web/mcp: dist + production node_modules) into Feeder.app/Contents/Resources/mcp.
// electron-builder leaves node_modules out of extraResources, so this is done after packing instead.
const fs = require('node:fs')
const path = require('node:path')

exports.default = async function afterPack(context) {
  const src = path.join(__dirname, '..', 'mcp')
  const app = fs.readdirSync(context.appOutDir).find((f) => f.endsWith('.app'))
  const dest = path.join(context.appOutDir, app, 'Contents', 'Resources', 'mcp')
  fs.rmSync(dest, { recursive: true, force: true })
  fs.mkdirSync(dest, { recursive: true })
  // Left out: source maps, TypeScript types, pdf.js's non-Node builds and its optional native canvas
  // (only needed to render pages; text extraction works without it)
  const skip = /(\.map|\.d\.ts|\.d\.mts)$|[\\/]@napi-rs[\\/]|[\\/]pdfjs-dist[\\/](build|web|types|image_decoders)[\\/]/
  for (const part of ['dist', 'node_modules', 'package.json'])
    fs.cpSync(path.join(src, part), path.join(dest, part), { recursive: true, dereference: true, filter: (f) => !skip.test(f) })
}
