// Static mockup renderer. ?layout=list|two  &state=clean|active
const P = {
  drag: 'M11 18a2 2 0 1 1-4 0 2 2 0 0 1 4 0m-2-8a2 2 0 1 0 0 4 2 2 0 0 0 0-4m0-6a2 2 0 1 0 0 4 2 2 0 0 0 0-4m6 4a2 2 0 1 0 0-4 2 2 0 0 0 0 4m0 2a2 2 0 1 0 0 4 2 2 0 0 0 0-4m0 6a2 2 0 1 0 0 4 2 2 0 0 0 0-4Z',
  edit: 'M20.71 7.04c.39-.39.39-1.04 0-1.41l-2.34-2.34c-.37-.39-1.02-.39-1.41 0l-1.84 1.83 3.75 3.75M3 17.25V21h3.75L17.81 9.93l-3.75-3.75L3 17.25Z',
  del: 'M6 19a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V7H6v12M19 4h-3.5l-1-1h-5l-1 1H5v2h14V4Z',
  tune: 'M3 17v2h6v-2H3M3 5v2h10V5H3m10 16v-2h8v-2h-8v-2h-2v6h2M7 9v2H3v2h4v2h2V9H7m14 4v-2H11v2h10m-6-4h2V7h4V5h-4V3h-2v6Z',
  add: 'M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2Z',
  folder: 'M20 6h-8l-2-2H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2m0 12H4V8h16v10Z',
  newfolder: 'M20 6h-8l-2-2H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2m-1 8h-3v3h-2v-3h-3v-2h3V9h2v3h3v2Z',
  back: 'M20 11v2H8l5.5 5.5-1.42 1.42L4.16 12l7.92-7.92L13.5 5.5 8 11h12Z',
  article: 'M19 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2m-5 14H7v-2h7v2m3-4H7v-2h10v2m0-4H7V7h10v2Z',
  bookmark: 'M17 3H7a2 2 0 0 0-2 2v16l7-3 7 3V5a2 2 0 0 0-2-2m0 15-5-2.18L7 18V5h10v13Z',
  settings: 'M12 15.5A3.5 3.5 0 0 1 8.5 12 3.5 3.5 0 0 1 12 8.5a3.5 3.5 0 0 1 3.5 3.5 3.5 3.5 0 0 1-3.5 3.5m7.43-2.53c.04-.32.07-.64.07-.97s-.03-.66-.07-1l2.11-1.63c.19-.15.24-.42.12-.64l-2-3.46c-.12-.22-.39-.31-.61-.22l-2.49 1c-.52-.39-1.06-.73-1.69-.98l-.37-2.65A.506.506 0 0 0 14 2h-4c-.25 0-.46.18-.5.42l-.37 2.65c-.63.25-1.17.59-1.69.98l-2.49-1c-.22-.09-.49 0-.61.22l-2 3.46c-.13.22-.07.49.12.64L4.57 11c-.04.34-.07.67-.07 1s.03.65.07.97l-2.11 1.66c-.19.15-.25.42-.12.64l2 3.46c.12.22.39.3.61.22l2.49-1.01c.52.4 1.06.74 1.69.99l.37 2.65c.04.24.25.42.5.42h4c.25 0 .46-.18.5-.42l.37-2.65c.63-.26 1.17-.59 1.69-.99l2.49 1.01c.22.08.49 0 .61-.22l2-3.46c.12-.22.07-.49-.12-.64l-2.11-1.66Z',
  upload: 'M9 16v-6H5l7-7 7 7h-4v6H9m-4 4v-2h14v2H5Z',
  download: 'M5 20h14v-2H5m14-9h-4V3H9v6H5l7 7 7-7Z',
  check: 'M21 7 9 19l-5.5-5.5 1.41-1.41L9 16.17 19.59 5.59 21 7Z',
  close: 'M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12 19 6.41Z',
  filter: 'M14 12v7.88c.04.3-.06.62-.29.83a.996.996 0 0 1-1.41 0l-2.01-2.01a.99.99 0 0 1-.29-.83V12h-.03L4.21 4.62a1 1 0 0 1 .17-1.4c.19-.14.4-.22.62-.22h14c.22 0 .43.08.62.22a1 1 0 0 1 .17 1.4L14.03 12H14Z',
}
const ic = (n, cls = '') => `<svg class="i ${cls}" viewBox="0 0 24 24"><path d="${P[n]}"/></svg>`

