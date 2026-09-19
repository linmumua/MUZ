const fs = require('fs');
const http = require('http');
const path = require('path');
const assert = require('assert/strict');

const [htmlPath, statePath, token, screenshotDirArg] = process.argv.slice(2);
if (!htmlPath || !statePath || !token) throw new Error('用法：contract.cjs <html> <state.json> <token> [screenshot-dir]');

function loadPuppeteer() {
  const candidates = [
    process.env.PUPPETEER_MODULE,
    process.env.PUPPETEER_MODULE_PATH,
    'puppeteer',
    'C:/Users/Admin/AppData/Local/npm-cache/_npx/7d92d9a2d2ccc630/node_modules/puppeteer'
  ].filter(Boolean);
  const errors = [];
  for (const candidate of candidates) {
    try { return require(candidate); } catch (error) { errors.push(candidate + ': ' + error.message); }
  }
  throw new Error('找不到 Puppeteer：' + errors.join(' | '));
}

const puppeteer = loadPuppeteer();
const apiState = JSON.parse(fs.readFileSync(statePath, 'utf8'));
const html = fs.readFileSync(htmlPath, 'utf8')
  .replaceAll('__MUZ_TOKEN__', token)
  .replaceAll('__MUZ_STATE__', JSON.stringify(apiState.snapshot));
const projectRoot = process.cwd();
const resourceRoot = path.resolve(projectRoot, 'build/paper-26.2/resources/main/craftengine/muz/resourcepack/assets/muz/textures/font');
const backgroundPath = path.resolve(projectRoot, 'src/main/resources/debug-world-background.png');
const screenshotDir = path.resolve(screenshotDirArg || path.join(require('os').tmpdir(), 'muz-debug-web-browser-screenshots'));
fs.mkdirSync(screenshotDir, {recursive: true});
const resources = apiState.previewResources || [];
if (!Array.isArray(resources) || resources.length === 0) throw new Error('Java fixture 未导出正式 PREVIEW_RESOURCE_WHITELIST manifest');
const resourceByTexture = new Map(resources.map(resource => [resource.texture, resource]));
const fieldRows = apiState.snapshot?.fields || [];
const fieldOptions = new Map(fieldRows.map(field => [field.key, Array.isArray(field.options) ? field.options : []]));
const saveRequests = [];
const requestedPaths = [];

