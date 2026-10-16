'use strict';

/* ================= helpers ================= */
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));

function h(tag, attrs, ...kids) {
  const e = document.createElement(tag);
  if (attrs) for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === 'class') e.className = v;
    else if (k === 'text') e.textContent = v;
    else if (k === 'html') e.innerHTML = v;
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v === true ? '' : v);
  }
  for (const k of kids.flat()) if (k != null && k !== false) e.append(k.nodeType ? k : document.createTextNode(k));
  return e;
}
function icon(name, cls) {
  const ns = 'http://www.w3.org/2000/svg';
  const s = document.createElementNS(ns, 'svg');
  s.setAttribute('class', 'i' + (cls ? ' ' + cls : ''));
  const u = document.createElementNS(ns, 'use');
  u.setAttribute('href', '#i-' + name);
  s.append(u);
  return s;
}

function plural(n, forms) {
  const a = Math.abs(n) % 100, b = a % 10;
  if (a > 10 && a < 20) return forms[2];
  if (b > 1 && b < 5) return forms[1];
  if (b === 1) return forms[0];
  return forms[2];
}
function fmtBytes(n) {
  if (n == null) return '—';
  const u = ['Б', 'КБ', 'МБ', 'ГБ', 'ТБ'];
  let i = 0;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return (i === 0 ? n : n.toFixed(n < 10 ? 1 : 0)).toString().replace('.', ',') + ' ' + u[i];
}
function fmtDuration(ms) {
  const s = Math.max(0, Math.round(ms / 1000));
  const hh = Math.floor(s / 3600), mm = Math.floor(s % 3600 / 60), ss = s % 60;
  const p = (x) => String(x).padStart(2, '0');
  return hh ? `${hh}:${p(mm)}:${p(ss)}` : `${mm}:${p(ss)}`;
}
function fmtUptime(sec) {
  const d = Math.floor(sec / 86400), hh = Math.floor(sec % 86400 / 3600), mm = Math.floor(sec % 3600 / 60);
  if (d) return `${d} д ${hh} ч`;
  if (hh) return `${hh} ч ${mm} мин`;
  return `${mm} мин`;
}
/* All times are shown in the camera's time zone, matching the date burned into the video. */
let camTzMin = null;
const camDate = (ms) => new Date(ms + (camTzMin != null ? camTzMin : -new Date(ms).getTimezoneOffset()) * 60000);
const fmtTime = (ms) => camDate(ms).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit', second: '2-digit', timeZone: 'UTC' });
const fmtShortTime = (ms) => camDate(ms).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit', timeZone: 'UTC' });
function dayKey(ms) {
  const d = camDate(ms);
  return d.getUTCFullYear() + '-' + d.getUTCMonth() + '-' + d.getUTCDate();
}
function fmtDay(ms) {
  const d = camDate(ms), now = camDate(Date.now());
  const start = (x) => Date.UTC(x.getUTCFullYear(), x.getUTCMonth(), x.getUTCDate());
  const diff = Math.round((start(now) - start(d)) / 86400000);
  if (diff === 0) return 'Сегодня';
  if (diff === 1) return 'Вчера';
  const opts = { day: 'numeric', month: 'long', weekday: 'short', timeZone: 'UTC' };
  if (d.getUTCFullYear() !== now.getUTCFullYear()) opts.year = 'numeric';
  return d.toLocaleDateString('ru-RU', opts);
}
function fmtDateTime(ms) {
  return fmtDay(ms) + ', ' + fmtTime(ms);
}

function toast(text, isError) {
  const t = h('div', { class: 'toast' + (isError ? ' error' : ''), text });
  $('#toasts').append(t);
  setTimeout(() => t.remove(), isError ? 5000 : 2500);
}

function storageGet(k, d) { try { const v = localStorage.getItem(k); return v == null ? d : v; } catch (e) { return d; } }
function storageSet(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* private mode */ } }

class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}
async function api(path, opts = {}) {
  const init = { method: opts.method || 'GET', headers: {}, credentials: 'same-origin' };
  if (opts.body !== undefined) {
    init.method = opts.method || 'POST';
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(opts.body);
  }
  const r = await fetch(path, init);
  let data = null;
  if ((r.headers.get('content-type') || '').includes('json')) data = await r.json().catch(() => null);
  if (r.status === 401 && path !== '/api/login') { showLogin(); throw new ApiError(401, 'Требуется вход'); }
  if (!r.ok) throw new ApiError(r.status, (data && data.error) || r.statusText);
  return data;
}

/* ================= state ================= */
const S = {
  status: null,
  values: {},
  groups: null,
  recordings: [],
  current: null,
  storage: null,
  filter: 'all',
  selecting: false,
  selected: new Set(),
  shown: 60,
  events: [],
  view: 'live',
  booted: false,
};

