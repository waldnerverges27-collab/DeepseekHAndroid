#!/usr/bin/env bash
# /usr/local/bin/dsh-doctor
# Verifica desde la terminal integrada que bash y el motor dsh comparten el mismo contenedor proot.
set -euo pipefail

echo "============================================================"
echo " DeepSeek Harness Android — Diagnóstico de Contenedor PRoot"
echo "============================================================"
echo "[1] Identidad y Kernel:"
echo "    whoami / id : $(whoami) / $(id)"
echo "    uname -a    : $(uname -a)"
if [ -f /etc/os-release ]; then
  . /etc/os-release
  echo "    distro      : ${PRETTY_NAME:-Debian/Ubuntu}"
fi

echo ""
echo "[2] Runtime Node.js (última versión), glibc y entorno compartido:"
echo "    bash        : $(command -v bash) ($(bash --version | head -n 1))"
echo "    node        : $(command -v node || echo 'NO ENCONTRADO') ($(node --version 2>/dev/null || echo 'n/a'))"
echo "    npm         : $(command -v npm || echo 'NO ENCONTRADO') ($(npm --version 2>/dev/null || echo 'n/a'))"
echo "    corepack    : $(command -v corepack || echo 'n/a') (pnpm/yarn listos bajo demanda)"
echo "    actualizador: dsh-update-node (ejecuta 'dsh-update-node latest' para actualizar Node en vivo)"
echo "    dsh         : $(command -v dsh || echo '/opt/dsh/node_modules/.bin/dsh')"
echo "    DSH_HOME    : ${DSH_HOME:-/root/.dsh}"
echo "    NODE_OPTIONS: ${NODE_OPTIONS:-<vacío>}"
if command -v ldd >/dev/null 2>&1 && command -v node >/dev/null 2>&1; then
  echo "    glibc (ldd) : $(ldd --version | head -n 1)"
fi

echo ""
echo "[3] Puntos de montaje y puente SAF:"
echo "    Workspace   : /root/workspace -> $(ls -ld /root/workspace 2>/dev/null || echo 'no existe')"
echo "    Puente SAF  : /mnt/saf        -> $(ls -ld /mnt/saf 2>/dev/null || echo 'no existe')"
if [ -d /mnt/saf ]; then
  echo "    Carpetas SAF montadas:"
  ls -la /mnt/saf || true
fi

echo ""
echo "[4] Procesos en el contenedor y puerto 3080:"
ps -ef 2>/dev/null | grep -E "node|dsh|proot|bash" | grep -v grep || true
if command -v curl >/dev/null 2>&1; then
  HTTP_CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://127.0.0.1:${DSH_PORT:-3080}/ || echo '000')"
  echo "    HTTP 127.0.0.1:${DSH_PORT:-3080}/ -> código ${HTTP_CODE}"
fi
echo "============================================================"
