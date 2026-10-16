// Headless-Chrome smoke test for the web UI via the DevTools protocol (Node 22+, no packages).
//
//   node tools/browser-check.mjs <url> [--wait 8] [--shot out.png] [--width 1400 --height 900] [--mobile]
//                                [--eval "js expression"] [--chrome path]
//
// Prints video element state (resolution, decoded frames, buffer lag), console errors and the
// result of --eval; saves a screenshot if --shot is given.
import { spawn } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const args = process.argv.slice(2);
const url = args[0];
const opt = (name, def) => {
  const i = args.indexOf('--' + name);
  return i >= 0 ? args[i + 1] : def;
};
const flag = (name) => args.includes('--' + name);
if (!url) {
  console.error('usage: node tools/browser-check.mjs <url> [--wait s] [--shot file.png] [--mobile] [--eval js]');
  process.exit(2);
}
const waitSec = Number(opt('wait', '8'));
const width = Number(opt('width', flag('mobile') ? '390' : '1400'));
const height = Number(opt('height', flag('mobile') ? '844' : '900'));
const chrome = opt('chrome', 'C:/Program Files/Google/Chrome/Application/chrome.exe');
const port = 9300 + Math.floor(Math.random() * 500);
const profile = mkdtempSync(join(tmpdir(), 'cdp-'));

const proc = spawn(chrome, [
  '--headless=new', `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`,
  '--autoplay-policy=no-user-gesture-required', '--no-first-run', '--no-default-browser-check',
  `--window-size=${width},${height}`, 'about:blank',
], { stdio: 'ignore' });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let target;
for (let i = 0; i < 50 && !target; i++) {
  await sleep(200);
  try {
    const list = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
    target = list.find((t) => t.type === 'page');
  } catch { /* not up yet */ }
}
if (!target) { console.error('chrome did not start'); proc.kill(); process.exit(1); }

const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((r) => ws.addEventListener('open', r, { once: true }));
let seq = 0;
const pending = new Map();
const consoleLines = [];
ws.addEventListener('message', (ev) => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); return; }
  if (m.method === 'Runtime.consoleAPICalled') {
    const text = m.params.args.map((a) => a.value ?? a.description ?? '').join(' ');
    consoleLines.push(`[console.${m.params.type}] ${text}`);
  } else if (m.method === 'Runtime.exceptionThrown') {
    const d = m.params.exceptionDetails;
    consoleLines.push(`[exception] ${d.exception?.description || d.text}`);
  } else if (m.method === 'Log.entryAdded') {
    consoleLines.push(`[log.${m.params.entry.level}] ${m.params.entry.text} ${m.params.entry.url || ''}`);
  }
});
const send = (method, params = {}) => new Promise((resolve) => {
  const id = ++seq;
  pending.set(id, resolve);
  ws.send(JSON.stringify({ id, method, params }));
});
const evaluate = async (expr) => {
  const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
  return r.result?.result?.value ?? r.result?.exceptionDetails?.exception?.description;
};

await send('Runtime.enable');
await send('Log.enable');
await send('Page.enable');
await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: flag('mobile') ? 2 : 1, mobile: flag('mobile') });
await send('Page.navigate', { url });
await sleep(waitSec * 1000);

const state = await evaluate(`(() => {
  const v = document.querySelector('#video') || document.querySelector('video');
  if (!v) return { video: null };
  const q = v.getVideoPlaybackQuality ? v.getVideoPlaybackQuality() : {};
  const b = v.buffered;
  return {
    readyState: v.readyState, paused: v.paused, currentTime: +v.currentTime.toFixed(3),
    size: v.videoWidth + 'x' + v.videoHeight,
    buffered: b.length ? [+b.start(0).toFixed(2), +b.end(b.length - 1).toFixed(2)] : null,
    lag: b.length ? +(b.end(b.length - 1) - v.currentTime).toFixed(3) : null,
    decoded: q.totalVideoFrames, dropped: q.droppedVideoFrames, rate: v.playbackRate,
    overlay: document.querySelector('#stage-overlay')?.hidden === false ? document.querySelector('#stage-msg')?.textContent : null,
  };
})()`);
console.log('video:', JSON.stringify(state));
const extra = opt('eval');
if (extra) console.log('eval:', JSON.stringify(await evaluate(extra)));
if (consoleLines.length) console.log(consoleLines.join('\n'));
const shot = opt('shot');
if (shot) {
  const r = await send('Page.captureScreenshot', { format: 'png' });
  writeFileSync(shot, Buffer.from(r.result.data, 'base64'));
  console.log('screenshot:', shot);
}
ws.close();
proc.kill();
await sleep(300);
try { rmSync(profile, { recursive: true, force: true }); } catch { /* chrome may still hold files */ }
