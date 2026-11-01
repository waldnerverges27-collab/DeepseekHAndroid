(function () {
  'use strict';
  if (window.__dshAndroidPolyfillsApplied) return;
  window.__dshAndroidPolyfillsApplied = true;

  // Object.hasOwn (ES2022) para WebViews de Android 10/11
  if (typeof Object.hasOwn !== 'function') {
    Object.hasOwn = function (obj, prop) {
      return Object.prototype.hasOwnProperty.call(Object(obj), prop);
    };
  }

  // Array.prototype.at / String.prototype.at
  if (typeof Array.prototype.at !== 'function') {
    Array.prototype.at = function (n) {
      n = Math.trunc(n) || 0;
      if (n < 0) n += this.length;
      if (n < 0 || n >= this.length) return undefined;
      return this[n];
    };
  }
  if (typeof String.prototype.at !== 'function') {
    String.prototype.at = function (n) {
      n = Math.trunc(n) || 0;
      if (n < 0) n += this.length;
      if (n < 0 || n >= this.length) return undefined;
      return String(this).charAt(n);
    };
  }

  // AbortSignal.any / AbortSignal.timeout (requerido por el cliente web de dsh)
  if (typeof AbortSignal !== 'undefined') {
    if (typeof AbortSignal.any !== 'function') {
      AbortSignal.any = function (signals) {
        const controller = new AbortController();
        for (const sig of signals) {
          if (sig && sig.aborted) {
            controller.abort(sig.reason);
            return controller.signal;
          }
          if (sig && typeof sig.addEventListener === 'function') {
            sig.addEventListener('abort', function () {
              controller.abort(sig.reason);
            }, { once: true });
          }
        }
        return controller.signal;
      };
    }
    if (typeof AbortSignal.timeout !== 'function') {
      AbortSignal.timeout = function (ms) {
        const controller = new AbortController();
        setTimeout(function () {
          controller.abort(new DOMException('Signal timed out', 'TimeoutError'));
        }, ms);
        return controller.signal;
      };
    }
  }
})();
