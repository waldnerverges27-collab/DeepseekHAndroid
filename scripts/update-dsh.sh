#!/usr/bin/env bash
# ==============================================================================
# update-dsh.sh
#
# Actualiza la versión de `@deepseek-ai/dsh`:
#   Modo A (`--in-container`): ejecutar DENTRO de la terminal integrada de Android
#     para actualizar `dsh` en caliente sobre el contenedor PRoot instalado.
#   Modo B (por defecto en PC): desempaqueta `app/src/main/assets/rootfs.tar.xz`
#     usando Docker ARM64, actualiza `@deepseek-ai/dsh`, incrementa `rootfs.version`
#     y reempaqueta el asset sin reconstruir Debian desde cero.
#
# Uso:
#   En Android (Terminal integrada):
#     bash /mnt/saf/<repo>/scripts/update-dsh.sh --in-container [version]
#   En máquina de compilación:
#     ./scripts/update-dsh.sh [version]
# ==============================================================================
set -euo pipefail

MODE="host"
TARGET_VERSION="latest"

for arg in "$@"; do
  case "$arg" in
    --in-container) MODE="container" ;;
    *) TARGET_VERSION="$arg" ;;
  esac
done

if [ "$MODE" = "container" ]; then
  echo "[update-dsh] Actualizando @deepseek-ai/dsh@${TARGET_VERSION} dentro del contenedor PRoot..."
  mkdir -p /opt/dsh
  cd /opt/dsh
  [ -f package.json ] || npm init -y >/dev/null
  npm install --omit=dev "@deepseek-ai/dsh@${TARGET_VERSION}"
  ln -sf /opt/dsh/node_modules/.bin/dsh /usr/local/bin/dsh
  npm cache clean --force
  echo "[update-dsh] Versión instalada: $(dsh --version || true)"
  echo "[update-dsh] Pulsa 'Reiniciar' en la notificación de DeepSeek Harness para recargar el motor."
  exit 0
fi

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DSH_VERSION="$TARGET_VERSION" "${PROJECT_ROOT}/scripts/build-rootfs-docker.sh"