const COLORS = ['#e4572e', '#3b7dd8', '#2a9d8f', '#8e44ad', '#f4a261', '#264653', '#c0392b', '#16a085']
const FOLDERS = [
  ['Tech', [
    ['Hacker News', 'news.ycombinator.com/rss', 'Updated 4 min ago', 42],
    ['The Verge', 'theverge.com/rss/index.xml', 'Updated 12 min ago', 18],
    ['Ars Technica', 'feeds.arstechnica.com/arstechnica/index', 'Updated 12 min ago', 9],
    ['Simon Willison', 'simonwillison.net/atom/everything/', 'Updated 1 h ago', 3],
    ['Daring Fireball', 'daringfireball.net/feeds/main', 'Failed: 503 from server', 0, true],
  ]],
  ['Product', [
    ['Lenny\'s Newsletter', 'lennysnewsletter.com/feed', 'Updated 2 h ago', 2],
    ['Stratechery', 'stratechery.com/feed/', 'Updated 5 h ago', 1],
    ['Mind the Product', 'mindtheproduct.com/feed/', 'Updated 5 h ago', 6],
  ]],
  ['India', [
    ['The Ken', 'the-ken.com/feed/', 'Updated 30 min ago', 4],
    ['Mint — Technology', 'livemint.com/rss/technology', 'Updated 30 min ago', 27],
  ]],
  ['', [
    ['xkcd', 'xkcd.com/atom.xml', 'Updated 1 d ago', 1],
    ['Kottke', 'feeds.kottke.org/main', 'Updated 3 h ago', 11],
  ]],
]
const FOLDER_NAMES = FOLDERS.map((f) => f[0]).filter(Boolean)
let ci = 0
const folderSelect = (cur) =>
  `<select class="inl">${[...FOLDER_NAMES, ''].map((n) => `<option ${n === cur ? 'selected' : ''}>${n || 'No folder'}</option>`).join('')}<option>+ New folder…</option></select>`