/* ================= theme ================= */
function applyTheme(t) {
  document.documentElement.dataset.theme = t;
  $('#theme-btn use').setAttribute('href', t === 'light' ? '#i-moon' : '#i-sun');
  document.querySelector('meta[name=theme-color]').content = t === 'light' ? '#f3f5f9' : '#0d1016';
}
function initTheme() {
  const pref = storageGet('theme', matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark');
  applyTheme(pref);
  $('#theme-btn').onclick = () => {
    const t = document.documentElement.dataset.theme === 'light' ? 'dark' : 'light';
    storageSet('theme', t);
    applyTheme(t);
  };
}

/* ================= router ================= */
const VIEWS = ['live', 'recordings', 'events', 'settings'];
function route() {
  const v = location.hash.replace(/^#\/?/, '').split('?')[0] || 'live';
  const view = VIEWS.includes(v) ? v : 'live';
  S.view = view;
  for (const name of VIEWS) $('#view-' + name).hidden = name !== view;
  $$('#tabs a').forEach((a) => a.classList.toggle('active', a.dataset.view === view));
  if (view === 'live') startPlayer(); else schedulePlayerStop();
  if (view === 'recordings') loadRecordings();
  if (view === 'events') loadEvents();
  if (view === 'settings') loadSettings();
  window.scrollTo(0, 0);
}

/* ================= events channel ================= */
let eventsWs = null;
let eventsRetry = 1000;
function connectEvents() {
  const ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws/events');
  eventsWs = ws;
  ws.onmessage = (ev) => {
    let m;
    try { m = JSON.parse(ev.data); } catch (e) { return; }
    eventsRetry = 1000;
    switch (m.type) {
      case 'hello':
        S.values = m.values || {};
        updateStatus(m.status);
        updateFromValues();
        break;
      case 'status': updateStatus(m.status); break;
      case 'motion': updateMotion(m); break;
      case 'settings':
        S.values = m.values || S.values;
        updateFromValues();
        if (S.view === 'settings') refreshSettingValues(m.changed || []);
        break;
      case 'event':
        S.events.unshift(m);
        S.events.length = Math.min(S.events.length, 300);
        renderMiniEvents();
        if (S.view === 'events') renderEvents();
        break;
      case 'recordings':
        if (S.view === 'recordings') loadRecordings();
        break;
    }
  };
  ws.onclose = () => {
    if (eventsWs !== ws) return;
    setLive('err', 'Нет связи');
    setTimeout(async () => {
      try {
        const s = await api('/api/session');
        if (s.authRequired && !s.authorized) { showLogin(); return; }
      } catch (e) { /* server unreachable: keep retrying */ }
      connectEvents();
    }, eventsRetry);
    eventsRetry = Math.min(eventsRetry * 2, 8000);
  };
}

/* ================= status ================= */
function setLive(kind, text) {
  const p = $('#pill-live');
  p.classList.toggle('on', kind === 'on');
  p.classList.toggle('err', kind === 'err');
  $('.t', p).textContent = text;
}

function updateStatus(st) {
  if (!st) return;
  S.status = st;
  if (st.tzOffsetMin != null) camTzMin = st.tzOffsetMin;
  $('#cam-name').textContent = st.name || 'Камера';
  document.title = (st.name || 'Камера') + ' — видеонаблюдение';
  const cam = st.camera || {};
  if (cam.state === 'running') setLive('on', 'В эфире');
  else if (cam.state === 'error') setLive('err', 'Ошибка камеры');
  else if (cam.state === 'starting') setLive('', 'Запуск…');
  else setLive('err', 'Камера выключена');

  const rec = st.recording || {};
  $('#pill-rec').hidden = !rec.active;
  const rb = $('#btn-record');
  rb.classList.toggle('rec-on', !!rec.manual);
  $('use', rb).setAttribute('href', rec.manual ? '#i-stop' : '#i-record');
  $('span', rb).textContent = rec.manual ? 'Остановить' : 'Записать';

  $('#st-res').textContent = cam.width ? `${cam.width}×${cam.height}` : '—';
  $('#st-fps').textContent = st.stream ? String(st.stream.fps).replace('.', ',') : '—';
  $('#st-kbps').textContent = st.stream ? st.stream.kbps + ' кбит/с' : '—';
  $('#st-viewers').textContent = st.stream ? st.stream.viewers : '—';
  const d = st.device || {};
  $('#st-battery').textContent = d.battery != null ? d.battery + '%' + (d.charging ? ' ⚡' : '') : '—';
  $('#st-temp').textContent = d.tempC != null ? d.tempC.toFixed(1).replace('.', ',') + ' °C' : '—';
  $('#st-temp').style.color = d.tempC >= 45 ? 'var(--danger)' : '';
  $('#st-free').textContent = st.storage ? fmtBytes(st.storage.freeBytes) : '—';
  $('#st-uptime').textContent = st.uptimeSec != null ? fmtUptime(st.uptimeSec) : '—';
  $('#logout-btn').hidden = !st.authRequired;
  if (st.motion) updateMotion(st.motion);
  if (cam.state === 'error' && S.view === 'live') stageMessage('Ошибка камеры: ' + (cam.error || 'неизвестно'), true);
  if (S.view === 'settings') { renderInfo(); renderNotices(); }
}

function updateFromValues() {
  const v = S.values;
  $$('#rec-mode button').forEach((b) => b.classList.toggle('on', b.dataset.mode === v['record.mode']));
  $('#btn-torch').classList.toggle('on', !!v['camera.torch']);
  if (S.status && S.status.motion) updateMotion(S.status.motion);
}

function updateMotion(m) {
  const minArea = Number(S.values['motion.minArea'] || 1);
  const scale = Math.max(minArea * 4, 5);
  $('#motion-meter').style.width = Math.min(100, (m.score || 0) / scale * 100) + '%';
  $('#motion-threshold').style.left = Math.min(100, minArea / scale * 100) + '%';
  const stateEl = $('#motion-state');
  if (m.enabled === false) { stateEl.textContent = 'Выключен'; stateEl.className = ''; }
  else if (m.active) { stateEl.textContent = 'Движение!'; stateEl.className = 'active'; }
  else { stateEl.textContent = 'Спокойно'; stateEl.className = ''; }
  $('#motion-score').textContent = (m.score || 0).toFixed(1).replace('.', ',') + '%';
  $('#pill-motion').hidden = !m.active;
  drawMotionBox(m.active ? m.box : null);
}

function drawMotionBox(box) {
  const el = $('#motion-box');
  const v = $('#video');
  if (!box || !v.videoWidth || S.view !== 'live') { el.hidden = true; return; }
  const stage = $('#stage').getBoundingClientRect();
  const vr = v.videoWidth / v.videoHeight, sr = stage.width / stage.height;
  let w, hh, x0, y0;
  if (vr > sr) { w = stage.width; hh = w / vr; x0 = 0; y0 = (stage.height - hh) / 2; }
  else { hh = stage.height; w = hh * vr; y0 = 0; x0 = (stage.width - w) / 2; }
  el.style.left = (x0 + box[0] * w) + 'px';
  el.style.top = (y0 + box[1] * hh) + 'px';
  el.style.width = (box[2] * w) + 'px';
  el.style.height = (box[3] * hh) + 'px';
  el.hidden = false;
}

/* ================= live view ================= */
let player = null;
let playerStopTimer = 0;
let lastBytes = 0;
let lastBytesAt = 0;

function stageMessage(text, isError) {
  const o = $('#stage-overlay');
  if (!text) { o.hidden = true; return; }
  o.hidden = false;
  o.classList.toggle('error', !!isError);
  $('#stage-msg').textContent = text;
}

function startPlayer() {
  clearTimeout(playerStopTimer);
  if (!LivePlayer.supported()) {
    stageMessage('Этот браузер не поддерживает воспроизведение H.264 через Media Source Extensions. Откройте страницу в Chrome, Edge, Firefox или Safari.', true);
    return;
  }
  if (!player) {
    player = new LivePlayer($('#video'), {
      onState: (s, msg) => {
        if (s === 'playing') stageMessage(null);
        else if (s === 'connecting') stageMessage('Подключение к камере…');
        else if (s === 'paused') { stageMessage(null); $('#stage-play').hidden = false; }
        else if (s === 'error') stageMessage(msg, true);
        else if (msg) stageMessage(msg);
      },
      onStats: ({ lag, bytes, info }) => {
        if (info) {
          $('#btn-sound').hidden = !info.audio;
          $('#badge-res').textContent = `${info.width}×${info.height}`;
          $('#stage').style.setProperty('--ar', String(info.width / info.height));
        }
        const now = performance.now();
        if (now - lastBytesAt >= 2000) {
          lastBytes = bytes; lastBytesAt = now;
        }
        $('#badge-lat').textContent = lag ? lag.toFixed(2).replace('.', ',') + ' с' : '—';
      },
    });
  }
  player.start();
}
function schedulePlayerStop() {
  clearTimeout(playerStopTimer);
  // Keep the stream warm briefly so flipping between tabs does not restart it.
  playerStopTimer = setTimeout(() => { if (player) player.stop(); }, 20000);
}

function initLive() {
  $('#stage-play').onclick = () => {
    $('#stage-play').hidden = true;
    $('#video').play().catch(() => {});
  };
  $('#btn-snapshot').onclick = () => {
    const a = h('a', { href: '/api/snapshot.jpg?w=4096&download=1', download: '' });
    document.body.append(a); a.click(); a.remove();
  };
  $('#btn-record').onclick = async () => {
    const on = !(S.status && S.status.recording && S.status.recording.manual);
    try {
      await api('/api/record', { body: { on } });
      toast(on ? 'Запись началась' : 'Запись остановлена');
    } catch (e) { toast(e.message, true); }
  };
  $('#btn-torch').onclick = () => saveSetting('camera.torch', !S.values['camera.torch']);
  $('#btn-sound').onclick = () => {
    const v = $('#video');
    v.muted = !v.muted;
    if (!v.muted) v.play().catch(() => {});
    $('#btn-sound').classList.toggle('on', !v.muted);
    $('#btn-sound use').setAttribute('href', v.muted ? '#i-mute' : '#i-volume');
  };
  $('#btn-fullscreen').onclick = () => {
    const stage = $('#stage'), v = $('#video');
    if (document.fullscreenElement) document.exitFullscreen();
    else if (stage.requestFullscreen) stage.requestFullscreen().catch(() => {});
    else if (v.webkitEnterFullscreen) v.webkitEnterFullscreen();
  };
  $$('#rec-mode button').forEach((b) => { b.onclick = () => saveSetting('record.mode', b.dataset.mode); });
  window.addEventListener('resize', () => S.status && S.status.motion && drawMotionBox(S.status.motion.active ? S.status.motion.box : null));
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) schedulePlayerStop();
    else if (S.view === 'live') startPlayer();
  });
}

