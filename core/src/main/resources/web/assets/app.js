'use strict';

/* ================= localization ================= */
const STRINGS = {
  ru: {
    app_title: 'Видеонаблюдение',
    camera: 'Камера',
    recordings: 'Записи',
    events: 'События',
    settings: 'Настройки',
    login: 'Вход',
    logout: 'Выход',
    language: 'Язык',
    camera_running: 'Камера работает',
    camera_stopped: 'Камера остановлена',
    camera_starting: 'Запуск камеры…',
    camera_error: 'Ошибка камеры',
    stop: 'Остановить',
    start: 'Запустить',
    snapshot: 'Снимок',
    record: 'Запись',
    flashlight: 'Фонарик',
    sound: 'Звук',
    motion_detected: 'Движение обнаружено',
    recording_started: 'Запись началась',
    recording_stopped: 'Запись остановлена',
    no_recordings: 'Записей нет',
    download: 'Скачать',
    delete: 'Удалить',
    play: 'Воспроизвести',
    prev: 'Предыдущая',
    next: 'Следующая',
    earlier: 'Раньше',
    later: 'Позже',
    web_interface: 'Веб-интерфейс',
    stream_for_vlc: 'Поток для VLC, видеорегистратора, Home Assistant',
    copy: 'Копировать',
    copied: 'Скопировано',
    motion_zones: 'Зоны детекции движения',
    login_title: 'Вход',
    password: 'Пароль',
    enter: 'Войти',
    wrong_password: 'Неверный пароль',
    chat: 'Чат',
    send_test: 'Отправить тестовое сообщение',
    test_sent: 'Отправлено — проверьте Telegram',
    failed_to_load_recordings: 'Не удалось загрузить записи',
    recording_deleted: 'Запись удалена',
    deleted: 'Удалено',
    recording_still_writing: 'Запись ещё пишется или уже удалена',
    saved_camera_restarting: 'Сохранено, камера перезапускается…',
    port_changed: 'Порт изменён, переход на новый адрес…',
    saved: 'Сохранено',
    not_saved: 'Не сохранено',
    no_chats: 'Чатов нет: напишите своему боту любое сообщение и повторите',
    field_empty: 'Поле пустое',
    password_set: 'Пароль установлен',
    camera_restarting: 'Камера перезапускается…',
    copy_address: 'Скопируйте адрес',
    no_frame_from_camera: 'Нет кадра с камеры — зоны можно задать вслепую',
    motion_off: 'Выключен',
    motion_active: 'Движение!',
    motion_idle: 'Спокойно',
    recordings_storage: 'Записи',
    selected: 'Выбрано',
    last_send_failed: 'Последняя отправка не удалась',
    audio_not_recording: 'Звук не записывается',
    microphone_unavailable: 'микрофон недоступен',
    microphone_working: 'Микрофон работает',
    login_required: 'Требуется вход',
    no_connection: 'Нет связи',
    live: 'В эфире',
    camera_error_prefix: 'Ошибка камеры: ',
    starting: 'Запуск…',
    browser_not_supported: 'Этот браузер не поддерживает воспроизведение H.264 через Media Source Extensions. Откройте страницу в Chrome, Edge, Firefox или Safari.',
    iphone_not_supported: 'На iPhone живое видео в браузере работает с iOS 17.1. Обновите систему или откройте поток в приложении VLC: ',
    connecting: 'Подключение к камере…',
    nothing_yet: 'Пока ничего',
    trigger_motion: 'Движение',
    trigger_continuous: 'Непрерывная',
    trigger_manual: 'Ручная',
    trigger_recovered: 'Восстановлена',
    recording_singular: 'запись',
    recording_plural: 'записи',
    recording_many: 'записей',
    recording_in_progress: 'Идёт запись',
    delete_recording_confirm: 'Удалить эту запись?',
    no_events_yet: 'Событий пока нет',
    open_recording: 'Открыть запись',
    lang_system: 'Как в системе / System default',
    lang_ru: 'Русский',
    auto: 'Авто',
    configure: 'Настроить',
    zones_excluded: 'Есть исключённые зоны',
    zones_none: 'Весь кадр',
    find: 'Найти',
    use_chat: 'Использовать чат «',
    chat_suffix: '» (',
    new_value: 'Новое значение',
    set_password: 'Задать пароль',
    paste_value: 'Вставьте значение',
    save: 'Сохранить',
    remove: 'Убрать',
    disable_password_confirm: 'Отключить защиту паролем? Доступ к камере получит любой в этой сети.',
    password_set_notice: 'Пароль установлен',
    password_not_set_notice: 'Пароль не установлен — доступ открыт всем в сети',
    set_notice: 'Задан',
    not_set_notice: 'Не задан',
    connection_info: 'Подключение и сведения',
    wifi: 'Wi-Fi',
    ethernet: 'Ethernet',
    hotspot: 'Точка доступа',
    vpn: 'VPN',
    mobile: 'Мобильная сеть',
    network: 'Сеть',
    copy: 'Копировать',
    web_interface: 'Веб-интерфейс',
    stream_for_vlc: 'Поток для VLC, видеорегистратора, Home Assistant',
    device: 'Устройство',
    encoder: 'Кодировщик',
    version: 'Версия',
    uptime: 'Работает',
    restart_camera: 'Перезапустить камеру',
    clear: 'Очистить',
    invert: 'Инвертировать',
    motion_zones: 'Зоны детекции движения',
    zones_hint: 'Проведите по клеткам, чтобы исключить их из детекции (заштрихованы красным). Например, дорогу, деревья или телевизор.',
    cancel: 'Отмена',
    login_title: 'Вход',
    unavailable: 'unavailable: ',
  },
  en: {
    app_title: 'Surveillance',
    camera: 'Camera',
    recordings: 'Recordings',
    events: 'Events',
    settings: 'Settings',
    login: 'Login',
    logout: 'Logout',
    language: 'Language',
    camera_running: 'Camera running',
    camera_stopped: 'Camera stopped',
    camera_starting: 'Starting camera…',
    camera_error: 'Camera error',
    stop: 'Stop',
    start: 'Start',
    snapshot: 'Snapshot',
    record: 'Record',
    flashlight: 'Flashlight',
    sound: 'Sound',
    motion_detected: 'Motion detected',
    recording_started: 'Recording started',
    recording_stopped: 'Recording stopped',
    no_recordings: 'No recordings',
    download: 'Download',
    delete: 'Delete',
    play: 'Play',
    prev: 'Previous',
    next: 'Next',
    earlier: 'Earlier',
    later: 'Later',
    web_interface: 'Web interface',
    stream_for_vlc: 'Stream for VLC, NVR, Home Assistant',
    copy: 'Copy',
    copied: 'Copied',
    motion_zones: 'Motion detection zones',
    login_title: 'Login',
    password: 'Password',
    enter: 'Enter',
    wrong_password: 'Wrong password',
    chat: 'Chat',
    send_test: 'Send test message',
    test_sent: 'Sent — check Telegram',
    failed_to_load_recordings: 'Failed to load recordings',
    recording_deleted: 'Recording deleted',
    deleted: 'Deleted',
    recording_still_writing: 'Recording still writing or already deleted',
    saved_camera_restarting: 'Saved, camera restarting…',
    port_changed: 'Port changed, navigating to new address…',
    saved: 'Saved',
    not_saved: 'Not saved',
    no_chats: 'No chats: send your bot any message and try again',
    field_empty: 'Field is empty',
    password_set: 'Password set',
    camera_restarting: 'Camera restarting…',
    copy_address: 'Copy address',
    no_frame_from_camera: 'No frame from camera — zones can be set blindly',
    motion_off: 'Off',
    motion_active: 'Motion!',
    motion_idle: 'Idle',
    recordings_storage: 'Recordings',
    selected: 'Selected',
    last_send_failed: 'Last send failed',
    audio_not_recording: 'Audio not recording',
    microphone_unavailable: 'microphone unavailable',
    microphone_working: 'Microphone working',
    login_required: 'Login required',
    no_connection: 'No connection',
    live: 'Live',
    camera_error_prefix: 'Camera error: ',
    starting: 'Starting…',
    browser_not_supported: 'This browser does not support H.264 playback via Media Source Extensions. Open the page in Chrome, Edge, Firefox or Safari.',
    iphone_not_supported: 'On iPhone, live video in the browser works from iOS 17.1. Update your system or open the stream in VLC: ',
    connecting: 'Connecting to camera…',
    nothing_yet: 'Nothing yet',
    trigger_motion: 'Motion',
    trigger_continuous: 'Continuous',
    trigger_manual: 'Manual',
    trigger_recovered: 'Recovered',
    recording_singular: 'recording',
    recording_plural: 'recordings',
    recording_many: 'recordings',
    recording_in_progress: 'Recording in progress',
    delete_recording_confirm: 'Delete this recording?',
    no_events_yet: 'No events yet',
    open_recording: 'Open recording',
    lang_system: 'System default',
    lang_ru: 'Русский',
    auto: 'Auto',
    configure: 'Configure',
    zones_excluded: 'Some zones excluded',
    zones_none: 'Full frame',
    find: 'Find',
    use_chat: 'Use chat «',
    chat_suffix: '» (',
    new_value: 'New value',
    set_password: 'Set password',
    paste_value: 'Paste value',
    save: 'Save',
    remove: 'Remove',
    disable_password_confirm: 'Disable password protection? Anyone on this network will be able to access the camera.',
    password_set_notice: 'Password set',
    password_not_set_notice: 'No password — open to everyone on the network',
    set_notice: 'Set',
    not_set_notice: 'Not set',
    connection_info: 'Connection info',
    wifi: 'Wi-Fi',
    ethernet: 'Ethernet',
    hotspot: 'Hotspot',
    vpn: 'VPN',
    mobile: 'Mobile',
    network: 'Network',
    copy: 'Copy',
    web_interface: 'Web interface',
    stream_for_vlc: 'Stream for VLC, NVR, Home Assistant',
    device: 'Device',
    encoder: 'Encoder',
    version: 'Version',
    uptime: 'Uptime',
    restart_camera: 'Restart camera',
    clear: 'Clear',
    invert: 'Invert',
    motion_zones: 'Motion detection zones',
    zones_hint: 'Swipe across cells to exclude them from detection (shaded red). For example, a road, trees, or a TV.',
    cancel: 'Cancel',
    login_title: 'Login',
    unavailable: 'unavailable: ',
  },
};