const OPEN = ['Default', 'Reader', 'Browser tab']
const openSelect = (folder, idx) => {
  const cur = folder === 'Tech' && idx === 0 ? 'Browser tab' : folder === 'India' && idx === 1 ? 'Reader' : 'Default'
  return `<select class="inl" title="How articles from this feed open">${OPEN.map((o) => `<option ${o === cur ? 'selected' : ''}>${o}</option>`).join('')}</select>`
}
const full = (folder, idx) => !(folder === 'Tech' && idx === 0) && !(folder === '' && idx === 0)
const q = new URLSearchParams(location.search)
const layout = q.get('layout') || 'list'
const active = q.get('state') === 'active'
const statsMode = q.get('stats') // inline | panel
P.chart = 'M22 21H2V3h2v16h2v-7h4v7h2V8h4v11h2v-4h4v6Z'
P.chevron = 'M7.41 8.58 12 13.17l4.59-4.59L18 10l-6 6-6-6 1.41-1.42Z'
const RATES = { 'Hacker News': 31, 'The Verge': 22, 'Ars Technica': 14, 'Simon Willison': 3.1, 'Daring Fireball': 0.4, "Lenny's Newsletter": 0.3, 'Stratechery': 0.6, 'Mind the Product': 1.2, 'The Ken': 1.8, 'Mint — Technology': 9.5, 'xkcd': 0.4, 'Kottke': 4.2 }
function series(title) {
  let s = [...title].reduce((a, c) => a * 31 + c.charCodeAt(0), 7) >>> 0
  const r = RATES[title] ?? 1
  return Array.from({ length: 30 }, (_, i) => {
    s = (s * 1103515245 + 12345) >>> 0
    const noise = (s % 1000) / 1000
    if (title === 'Daring Fireball' && i > 22) return 0
    return Math.max(0, Math.round(r * (0.4 + noise * 1.2) * (i % 7 > 4 && r > 2 ? 0.5 : 1)))
  })
}
function spark(vals, w = 92, h = 22) {
  const max = Math.max(1, ...vals), bw = w / vals.length
  return `<svg width="${w}" height="${h}" class="spark">${vals.map((v, i) => `<rect x="${i * bw}" y="${h - Math.max(v ? 2 : 1, (v / max) * h)}" width="${bw - 1}" height="${Math.max(v ? 2 : 1, (v / max) * h)}" rx="0.5" fill="${v ? 'var(--accent)' : 'var(--border)'}"/>`).join('')}</svg>`
}
const perDay = (t) => { const r = RATES[t] ?? 1; return r >= 1 ? `${r.toFixed(r >= 10 ? 0 : 1)}/day` : `${(r * 7).toFixed(1)}/week` }
function activityCell(f) {
  const [title, , stat, , err] = f
  const s = series(title)
  return `<div class="act"><div class="actline">${spark(s)}<b>${perDay(title)}</b></div><div class="stat ${err ? 'err' : ''}">${err ? stat : stat.replace('Updated', 'Checked')}</div></div>`
}
function statsPanel(f) {
  const [title] = f
  const s = series(title)
  const max = Math.max(...s)
  const days = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'], dow = [38, 41, 44, 40, 36, 18, 15]
  const tile = (k, v, sub) => `<div class="tile"><div class="k">${k}</div><div class="v">${v}</div>${sub ? `<div class="sub">${sub}</div>` : ''}</div>`
  return `<div class="spanel">
    <div class="tiles">
      ${tile('Articles per day', '31', '30-day average · 18 last week')}
      ${tile('Last new article', '22 min ago', 'Checked 4 min ago · OK')}
      ${tile('You read', '34%', '312 of 918 in 30 days')}
      ${tile('Unread', '42', '3 saved')}
      ${tile('Average length', '4 min', '820 words · full article')}
      ${tile('Subscribed', 'Mar 2025', '2,140 articles stored')}
    </div>
    <div class="charts">
      <div><div class="k">New articles, last 30 days</div><svg width="100%" height="70" viewBox="0 0 300 70" preserveAspectRatio="none">${s.map((v, i) => `<rect x="${i * 10}" y="${66 - (v / max) * 62}" width="8" height="${(v / max) * 62}" rx="1" fill="var(--accent)"/>`).join('')}<line x1="0" x2="300" y1="66.5" y2="66.5" stroke="var(--border)"/></svg><div class="axis"><span>Oct 20</span><span>Nov 19</span></div></div>
      <div><div class="k">When it posts</div><div class="dow">${days.map((d, i) => `<div><span style="height:${dow[i]}px"></span><em>${d[0]}</em></div>`).join('')}</div></div>
    </div>
  </div>`
}