const EV_ICONS = { motion: 'motion', recording: 'record', camera: 'video', system: 'refresh', settings: 'sliders', access: 'lock' };

function renderMiniEvents() {
  const ul = $('#mini-events');
  ul.textContent = '';
  const list = S.events.slice(0, 6);
  if (!list.length) { ul.append(h('li', { class: 'muted', text: 'Пока ничего' })); return; }
  for (const e of list) ul.append(h('li', null, h('time', { text: fmtShortTime(e.timeMs) }), h('span', { text: e.message })));
}

/* ================= recordings ================= */
async function loadRecordings() {
  try {
    const r = await api('/api/recordings');
    S.recordings = r.recordings || [];
    S.current = r.current || null;
    S.storage = r.storage || null;
    renderRecordings();
  } catch (e) { if (e.status !== 401) toast('Не удалось загрузить записи: ' + e.message, true); }
}

const TRIGGERS = { motion: 'Движение', continuous: 'Непрерывная', manual: 'Ручная', recovered: 'Восстановлена' };

function filteredRecordings() {
  return S.filter === 'all' ? S.recordings : S.recordings.filter((r) => r.trigger === S.filter);
}

function renderRecordings() {
  const st = S.storage;
  if (st) {
    const total = st.totalBytes || 1;
    $('#storage-text').textContent = `Записи: ${fmtBytes(st.usedBytes)} · свободно ${fmtBytes(st.freeBytes)} из ${fmtBytes(st.totalBytes)} (${st.label})`;
    $('#storage-fill').style.width = Math.min(100, (total - st.freeBytes) / total * 100) + '%';
  }
  const list = filteredRecordings();
  const box = $('#rec-list');
  box.textContent = '';
  box.classList.toggle('selecting', S.selecting);
  $('#rec-empty').hidden = list.length > 0 || !!S.current;

  const shown = list.slice(0, S.shown);
  const days = new Map();
  if (S.current && S.filter === 'all') days.set(dayKey(S.current.startMs), [{ ...S.current, live: true }]);
  for (const r of shown) {
    const k = dayKey(r.startMs);
    if (!days.has(k)) days.set(k, []);
    days.get(k).push(r);
  }
  for (const [, items] of days) {
    const real = items.filter((x) => !x.live);
    const dur = real.reduce((a, x) => a + x.durationMs, 0);
    const grid = h('div', { class: 'rec-grid' });
    for (const r of items) grid.append(recCard(r));
    box.append(h('div', { class: 'day' },
      h('div', { class: 'day-head' },
        h('h2', { text: fmtDay(items[0].startMs) }),
        h('span', { text: real.length ? `${real.length} ${plural(real.length, ['запись', 'записи', 'записей'])} · ${fmtDuration(dur)}` : '' })),
      grid));
  }
  $('#btn-more').hidden = list.length <= S.shown;
  updateSelectBar();
}

