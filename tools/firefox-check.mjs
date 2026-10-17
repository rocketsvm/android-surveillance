// Headless-Firefox smoke test for the web UI via WebDriver BiDi (Node 22+, no packages).
// Same output as browser-check.mjs (Chrome), for checking the MSE player in Firefox.
//
//   node tools/firefox-check.mjs <url> [--wait 8] [--shot out.png] [--width 1400 --height 900]
//                                [--eval "js expression"] [--before "js" --then 6] [--firefox path]
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
if (!url) {
  console.error('usage: node tools/firefox-check.mjs <url> [--wait s] [--shot file.png] [--eval js]');
  process.exit(2);
}
const waitSec = Number(opt('wait', '8'));
const width = Number(opt('width', '1400'));
const height = Number(opt('height', '900'));
const firefox = opt('firefox', 'C:/Program Files/Mozilla Firefox/firefox.exe');
const port = 9800 + Math.floor(Math.random() * 500);
const profile = mkdtempSync(join(tmpdir(), 'ff-'));
writeFileSync(join(profile, 'user.js'), [
  'user_pref("network.proxy.type", 0);',               // no system proxy for LAN addresses
  'user_pref("media.autoplay.default", 0);',
  'user_pref("media.autoplay.blocking_policy", 0);',
  'user_pref("browser.shell.checkDefaultBrowser", false);',
  'user_pref("datareporting.policy.dataSubmissionEnabled", false);',
  'user_pref("toolkit.telemetry.reportingpolicy.firstRun", false);',
  'user_pref("browser.startup.homepage_override.mstone", "ignore");',
].join('\n'));

const proc = spawn(firefox, [
  '--headless', '--no-remote', '--profile', profile, '--remote-debugging-port', String(port), 'about:blank',
], { stdio: 'ignore' });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let ws;
for (let i = 0; i < 75 && !ws; i++) {
  await sleep(200);
  try {
    const sock = new WebSocket(`ws://127.0.0.1:${port}/session`);
    await new Promise((resolve, reject) => {
      sock.addEventListener('open', resolve, { once: true });
      sock.addEventListener('error', reject, { once: true });
    });
    ws = sock;
  } catch { /* not up yet */ }
}
if (!ws) { console.error('firefox did not start'); proc.kill(); process.exit(1); }

let seq = 0;
const pending = new Map();
const consoleLines = [];
ws.addEventListener('message', (ev) => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); return; }
  if (m.method === 'log.entryAdded') {
    const p = m.params;
    consoleLines.push(`[${p.type}.${p.level}] ${p.text}`);
  }
});
const send = (method, params = {}) => new Promise((resolve) => {
  const id = ++seq;
  pending.set(id, resolve);
  ws.send(JSON.stringify({ id, method, params }));
});

const session = await send('session.new', { capabilities: { alwaysMatch: { acceptInsecureCerts: true } } });
if (session.type === 'error') { console.error('session.new:', session.message); proc.kill(); process.exit(1); }
console.log('browser:', session.result.capabilities.browserName, session.result.capabilities.browserVersion);
const context = (await send('browsingContext.getTree')).result.contexts[0].context;
const evaluate = async (expr) => {
  const r = await send('script.evaluate', { expression: expr, target: { context }, awaitPromise: true, resultOwnership: 'none' });
  if (r.type === 'error') return `error: ${r.message}`;
  if (r.result.type === 'exception') return `exception: ${r.result.exceptionDetails.text}`;
  return r.result.result.value;
};

await send('session.subscribe', { events: ['log.entryAdded'] });
await send('browsingContext.setViewport', { context, viewport: { width, height } });
await send('browsingContext.navigate', { context, url, wait: 'complete' });
await sleep(waitSec * 1000);
// Optional interaction before measuring, e.g. filling a login form: --before "js" [--then 6]
const before = opt('before');
if (before) {
  console.log('before:', JSON.stringify(await evaluate(before)));
  await sleep(Number(opt('then', '6')) * 1000);
}

const state = await evaluate(`JSON.stringify((() => {
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
})())`);
console.log('video:', state);
const extra = opt('eval');
if (extra) console.log('eval:', JSON.stringify(await evaluate(extra)));
if (consoleLines.length) console.log(consoleLines.join('\n'));
const shot = opt('shot');
if (shot) {
  const r = await send('browsingContext.captureScreenshot', { context });
  writeFileSync(shot, Buffer.from(r.result.data, 'base64'));
  console.log('screenshot:', shot);
}
await send('session.end');
ws.close();
proc.kill();
await sleep(500);
try { rmSync(profile, { recursive: true, force: true }); } catch { /* firefox may still hold files */ }
