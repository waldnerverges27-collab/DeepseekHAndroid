#!/usr/bin/env bash
# ==============================================================================
# update-node.sh
#
# Actualiza el runtime de Node.js dentro de un `app/src/main/assets/rootfs.tar.xz`
# ya construido sin tener que reconstruir toda la imagen Debian/Ubuntu desde cero,
# preservando `@deepseek-ai/dsh` y los paquetes globales existentes.
#
# NOTA: Si ya tienes la app instalada en tu teléfono Android, también puedes
# actualizar Node.js EN VIVO desde la pestaña Terminal ejecutando directamente:
#   dsh-update-node latest
#
# Uso en host de desarrollo:
#   ./scripts/update-node.sh              # Actualiza a la versión estable MÁS ACTUAL
#   ./scripts/update-node.sh latest       # Igual que arriba (ej. v26.13.0+)
#   ./scripts/update-node.sh lts          # Actualiza a la última LTS activa
#   ./scripts/update-node.sh 26.13.0      # Actualiza a una versión específica
# ==============================================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_NODE="${1:-${NODE_VERSION:-latest}}"
ROOTFS_ARCHIVE="${ROOTFS_ARCHIVE:-${PROJECT_ROOT}/app/src/main/assets/rootfs.tar.xz}"
VERSION_FILE="${PROJECT_ROOT}/app/src/main/assets/rootfs.version"
NODE_ARCH="${NODE_ARCH:-linux-arm64}"

if [ ! -f "$ROOTFS_ARCHIVE" ]; then
  echo "[update-node][ERROR] No existe ${ROOTFS_ARCHIVE}. Ejecuta primero ./scripts/build-rootfs-docker.sh o ./scripts/build-rootfs.sh" >&2
  exit 1
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

echo "[update-node] Extrayendo ${ROOTFS_ARCHIVE}..."
mkdir -p "${WORK_DIR}/rootfs"
tar -xJf "$ROOTFS_ARCHIVE" -C "${WORK_DIR}/rootfs"

echo "[update-node] Descargando e instalando Node.js (${TARGET_NODE}, ${NODE_ARCH}) en rootfs/usr/local..."
python3 - "$TARGET_NODE" "$NODE_ARCH" "${WORK_DIR}" <<'PYEOF'
import json, re, subprocess, sys, urllib.request

spec = sys.argv[1].lstrip("v")
arch = sys.argv[2]
work = sys.argv[3]
base = "https://nodejs.org/dist"

if re.match(r"^\d+\.\d+\.\d+$", spec):
    resolved = spec
else:
    with urllib.request.urlopen(f"{base}/index.json", timeout=20) as resp:
        entries = json.loads(resp.read().decode("utf-8"))
    resolved = None
    for item in entries:
        ver = item.get("version", "").lstrip("v")
        if not re.match(r"^\d+\.\d+\.\d+$", ver):
            continue
        if arch not in item.get("files", []):
            continue
        if spec.lower() in ("latest", "current"):
            resolved = ver
            break
        if spec.lower() == "lts" and item.get("lts"):
            resolved = ver
            break
        if spec.isdigit() and ver.startswith(f"{spec}."):
            resolved = ver
            break
    if not resolved:
        raise SystemExit(f"No se pudo resolver Node.js para canal={spec} ({arch})")

with open(f"{work}/resolved_version.txt", "w", encoding="utf-8") as fh:
    fh.write(resolved)

tarball = f"node-v{resolved}-{arch}.tar.xz"
subprocess.check_call(["curl", "-fsSL", "--retry", "3", "-o", f"{work}/{tarball}", f"{base}/v{resolved}/{tarball}"])
subprocess.check_call(["curl", "-fsSL", "--retry", "3", "-o", f"{work}/SHASUMS256.txt", f"{base}/v{resolved}/SHASUMS256.txt"])
PYEOF

RESOLVED_VER="$(cat "${WORK_DIR}/resolved_version.txt")"
TARBALL_NAME="node-v${RESOLVED_VER}-${NODE_ARCH}.tar.xz"
EXPECTED_SHA="$(awk -v fn="$TARBALL_NAME" '$2 == fn {print $1}' "${WORK_DIR}/SHASUMS256.txt" | head -n 1)"
ACTUAL_SHA="$(sha256sum "${WORK_DIR}/${TARBALL_NAME}" | awk '{print $1}')"

if [ -z "$EXPECTED_SHA" ] || [ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]; then
  echo "[update-node][ERROR] Verificación SHA-256 fallida para ${TARBALL_NAME}" >&2
  exit 1
fi

rm -rf \
  "${WORK_DIR}/rootfs/usr/local/lib/node_modules/npm" \
  "${WORK_DIR}/rootfs/usr/local/lib/node_modules/corepack" \
  "${WORK_DIR}/rootfs/usr/local/include/node"

tar -xJf "${WORK_DIR}/${TARBALL_NAME}" -C "${WORK_DIR}/rootfs/usr/local" --strip-components=1 \
  --exclude='*/CHANGELOG.md' \
  --exclude='*/README.md' \
  --exclude='*/share/doc' \
  --exclude='*/share/man'

ln -sf /opt/dsh/node_modules/.bin/dsh "${WORK_DIR}/rootfs/usr/local/bin/dsh"
cp "${PROJECT_ROOT}/app/src/main/assets/container/update-node.sh" "${WORK_DIR}/rootfs/usr/local/bin/dsh-update-node"
chmod 0755 "${WORK_DIR}/rootfs/usr/local/bin/dsh-update-node"

echo "[update-node] Re-empaquetando ${ROOTFS_ARCHIVE}..."
XZ_OPT="-T0 -6" tar -cJf "$ROOTFS_ARCHIVE" -C "${WORK_DIR}/rootfs" .
sha256sum "$ROOTFS_ARCHIVE" > "${ROOTFS_ARCHIVE}.sha256"

STAMP="rootfs-node-v${RESOLVED_VER}-$(date -u +%Y%m%d%H%M)"
echo "$STAMP" > "$VERSION_FILE"

echo "[update-node] Completado: Node.js v${RESOLVED_VER} empaquetado (${STAMP})."
