(() => {
  const STORAGE_KEY = 'blockified-site-zoom';
  const BASE_STORAGE_KEY = 'blockified-site-zoom-base';
  const WINDOW_NAME_TOKEN = 'blockifiedZoom';
  const WINDOW_NAME_BASE_TOKEN = 'blockifiedZoomBase';
  const MIN_ZOOM = 0.5;
  const MAX_ZOOM = 2;
  const STEP = 0.1;

  function clamp(value) {
    return Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, value));
  }

  function parseZoom(value) {
    const parsed = Number.parseFloat(value);
    return Number.isFinite(parsed) ? clamp(parsed) : null;
  }

  function readWindowName(token, constrain = true) {
    const match = window.name.match(new RegExp(`(?:^|;)${token}=([0-9.]+)(?:;|$)`));
    if (!match) return null;
    if (constrain) return parseZoom(match[1]);
    const value = Number.parseFloat(match[1]);
    return Number.isFinite(value) && value > 0 ? value : null;
  }

  function readZoom() {
    try {
      const sessionValue = parseZoom(sessionStorage.getItem(STORAGE_KEY));
      if (sessionValue !== null) return sessionValue;
    } catch (_) { /* window.name remains available for local file previews. */ }

    try {
      const localValue = parseZoom(localStorage.getItem(STORAGE_KEY));
      if (localValue !== null) return localValue;
    } catch (_) { /* Local storage may be unavailable on file URLs. */ }

    return readWindowName(WINDOW_NAME_TOKEN) ?? 1;
  }

  function readBaseDpr() {
    try {
      const sessionValue = Number.parseFloat(sessionStorage.getItem(BASE_STORAGE_KEY));
      if (Number.isFinite(sessionValue) && sessionValue > 0) return sessionValue;
    } catch (_) { /* window.name remains available for local file previews. */ }

    try {
      const localValue = Number.parseFloat(localStorage.getItem(BASE_STORAGE_KEY));
      if (Number.isFinite(localValue) && localValue > 0) return localValue;
    } catch (_) { /* Local storage may be unavailable on file URLs. */ }

    const windowValue = readWindowName(WINDOW_NAME_BASE_TOKEN, false);
    return windowValue ?? window.devicePixelRatio ?? 1;
  }

  let zoom = readZoom();
  const baseDpr = readBaseDpr();
  let nativeDpr = window.devicePixelRatio || 1;
  let indicator;
  let indicatorTimer;
  let wheelLocked = false;

  function writeWindowNameValue(name, value, precision = 2) {
    const token = `${name}=${value.toFixed(precision)}`;
    const pattern = new RegExp(`(^|;)${name}=[0-9.]+(?=;|$)`);
    if (pattern.test(window.name)) {
      window.name = window.name.replace(pattern, (match, prefix) => `${prefix}${token}`);
    } else {
      window.name = window.name ? `${window.name};${token}` : token;
    }
  }

  function saveZoom() {
    const value = zoom.toFixed(2);
    try { sessionStorage.setItem(STORAGE_KEY, value); } catch (_) { /* Optional. */ }
    try { localStorage.setItem(STORAGE_KEY, value); } catch (_) { /* Optional. */ }
    try { sessionStorage.setItem(BASE_STORAGE_KEY, String(baseDpr)); } catch (_) { /* Optional. */ }
    try { localStorage.setItem(BASE_STORAGE_KEY, String(baseDpr)); } catch (_) { /* Optional. */ }
    writeWindowNameValue(WINDOW_NAME_TOKEN, zoom);
    writeWindowNameValue(WINDOW_NAME_BASE_TOKEN, baseDpr, 4);
  }

  function applyZoom() {
    document.documentElement.style.zoom = String((baseDpr * zoom) / nativeDpr);
  }

  function ensureIndicator() {
    if (indicator || !document.body) return indicator;
    indicator = document.createElement('output');
    indicator.className = 'site-zoom-indicator';
    indicator.setAttribute('aria-live', 'polite');
    document.body.append(indicator);
    return indicator;
  }

  function showIndicator() {
    const output = ensureIndicator();
    if (!output) return;
    output.value = `${Math.round(zoom * 100)}%`;
    output.classList.add('visible');
    clearTimeout(indicatorTimer);
    indicatorTimer = setTimeout(() => output.classList.remove('visible'), 850);
  }

  function setZoom(value, announce = true) {
    zoom = Math.round(clamp(value) * 100) / 100;
    applyZoom();
    saveZoom();
    if (announce) showIndicator();
  }

  const style = document.createElement('style');
  style.textContent = `
    .site-zoom-indicator {
      position: fixed;
      z-index: 10000;
      top: 0.8rem;
      right: 0.8rem;
      min-width: 4rem;
      padding: 0.55rem 0.7rem;
      border: 1px solid rgba(255, 112, 46, 0.55);
      border-radius: 0.65rem;
      color: #fffaf7;
      background: rgba(11, 8, 16, 0.94);
      box-shadow: 0 0.75rem 2rem rgba(0, 0, 0, 0.32);
      font: 700 0.82rem/1 Inter, ui-sans-serif, system-ui, sans-serif;
      text-align: center;
      pointer-events: none;
      opacity: 0;
      transform: translateY(-0.45rem);
      transition: opacity 140ms ease, transform 140ms ease;
    }
    .site-zoom-indicator.visible {
      opacity: 1;
      transform: translateY(0);
    }
    @media (prefers-reduced-motion: reduce) {
      .site-zoom-indicator { transition: none; }
    }
  `;
  document.head.append(style);
  applyZoom();
  saveZoom();

  document.addEventListener('keydown', event => {
    if (!(event.ctrlKey || event.metaKey) || event.altKey) return;
    if (event.key === '+' || event.key === '=') {
      event.preventDefault();
      setZoom(zoom + STEP);
    } else if (event.key === '-' || event.key === '_') {
      event.preventDefault();
      setZoom(zoom - STEP);
    } else if (event.key === '0') {
      event.preventDefault();
      setZoom(1);
    }
  }, true);

  document.addEventListener('wheel', event => {
    if (!event.ctrlKey) return;
    event.preventDefault();
    if (wheelLocked) return;
    wheelLocked = true;
    setZoom(zoom + (event.deltaY < 0 ? STEP : -STEP));
    setTimeout(() => { wheelLocked = false; }, 80);
  }, { passive: false, capture: true });

  window.addEventListener('resize', () => {
    const nextNativeDpr = window.devicePixelRatio || 1;
    if (Math.abs(nextNativeDpr - nativeDpr) < 0.001) return;
    zoom = Math.round(clamp(zoom * (nextNativeDpr / nativeDpr)) * 100) / 100;
    nativeDpr = nextNativeDpr;
    applyZoom();
    saveZoom();
    showIndicator();
  });

  window.BlockifiedSiteZoom = Object.freeze({
    get: () => zoom,
    getCssScale: () => (baseDpr * zoom) / nativeDpr,
    toLayoutPixels: value => value / ((baseDpr * zoom) / nativeDpr),
    set: value => setZoom(Number(value))
  });
})();