function getLang() {
  return localStorage.getItem('surveil_lang') || 'auto';
}
function setLang(lang) {
  localStorage.setItem('surveil_lang', lang);
  applyLang();
}
function resolveLang() {
  const lang = getLang();
  if (lang === 'auto') {
    return navigator.language.startsWith('ru') ? 'ru' : 'en';
  }
  return lang;
}
function t(key) {
  const lang = resolveLang();
  return STRINGS[lang][key] || STRINGS.en[key] || key;
}
function applyLang() {
  const lang = resolveLang();
  document.documentElement.lang = lang;
  // Update all elements with data-i18n attribute
  document.querySelectorAll('[data-i18n]').forEach(el => {
    const key = el.getAttribute('data-i18n');
    el.textContent = t(key);
  });
  // Update document title
  if (status.name) {
    document.title = status.name + ' — ' + t('app_title');
  }
}

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
  if (r.status === 401 && path !== '/api/login') { showLogin(); throw new ApiError(401, t('login_required')); }
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
    setLive('err', t('no_connection'));
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
  p.title = text;   // the text is hidden on narrow screens
}

function updateStatus(st) {
  if (!st) return;
  S.status = st;
  if (st.tzOffsetMin != null) camTzMin = st.tzOffsetMin;
  $('#cam-name').textContent = st.name || t('camera');
  document.title = (st.name || t('camera')) + ' — ' + t('app_title');
  const cam = st.camera || {};
  if (cam.state === 'running') setLive('on', t('live'));
  else if (cam.state === 'error') setLive('err', t('camera_error'));
  else if (cam.state === 'starting') setLive('', t('starting'));
  else setLive('err', t('camera_stopped'));

  const rec = st.recording || {};
  $('#pill-rec').hidden = !rec.active;
  const rb = $('#btn-record');
  rb.classList.toggle('rec-on', !!rec.manual);
  $('use', rb).setAttribute('href', rec.manual ? '#i-stop' : '#i-record');
  $('span', rb).textContent = rec.manual ? t('stop') : t('record');

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
  if (cam.state === 'error' && S.view === 'live') stageMessage(t('camera_error_prefix') + (cam.error || 'unknown'), true);
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
  if (m.enabled === false) { stateEl.textContent = t('motion_off'); stateEl.className = ''; }
  else if (m.active) { stateEl.textContent = t('motion_active'); stateEl.className = 'active'; }
  else { stateEl.textContent = t('motion_idle'); stateEl.className = ''; }
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

function unsupportedMessage() {
  // iPhone Safari has (Managed)MediaSource only since iOS 17.1; other browsers there use the same engine.
  if (!/iPhone|iPod/.test(navigator.userAgent)) {
    return t('browser_not_supported');
  }
  const rtsp = S.status && S.status.rtsp;
  const alt = rtsp && rtsp.enabled && rtsp.port ? `rtsp://${location.hostname}:${rtsp.port}/live` : `http://${location.host}/live.mp4`;
  return `${t('iphone_not_supported')}${alt}`;
}

function startPlayer() {
  clearTimeout(playerStopTimer);
  if (!LivePlayer.supported()) {
    stageMessage(unsupportedMessage(), true);
    return;
  }
  if (!player) {
    player = new LivePlayer($('#video'), {
      onState: (s, msg) => {
        if (s === 'playing') stageMessage(null);
        else if (s === 'connecting') stageMessage(t('connecting'));
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
      toast(on ? t('recording_started') : t('recording_stopped'));
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
  if (!list.length) { ul.append(h('li', { class: 'muted', text: t('nothing_yet') })); return; }
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
  } catch (e) { if (e.status !== 401) toast(t('failed_to_load_recordings') + ': ' + e.message, true); }
}

const TRIGGERS = { motion: 'motion', continuous: 'continuous', manual: 'manual', recovered: 'recovered' };

function filteredRecordings() {
  return S.filter === 'all' ? S.recordings : S.recordings.filter((r) => r.trigger === S.filter);
}

function renderRecordings() {
  const st = S.storage;
  if (st) {
    const total = st.totalBytes || 1;
    $('#storage-text').textContent = `${t('recordings_storage')}: ${fmtBytes(st.usedBytes)} · ${t('free')} ${fmtBytes(st.freeBytes)} / ${fmtBytes(st.totalBytes)} (${st.label})`;
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
        h('span', { text: real.length ? `${real.length} ${plural(real.length, [t('recording_singular'), t('recording_plural'), t('recording_many')])} · ${fmtDuration(dur)}` : '' })),
      grid));
  }
  $('#btn-more').hidden = list.length <= S.shown;
  updateSelectBar();
}

function recCard(r) {
  if (r.live) {
    return h('div', { class: 'rec-card' },
      h('div', { class: 'rec-thumb' },
        h('div', { class: 'live-rec' }, h('span', { class: 'dot' }), t('recording_in_progress'))),
      h('div', { class: 'rec-meta' },
        h('div', null, h('div', { class: 'rec-time', text: fmtTime(r.startMs) }), h('div', { class: 'rec-sub', text: t('trigger_' + TRIGGERS[r.trigger]) || '' })),
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
      h('span', { class: 'tag ' + r.trigger, text: t('trigger_' + TRIGGERS[r.trigger]) || r.trigger })));
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
  $('#select-count').textContent = `${t('selected')}: ${S.selected.size}`;
  $('#btn-delete-selected').disabled = S.selected.size === 0;
}

function openRecording(id) {
  const list = filteredRecordings();
  let idx = list.findIndex((r) => r.id === id);
  if (idx < 0) return;
  const show = () => {
    const r = list[idx];
    const video = h('video', { src: `/rec/${r.id}.mp4`, controls: true, autoplay: true, playsinline: true, preload: 'metadata' });
    const prev = h('button', { class: 'btn ghost', disabled: idx >= list.length - 1, title: t('prev') }, icon('left'), t('earlier'));
    const next = h('button', { class: 'btn ghost', disabled: idx <= 0, title: t('next') }, t('later'), icon('right'));
    prev.onclick = () => { idx++; show(); };
    next.onclick = () => { idx--; show(); };
    const dl = h('a', { class: 'btn', href: `/rec/${r.id}.mp4?download=1` }, icon('download'), t('download'));
    const del = h('button', { class: 'btn danger' }, icon('trash'), t('delete'));
    del.onclick = async () => {
      if (!confirm(t('delete_recording_confirm'))) return;
      try {
        await api('/api/recordings/' + encodeURIComponent(r.id), { method: 'DELETE' });
        S.recordings = S.recordings.filter((x) => x.id !== r.id);
        list.splice(idx, 1);
        toast(t('recording_deleted'));
        renderRecordings();
        if (!list.length) closeModal();
        else { idx = Math.min(idx, list.length - 1); show(); }
      } catch (e) { toast(e.message, true); }
    };
    openModal({
      title: `${fmtDateTime(r.startMs)} · ${fmtDuration(r.durationMs)} · ${t('trigger_' + TRIGGERS[r.trigger]) || ''}`,
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
      toast(`${t('deleted')}: ${r.deleted}`);
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
  if (!S.events.length) { ul.append(h('li', { class: 'muted', text: t('no_events_yet') })); return; }
  for (const e of S.events) {
    const body = h('div', { class: 'ev-body' }, h('div', { text: e.message }), h('div', { class: 'ev-time', text: fmtDateTime(e.timeMs) }));
    if (e.recordingId) {
      const a = h('a', { href: '#/recordings', class: 'ev-link', text: t('open_recording') });
      a.onclick = async (ev) => {
        ev.preventDefault();
        if (!S.recordings.length) await loadRecordings();
        if (S.recordings.some((r) => r.id === e.recordingId)) openRecording(e.recordingId);
        else toast(t('recording_still_writing'));
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
    if (def && def.apply === 'pipeline' && r.changed.length) toast(t('saved_camera_restarting'));
    else if (def && def.apply === 'server' && r.changed.length) {
      toast(t('port_changed'));
      setTimeout(() => { location.port = String(value); }, 1500);
    } else if (r.changed.length) toast(t('saved'));
    return true;
  } catch (e) {
    toast(t('not_saved') + ': ' + e.message, true);
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

const GROUP_TITLES = {
  ru: { video: 'Видео', camera: 'Камера', osd: 'Надписи на видео', motion: 'Детектор движения', record: 'Запись', audio: 'Звук', notify: 'Уведомления в Telegram', network: 'Доступ', system: 'Система' },
  en: { video: 'Video', camera: 'Camera', osd: 'On-screen display', motion: 'Motion detection', record: 'Recording', audio: 'Audio', notify: 'Telegram notifications', network: 'Access', system: 'System' },
};
function groupTitle(id) {
  const lang = resolveLang();
  return GROUP_TITLES[lang][id] || GROUP_TITLES.en[id] || id;
}

function renderSettings() {
  const root = $('#settings-groups');
  root.textContent = '';
  root.classList.toggle('show-adv', $('#show-advanced').checked);

  // Language selector card
  const langCard = h('div', { class: 'card group' }, h('h2', { text: t('language') }));
  const langSelect = h('select', { class: 'input', style: 'max-width:200px' });
  for (const [code, label] of [['auto', t('lang_system')], ['en', 'English'], ['ru', t('lang_ru')]]) {
    const opt = h('option', { value: code, selected: getLang() === code });
    opt.textContent = label;
    langSelect.append(opt);
  }
  langSelect.onchange = () => { setLang(langSelect.value); renderSettings(); };
  langCard.append(langSelect);
  root.append(langCard);

  for (const g of S.groups || []) {
    const card = h('div', { class: 'card group', 'data-group': g.id }, h('h2', { text: groupTitle(g.id) }));
    card.append(h('div', { class: 'group-notice', hidden: true }));
    for (const def of g.settings) card.append(renderField(def));
    if (g.id === 'notify') {
      const test = h('button', { class: 'btn', type: 'button', style: 'margin-top:12px' }, t('send_test'));
      test.onclick = async () => {
        test.disabled = true;
        try { await api('/api/notify/test', { body: {} }); toast(t('test_sent')); }
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
    notify.textContent = st.notify.error ? `${t('last_send_failed')}: ${st.notify.error}` : '';
  }
  const audio = $('[data-group="audio"] .group-notice');
  if (!audio || !st || !st.camera) return;
  const cam = st.camera;
  if (cam.audio === 'error') {
    audio.hidden = false;
    audio.className = 'group-notice warn';
    audio.textContent = `${t('audio_not_recording')}: ${cam.audioError || t('microphone_unavailable')}`;
  } else if (cam.audio === 'on') {
    audio.hidden = false;
    audio.className = 'group-notice ok';
    audio.textContent = t('microphone_working');
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
    if (!opts.some((o) => o.value === value)) sel.append(h('option', { value: value, text: value || t('auto') }));
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
    const btn = h('button', { class: 'btn', type: 'button' }, icon('grid'), t('configure'));
    btn.onclick = () => openZonesEditor(def);
    const info = h('span', { class: 'muted', text: value ? t('zones_excluded') : t('zones_none') });
    row.append(h('div', { class: 'field-control' }, info, btn));
  } else if (def.type === 'text' && def.editor === 'tg-chat') {
    const input = h('input', { type: 'text', maxlength: def.maxLength, value, placeholder: '123456789' });
    input.onchange = () => saveSetting(def.key, input.value.trim(), field);
    const find = h('button', { class: 'btn', type: 'button', text: t('find') });
    find.onclick = async () => {
      try {
        const r = await api('/api/notify/chats', { body: {} });
        const chats = r.chats || [];
        if (!chats.length) { toast(t('no_chats'), true); return; }
        const pick = chats.length === 1 ? chats[0] : chats.find((c) => confirm(`${t('use_chat')}${c.title}${t('chat_suffix')}${c.id})?`));
        if (pick && await saveSetting(def.key, pick.id, field)) { input.value = pick.id; toast(t('chat') + ': ' + pick.title); }
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
    const input = h('input', { type: 'password', placeholder: isSet ? t('new_value') : (isPassword ? t('set_password') : t('paste_value')), autocomplete: 'new-password', maxlength: def.maxLength });
    const save = h('button', { class: 'btn primary', type: 'button', text: t('save') });
    save.onclick = async () => {
      const v = input.value.trim();
      if (!v) { toast(t('field_empty'), true); return; }
      if (await saveSetting(def.key, v, field)) {
        if (isPassword) {
          const user = S.values['net.username'] || 'admin';
          await api('/api/login', { body: { username: user, password: v } }).catch(() => {});
          toast(t('password_set'));
        }
        loadSettings();
      }
    };
    const ctl = h('div', { class: 'field-control' }, input, save);
    if (isSet) {
      const clear = h('button', { class: 'btn ghost', type: 'button', text: t('remove') });
      clear.onclick = async () => {
        if (isPassword && !confirm(t('disable_password_confirm'))) return;
        if (await saveSetting(def.key, '', field)) loadSettings();
      };
      ctl.append(clear);
    }
    field.append(h('div', { class: 'range-wrap' }, ctl));
    const state = isPassword
      ? (isSet ? t('password_set_notice') : t('password_not_set_notice'))
      : (isSet ? t('set_notice') : t('not_set_notice'));
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
  card.append(h('h2', { text: t('connection_info') }));
  const port = st.port || location.port || 80;
  const list = h('div', { class: 'addr-list' });
  const kinds = { wifi: 'Wi-Fi', ethernet: 'Ethernet', hotspot: t('hotspot'), vpn: 'VPN', mobile: t('mobile'), other: t('network') };
  for (const a of (st.network || []).filter((x) => !x.ipv6)) {
    const url = `http://${a.address}:${port}/`;
    const copy = h('button', { class: 'icon-btn', title: t('copy') }, icon('copy'));
    copy.onclick = () => copyText(url);
    list.append(h('div', { class: 'addr' }, h('code', { text: url }), copy, h('span', { class: 'kind', text: `${kinds[a.kind] || a.kind} · ${a.iface}` })));
  }
  card.append(h('div', { class: 'card-title', text: t('web_interface') }), list);
  const host = (st.network || []).find((x) => !x.ipv6);
  if (host) {
    const streams = [];
    if (st.rtsp && st.rtsp.enabled && st.rtsp.port) streams.push([`rtsp://${host.address}:${st.rtsp.port}/live`, 'RTSP']);
    streams.push([`http://${host.address}:${port}/live.mp4`, 'HTTP']);
    const list2 = h('div', { class: 'addr-list' });
    for (const [url, kind] of streams) {
      const copy = h('button', { class: 'icon-btn', title: t('copy') }, icon('copy'));
      copy.onclick = () => copyText(url);
      list2.append(h('div', { class: 'addr' }, h('code', { text: url }), copy, h('span', { class: 'kind', text: kind })));
    }
    card.append(h('div', { class: 'card-title', text: t('stream_for_vlc') }), list2);
    if (st.rtsp && st.rtsp.error) card.append(h('div', { class: 'group-notice warn', text: st.rtsp.error }));
  }
  const d = st.device || {};
  card.append(h('dl', { class: 'kv' },
    h('dt', { text: t('device') }), h('dd', { text: [d.model, d.os].filter(Boolean).join(', ') || '—' }),
    h('dt', { text: t('encoder') }), h('dd', { text: (st.camera && st.camera.encoder) || '—' }),
    h('dt', { text: t('version') }), h('dd', { text: st.version || '—' }),
    h('dt', { text: t('uptime') }), h('dd', { text: fmtUptime(st.uptimeSec || 0) })));
  const restart = h('button', { class: 'btn', style: 'margin-top:14px' }, icon('refresh'), t('restart_camera'));
  restart.onclick = async () => { await api('/api/camera/restart', { body: {} }).catch((e) => toast(e.message, true)); toast(t('camera_restarting')); };
  card.append(restart);
}

function copyText(t) {
  const done = () => toast(t('copied'));
  if (navigator.clipboard && window.isSecureContext) navigator.clipboard.writeText(t).then(done, () => fallbackCopy(t, done));
  else fallbackCopy(t, done);
}
function fallbackCopy(t, done) {
  const ta = h('textarea', { style: 'position:fixed;opacity:0' });
  ta.value = t;
  document.body.append(ta);
  ta.select();
  try { document.execCommand('copy'); done(); } catch (e) { prompt(t('copy_address'), t); }
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
  img.onerror = () => { ensure(); build(); toast(t('no_frame_from_camera'), true); };
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

  const clear = h('button', { class: 'btn ghost', type: 'button', text: t('clear') });
  clear.onclick = () => { z.bits.fill(false); build(); };
  const invert = h('button', { class: 'btn ghost', type: 'button', text: t('invert') });
  invert.onclick = () => { z.bits = z.bits.map((b) => !b); build(); };
  const save = h('button', { class: 'btn primary', type: 'button', text: t('save') });
  save.onclick = async () => {
    if (await saveSetting(def.key, formatZones(z))) { closeModal(); renderSettings(); }
  };
  const cancel = h('button', { class: 'btn ghost', type: 'button', text: t('cancel') });
  cancel.onclick = closeModal;
  openModal({
    title: t('motion_zones'),
    body: [wrap, h('div', { class: 'zones-hint', text: t('zones_hint') })],
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
  applyLang();
  try {
    const s = await api('/api/session');
    if (s.tzOffsetMin != null) camTzMin = s.tzOffsetMin;
    if (s.name) $('#cam-name').textContent = s.name;
    if (s.authRequired && !s.authorized) { $('#login-title').textContent = s.name || t('login_title'); showLogin(); return; }
  } catch (e) {
    stageMessage(t('camera') + ' ' + t('unavailable') + e.message, true);
  }
  start();
}

boot();