function realResourcePath(texture) {
  if (typeof texture !== 'string' || !texture.startsWith('muz:font/')) throw new Error('非法真实资源键：' + texture);
  const relative = texture.slice('muz:font/'.length).replaceAll('/', path.sep);
  const file = path.resolve(resourceRoot, relative);
  if (file !== resourceRoot && !file.startsWith(resourceRoot + path.sep)) throw new Error('资源路径越界：' + texture);
  return file;
}
function pngSize(file) {
  const bytes = fs.readFileSync(file);
  assert.equal(bytes.readUInt32BE(0), 0x89504e47, 'PNG 签名无效：' + file);
  assert.equal(bytes.toString('ascii', 12, 16), 'IHDR', 'PNG 缺少 IHDR：' + file);
  return {width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20)};
}
for (const resource of resources) {
  if (!resource.texture?.startsWith('muz:font/')) throw new Error('manifest 资源键无效：' + JSON.stringify(resource));
  const file = realResourcePath(resource.texture);
  if (!fs.existsSync(file)) throw new Error('真实 PNG 不存在：' + file);
  const size = pngSize(file);
  assert(size.width > 1 && size.height > 1, '禁止 1×1 假图：' + resource.texture);
}
const hotbarNames = ['hotbar_egg.png', 'hotbar_water.png', 'hotbar_tomato.png', 'hotbar_select.png'];
for (const name of hotbarNames) {
  const resource = resources.find(item => item.texture === 'muz:font/' + name);
  assert(resource, '正式 manifest 缺少真实 Hotbar 资源：' + name);
  assert.deepEqual(pngSize(realResourcePath(resource.texture)), {width: 20, height: 22}, 'Hotbar PNG 几何错误：' + name);
}
assert(fs.existsSync(backgroundPath), '真实世界背景不存在：' + backgroundPath);

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  const requestPath = url.pathname;
  requestedPaths.push(requestPath);
  const json = (status, value) => {
    const body = Buffer.from(JSON.stringify(value));
    res.writeHead(status, {'Content-Type': 'application/json', 'Content-Length': body.length});
    res.end(body);
  };
  if (requestPath === '/') {
    const body = Buffer.from(html);
    res.writeHead(200, {'Content-Type': 'text/html; charset=utf-8', 'Content-Length': body.length});
    res.end(body);
    return;
  }
  if (requestPath === '/favicon.ico' || requestPath === '/favicon.png') {
    res.writeHead(204);
    res.end();
    return;
  }
  if (requestPath === '/api/state') { json(200, apiState); return; }
  if (requestPath === '/api/preview-resources') { json(200, {ok: true, resources}); return; }
  if (requestPath === '/api/preview-background') {
    const body = fs.readFileSync(backgroundPath);
    res.writeHead(200, {'Content-Type': 'image/png', 'Content-Length': body.length, 'Cache-Control': 'no-store'});
    res.end(body);
    return;
  }
  if (requestPath.startsWith('/api/resource/')) {
    const texture = decodeURIComponent(requestPath.slice('/api/resource/'.length));
    const resource = resourceByTexture.get(texture);
    if (!resource) { json(404, {ok: false, messages: ['资源不在正式 manifest 白名单内：' + texture]}); return; }
    const body = fs.readFileSync(realResourcePath(resource.texture));
    res.writeHead(200, {'Content-Type': 'image/png', 'Content-Length': body.length, 'Cache-Control': 'no-store'});
    res.end(body);
    return;
  }
  if (requestPath === '/api/save') {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', () => {
      let request = {};
      try { request = JSON.parse(body || '{}'); } catch (_) { json(400, {ok: false, messages: ['JSON 无效']}); return; }
      const values = request.values || {};
      for (const [key, raw] of Object.entries(values)) {
        const options = fieldOptions.get(key) || [];
        if (options.length && !options.some(option => String(option) === String(raw) || Number(option) === Number(raw))) {
          json(400, {ok: false, messages: [key + ' 不是当前资源包已生成的合法档位。']});
          return;
        }
        const field = fieldRows.find(item => item.key === key);
        if (field?.type === 'integer' && (!Number.isFinite(raw) || !Number.isInteger(raw)
            || (field.min != null && raw < field.min) || (field.max != null && raw > field.max))) {
          json(400, {ok: false, messages: [key + ' 必须是范围内的整数。']});
          return;
        }
      }
      saveRequests.push({...values});
      apiState.snapshot.values = {...apiState.snapshot.values, ...values};
      json(200, {ok: true, snapshot: apiState.snapshot, appliedKeys: Object.keys(values), messages: []});
    });
    return;
  }
  if (requestPath === '/api/reload') { json(200, {ok: true, snapshot: apiState.snapshot, appliedKeys: [], messages: []}); return; }
  json(404, {ok: false, messages: ['不存在']});
});

