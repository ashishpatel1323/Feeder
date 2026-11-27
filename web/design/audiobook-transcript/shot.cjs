const { chromium } = require(process.env.PW);
(async () => {
  const b = await chromium.launch({ channel: 'chrome' });
  const p = await b.newPage({ viewport: { width: 1800, height: 1000 }, deviceScaleFactor: 2 });
  await p.goto('file://' + __dirname + '/index.html');
  await p.waitForTimeout(3500);
  for (const id of ['a','b','c']) await (await p.$('#'+id)).screenshot({ path: __dirname + `/${id}.png` });
  await b.close();
})();