function recCard(r) {
  if (r.live) {
    return h('div', { class: 'rec-card' },
      h('div', { class: 'rec-thumb' },
        h('div', { class: 'live-rec' }, h('span', { class: 'dot' }), 'Идёт запись')),
      h('div', { class: 'rec-meta' },
        h('div', null, h('div', { class: 'rec-time', text: fmtTime(r.startMs) }), h('div', { class: 'rec-sub', text: TRIGGERS[r.trigger] || '' })),
      ));
  }
  const thumb = h('div', { class: 'rec-thumb' });
  if (r.thumb) thumb.append(h('img', { src: `/rec/${r.id}.jpg`, loading: 'lazy', alt: '', style: 'width:100%;height:100%;object-fit:cover;display:block' }));
  thumb.append(h('span', { class: 'dur', text: fmtDuration(r.durationMs) }));
  thumb.append(h('span', { class: 'rec-check' }, icon('check')));
  const card = h('button', { class: 'rec-card' + (S.selected.has(r.id) ? ' selected' : ''), type: 'button' },
    thumb,
    h('div', { class: 'rec-meta' },
      h('div', null,
        h('div', { class: 'rec-time', text: fmtTime(r.startMs) }),
        h('div', { class: 'rec-sub', text: fmtBytes(r.sizeBytes) + (r.width ? ` · ${r.height}p` : '') })),
      h('span', { class: 'tag ' + r.trigger, text: TRIGGERS[r.trigger] || r.trigger })));
  card.onclick = () => {
    if (S.selecting) {
      if (S.selected.has(r.id)) S.selected.delete(r.id); else S.selected.add(r.id);
      card.classList.toggle('selected', S.selected.has(r.id));
      updateSelectBar();
    } else openRecording(r.id);
  };
  return card;
}

function updateSelectBar() {
  $('#select-bar').hidden = !S.selecting;
  $('#select-count').textContent = `Выбрано: ${S.selected.size}`;
  $('#btn-delete-selected').disabled = S.selected.size === 0;
}

function openRecording(id) {
  const list = filteredRecordings();
  let idx = list.findIndex((r) => r.id === id);
  if (idx < 0) return;
  const show = () => {
    const r = list[idx];
    const video = h('video', { src: `/rec/${r.id}.mp4`, controls: true, autoplay: true, playsinline: true, preload: 'metadata' });
    const prev = h('button', { class: 'btn ghost', disabled: idx >= list.length - 1, title: 'Предыдущая' }, icon('left'), 'Раньше');
    const next = h('button', { class: 'btn ghost', disabled: idx <= 0, title: 'Следующая' }, 'Позже', icon('right'));
    prev.onclick = () => { idx++; show(); };
    next.onclick = () => { idx--; show(); };
    const dl = h('a', { class: 'btn', href: `/rec/${r.id}.mp4?download=1` }, icon('download'), 'Скачать');
    const del = h('button', { class: 'btn danger' }, icon('trash'), 'Удалить');
    del.onclick = async () => {
      if (!confirm('Удалить эту запись?')) return;
      try {
        await api('/api/recordings/' + encodeURIComponent(r.id), { method: 'DELETE' });
        S.recordings = S.recordings.filter((x) => x.id !== r.id);
        list.splice(idx, 1);
        toast('Запись удалена');
        renderRecordings();
        if (!list.length) closeModal();
        else { idx = Math.min(idx, list.length - 1); show(); }
      } catch (e) { toast(e.message, true); }
    };
    openModal({
      title: `${fmtDateTime(r.startMs)} · ${fmtDuration(r.durationMs)} · ${TRIGGERS[r.trigger] || ''}`,
      body: video,
      foot: [h('div', { class: 'btn-row' }, prev, next), h('div', { class: 'btn-row' }, dl, del)],
    });
  };
  show();
}

