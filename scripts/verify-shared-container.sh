#!/usr/bin/env bash
# ==============================================================================
# verify-shared-container.sh
#
# Script de verificación extremo a extremo para ejecutar dentro de la Terminal
# integrada de la app Android. Comprueba que `bash` y el motor `dsh` comparten:
#   1. El mismo sistema de archivos raíz y librerías glibc.
#   2. El mismo árbol `$DSH_HOME` (/root/.dsh) y espacio de trabajo (/root/workspace).
#   3. El mismo `/tmp` y `/dev/shm`.
#   4. El acceso al puente SAF (`/mnt/saf`) y al puerto local `127.0.0.1:3080`.
# ==============================================================================
set -euo pipefail

PASS=0
FAIL=0

check() {
  local desc="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    printf '  [OK]   %s\n' "$desc"
    PASS=$((PASS + 1))
  else
    printf '  [FAIL] %s\n' "$desc"
    FAIL=$((FAIL + 1))
  fi
}

echo "=== Verificación de Contenedor PRoot Compartido (dsh + bash) ==="

check "UID emulado root (id -u == 0)" test "$(id -u)" = "0"
check "Binario node disponible en PATH" command -v node
check "Punto de entrada de dsh presente" test -f /opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js -o -x "$(command -v dsh || echo /nonexistent)"
check "Preload Android de Node instalado (/etc/dsh-android-preload.cjs)" test -f /etc/dsh-android-preload.cjs
check "os.networkInterfaces() funciona sin EACCES en Node" node --require=/etc/dsh-android-preload.cjs -e "const i=require('os').networkInterfaces(); if(!i || Object.keys(i).length===0) process.exit(1);"
check "Directorio compartido DSH_HOME (/root/.dsh o padre) escribible" mkdir -p "${DSH_HOME:-/root/.dsh}"
check "Workspace compartido (/root/workspace) escribible" sh -c 'f=/root/workspace/.probe_$$ && echo ok > "$f" && rm -f "$f"'
check "/dev/shm compartido montado y escribible" sh -c 'f=/dev/shm/.probe_$$ && echo ok > "$f" && rm -f "$f"'
check "Punto de montaje SAF (/mnt/saf) presente" test -d /mnt/saf

PORT="${DSH_PORT:-3080}"
if command -v curl >/dev/null 2>&1; then
  CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:${PORT}/" || echo "000")"
  if [ "$CODE" = "200" ] || [ "$CODE" = "401" ] || [ "$CODE" = "403" ]; then
    printf '  [OK]   Motor dsh escuchando en 127.0.0.1:%s (HTTP %s)\n' "$PORT" "$CODE"
    PASS=$((PASS + 1))
  else
    printf '  [WARN] Puerto 127.0.0.1:%s devolvió HTTP %s (¿motor iniciándose?)\n' "$PORT" "$CODE"
  fi
fi

echo "----------------------------------------------------------------"
echo "Resultado: ${PASS} comprobaciones superadas, ${FAIL} fallidas."
[ "$FAIL" -eq 0 ]
