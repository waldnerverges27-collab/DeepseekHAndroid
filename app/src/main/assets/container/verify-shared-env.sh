#!/usr/bin/env bash
# /usr/local/bin/dsh-doctor
# Verifica desde la terminal integrada que zsh/bash y el motor dsh comparten el mismo contenedor proot.
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
echo "[2] Shells (Zsh + Oh My Zsh / Bash), Node.js y entorno compartido:"
echo "    zsh         : $(command -v zsh || echo 'n/a') ($(zsh --version 2>/dev/null || echo 'n/a'))"
echo "    oh-my-zsh   : $([ -d /root/.oh-my-zsh ] && echo 'instalado (+ autosuggestions + syntax-highlighting + completions)' || echo 'no instalado')"
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
echo "[3] Prueba de enlaces duros (link2symlink / persistencia de sesiones dsh):"
TMP_TEST="/root/.dsh_l2s_check_$$"
LNK_TEST="/root/.dsh_l2s_link_$$"
rm -f "$TMP_TEST" "$LNK_TEST"
echo "ok" > "$TMP_TEST"
if ln "$TMP_TEST" "$LNK_TEST" && rm -f "$TMP_TEST" && [ "$(cat "$LNK_TEST")" = "ok" ]; then
  echo "    fs.link()   : OK (lectura tras ln + rm verificada)"
else
  echo "    fs.link()   : FALLO"
fi
rm -f "$TMP_TEST" "$LNK_TEST"

echo ""
echo "[4] Puntos de montaje y puente SAF:"
echo "    Workspace   : /root/workspace -> $(ls -ld /root/workspace 2>/dev/null || echo 'no existe')"
echo "    Puente SAF  : /mnt/saf        -> $(ls -ld /mnt/saf 2>/dev/null || echo 'no existe')"
if [ -d /mnt/saf ]; then
  echo "    Carpetas SAF montadas:"
  ls -la /mnt/saf || true
fi

echo ""
echo "[5] Procesos en el contenedor y puerto 3080:"
ps -ef 2>/dev/null | grep -E "node|dsh|proot|zsh|bash" | grep -v grep || true
if command -v curl >/dev/null 2>&1; then
  HTTP_CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://127.0.0.1:${DSH_PORT:-3080}/ || echo '000')"
  echo "    HTTP 127.0.0.1:${DSH_PORT:-3080}/ -> código ${HTTP_CODE}"
fi
echo "============================================================"
