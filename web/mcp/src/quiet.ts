/** Hides Node's "SQLite is an experimental feature" warning (import this first in every entry point). */
const emit = process.emitWarning.bind(process)
process.emitWarning = ((warning: string | Error, ...rest: unknown[]) => {
  const text = typeof warning === 'string' ? warning : warning.message
  if (/SQLite is an experimental feature/.test(text)) return
  return (emit as (...a: unknown[]) => void)(warning, ...rest)
}) as typeof process.emitWarning