function initRecordings() {
  $$('#rec-filter button').forEach((b) => {
    b.onclick = () => {
      S.filter = b.dataset.f;
      S.shown = 60;
      $$('#rec-filter button').forEach((x) => x.classList.toggle('on', x === b));
      renderRecordings();
    };
  });
  $('#btn-more').onclick = () => { S.shown += 60; renderRecordings(); };
  $('#btn-select').onclick = () => { S.selecting = !S.selecting; S.selected.clear(); renderRecordings(); };
  $('#btn-select-cancel').onclick = () => { S.selecting = false; S.selected.clear(); renderRecordings(); };
  $('#btn-select-all').onclick = () => { filteredRecordings().forEach((r) => S.selected.add(r.id)); renderRecordings(); };
  $('#btn-delete-selected').onclick = async () => {
    const n = S.selected.size;
    if (!n || !confirm(`Удалить ${n} ${plural(n, ['запись', 'записи', 'записей'])}?`)) return;
    try {
      const r = await api('/api/recordings/delete', { body: { ids: Array.from(S.selected) } });
      toast(`Удалено: ${r.deleted}`);
      S.selected.clear();
      S.selecting = false;
      loadRecordings();
    } catch (e) { toast(e.message, true); }
  };
}

/* ================= events view ================= */
async function loadEvents() {
  try {
    const r = await api('/api/events?limit=300');
    S.events = r.events || [];
    renderEvents();
    renderMiniEvents();
  } catch (e) { /* handled */ }
}
function renderEvents() {
  const ul = $('#events-list');
  ul.textContent = '';
  if (!S.events.length) { ul.append(h('li', { class: 'muted', text: 'Событий пока нет' })); return; }
  for (const e of S.events) {
    const body = h('div', { class: 'ev-body' }, h('div', { text: e.message }), h('div', { class: 'ev-time', text: fmtDateTime(e.timeMs) }));
    if (e.recordingId) {
      const a = h('a', { href: '#/recordings', class: 'ev-link', text: 'Открыть запись' });
      a.onclick = async (ev) => {
        ev.preventDefault();
        if (!S.recordings.length) await loadRecordings();
        if (S.recordings.some((r) => r.id === e.recordingId)) openRecording(e.recordingId);
        else toast('Запись ещё пишется или уже удалена');
      };
      body.append(a);
    }
    ul.append(h('li', null, h('div', { class: 'ev-icon ' + e.kind }, icon(EV_ICONS[e.kind] || 'activity')), body));
  }
}

/* ================= settings ================= */
async function loadSettings() {
  try {
    const r = await api('/api/settings');
    S.groups = r.groups;
    S.values = r.values || S.values;
    renderSettings();
    renderInfo();
  } catch (e) { /* handled */ }
}

async function saveSetting(key, value, fieldEl) {
  try {
    const r = await api('/api/settings', { body: { [key]: value } });
    if (r.errors && r.errors[key]) { toast(r.errors[key], true); return false; }
    S.values = r.values;
    updateFromValues();
    if (fieldEl) { fieldEl.classList.add('saved'); setTimeout(() => fieldEl.classList.remove('saved'), 1500); }
    const def = findDef(key);
    if (def && def.apply === 'pipeline' && r.changed.length) toast('Сохранено, камера перезапускается…');
    else if (def && def.apply === 'server' && r.changed.length) {
      toast('Порт изменён, переход на новый адрес…');
      setTimeout(() => { location.port = String(value); }, 1500);
    } else if (r.changed.length) toast('Сохранено');
    return true;
  } catch (e) {
    toast('Не сохранено: ' + e.message, true);
    return false;
  }
}

function findDef(key) {
  for (const g of S.groups || []) for (const s of g.settings) if (s.key === key) return s;
  return null;
}

function fmtValue(def, v) {
  const n = Number(v);
  const txt = def.type === 'float' ? (Math.round(n * 100) / 100).toString().replace('.', ',') : String(n);
  return def.unit ? `${txt} ${def.unit}` : txt;
}

function renderSettings() {
  const root = $('#settings-groups');
  root.textContent = '';
  root.classList.toggle('show-adv', $('#show-advanced').checked);
  for (const g of S.groups || []) {
    const card = h('div', { class: 'card group', 'data-group': g.id }, h('h2', { text: g.title }));
    card.append(h('div', { class: 'group-notice', hidden: true }));
    for (const def of g.settings) card.append(renderField(def));
    if (g.id === 'notify') {
      const test = h('button', { class: 'btn', type: 'button', style: 'margin-top:12px' }, 'Отправить тестовое сообщение');
      test.onclick = async () => {
        test.disabled = true;
        try { await api('/api/notify/test', { body: {} }); toast('Отправлено — проверьте Telegram'); }
        catch (e) { toast(e.message, true); }
        test.disabled = false;
      };
      card.append(test);
    }
    root.append(card);
  }
  renderNotices();
}