function feedRow(f, folder, idx) {
  const [title, url, stat, unread, err] = f
  const color = COLORS[ci++ % COLORS.length]
  const selected = active && ((folder === 'Tech' && (idx === 1 || idx === 2)))
  const editing = active && folder === 'Product' && idx === 0
  const dragging = active && folder === 'India' && idx === 1
  const dropBefore = active && folder === 'Tech' && idx === 3
  const cls = ['row', selected && 'sel', editing && 'edit', dragging && 'dragging', dropBefore && 'drop-before'].filter(Boolean).join(' ')
  const tcell = editing
    ? `<div class="tcell"><input class="inl" value="Lenny's Newsletter (PM)" autofocus><div class="url">${url} · original title “${title}”</div></div>`
    : `<div class="tcell"><div class="ttl">${title}</div><div class="url">${url}</div></div>`
  const acts = editing
    ? `<button class="ib" title="Save" style="color:var(--accent)">${ic('check', 's')}</button><button class="ib" title="Cancel">${ic('close', 's')}</button>`
    : `<button class="ib" title="Rename">${ic('edit', 's')}</button>${statsMode === 'panel' ? `<button class="ib ${title === 'Hacker News' ? 'on' : ''}" title="Show stats">${ic('chart', 's')}</button>` : ''}<button class="ib" title="Feed options (full text, filters…)">${ic('tune', 's')}</button><button class="ib danger" title="Unsubscribe">${ic('del', 's')}</button>`
  return `<div class="${cls}">
    <span class="handle" title="Drag to reorder or move">${ic('drag', 's')}</span>
    <span class="cb"><input type="checkbox" ${selected ? 'checked' : ''}></span>
    <span class="ficon" style="background:${color}">${title[0]}</span>
    ${tcell}
    <div class="fcell">${folderSelect(folder)}</div>
    <div class="ocell">${openSelect(folder, idx)}<span class="mfull" style="display:none"><span class="sw ${full(folder, idx) ? 'on' : ''}"></span>Full</span></div>
    <span class="fullcell" title="Fetch the full article instead of the RSS text"><span class="sw ${full(folder, idx) ? 'on' : ''}"></span></span>
    ${statsMode ? activityCell(f) : `<span class="stat ${err ? 'err' : ''}">${stat}</span>`}
    <span class="unread">${unread || ''}</span>
    <span class="acts" style="grid-column:auto">${acts}</span>
  </div>${statsMode === 'panel' && title === 'Hacker News' ? statsPanel(f) : ''}`
}

const colhead = () => `<div class="colhead"><span></span><span><input type="checkbox"></span><span></span><span>Feed</span><span>Folder</span><span>Opens in</span><span style="text-align:center">Full article</span><span>${statsMode ? 'Activity (30 days)' : 'Status'}</span><span style="text-align:right">Unread</span><span style="text-align:right">Actions</span></div>`

function folderBlock([name, feeds]) {
  const renaming = active && name === 'India'
  const target = false
  const unread = feeds.reduce((n, f) => n + f[3], 0)
  const title = name
    ? renaming
      ? `<input class="inl" value="India & Asia" autofocus><button class="ib" style="color:var(--accent)">${ic('check', 's')}</button><button class="ib">${ic('close', 's')}</button>`
      : `<span class="name">${name}</span>`
    : `<span class="name" style="color:var(--text-2)">No folder</span>`
  const acts = name && !renaming
    ? `<button class="btn ghost" title="Add a feed to ${name}">${ic('add', 's')}<span>Add feed</span></button><button class="ib" title="Rename folder">${ic('edit', 's')}</button><button class="ib danger" title="Delete folder">${ic('del', 's')}</button>`
    : ''
  return `<section class="folder ${target ? 'drop-target' : ''}">
    <div class="fhead">${name ? `<span class="handle" title="Drag to reorder folders">${ic('drag', 's')}</span>` : ''}${ic('folder', 's')}${title}<span class="cnt">${feeds.length} feeds · ${unread} unread</span><span class="sp"></span>${acts}</div>
    ${feeds.map((f, i) => feedRow(f, name, i)).join('')}
  </section>`
}

const header = (sub) => `
  <div class="head">
    <button class="ib" title="Back to articles">${ic('back')}</button>
    <h1>Manage feeds</h1><span class="meta">${sub}</span><span class="sp"></span>
    <button class="btn">${ic('upload', 's')}<span>Import OPML</span></button>
    <button class="btn">${ic('download', 's')}<span>Export OPML</span></button>
  </div>
  <div class="addbar">
    ${ic('add')}
    <input class="url" placeholder="Paste a feed or website address, e.g. theverge.com">
    <select><option>No folder</option>${FOLDER_NAMES.map((n) => `<option>${n}</option>`).join('')}</select>
    <button class="btn primary">Add feed</button>
  </div>`