function layerSelector(kind) { return `.layer[data-layer="${kind}"]`; }
async function layerRect(page, kind) {
  return page.$eval(layerSelector(kind), el => {
    const r = el.getBoundingClientRect();
    return {left: r.left, top: r.top, width: r.width, height: r.height,
      right: r.right, bottom: r.bottom, cx: r.left + r.width / 2, cy: r.top + r.height / 2,
      dragging: el.classList.contains('dragging')};
  });
}
async function frame(page) { await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))); }
async function fieldValue(page, key) {
  return page.$eval(`[data-key="${key}"]`, el => el.type === 'checkbox' ? (el.checked ? 1 : 0) : Number(el.value));
}
async function screenScale(page) {
  return page.$eval('#screen', el => el.getBoundingClientRect().width / parseFloat(getComputedStyle(el).width));
}
async function imageAlpha(page, src) {
  return page.evaluate(async url => {
    const image = new Image();
    image.src = url;
    await image.decode();
    const canvas = document.createElement('canvas');
    canvas.width = image.naturalWidth;
    canvas.height = image.naturalHeight;
    const context = canvas.getContext('2d');
    context.drawImage(image, 0, 0);
    const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
    let nonTransparent = 0;
    let nonBlack = 0;
    for (let i = 0; i < pixels.length; i += 4) {
      if (pixels[i + 3] > 0) nonTransparent++;
      if (pixels[i] || pixels[i + 1] || pixels[i + 2]) nonBlack++;
    }
    return {width: image.naturalWidth, height: image.naturalHeight, nonTransparent, nonBlack};
  }, src);
}
async function dragLayer(page, kind, dx, dy, keyX, keyY, foreignPointerUp = false) {
  const before = await layerRect(page, kind);
  assert(before.width > 0 && before.height > 0 && before.width * before.height > 20, kind + ' 图层必须有真实可见几何：' + JSON.stringify(before));
  const scale = await screenScale(page);
  const beforeValue = await fieldValue(page, keyX);
  const x = before.cx, y = before.cy;
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.move(x + dx * 0.4, y + dy * 0.4, {steps: 2});
  await page.mouse.move(x + dx, y + dy, {steps: 2});
  const during = await layerRect(page, kind);
  assert(during.dragging, kind + ' pointermove 后必须仍处于拖动状态');
  if (foreignPointerUp) {
    await page.$eval('#mcEditor', el => el.dispatchEvent(new PointerEvent('pointerup', {
      bubbles: true, pointerId: 987654, clientX: 2, clientY: 2
    })));
    await frame(page);
    assert(await page.$eval(layerSelector(kind), el => el.classList.contains('dragging')),
      kind + ' 非当前 pointerId 的 pointerup 不得结束首个 pointer');
  }
  await page.mouse.up();
  await frame(page);
  const after = await layerRect(page, kind);
  assert(Math.abs(after.left - before.left) > 3 || Math.abs(after.top - before.top) > 3,
    kind + ' 拖动没有改变最终位置：' + JSON.stringify({before, after}));
  assert(Math.abs(after.left - during.left) <= 3 && Math.abs(after.top - during.top) <= 3,
    kind + ' pointerup 后最终位置回弹：' + JSON.stringify({during, after}));
  const afterValue = await fieldValue(page, keyX);
  assert.equal(afterValue - beforeValue, Math.round(dx / scale),
    kind + ' CSS 位移未按当前 screenScale 换算为 MC 像素：' + JSON.stringify({scale, beforeValue, afterValue, dx}));
  return {before, during, after, scale, beforeValue, afterValue, keyY};
}
async function setRawField(page, key, value) {
  await page.$eval(`[data-key="${key}"]`, (el, next) => {
    el.value = String(next);
    el.dispatchEvent(new Event('input', {bubbles: true}));
  }, value);
  await frame(page);
}
async function setIntegerField(page, key, value) {
  await setRawField(page, key, value);
  assert.equal(await fieldValue(page, key), value, '连续整数表单值未写回：' + key);
}

