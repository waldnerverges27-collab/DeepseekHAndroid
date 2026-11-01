#!/usr/bin/env bash
# ==============================================================================
# build-rootfs-docker.sh
#
# Genera `app/src/main/assets/rootfs.tar.xz` usando Docker Buildx (`linux/arm64`)
# en cualquier host (Linux x86_64/arm64, macOS Apple Silicon/Intel o Windows WSL2)
# sin requerir privilegios root locales ni debootstrap instalado en el host.
#
# Por defecto instala Debian 13 (trixie) + la versión estable MÁS ACTUAL de
# Node.js (`NODE_VERSION=latest`, resuelta en vivo desde nodejs.org, p.ej. v26.x+).
#
# Uso:
#   ./scripts/build-rootfs-docker.sh
#   NODE_VERSION=latest DSH_VERSION=latest ./scripts/build-rootfs-docker.sh
#   NODE_VERSION=26.13.0 ./scripts/build-rootfs-docker.sh
# ==============================================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEBIAN_SUITE="${DEBIAN_SUITE:-trixie}"
NODE_VERSION="${NODE_VERSION:-latest}"
DSH_VERSION="${DSH_VERSION:-latest}"
ASSETS_DIR="${PROJECT_ROOT}/app/src/main/assets"

command -v docker >/dev/null 2>&1 || {
  echo "[build-rootfs-docker][ERROR] Docker no está instalado." >&2
  exit 1
}

mkdir -p "$ASSETS_DIR"

echo "[build-rootfs-docker] Construyendo rootfs ARM64 (Debian ${DEBIAN_SUITE}, Node=${NODE_VERSION}, dsh@${DSH_VERSION})..."
docker buildx build \
  --platform linux/arm64 \
  --file "${PROJECT_ROOT}/scripts/Dockerfile.rootfs" \
  --build-arg "DEBIAN_SUITE=${DEBIAN_SUITE}" \
  --build-arg "NODE_VERSION=${NODE_VERSION}" \
  --build-arg "DSH_VERSION=${DSH_VERSION}" \
  --target artifact \
  --output "type=local,dest=${ASSETS_DIR}" \
  "$PROJECT_ROOT"

BUNDLED_NODE="$(tr -d '[:space:]' < "${ASSETS_DIR}/node.version" 2>/dev/null || echo "${NODE_VERSION}")"
rm -f "${ASSETS_DIR}/node.version"

STAMP="debian-${DEBIAN_SUITE}-node-${BUNDLED_NODE}-dsh-${DSH_VERSION}-$(date -u +%Y%m%d%H%M)"
echo "$STAMP" > "${ASSETS_DIR}/rootfs.version"

echo "[build-rootfs-docker] Artefacto listo (Node ${BUNDLED_NODE}):"
ls -lh "${ASSETS_DIR}/rootfs.tar.xz" "${ASSETS_DIR}/rootfs.version"