function renderList() {
  return `${header('4 folders · 12 feeds')}
  <div class="tools">
    <input class="search" placeholder="Filter feeds by name or address">
    <button class="btn">${ic('newfolder', 's')}<span>New folder</span></button>
    <button class="btn ghost">${ic('filter', 's')}<span>Only feeds with errors</span></button>
    <span class="sp"></span>
    <span class="note">Order and empty folders stay in this browser; everything else syncs</span>
  </div>
  ${statsMode ? `<div class="sortbar"><span>Sort feeds</span>${['Folder order', 'Most active', 'Least active', 'Most unread', 'Read the least', 'Not updated in 30 days', 'Errors'].map((c, i) => `<button class="chip ${i === (statsMode === 'inline' ? 1 : 0) ? 'on' : ''}">${c}</button>`).join('')}</div>` : ''}
  ${active ? `<div class="bulk"><b>2 selected</b><span>Move to</span>${folderSelect('Tech').replace('class="inl"', 'class="inl" style="width:160px"')}<button class="btn danger">${ic('del', 's')}Unsubscribe</button><button class="btn ghost">Clear</button></div>` : ''}
  ${colhead()}
  ${FOLDERS.map(folderBlock).join('')}`
}

function renderTwo() {
  const sel = 'Tech'
  const list = FOLDERS.map(([n, feeds]) => {
    const isSel = n === sel
    const isTarget = active && n === 'Product'
    return `<div class="fi ${isSel ? 'sel' : ''} ${isTarget ? 'drop-target' : ''}">
      ${n ? `<span class="handle">${ic('drag', 's')}</span>` : '<span style="width:20px"></span>'}
      ${ic('folder', 's')}<span class="n">${n || 'No folder'}</span><span class="c">${feeds.length}</span>
      ${n ? `<button class="ib" title="Rename">${ic('edit', 's')}</button><button class="ib danger" title="Delete">${ic('del', 's')}</button>` : '<span style="width:60px"></span>'}
    </div>`
  }).join('')
  const all = `<div class="fi"><span style="width:20px"></span>${ic('article', 's')}<span class="n">All feeds</span><span class="c">12</span><span style="width:60px"></span></div>`
  const folder = FOLDERS.find((f) => f[0] === sel)
  ci = 0
  return `${header('4 folders · 12 feeds')}
  <div class="two">
    <div>
      <div class="fl">${all}${list}<div class="newf"><input placeholder="New folder name"><button class="btn">${ic('add', 's')}Add</button></div></div>
      <p class="note" style="margin:8px 4px">Drop feeds on a folder to move them. Order and empty folders stay in this browser.</p>
    </div>
    <div class="pane2">
      <div class="tools"><input class="search" placeholder="Filter feeds in Tech"><span class="sp"></span>
      ${active ? `<div class="bulk" style="margin:0"><b>2 selected</b><span>Move to</span>${folderSelect('Tech').replace('class="inl"', 'class="inl" style="width:150px"')}<button class="btn danger">${ic('del', 's')}Unsubscribe</button></div>` : ''}</div>
      ${colhead()}
      <section class="folder">${folder[1].map((f, i) => feedRow(f, sel, i)).join('')}</section>
    </div>
  </div>`
}

document.body.innerHTML = `<div class="app">
  <div class="rail">
    <span class="b">${ic('article')}</span><span class="b">${ic('bookmark')}</span><span style="flex:1"></span>
    <span class="b sel" title="Manage feeds">${ic('tune')}</span><span class="b">${ic('settings')}</span>
  </div>
  <main><div class="page">${layout === 'two' ? renderTwo() : renderList()}</div></main>
</div>`
