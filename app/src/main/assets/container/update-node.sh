#!/usr/bin/env bash
# ==============================================================================
# /usr/local/bin/dsh-update-node
#
# Instala o actualiza Node.js (binario oficial glibc + cabeceras C/C++ + npm +
# corepack/pnpm/yarn) dentro del contenedor PRoot o durante la construcción del
# rootfs, resolviendo dinámicamente la versión estable más actual publicada en
# https://nodejs.org/dist/index.json y verificando su suma SHA-256 oficial.
#
# Uso dentro de la Terminal integrada de Android (o chroot/Docker):
#   dsh-update-node            # Instala la versión estable MÁS ACTUAL (Current)
#   dsh-update-node latest     # Igual que arriba (ej. v26.13.0+)
#   dsh-update-node lts        # Instala la versión Active LTS más reciente
#   dsh-update-node 26.13.0    # Instala una versión exacta
# ==============================================================================
set -euo pipefail

REQUESTED="${1:-latest}"
INSTALL_PREFIX="${NODE_INSTALL_PREFIX:-/usr/local}"
NODE_DIST_BASE="${NODE_DIST_BASE:-https://nodejs.org/dist}"

log() { printf '\033[1;34m[node-updater]\033[0m %s\n' "$*"; }
err() { printf '\033[1;31m[node-updater][ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

command -v curl >/dev/null 2>&1 || err "Se requiere curl"
command -v python3 >/dev/null 2>&1 || err "Se requiere python3"
command -v tar >/dev/null 2>&1 || err "Se requiere tar"
command -v xz >/dev/null 2>&1 || err "Se requiere xz (xz-utils)"

MACHINE="$(uname -m)"
case "$MACHINE" in
  aarch64|arm64) NODE_ARCH="linux-arm64" ;;
  x86_64|amd64)  NODE_ARCH="linux-x64" ;;
  *) err "Arquitectura no soportada para Node.js oficial: ${MACHINE}" ;;
esac

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

resolve_version() {
  local spec="${1#v}"
  if [[ "$spec" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "$spec"
    return 0
  fi

  log "Consultando versiones oficiales de Node.js en ${NODE_DIST_BASE}/index.json (canal=${spec})..." >&2
  curl -fsSL --retry 3 --connect-timeout 15 "${NODE_DIST_BASE}/index.json" -o "${WORK_DIR}/index.json"

  python3 - "${WORK_DIR}/index.json" "$spec" "$NODE_ARCH" <<'PYEOF'
import json, re, sys

entries = json.load(open(sys.argv[1], encoding="utf-8"))
channel = sys.argv[2].lower()
arch_token = f"{sys.argv[3]}"

for item in entries:
    ver = item.get("version", "").lstrip("v")
    # Descartar pre-lanzamientos inestables (alpha/beta/rc/nightly)
    if not re.match(r"^\d+\.\d+\.\d+$", ver):
        continue
    files = item.get("files", [])
    # Verificar que exista el artefacto para esta arquitectura (ej. linux-arm64)
    if arch_token not in files and f"{arch_token}-tar" not in files:
        # En index.json de nodejs.org suele figurar como "linux-arm64"
        continue
    if channel in ("latest", "current"):
        print(ver)
        sys.exit(0)
    if channel == "lts" and item.get("lts"):
        print(ver)
        sys.exit(0)
    if channel.isdigit() and ver.startswith(f"{channel}."):
        print(ver)
        sys.exit(0)

raise SystemExit(f"No se encontró ninguna versión estable de Node.js para canal='{channel}' y arch='{arch_token}'")
PYEOF
}

RESOLVED_VER="$(resolve_version "$REQUESTED")"
TARBALL_NAME="node-v${RESOLVED_VER}-${NODE_ARCH}.tar.xz"
TARBALL_URL="${NODE_DIST_BASE}/v${RESOLVED_VER}/${TARBALL_NAME}"
SHASUMS_URL="${NODE_DIST_BASE}/v${RESOLVED_VER}/SHASUMS256.txt"

if [ -x "${INSTALL_PREFIX}/bin/node" ]; then
  CURRENT_VER="$("${INSTALL_PREFIX}/bin/node" --version 2>/dev/null || echo 'ninguna')"
  log "Versión actual detectada: ${CURRENT_VER} -> Objetivo: v${RESOLVED_VER}"
else
  log "Instalando Node.js v${RESOLVED_VER} (${NODE_ARCH})..."
fi

curl -fsSL --retry 3 --connect-timeout 20 -o "${WORK_DIR}/${TARBALL_NAME}" "$TARBALL_URL"
curl -fsSL --retry 3 --connect-timeout 20 -o "${WORK_DIR}/SHASUMS256.txt" "$SHASUMS_URL"

EXPECTED_SHA="$(awk -v fn="$TARBALL_NAME" '$2 == fn {print $1}' "${WORK_DIR}/SHASUMS256.txt" | head -n 1)"
[ -n "$EXPECTED_SHA" ] || err "No se encontró suma SHA-256 para ${TARBALL_NAME} en SHASUMS256.txt"

ACTUAL_SHA="$(sha256sum "${WORK_DIR}/${TARBALL_NAME}" | awk '{print $1}')"
[ "$ACTUAL_SHA" = "$EXPECTED_SHA" ] || err "Fallo de verificación SHA-256 (esperado=${EXPECTED_SHA}, actual=${ACTUAL_SHA})"
log "Suma SHA-256 verificada correctamente (${ACTUAL_SHA:0:16}...)"

mkdir -p "$INSTALL_PREFIX"
# Preservar otros paquetes globales instalados en /usr/local/lib/node_modules reemplazando solo npm/corepack propios
rm -rf "${INSTALL_PREFIX}/lib/node_modules/npm" "${INSTALL_PREFIX}/lib/node_modules/corepack" "${INSTALL_PREFIX}/include/node"

# Extraemos conservando include/node (cabeceras C/C++ para compilar addons nativos node-gyp en el futuro)
tar -xJf "${WORK_DIR}/${TARBALL_NAME}" -C "$INSTALL_PREFIX" --strip-components=1 \
  --exclude='*/CHANGELOG.md' \
  --exclude='*/README.md' \
  --exclude='*/share/doc' \
  --exclude='*/share/man'

# Habilitar corepack para disponer inmediatamente de pnpm y yarn además de npm/npx
if [ -x "${INSTALL_PREFIX}/bin/corepack" ]; then
  "${INSTALL_PREFIX}/bin/corepack" enable --install-directory "${INSTALL_PREFIX}/bin" 2>/dev/null || true
fi

# Restaurar el symlink de dsh si existe /opt/dsh
if [ -x /opt/dsh/node_modules/.bin/dsh ]; then
  ln -sf /opt/dsh/node_modules/.bin/dsh "${INSTALL_PREFIX}/bin/dsh"
fi

log "Node.js actualizado con éxito:"
log "  node : $("${INSTALL_PREFIX}/bin/node" --version)"
log "  npm  : $("${INSTALL_PREFIX}/bin/npm" --version)"
