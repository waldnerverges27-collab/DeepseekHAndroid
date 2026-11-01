'use strict';

/**
 * dsh-android-preload.cjs
 *
 * Preload de compatibilidad para Node.js (v24 / v26+ Current / futuras versiones ESM y CJS)
 * ejecutándose dentro de PRoot en Android 10–15+ sin root:
 * 1. Android 11+ (SELinux) bloquea bind() sobre sockets NETLINK_ROUTE, provocando que
 *    os.networkInterfaces() lance SystemError EACCES (errno 13) al iniciar `dsh web` u otras CLIs.
 * 2. Algunos kernels OEM en Android restringen /proc/stat devolviendo os.cpus() === [],
 *    lo que rompe pools de workers que calculan Math.max(os.cpus().length, ...).
 * 3. Sincroniza las exportaciones nombradas de ESM (`import { networkInterfaces } from 'node:os'`)
 *    mediante `module.syncBuiltinESMExports()`.
 */
const os = require('node:os');
const nodeModule = require('node:module');

const origNetworkInterfaces = os.networkInterfaces.bind(os);
os.networkInterfaces = function patchedNetworkInterfaces() {
  try {
    const result = origNetworkInterfaces();
    if (result && Object.keys(result).length > 0) {
      return result;
    }
  } catch (_err) {
    // Ignorar EACCES / EPERM de SELinux en Android 11+ y devolver interfaz loopback
  }
  return {
    lo: [
      {
        address: '127.0.0.1',
        netmask: '255.0.0.0',
        family: 'IPv4',
        mac: '00:00:00:00:00:00',
        internal: true,
        cidr: '127.0.0.1/8',
      },
    ],
  };
};

const origCpus = os.cpus.bind(os);
os.cpus = function patchedCpus() {
  try {
    const cpus = origCpus();
    if (Array.isArray(cpus) && cpus.length > 0) {
      return cpus;
    }
  } catch (_err) {
    // Fallback abajo
  }
  const parallelism = typeof os.availableParallelism === 'function' ? os.availableParallelism() : 4;
  const count = Math.max(1, parallelism || 4);
  return Array.from({ length: count }, (_, idx) => ({
    model: `Android-ARM64-Core-${idx}`,
    speed: 2000,
    times: { user: 0, nice: 0, sys: 0, idle: 1000, irq: 0 },
  }));
};

if (typeof nodeModule.syncBuiltinESMExports === 'function') {
  nodeModule.syncBuiltinESMExports();
}