/** Live state next to the related settings (e.g. why the microphone is not recording). */
function renderNotices() {
  const st = S.status;
  const notify = $('[data-group="notify"] .group-notice');
  if (notify && st && st.notify) {
    notify.hidden = !st.notify.error;
    notify.className = 'group-notice warn';
    notify.textContent = st.notify.error ? 'Последняя отправка не удалась: ' + st.notify.error : '';
  }
  const audio = $('[data-group="audio"] .group-notice');
  if (!audio || !st || !st.camera) return;
  const cam = st.camera;
  if (cam.audio === 'error') {
    audio.hidden = false;
    audio.className = 'group-notice warn';
    audio.textContent = 'Звук не записывается: ' + (cam.audioError || 'микрофон недоступен');
  } else if (cam.audio === 'on') {
    audio.hidden = false;
    audio.className = 'group-notice ok';
    audio.textContent = 'Микрофон работает';
  } else {
    audio.hidden = true;
  }
}

function renderField(def) {
  const value = S.values[def.key] !== undefined ? S.values[def.key] : def.value;
  const field = h('div', { class: 'field' + (def.advanced ? ' advanced' : ''), 'data-key': def.key });
  const label = h('div', null, h('div', { class: 'field-label', text: def.title }), def.help ? h('div', { class: 'field-help', text: def.help }) : null);
  const row = h('div', { class: 'field-row' }, label);
  field.append(row);

  if (def.type === 'bool') {
    const input = h('input', { type: 'checkbox', class: 'toggle' });
    input.checked = !!value;
    input.onchange = () => saveSetting(def.key, input.checked, field);
    row.append(h('label', { class: 'field-control switch-label' }, input, h('span', { class: 'switch' })));
  } else if (def.type === 'choice') {
    const sel = h('select');
    const opts = def.options || [];
    if (!opts.some((o) => o.value === value)) sel.append(h('option', { value: value, text: value || 'Авто' }));
    for (const o of opts) sel.append(h('option', { value: o.value, text: o.label }));
    sel.value = value;
    sel.onchange = () => saveSetting(def.key, sel.value, field);
    row.append(h('div', { class: 'field-control' }, sel));
  } else if (def.type === 'int' || def.type === 'float') {
    const span = def.max - def.min;
    if (span / def.step <= 2000 && def.key !== 'net.port') {
      const range = h('input', { type: 'range', min: def.min, max: def.max, step: def.step, value });
      const out = h('span', { class: 'range-val', text: fmtValue(def, value) });
      range.oninput = () => { out.textContent = fmtValue(def, range.value); };
      range.onchange = () => saveSetting(def.key, Number(range.value), field);
      field.append(h('div', { class: 'range-wrap' }, range, out));
    } else {
      const num = h('input', { type: 'number', min: def.min, max: def.max, step: def.step, value });
      num.onchange = () => saveSetting(def.key, Number(num.value), field);
      row.append(h('div', { class: 'field-control' }, num, def.unit ? h('span', { class: 'muted', text: def.unit }) : null));
    }
  } else if (def.type === 'text' && def.editor === 'zones') {
    const btn = h('button', { class: 'btn', type: 'button' }, icon('grid'), 'Настроить');
    btn.onclick = () => openZonesEditor(def);
    const info = h('span', { class: 'muted', text: value ? 'Есть исключённые зоны' : 'Весь кадр' });
    row.append(h('div', { class: 'field-control' }, info, btn));
  } else if (def.type === 'text' && def.editor === 'tg-chat') {
    const input = h('input', { type: 'text', maxlength: def.maxLength, value, placeholder: '123456789' });
    input.onchange = () => saveSetting(def.key, input.value.trim(), field);
    const find = h('button', { class: 'btn', type: 'button', text: 'Найти' });
    find.onclick = async () => {
      try {
        const r = await api('/api/notify/chats', { body: {} });
        const chats = r.chats || [];
        if (!chats.length) { toast('Чатов нет: напишите своему боту любое сообщение и повторите', true); return; }
        const pick = chats.length === 1 ? chats[0] : chats.find((c) => confirm('Использовать чат «' + c.title + '» (' + c.id + ')?'));
        if (pick && await saveSetting(def.key, pick.id, field)) { input.value = pick.id; toast('Чат: ' + pick.title); }
      } catch (e) { toast(e.message, true); }
    };
    row.append(h('div', { class: 'field-control' }, input, find));
  } else if (def.type === 'text') {
    const input = h('input', { type: 'text', maxlength: def.maxLength, value });
    input.onchange = () => saveSetting(def.key, input.value.trim(), field);
    row.append(h('div', { class: 'field-control' }, input));
  } else if (def.type === 'secret') {
    const isSet = def.isSet;
    const isPassword = def.key === 'net.password';
    const input = h('input', { type: 'password', placeholder: isSet ? 'Новое значение' : (isPassword ? 'Задать пароль' : 'Вставьте значение'), autocomplete: 'new-password', maxlength: def.maxLength });
    const save = h('button', { class: 'btn primary', type: 'button', text: 'Сохранить' });
    save.onclick = async () => {
      const v = input.value.trim();
      if (!v) { toast('Поле пустое', true); return; }
      if (await saveSetting(def.key, v, field)) {
        if (isPassword) {
          const user = S.values['net.username'] || 'admin';
          await api('/api/login', { body: { username: user, password: v } }).catch(() => {});
          toast('Пароль установлен');
        }
        loadSettings();
      }
    };
    const ctl = h('div', { class: 'field-control' }, input, save);
    if (isSet) {
      const clear = h('button', { class: 'btn ghost', type: 'button', text: 'Убрать' });
      clear.onclick = async () => {
        if (isPassword && !confirm('Отключить защиту паролем? Доступ к камере получит любой в этой сети.')) return;
        if (await saveSetting(def.key, '', field)) loadSettings();
      };
      ctl.append(clear);
    }
    field.append(h('div', { class: 'range-wrap' }, ctl));
    const state = isPassword
      ? (isSet ? 'Пароль установлен' : 'Пароль не установлен — доступ открыт всем в сети')
      : (isSet ? 'Задан' : 'Не задан');
    label.append(h('div', { class: 'field-help', text: state }));
  }
  return field;
}