(async () => {
  let browser;
  // 启动阶段失败也必须进入收尾，不能留下监听服务或已启动的浏览器。
  try {
  await new Promise((resolve, reject) => server.listen(0, '127.0.0.1', resolve).on('error', reject));
  const url = `http://127.0.0.1:${server.address().port}/`;
  const launchOptions = {headless: true, args: ['--no-sandbox', '--disable-gpu', '--no-first-run'], defaultViewport: {width: 1280, height: 900}};
  const executablePath = process.env.CHROME_PATH || await Promise.resolve(puppeteer.executablePath());
  if (executablePath) launchOptions.executablePath = executablePath;
  browser = await puppeteer.launch(launchOptions);
  const page = await browser.newPage();
  const errors = [];
  let expectingInvalidSave = false;
  let expectedSaveErrors = 0;
  page.on('pageerror', error => errors.push(String(error)));
  page.on('console', message => {
    if (message.type() !== 'error') return;
    const source = message.location().url || '';
    // 只有本用例刻意提交小数的保存接口 400 是预期拒绝；资源失败和 JS 异常仍全部报错。
    if (expectingInvalidSave && source === url + 'api/save'
        && message.text().includes('400 (Bad Request)')) {
      expectedSaveErrors++;
      return;
    }
    errors.push(message.text());
  });
    await page.goto(url, {waitUntil: 'networkidle0'});
    await page.waitForSelector('#screen .layer');
    await page.waitForFunction(() => {
      const hotbar = document.querySelector('.layer[data-layer="hotbar"]');
      const images = hotbar ? [...hotbar.querySelectorAll('img')] : [];
      return images.length >= 4 && images.every(img => img.complete && img.naturalWidth > 0 && img.naturalHeight > 0);
    }, {timeout: 10000});
    assert.equal(errors.length, 0, '页面启动 JS 错误：' + errors.join(' | '));

    const initialShot = path.join(screenshotDir, 'debug-web-initial.png');
    await page.screenshot({path: initialShot, fullPage: true});
    const screen = await page.$eval('#screen', el => {
      const rect = el.getBoundingClientRect();
      const style = getComputedStyle(el);
      return {width: rect.width, height: rect.height, cssWidth: parseFloat(style.width), cssHeight: parseFloat(style.height),
        ratio: rect.width / parseFloat(style.width), expected: Math.min((innerWidth - 28) / parseFloat(style.width), (innerHeight - 80) / parseFloat(style.height))};
    });
    assert(screen.width > 640 && screen.height > 360, '正式 HUD 预览必须按 fit 实际放大：' + JSON.stringify(screen));
    assert(Math.abs(screen.ratio - screen.expected) < 0.06, 'screenScale 必须接近 viewport 可用尺寸：' + JSON.stringify(screen));
    const boundary = await page.$eval('.screen-boundary', element => {
      const rect = element.getBoundingClientRect(), style = getComputedStyle(element);
      return {width: rect.width, height: rect.height, borderTop: style.borderTopWidth,
        borderRight: style.borderRightWidth, borderBottom: style.borderBottomWidth,
        borderLeft: style.borderLeftWidth, pointerEvents: style.pointerEvents,
        label: element.querySelector('.screen-boundary-label')?.textContent};
    });
    assert(Math.abs(boundary.width - screen.width) < 1 && Math.abs(boundary.height - screen.height) < 1,
      '边界必须覆盖完整逻辑屏幕：' + JSON.stringify({screen, boundary}));
    assert(['borderTop', 'borderRight', 'borderBottom', 'borderLeft'].every(key => parseFloat(boundary[key]) > 0),
      '逻辑屏幕四边必须可见：' + JSON.stringify(boundary));
    assert.equal(boundary.pointerEvents, 'none', '边界不可拦截拖动与缩放');
    assert.equal(boundary.label, '640×360 MC 边界');
    assert.equal(await page.$eval('#snapToggle', el => el.getAttribute('aria-pressed')), 'false', '默认必须是自由拖动');

    const background = await page.$eval('#worldBackgroundImage', el => ({image: getComputedStyle(el).backgroundImage, filter: getComputedStyle(el).filter, opacity: getComputedStyle(el).opacity}));
    assert(background.image.includes('/api/preview-background'), '必须加载真实 Minecraft 世界背景：' + JSON.stringify(background));
    assert(background.filter.includes('blur'), '世界背景必须使用模糊层：' + JSON.stringify(background));
    const backgroundBytes = await page.evaluate(async () => (await fetch('/api/preview-background', {cache: 'no-store'})).arrayBuffer().then(bytes => bytes.byteLength));
    assert(backgroundBytes > 100, '真实世界背景不能是空占位图：' + backgroundBytes);
    const hudFilters = await page.evaluate(() => ({screen: getComputedStyle(document.querySelector('#screen')).filter, layer: getComputedStyle(document.querySelector('.layer')).filter}));
    assert.equal(hudFilters.screen, 'none', '背景 blur 不得作用于 HUD 逻辑舞台：' + JSON.stringify(hudFilters));
    assert.equal(hudFilters.layer, 'none', '背景 blur 不得作用于 HUD 图层：' + JSON.stringify(hudFilters));

    const hotbarImages = await page.$$eval('.layer[data-layer="hotbar"] img', images => images.map(img => ({src: img.src, width: img.naturalWidth, height: img.naturalHeight, visible: img.getBoundingClientRect().width > 0 && img.getBoundingClientRect().height > 0 && getComputedStyle(img).display !== 'none' && getComputedStyle(img).visibility !== 'hidden' && Number(getComputedStyle(img).opacity) > 0})));
    assert.equal(hotbarImages.length, 4, 'Hotbar 必须是三个道具加一个独立选中框：' + JSON.stringify(hotbarImages));
    assert(hotbarImages.every(item => item.width === 20 && item.height === 22 && item.visible), '三个道具与选中框必须按真实 20×22 PNG 可见：' + JSON.stringify(hotbarImages));
    const iconImages = hotbarImages.filter(item => /hotbar_(egg|water|tomato)\.png/.test(item.src));
    assert.equal(iconImages.length, 3, 'Hotbar 必须包含鸡蛋、水桶、番茄真实资源：' + JSON.stringify(hotbarImages));
    assert.equal(new Set(iconImages.map(item => item.src)).size, 3, '三个 Hotbar 图标不能复用同一 URL');
    for (const item of iconImages) {
      const alpha = await imageAlpha(page, item.src);
      assert(alpha.nonTransparent > 3 && alpha.nonBlack > 3, 'Hotbar 真实 PNG 不得是透明/空假图：' + JSON.stringify({src: item.src, alpha}));
    }
    const selectImage = hotbarImages.find(item => /hotbar_select\.png/.test(item.src));
    assert(selectImage, '缺少真实 hotbar_select.png');
    const selectAlpha = await imageAlpha(page, selectImage.src);
    assert(selectAlpha.nonTransparent > 3, '选中框真实 PNG 必须有可见 alpha：' + JSON.stringify(selectAlpha));

    // 槽原点可在屏外，只要真实头像仍在屏内，就不能把透明留白当作越界。
    const geo = apiState.snapshot.geometry;
    const avatarLayout = geo.avatarLayouts.find(layout => layout.middleScale === 6 && layout.outlined);
    const avatarAdvance = avatarLayout.slots.length * avatarLayout.slotWidth + (avatarLayout.slots.length - 1) * 6;
    const cardGeo = geo.cards.find(card => card.height === 53);
    const cardAdvance = (geo.sampleCards.length - 1) * 22 + cardGeo.advance;
    const counterGeo = geo.counterTiers.find(tier => tier.scale === 100);
    const counterAdvance = geo.counters.length * counterGeo.advance + (geo.counters.length - 1) * 2;
    const containerAdvance = Math.max(cardAdvance, avatarAdvance, counterAdvance);
    const avatarOrigin = Math.floor((geo.screenWidth - containerAdvance) / 2) + Math.floor((containerAdvance - avatarAdvance) / 2);
    const insideOffset = -5 - avatarOrigin;
    const firstLead = Math.floor((avatarLayout.slots[0].slotWidth - avatarLayout.slots[0].contentAdvance) / 2);
    assert(firstLead > 5, '夹具必须包含足够的透明槽内留白');
    await setRawField(page, 'trick-hud.avatar-offset-x', insideOffset);
    assert.equal(await fieldValue(page, 'trick-hud.avatar-offset-x'), insideOffset, '槽原点越界但真实内容在屏内时不得改写偏移');
    assert.equal(await page.$eval('.layer[data-layer="avatar"]', el => Number(el.dataset.x)), firstLead - 5);
    await assertLogicalBounds(page, '头像透明留白不参与越界');
    await setRawField(page, 'trick-hud.avatar-offset-x', 0);
    // 服务端用净前进量居中：此负间距恰好填满 640px，不应自动写入 +590px 偏移。
    await setRawField(page, 'trick-hud.avatar-gap', -360);
    assert.equal(await fieldValue(page, 'trick-hud.avatar-gap'), -360, '边界内负间距必须原样保留');
    assert.equal(await fieldValue(page, 'trick-hud.avatar-offset-x'), 0, '合法居中不得被网页改写为游戏内越界偏移');
    const edgeAvatar = await page.$eval('.layer[data-layer="avatar"]', el => ({x: Number(el.dataset.x), width: Number(el.dataset.w)}));
    assert.deepEqual(edgeAvatar, {x: 0, width: 640}, '头像视觉包络必须与服务端净前进量居中对齐');
    await assertLogicalBounds(page, '负间距服务端居中对齐');
    await setRawField(page, 'trick-hud.avatar-gap', -6);
    assert.equal(await fieldValue(page, 'trick-hud.avatar-gap'), -6, '合法负头像间距必须保留');
    await assertLogicalBounds(page, '合法负头像间距');
    await setRawField(page, 'trick-hud.avatar-gap', -10000);
    const normalizedAvatarGap = await fieldValue(page, 'trick-hud.avatar-gap');
    assert(Number.isInteger(normalizedAvatarGap) && normalizedAvatarGap > -10000 && normalizedAvatarGap <= 0,
      '极端负头像间距必须按真实左/右包络规范到可见整数：' + normalizedAvatarGap);
    await assertLogicalBounds(page, '极端负头像间距');
    await setIntegerField(page, 'trick-hud.avatar-gap', 6);
    await setIntegerField(page, 'trick-hud.avatar-offset-x', 0);

    const drags = [];
    drags.push(await dragLayer(page, 'card', 36, 18, 'trick-hud.card-offset-x', 'trick-hud.offset-down', true));
    drags.push(await dragLayer(page, 'avatar', 36, 18, 'trick-hud.avatar-offset-x', 'trick-hud.avatar-offset-down'));
    drags.push(await dragLayer(page, 'counter', 36, 18, 'trick-hud.counter.offset-x', 'trick-hud.counter.offset-down'));
    drags.push(await dragLayer(page, 'hotbar', 36, 18, 'hotbar-hud.offset-x', 'hotbar-hud.offset-y'));
    assert.equal(drags.length, 4, '牌行、头像、记牌、Hotbar 四层必须全部完成最终帧拖动');

    const scale3 = await screenScale(page);
    await page.$eval('#guiScale', el => { el.value = '2'; el.dispatchEvent(new Event('change', {bubbles: true})); });
    await frame(page);
    const scale2 = await screenScale(page);
    await page.$eval('#guiScale', el => { el.value = '4'; el.dispatchEvent(new Event('change', {bubbles: true})); });
    await frame(page);
    const scale4 = await screenScale(page);
    assert(scale2 > 0, 'GUI 2 倍率必须产生有效 CSS 缩放：' + JSON.stringify({scale2, scale3}));
    assert(scale4 >= scale3 - 0.02, 'GUI 4 倍率不得比 GUI 3 更小：' + JSON.stringify({scale4, scale3}));
    await page.$eval('#guiScale', el => { el.value = '3'; el.dispatchEvent(new Event('change', {bubbles: true})); });
    await frame(page);

    const layoutIntegers = {
      'trick-hud.card-step': [22.5, 22],
      'trick-hud.avatar-gap': [6.5, 6],
      'trick-hud.counter.gap': [2.5, 2]
    };
    for (const [key, [fraction, valid]] of Object.entries(layoutIntegers)) {
      await setRawField(page, key, fraction);
      assert.equal(await fieldValue(page, key), fraction, '布局小数不得被网页先行取整：' + key);
      const requestCountBeforeLayoutInvalid = saveRequests.length;
      const saveErrorsBeforeLayoutInvalid = expectedSaveErrors;
      expectingInvalidSave = true;
      const rejectedLayoutSave = page.waitForResponse(response => response.url() === url + 'api/save' && response.status() === 400);
      await page.$eval('#save', el => el.click());
      await rejectedLayoutSave;
      await page.waitForFunction(() => document.querySelector('#status')?.classList.contains('error'), {timeout: 5000});
      await frame(page);
      expectingInvalidSave = false;
      assert.equal(expectedSaveErrors, saveErrorsBeforeLayoutInvalid + 1,
        '布局小数必须只产生一次预期 400：' + key);
      assert.equal(saveRequests.length, requestCountBeforeLayoutInvalid, '布局小数不得伪成功保存：' + key);
      assert.equal(await fieldValue(page, key), fraction, '保存失败必须保留布局小数与 dirty：' + key);
      await setIntegerField(page, key, valid);
    }

    const continuousYKeys = ['trick-hud.offset-down', 'trick-hud.avatar-offset-down', 'trick-hud.counter.offset-down', 'hotbar-hud.offset-y'];
    for (const key of continuousYKeys) {
      assert.equal((fieldOptions.get(key) || []).length, 0, '连续 Y 字段不得暴露离散 options：' + key);
    }
    await setRawField(page, 'trick-hud.offset-down', 37.5);
    assert.equal(await fieldValue(page, 'trick-hud.offset-down'), 37.5, '小数输入不得被网页先行吸附');
    const requestCountBeforeInvalid = saveRequests.length;
    const saveErrorsBeforeInvalid = expectedSaveErrors;
    expectingInvalidSave = true;
    const rejectedSave = page.waitForResponse(response => response.url() === url + 'api/save' && response.status() === 400);
    await page.$eval('#save', el => el.click());
    await rejectedSave;
    await page.waitForFunction(() => document.querySelector('#status')?.classList.contains('error'), {timeout: 5000});
    await frame(page);
    expectingInvalidSave = false;
    assert.equal(expectedSaveErrors, saveErrorsBeforeInvalid + 1, '非法小数必须仅产生一次保存接口的预期 400，不得屏蔽其它错误');
    assert.equal(saveRequests.length, requestCountBeforeInvalid, '非法小数不得伪成功保存');
    assert.equal(await fieldValue(page, 'trick-hud.offset-down'), 37.5, '保存失败必须保留非法页面值与 dirty 状态');

    await setIntegerField(page, 'trick-hud.offset-down', 37);
    await setIntegerField(page, 'trick-hud.avatar-offset-down', 83);
    await setIntegerField(page, 'trick-hud.counter.offset-down', 157);
    await setIntegerField(page, 'hotbar-hud.offset-y', -20);
    const requestCountBeforeSave = saveRequests.length;
    await page.$eval('#save', el => el.click());
    await page.waitForFunction(() => document.querySelector('#status')?.classList.contains('ok'), {timeout: 5000});
    assert(saveRequests.length > requestCountBeforeSave, '连续整数 Y 必须真正发起保存');
    const saved = saveRequests[saveRequests.length - 1];
    for (const [key, value] of Object.entries({
      'trick-hud.offset-down': 37,
      'trick-hud.avatar-offset-down': 83,
      'trick-hud.counter.offset-down': 157,
      'hotbar-hud.offset-y': -20
    })) {
      assert.equal(saved[key], value, '保存 patch 必须保留原始连续整数：' + key);
    }
    assert.match(await page.$eval('#hint', el => el.textContent), /已保存并应用/,
      '保存成功后必须显示应用结果提示');

    await setIntegerField(page, 'trick-hud.counter.offset-down', 211);
    const dirtyBeforeReload = await page.$eval('#status', el => el.textContent);
    assert(/未保存|修改/.test(dirtyBeforeReload), '重载前应保留未保存状态');
    await page.$eval('#reload', el => el.click());
    await page.waitForFunction(() => document.querySelector('#status')?.classList.contains('ok'), {timeout: 5000});
    assert.equal(await fieldValue(page, 'trick-hud.counter.offset-down'), 157,
      '重新读取必须恢复磁盘快照并丢弃未保存连续 Y');

    for (const width of [320, 375]) {
      await page.setViewport({width, height: 700});
      await frame(page);
      const responsive = await page.evaluate(() => {
        const toolbar = document.querySelector('.toolbar');
        const r = toolbar.getBoundingClientRect();
        return {overflow: document.documentElement.scrollWidth > innerWidth + 1, left: r.left, right: r.right, width: r.width};
      });
      assert.equal(responsive.overflow, false, '窄屏不得产生横向溢出：' + JSON.stringify(responsive));
      assert(responsive.left >= -1 && responsive.right <= width + 1, '窄屏工具栏必须留在 viewport 内：' + JSON.stringify(responsive));
    }
    await page.setViewport({width: 1280, height: 900});
    await frame(page);
    const finalShot = path.join(screenshotDir, 'debug-web-final.png');
    await page.screenshot({path: finalShot, fullPage: true});
    assert.equal(errors.length, 0, '浏览器契约期间出现 JS/console error：' + errors.join(' | '));
    console.log('BROWSER_CONTRACT_SCREENSHOT_INITIAL=' + initialShot);
    console.log('BROWSER_CONTRACT_SCREENSHOT_FINAL=' + finalShot);
    console.log('BROWSER_CONTRACT=PASS');
  } finally {
    try { if (browser) await browser.close(); } finally { if (server.listening) server.close(); }
  }
})().catch(error => { console.error('BROWSER_CONTRACT_REQUESTS=' + JSON.stringify(requestedPaths)); console.error('BROWSER_CONTRACT=FAIL', error.stack || error); process.exitCode = 1; });
