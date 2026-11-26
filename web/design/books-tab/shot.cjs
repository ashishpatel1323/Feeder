const { chromium } = require('/private/tmp/claude-501/ytshots/node_modules/playwright-core');
(async () => {
  const b = await chromium.launch({ channel: 'chrome' });
  const p = await b.newPage({ viewport: { width: 1500, height: 1000 }, deviceScaleFactor: 2 });
  await p.goto('file:///private/tmp/claude-501/books/index.html');
  await p.waitForTimeout(3500);
  for (const id of ['a','aw','b','c','chg']) await (await p.$('#'+id)).screenshot({ path: `/private/tmp/claude-501/books/${id}.png` });
  await b.close();
})();