/** Updates controls for keys changed elsewhere (phone, another browser) without a full re-render. */
function refreshSettingValues(keys) {
  if (!S.groups) return;
  const active = document.activeElement;
  for (const key of keys) {
    const field = $(`.field[data-key="${CSS.escape(key)}"]`);
    if (!field || field.contains(active)) continue;
    const def = findDef(key);
    if (!def || def.type === 'secret') continue;
    def.value = S.values[key];
    field.replaceWith(renderField(def));
  }
}

function renderInfo() {
  const st = S.status;
  const card = $('#info-card');
  if (!st) return;
  card.textContent = '';
  card.append(h('h2', { text: 'Подключение и сведения' }));
  const port = st.port || location.port || 80;
  const list = h('div', { class: 'addr-list' });
  const kinds = { wifi: 'Wi-Fi', ethernet: 'Ethernet', hotspot: 'Точка доступа', vpn: 'VPN', mobile: 'Мобильная сеть', other: 'Сеть' };
  for (const a of (st.network || []).filter((x) => !x.ipv6)) {
    const url = `http://${a.address}:${port}/`;
    const copy = h('button', { class: 'icon-btn', title: 'Копировать' }, icon('copy'));
    copy.onclick = () => copyText(url);
    list.append(h('div', { class: 'addr' }, h('code', { text: url }), copy, h('span', { class: 'kind', text: `${kinds[a.kind] || a.kind} · ${a.iface}` })));
  }
  card.append(h('div', { class: 'card-title', text: 'Веб-интерфейс' }), list);
  const host = (st.network || []).find((x) => !x.ipv6);
  if (host) {
    const streams = [];
    if (st.rtsp && st.rtsp.enabled && st.rtsp.port) streams.push([`rtsp://${host.address}:${st.rtsp.port}/live`, 'RTSP']);
    streams.push([`http://${host.address}:${port}/live.mp4`, 'HTTP']);
    const list2 = h('div', { class: 'addr-list' });
    for (const [url, kind] of streams) {
      const copy = h('button', { class: 'icon-btn', title: 'Копировать' }, icon('copy'));
      copy.onclick = () => copyText(url);
      list2.append(h('div', { class: 'addr' }, h('code', { text: url }), copy, h('span', { class: 'kind', text: kind })));
    }
    card.append(h('div', { class: 'card-title', text: 'Поток для VLC, видеорегистратора, Home Assistant' }), list2);
    if (st.rtsp && st.rtsp.error) card.append(h('div', { class: 'group-notice warn', text: st.rtsp.error }));
  }
  const d = st.device || {};
  card.append(h('dl', { class: 'kv' },
    h('dt', { text: 'Устройство' }), h('dd', { text: [d.model, d.os].filter(Boolean).join(', ') || '—' }),
    h('dt', { text: 'Кодировщик' }), h('dd', { text: (st.camera && st.camera.encoder) || '—' }),
    h('dt', { text: 'Версия' }), h('dd', { text: st.version || '—' }),
    h('dt', { text: 'Работает' }), h('dd', { text: fmtUptime(st.uptimeSec || 0) })));
  const restart = h('button', { class: 'btn', style: 'margin-top:14px' }, icon('refresh'), 'Перезапустить камеру');
  restart.onclick = async () => { await api('/api/camera/restart', { body: {} }).catch((e) => toast(e.message, true)); toast('Камера перезапускается…'); };
  card.append(restart);
}

function copyText(t) {
  const done = () => toast('Скопировано');
  if (navigator.clipboard && window.isSecureContext) navigator.clipboard.writeText(t).then(done, () => fallbackCopy(t, done));
  else fallbackCopy(t, done);
}
function fallbackCopy(t, done) {
  const ta = h('textarea', { style: 'position:fixed;opacity:0' });
  ta.value = t;
  document.body.append(ta);
  ta.select();
  try { document.execCommand('copy'); done(); } catch (e) { prompt('Скопируйте адрес:', t); }
  ta.remove();
}

/* ---------- motion zones editor ---------- */
function parseZones(s) {
  const m = /^(\d+)x(\d+):([0-9a-f]*)$/i.exec(s || '');
  if (!m) return null;
  const cols = +m[1], rows = +m[2], hex = m[3];
  const bits = new Array(cols * rows).fill(false);
  for (let i = 0; i < bits.length; i++) {
    const nib = parseInt(hex[i >> 2] || '0', 16);
    bits[i] = ((nib >> (3 - (i % 4))) & 1) === 1;
  }
  return { cols, rows, bits };
}
function formatZones(z) {
  if (!z.bits.some(Boolean)) return '';
  let s = `${z.cols}x${z.rows}:`;
  for (let i = 0; i < z.bits.length; i += 4) {
    let nib = 0;
    for (let b = 0; b < 4; b++) nib = (nib << 1) | (z.bits[i + b] ? 1 : 0);
    s += nib.toString(16);
  }
  return s;
}

