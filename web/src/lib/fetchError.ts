/**
 * A full-text fetch error as one plain sentence. The server's message can carry the site's whole
 * HTML error page ("HTTP 403: <!DOCTYPE html>…"), which must never reach the reader.
 */
export function fullTextErrorText(message: string): string {
  const status = /HTTP (\d{3})/.exec(message)?.[1]
  if (status === '401' || status === '403') return 'This site blocks apps from reading its full text.'
  if (status === '404' || status === '410') return 'The full article is no longer on the site.'
  if (status === '429') return 'The site is limiting requests right now, so the full text didn’t load.'
  if (status && status.startsWith('5')) return 'The site had a problem sending the full text.'
  if (/timeout|timed out|aborted/i.test(message)) return 'The site took too long to send the full text.'
  if (/failed to fetch|network/i.test(message)) return 'No connection, so the full text didn’t load.'
  const plain = message.replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim()
  return `Couldn’t load the full text${plain ? ` (${plain.length > 80 ? `${plain.slice(0, 80)}…` : plain})` : ''}.`
}
