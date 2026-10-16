'use strict';

/**
 * Live H.264 player: fragmented MP4 over WebSocket into Media Source Extensions.
 * Works over plain HTTP on a LAN (MSE does not need a secure context, unlike WebCodecs).
 * Keeps latency low by nudging playbackRate and jumping to the live edge when it falls behind.
 */
class LivePlayer {
  constructor(video, opts = {}) {
    this.video = video;
    this.onState = opts.onState || (() => {});
    this.onStats = opts.onStats || (() => {});
    this.url = opts.url || ((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws/live');
    this.targetLag = 0.35;
    this.ws = null;
    this.ms = null;
    this.sb = null;
    this.queue = [];
    this.running = false;
    this.retryDelay = 500;
    this.retryTimer = 0;
    this.watchTimer = 0;
    this.lastDataAt = 0;
    this.bytes = 0;
    this.info = null;
    this.lag = 0;
  }

  static supported() {
    const MS = window.MediaSource || window.ManagedMediaSource;
    return !!MS && MS.isTypeSupported('video/mp4; codecs="avc1.42E01E"');
  }

  start() {
    if (this.running) return;
    this.running = true;
    this.connect();
    this.watchTimer = setInterval(() => this.watch(), 500);
  }

  stop() {
    this.running = false;
    clearTimeout(this.retryTimer);
    clearInterval(this.watchTimer);
    this.teardown();
    this.onState('stopped');
  }

  connect() {
    this.teardown();
    if (!this.running) return;
    this.onState('connecting');
    let ws;
    try {
      ws = new WebSocket(this.url);
    } catch (e) {
      this.scheduleRetry();
      return;
    }
    ws.binaryType = 'arraybuffer';
    this.ws = ws;
    this.lastDataAt = performance.now();
    ws.onmessage = (ev) => {
      this.lastDataAt = performance.now();
      if (typeof ev.data === 'string') {
        let msg;
        try { msg = JSON.parse(ev.data); } catch (e) { return; }
        if (msg.type === 'init') this.init(msg);
        return;
      }
      this.bytes += ev.data.byteLength;
      this.queue.push(ev.data);
      this.pump();
    };
    ws.onclose = (ev) => {
      if (this.ws !== ws) return;
      this.ws = null;
      if (!this.running) return;
      if (ev.code === 4000) {           // format changed: reconnect at once
        this.retryDelay = 200;
        this.onState('reconnecting', 'Параметры видео изменились…');
      } else if (ev.code === 4001) {    // camera stopped
        this.retryDelay = 2000;
        this.onState('offline', 'Камера остановлена');
      } else {
        this.onState('reconnecting', 'Нет связи с камерой, переподключение…');
      }
      this.scheduleRetry();
    };
  }

  scheduleRetry() {
    clearTimeout(this.retryTimer);
    if (!this.running) return;
    this.retryTimer = setTimeout(() => this.connect(), this.retryDelay);
    this.retryDelay = Math.min(this.retryDelay * 2, 5000);
  }

  init(msg) {
    this.info = msg;
    const MS = window.MediaSource || window.ManagedMediaSource;
    if (!MS || !MS.isTypeSupported(msg.mime)) {
      this.onState('error', 'Браузер не умеет воспроизводить H.264 (' + msg.mime + ')');
      this.running = false;
      this.teardown();
      return;
    }
    this.resetMedia();
    const ms = new MS();
    this.ms = ms;
    if (window.ManagedMediaSource && MS === window.ManagedMediaSource) {
      this.video.disableRemotePlayback = true;
      this.video.srcObject = ms;
    } else {
      this.video.src = URL.createObjectURL(ms);
    }
    ms.addEventListener('sourceopen', () => {
      if (this.ms !== ms) return;
      if (!window.ManagedMediaSource || MS !== window.ManagedMediaSource) URL.revokeObjectURL(this.video.src);
      try {
        const sb = ms.addSourceBuffer(msg.mime);
        sb.mode = 'segments';
        sb.addEventListener('updateend', () => this.pump());
        sb.addEventListener('error', () => this.fail('Ошибка декодера'));
        this.sb = sb;
        this.pump();
      } catch (e) {
        this.fail('Не удалось открыть поток: ' + e.message);
      }
    }, { once: true });
    this.retryDelay = 500;
  }

  pump() {
    const sb = this.sb;
    if (!sb || sb.updating || this.queue.length === 0) return;
    if (this.ms.readyState !== 'open') return;
    let chunk;
    if (this.queue.length === 1) {
      chunk = this.queue[0];
    } else {
      let len = 0;
      for (const b of this.queue) len += b.byteLength;
      const all = new Uint8Array(len);
      let off = 0;
      for (const b of this.queue) { all.set(new Uint8Array(b), off); off += b.byteLength; }
      chunk = all.buffer;
    }
    this.queue = [];
    try {
      sb.appendBuffer(chunk);
    } catch (e) {
      if (e.name === 'QuotaExceededError') {
        this.queue.unshift(chunk);
        this.trim(true);
      } else {
        this.fail('Ошибка буфера: ' + e.message);
      }
      return;
    }
    if (this.video.paused) {
      const p = this.video.play();
      if (p && p.catch) p.catch(() => this.onState('paused'));
    }
  }

  /** Called every 500 ms: latency control, buffer trimming, stall detection. */
  watch() {
    if (!this.running) return;
    const v = this.video;
    const now = performance.now();
    if (this.ws && now - this.lastDataAt > 6000) {
      this.onState('reconnecting', 'Поток прервался, переподключение…');
      this.connect();
      return;
    }
    const b = v.buffered;
    if (b && b.length) {
      const end = b.end(b.length - 1);
      const lag = end - v.currentTime;
      this.lag = lag;
      if (v.currentTime < b.start(b.length - 1) || lag > 2.0) {
        v.currentTime = Math.max(b.start(b.length - 1), end - this.targetLag);
        v.playbackRate = 1;
      } else if (lag > 0.9) v.playbackRate = 1.25;
      else if (lag > this.targetLag + 0.15) v.playbackRate = 1.08;
      else if (lag < 0.12) v.playbackRate = 0.96;
      else v.playbackRate = 1;
      if (!v.paused && v.readyState >= 2) this.onState('playing');
      this.trim(false);
    }
    this.onStats({ lag: this.lag, bytes: this.bytes, info: this.info });
  }

  trim(force) {
    const sb = this.sb;
    if (!sb || sb.updating) return;
    const b = sb.buffered;
    if (!b.length) return;
    const start = b.start(0);
    const keepFrom = this.video.currentTime - (force ? 2 : 10);
    if (keepFrom - start > (force ? 0.5 : 5)) {
      try { sb.remove(start, keepFrom); } catch (e) { /* ignore */ }
    }
  }

  fail(message) {
    this.onState('reconnecting', message);
    this.connect();
  }

  resetMedia() {
    this.sb = null;
    this.queue = [];
    if (this.ms) {
      try { if (this.ms.readyState === 'open') this.ms.endOfStream(); } catch (e) { /* ignore */ }
    }
    this.ms = null;
    this.video.removeAttribute('src');
    this.video.srcObject = null;
  }

  teardown() {
    if (this.ws) {
      const ws = this.ws;
      this.ws = null;
      ws.onmessage = null;
      ws.onclose = null;
      try { ws.close(); } catch (e) { /* ignore */ }
    }
    this.resetMedia();
  }
}

window.LivePlayer = LivePlayer;