function openZonesEditor(def) {
  const img = h('img', { src: '/api/snapshot.jpg?w=960&t=' + Date.now(), alt: '', draggable: 'false' });
  const grid = h('div', { class: 'zones-grid' });
  const wrap = h('div', { class: 'zones' }, img, grid);
  let z = parseZones(S.values[def.key]);
  const build = () => {
    grid.textContent = '';
    grid.style.gridTemplateColumns = `repeat(${z.cols}, 1fr)`;
    grid.style.gridTemplateRows = `repeat(${z.rows}, 1fr)`;
    z.bits.forEach((off, i) => grid.append(h('div', { class: off ? 'off' : '', 'data-i': i })));
  };
  const ensure = () => {
    if (z) return;
    const ar = img.naturalWidth / img.naturalHeight || 16 / 9;
    const dims = ar >= 1.5 ? [16, 9] : ar >= 1 ? [16, 12] : ar >= 0.7 ? [12, 16] : [9, 16];
    z = { cols: dims[0], rows: dims[1], bits: new Array(dims[0] * dims[1]).fill(false) };
  };
  img.onload = () => { ensure(); build(); };
  img.onerror = () => { ensure(); build(); toast('Нет кадра с камеры — зоны можно задать вслепую', true); };
  let paint = null;
  const cellAt = (ev) => {
    const t = document.elementFromPoint(ev.clientX, ev.clientY);
    return t && t.parentElement === grid ? t : null;
  };
  grid.addEventListener('pointerdown', (ev) => {
    const c = cellAt(ev);
    if (!c) return;
    grid.setPointerCapture(ev.pointerId);
    paint = !z.bits[+c.dataset.i];
    z.bits[+c.dataset.i] = paint;
    c.classList.toggle('off', paint);
  });
  grid.addEventListener('pointermove', (ev) => {
    if (paint == null) return;
    const c = cellAt(ev);
    if (!c) return;
    z.bits[+c.dataset.i] = paint;
    c.classList.toggle('off', paint);
  });
  const end = () => { paint = null; };
  grid.addEventListener('pointerup', end);
  grid.addEventListener('pointercancel', end);

  const clear = h('button', { class: 'btn ghost', type: 'button', text: 'Очистить' });
  clear.onclick = () => { z.bits.fill(false); build(); };
  const invert = h('button', { class: 'btn ghost', type: 'button', text: 'Инвертировать' });
  invert.onclick = () => { z.bits = z.bits.map((b) => !b); build(); };
  const save = h('button', { class: 'btn primary', type: 'button', text: 'Сохранить' });
  save.onclick = async () => {
    if (await saveSetting(def.key, formatZones(z))) { closeModal(); renderSettings(); }
  };
  const cancel = h('button', { class: 'btn ghost', type: 'button', text: 'Отмена' });
  cancel.onclick = closeModal;
  openModal({
    title: 'Зоны детекции движения',
    body: [wrap, h('div', { class: 'zones-hint', text: 'Проведите по клеткам, чтобы исключить их из детекции (заштрихованы красным). Например, дорогу, деревья или телевизор.' })],
    foot: [h('div', { class: 'btn-row' }, clear, invert), h('div', { class: 'btn-row' }, cancel, save)],
  });
}

/* ================= modal ================= */
function openModal({ title, body, foot, narrow }) {
  const m = $('#modal');
  $('#modal-title').textContent = title || '';
  const b = $('#modal-body');
  b.textContent = '';
  [].concat(body).forEach((x) => b.append(x));
  const f = $('#modal-foot');
  f.textContent = '';
  [].concat(foot || []).forEach((x) => f.append(x));
  $('.modal-box', m).classList.toggle('narrow', !!narrow);
  m.hidden = false;
}
function closeModal() {
  const m = $('#modal');
  $$('video', m).forEach((v) => { v.pause(); v.removeAttribute('src'); v.load(); });
  m.hidden = true;
  $('#modal-body').textContent = '';
}

/* ================= login ================= */
function showLogin() {
  $('#login').hidden = false;
  if (player) player.stop();
  setTimeout(() => $('#login input[name=password]').focus(), 50);
}
function initLogin() {
  $('#login-form').onsubmit = async (ev) => {
    ev.preventDefault();
    const f = ev.target;
    $('#login-error').textContent = '';
    try {
      await api('/api/login', { body: { username: f.username.value, password: f.password.value } });
      $('#login').hidden = true;
      f.password.value = '';
      start();
    } catch (e) {
      $('#login-error').textContent = e.message;
    }
  };
  $('#logout-btn').onclick = async () => {
    await api('/api/logout', { body: {} }).catch(() => {});
    location.reload();
  };
}

/* ================= boot ================= */
function start() {
  if (!S.booted) {
    S.booted = true;
    connectEvents();
    loadEvents();
  } else if (!eventsWs || eventsWs.readyState > 1) {
    connectEvents();
  }
  route();
}

async function boot() {
  initTheme();
  initLive();
  initRecordings();
  initLogin();
  $$('[data-close]').forEach((e) => { e.onclick = closeModal; });
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !$('#modal').hidden) closeModal(); });
  $('#show-advanced').checked = storageGet('advanced', '0') === '1';
  $('#show-advanced').onchange = (e) => {
    storageSet('advanced', e.target.checked ? '1' : '0');
    $('#settings-groups').classList.toggle('show-adv', e.target.checked);
  };
  window.addEventListener('hashchange', route);
  try {
    const s = await api('/api/session');
    if (s.tzOffsetMin != null) camTzMin = s.tzOffsetMin;
    if (s.name) $('#cam-name').textContent = s.name;
    if (s.authRequired && !s.authorized) { $('#login-title').textContent = s.name || 'Вход'; showLogin(); return; }
  } catch (e) {
    stageMessage('Камера недоступна: ' + e.message, true);
  }
  start();
}

boot();
