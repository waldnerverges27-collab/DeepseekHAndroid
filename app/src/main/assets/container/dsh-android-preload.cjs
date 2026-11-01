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
 * 3. Publicación exclusiva de archivos regulares sin depender de enlaces simbólicos intermedios
 *    de PRoot (`--link2symlink`) para artefactos de sesión de `deepseek-harness` (`/.dsh/`, `.tmp`)
 *    y respaldo automático `COPYFILE_EXCL` en sistemas de archivos FUSE de Android (`/sdcard`,
 *    `/storage`, `/mnt/saf`) donde `link(2)` y `symlink(2)` devuelven EPERM/EXDEV/ENOSYS.
 * 4. Sincroniza las exportaciones nombradas de ESM (`import { link } from 'node:fs/promises'`,
 *    `import { networkInterfaces } from 'node:os'`) mediante `module.syncBuiltinESMExports()`.
 */
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const os = require('node:os');
const nodeModule = require('node:module');

const MAX_IN_MEMORY_EXCLUSIVE_COPY_BYTES = 32 * 1024 * 1024;

function toPathString(p) {
  if (typeof p === 'string') return p;
  if (Buffer.isBuffer(p)) return p.toString('utf8');
  if (p && typeof p === 'object' && p.protocol === 'file:') {
    try {
      const { fileURLToPath } = require('node:url');
      return fileURLToPath(p);
    } catch (_err) {
      return String(p);
    }
  }
  return String(p);
}

function shouldMaterializeRegularFileOnLink(existingPath, newPath) {
  const src = toPathString(existingPath);
  const dst = toPathString(newPath);
  return (
    src.includes('/.dsh/') ||
    dst.includes('/.dsh/') ||
    src.endsWith('.tmp') ||
    dst.startsWith('/mnt/saf/') ||
    dst.startsWith('/sdcard/') ||
    dst.startsWith('/storage/')
  );
}

function isLinkFallbackError(err) {
  const code = err && err.code;
  return (
    code === 'EPERM' ||
    code === 'EACCES' ||
    code === 'EXDEV' ||
    code === 'ENOSYS' ||
    code === 'ENOTSUP' ||
    code === 'EOPNOTSUPP'
  );
}

async function atomicExclusiveCopyAsync(existingPath, newPath) {
  const srcStat = await fsp.lstat(existingPath);
  if (srcStat.isDirectory()) {
    throw Object.assign(
      new Error(`EPERM: operation not permitted, link '${toPathString(existingPath)}' -> '${toPathString(newPath)}'`),
      {
        errno: -1,
        code: 'EPERM',
        syscall: 'link',
        path: toPathString(existingPath),
        dest: toPathString(newPath),
      }
    );
  }
  if (srcStat.isSymbolicLink()) {
    const linkTarget = await fsp.readlink(existingPath);
    await fsp.symlink(linkTarget, newPath);
    return;
  }
  if (srcStat.size > MAX_IN_MEMORY_EXCLUSIVE_COPY_BYTES) {
    await fsp.copyFile(existingPath, newPath, fs.constants.COPYFILE_EXCL);
    try {
      const fh = await fsp.open(newPath, 'r+');
      try {
        await fh.sync();
      } finally {
        await fh.close();
      }
    } catch (_syncErr) {
      // Algunos montajes FUSE o archivos solo-lectura no permiten reabrir con r+/fsync
    }
    return;
  }

  const content = await fsp.readFile(existingPath);
  const mode = (srcStat.mode & 0o777) || 0o600;
  const fh = await fsp.open(newPath, 'wx', mode);
  let committed = false;
  try {
    if (content.length > 0) {
      await fh.writeFile(content);
    }
    try {
      await fh.sync();
    } catch (_syncErr) {
      // Ignorar EINVAL/ENOSYS de fsync en montajes FUSE Android
    }
    committed = true;
  } finally {
    await fh.close();
    if (!committed) {
      try {
        await fsp.unlink(newPath);
      } catch (_cleanupErr) {}
    }
  }
}

function atomicExclusiveCopySync(existingPath, newPath) {
  const srcStat = fs.lstatSync(existingPath);
  if (srcStat.isDirectory()) {
    throw Object.assign(
      new Error(`EPERM: operation not permitted, link '${toPathString(existingPath)}' -> '${toPathString(newPath)}'`),
      {
        errno: -1,
        code: 'EPERM',
        syscall: 'link',
        path: toPathString(existingPath),
        dest: toPathString(newPath),
      }
    );
  }
  if (srcStat.isSymbolicLink()) {
    const linkTarget = fs.readlinkSync(existingPath);
    fs.symlinkSync(linkTarget, newPath);
    return;
  }
  if (srcStat.size > MAX_IN_MEMORY_EXCLUSIVE_COPY_BYTES) {
    fs.copyFileSync(existingPath, newPath, fs.constants.COPYFILE_EXCL);
    return;
  }
  const content = fs.readFileSync(existingPath);
  const mode = (srcStat.mode & 0o777) || 0o600;
  const fd = fs.openSync(newPath, 'wx', mode);
  let committed = false;
  try {
    if (content.length > 0) {
      fs.writeFileSync(fd, content);
    }
    try {
      fs.fsyncSync(fd);
    } catch (_syncErr) {}
    committed = true;
  } finally {
    fs.closeSync(fd);
    if (!committed) {
      try {
        fs.unlinkSync(newPath);
      } catch (_cleanupErr) {}
    }
  }
}

const origFspLink = fsp.link.bind(fsp);
async function patchedFspLink(existingPath, newPath) {
  if (shouldMaterializeRegularFileOnLink(existingPath, newPath)) {
    return atomicExclusiveCopyAsync(existingPath, newPath);
  }
  try {
    return await origFspLink(existingPath, newPath);
  } catch (err) {
    if (isLinkFallbackError(err)) {
      return atomicExclusiveCopyAsync(existingPath, newPath);
    }
    throw err;
  }
}

const origFsLinkSync = fs.linkSync.bind(fs);
function patchedFsLinkSync(existingPath, newPath) {
  if (shouldMaterializeRegularFileOnLink(existingPath, newPath)) {
    return atomicExclusiveCopySync(existingPath, newPath);
  }
  try {
    return origFsLinkSync(existingPath, newPath);
  } catch (err) {
    if (isLinkFallbackError(err)) {
      return atomicExclusiveCopySync(existingPath, newPath);
    }
    throw err;
  }
}

const origFsLink = fs.link.bind(fs);
function patchedFsLink(existingPath, newPath, callback) {
  const cb = typeof callback === 'function' ? callback : () => {};
  if (shouldMaterializeRegularFileOnLink(existingPath, newPath)) {
    atomicExclusiveCopyAsync(existingPath, newPath).then(() => cb(null), (err) => cb(err));
    return;
  }
  origFsLink(existingPath, newPath, (err) => {
    if (err && isLinkFallbackError(err)) {
      atomicExclusiveCopyAsync(existingPath, newPath).then(() => cb(null), (copyErr) => cb(copyErr));
      return;
    }
    cb(err || null);
  });
}

fsp.link = patchedFspLink;
if (fs.promises && fs.promises !== fsp) {
  fs.promises.link = patchedFspLink;
}
fs.linkSync = patchedFsLinkSync;
fs.link = patchedFsLink;

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
