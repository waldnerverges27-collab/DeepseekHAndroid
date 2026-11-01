#!/usr/bin/env bash
# ==============================================================================
# build-rootfs.sh
#
# Construye el sistema de archivos raíz autónomo (Debian 13 Trixie por defecto,
# o Ubuntu 24.04+ Noble) para ARM64 con glibc moderna, la versión estable MÁS
# ACTUAL de Node.js (`NODE_VERSION=latest`, resuelta automáticamente desde
# https://nodejs.org/dist/index.json — p.ej. Node.js v26.x+), npm/npx, corepack
# (`pnpm` y `yarn`), cabeceras C/C++ (`include/node`) para futuros addons nativos,
# deepseek-harness (`dsh`) y utilidades esenciales de terminal, generando
# `app/src/main/assets/rootfs.tar.xz`.
#
# Requisitos en host Linux (nativo arm64 o x86_64 con qemu-user-static):
#   sudo apt-get install -y debootstrap xz-utils curl tar ca-certificates python3
#   (en x86_64 además: sudo apt-get install -y qemu-user-static binfmt-support)
#
# Alternativa recomendada sin root local (macOS / Linux / WSL2):
#   ./scripts/build-rootfs-docker.sh
#
# Variables configurables:
#   DISTRO=debian|ubuntu            (por defecto: debian)
#   SUITE=trixie|bookworm|noble     (por defecto: trixie — Debian 13 Stable, glibc 2.41)
#   TARGET_ARCH=arm64|amd64         (por defecto: arm64)
#   NODE_VERSION=latest|lts|X.Y.Z   (por defecto: latest -> versión estable más actual en nodejs.org)
#   DSH_INSTALL_MODE=npm|git        (por defecto: npm)
#   DSH_VERSION=latest              (versión npm o rama/tag git de deepseek-harness)
#   INSTALL_PROOT_SKILLS=1          (instala skills de entorno proot en /root/.dsh/skills)
# ==============================================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DISTRO="${DISTRO:-debian}"
SUITE="${SUITE:-trixie}"
TARGET_ARCH="${TARGET_ARCH:-arm64}"
NODE_VERSION="${NODE_VERSION:-latest}"
DSH_INSTALL_MODE="${DSH_INSTALL_MODE:-npm}"
DSH_VERSION="${DSH_VERSION:-latest}"
INSTALL_PROOT_SKILLS="${INSTALL_PROOT_SKILLS:-1}"
STAGE_DIR="${STAGE_DIR:-${PROJECT_ROOT}/.rootfs-stage}"
OUTPUT_ASSET="${OUTPUT_ASSET:-${PROJECT_ROOT}/app/src/main/assets/rootfs.tar.xz}"
VERSION_FILE="${PROJECT_ROOT}/app/src/main/assets/rootfs.version"

log() { printf '\n\033[1;32m[build-rootfs]\033[0m %s\n' "$*"; }
err() { printf '\n\033[1;31m[build-rootfs][ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || err "Este script requiere privilegios de root para debootstrap/chroot (o usa ./scripts/build-rootfs-docker.sh)."
command -v debootstrap >/dev/null 2>&1 || err "No se encontró debootstrap. Instálalo con: apt-get install -y debootstrap"
command -v xz >/dev/null 2>&1 || err "No se encontró xz. Instálalo con: apt-get install -y xz-utils"

HOST_MACHINE="$(uname -m)"
USE_QEMU=0
if [ "$TARGET_ARCH" = "arm64" ] && [ "$HOST_MACHINE" != "aarch64" ] && [ "$HOST_MACHINE" != "arm64" ]; then
  command -v qemu-aarch64-static >/dev/null 2>&1 || err "Host ${HOST_MACHINE} detectado para target arm64: instala qemu-user-static o usa ./scripts/build-rootfs-docker.sh"
  USE_QEMU=1
fi

ROOTFS="${STAGE_DIR}/rootfs"
WORK="${STAGE_DIR}/work"

cleanup_mounts() {
  umount -lf "${ROOTFS}/proc" 2>/dev/null || true
  umount -lf "${ROOTFS}/sys" 2>/dev/null || true
  umount -lf "${ROOTFS}/dev/pts" 2>/dev/null || true
  umount -lf "${ROOTFS}/dev" 2>/dev/null || true
}
trap cleanup_mounts EXIT

cleanup_mounts
rm -rf "${STAGE_DIR}"
mkdir -p "${ROOTFS}" "${WORK}" "$(dirname "$OUTPUT_ASSET")"

if [ "$DISTRO" = "ubuntu" ]; then
  [ "$SUITE" = "trixie" ] && SUITE="noble"
  APT_MIRROR="${APT_MIRROR:-http://ports.ubuntu.com/ubuntu-ports}"
else
  APT_MIRROR="${APT_MIRROR:-http://deb.debian.org/debian}"
fi

log "1/7 Ejecutando debootstrap (${DISTRO} ${SUITE}, arch=${TARGET_ARCH})..."
if [ "$DISTRO" = "debian" ] && [ ! -e "/usr/share/debootstrap/scripts/${SUITE}" ] && [ -e "/usr/share/debootstrap/scripts/sid" ]; then
  ln -sf sid "/usr/share/debootstrap/scripts/${SUITE}"
fi
if [ "$USE_QEMU" -eq 1 ]; then
  debootstrap --arch="$TARGET_ARCH" --variant=minbase --foreign "$SUITE" "$ROOTFS" "$APT_MIRROR"
  cp "$(command -v qemu-aarch64-static)" "${ROOTFS}/usr/bin/qemu-aarch64-static"
  chroot "$ROOTFS" /debootstrap/debootstrap --second-stage
else
  debootstrap --arch="$TARGET_ARCH" --variant=minbase "$SUITE" "$ROOTFS" "$APT_MIRROR"
fi

mount --bind /proc "${ROOTFS}/proc"
mount --bind /dev "${ROOTFS}/dev"
cp /etc/resolv.conf "${ROOTFS}/etc/resolv.conf"

log "2/7 Instalando herramientas base de Linux, libatomic1 y Python 3..."
chroot "$ROOTFS" /usr/bin/env -i \
  HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8 \
  /bin/bash -c "
    apt-get update &&
    apt-get install -y --no-install-recommends \
      bash bash-completion ca-certificates curl git jq less libatomic1 nano openssh-client \
      procps python3 python3-venv ripgrep tar unzip xz-utils zsh &&
    git clone --depth 1 https://github.com/ohmyzsh/ohmyzsh.git /root/.oh-my-zsh &&
    git clone --depth 1 https://github.com/zsh-users/zsh-autosuggestions.git /root/.oh-my-zsh/custom/plugins/zsh-autosuggestions &&
    git clone --depth 1 https://github.com/zsh-users/zsh-syntax-highlighting.git /root/.oh-my-zsh/custom/plugins/zsh-syntax-highlighting &&
    git clone --depth 1 https://github.com/zsh-users/zsh-completions.git /root/.oh-my-zsh/custom/plugins/zsh-completions &&
    find /root/.oh-my-zsh -name '.git' -type d -prune -exec rm -rf {} + &&
    sed -i 's|^root:\(.*\):/bin/bash$|root:\1:/bin/zsh|' /etc/passwd &&
    apt-get clean
  "

log "3/7 Instalando Node.js (${NODE_VERSION}) + npm + corepack/pnpm y cabeceras C/C++..."
mkdir -p "${ROOTFS}/usr/local/bin"
cp "${PROJECT_ROOT}/app/src/main/assets/container/update-node.sh" "${ROOTFS}/usr/local/bin/dsh-update-node"
chmod 0755 "${ROOTFS}/usr/local/bin/dsh-update-node"

chroot "$ROOTFS" /usr/bin/env -i \
  HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  COREPACK_ENABLE_DOWNLOAD_PROMPT=0 LANG=C.UTF-8 \
  /bin/bash -c "
    /usr/local/bin/dsh-update-node '${NODE_VERSION}' &&
    npm install -g pnpm corepack
  "

BUNDLED_NODE="$(chroot "$ROOTFS" /usr/local/bin/node --version | tr -d '[:space:]')"
log "Node.js instalado en rootfs: ${BUNDLED_NODE}"

log "4/7 Instalando DeepSeek Harness (modo=${DSH_INSTALL_MODE}, versión=${DSH_VERSION})..."
mkdir -p "${ROOTFS}/opt/dsh"
if [ "$DSH_INSTALL_MODE" = "git" ]; then
  chroot "$ROOTFS" /usr/bin/env -i \
    HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    DEBIAN_FRONTEND=noninteractive COREPACK_ENABLE_DOWNLOAD_PROMPT=0 LANG=C.UTF-8 \
    /bin/bash -c "
      apt-get update &&
      apt-get install -y --no-install-recommends build-essential python3 make g++ &&
      git clone --depth 1 https://github.com/deepseek-ai/deepseek-harness.git /opt/dsh &&
      cd /opt/dsh &&
      pnpm install &&
      pnpm run build &&
      ln -sf /opt/dsh/packages/cmdline/bin/dsh.mjs /usr/local/bin/dsh 2>/dev/null || true &&
      apt-get purge -y --auto-remove build-essential g++ &&
      apt-get clean
    "
else
  chroot "$ROOTFS" /usr/bin/env -i \
    HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    DEBIAN_FRONTEND=noninteractive COREPACK_ENABLE_DOWNLOAD_PROMPT=0 LANG=C.UTF-8 \
    /bin/bash -c "
      apt-get update &&
      apt-get install -y --no-install-recommends build-essential python3 make g++ &&
      cd /opt/dsh &&
      npm init -y >/dev/null &&
      npm install --omit=dev @deepseek-ai/dsh@${DSH_VERSION} &&
      ln -sf /opt/dsh/node_modules/.bin/dsh /usr/local/bin/dsh &&
      npm cache clean --force &&
      apt-get purge -y --auto-remove build-essential g++ &&
      apt-get clean
    "
fi

if [ "$INSTALL_PROOT_SKILLS" = "1" ]; then
  log "5/7 Instalando skills de entorno Android PRoot en /root/.dsh/skills..."
  chroot "$ROOTFS" /usr/bin/env -i \
    HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    /bin/bash -c "
      mkdir -p /root/.dsh/skills &&
      if git clone --depth 1 https://github.com/duke5am/skills-for-proot-distro-ubuntu.git /tmp/proot-skills; then
        for skill in package-installation python-usage shell-usage writing-skills; do
          [ -d \"/tmp/proot-skills/\$skill\" ] && cp -r \"/tmp/proot-skills/\$skill\" /root/.dsh/skills/
        done
        rm -rf /tmp/proot-skills
      fi
    " || true
fi

log "6/7 Instalando scripts de compatibilidad Android/PRoot y limpiando cachés..."
cp "${PROJECT_ROOT}/app/src/main/assets/container/dsh-android-preload.cjs" "${ROOTFS}/etc/dsh-android-preload.cjs"
mkdir -p "${ROOTFS}/etc/profile.d" "${ROOTFS}/etc/zsh" "${ROOTFS}/usr/local/bin" "${ROOTFS}/root/workspace" "${ROOTFS}/mnt/saf" "${ROOTFS}/.l2s"
cp "${PROJECT_ROOT}/app/src/main/assets/container/profile-dsh.sh" "${ROOTFS}/etc/profile.d/dsh-android.sh"
cp "${PROJECT_ROOT}/app/src/main/assets/container/zshrc-dsh.zsh" "${ROOTFS}/etc/zsh/dsh-zshrc.zsh"
printf '# dsh-android-ohmyzsh\n[ -f /etc/zsh/dsh-zshrc.zsh ] && source /etc/zsh/dsh-zshrc.zsh\n' > "${ROOTFS}/root/.zshrc"
cp "${PROJECT_ROOT}/app/src/main/assets/container/verify-shared-env.sh" "${ROOTFS}/usr/local/bin/dsh-doctor"
chmod 0755 "${ROOTFS}/usr/local/bin/dsh-doctor" "${ROOTFS}/usr/local/bin/dsh-update-node"
ln -sfn /mnt/saf "${ROOTFS}/root/saf"

cleanup_mounts
rm -f "${ROOTFS}/usr/bin/qemu-aarch64-static"

# Eliminar documentación, locales innecesarios, listas APT y nodos de dispositivos especiales (/dev/*)
# para reducir el tamaño del APK y permitir extracción sin privilegios en Android.
rm -rf \
  "${ROOTFS}/usr/share/doc" \
  "${ROOTFS}/usr/share/man" \
  "${ROOTFS}/usr/share/info" \
  "${ROOTFS}/usr/share/locale" \
  "${ROOTFS}/var/lib/apt/lists/"* \
  "${ROOTFS}/var/cache/apt/archives/"* \
  "${ROOTFS}/tmp/"* \
  "${ROOTFS}/dev"
mkdir -p "${ROOTFS}/dev" "${ROOTFS}/proc" "${ROOTFS}/sys" "${ROOTFS}/tmp"

log "7/7 Empaquetando rootfs comprimido en ${OUTPUT_ASSET}..."
XZ_OPT="-T0 -6" tar -cJf "$OUTPUT_ASSET" -C "$ROOTFS" .
sha256sum "$OUTPUT_ASSET" > "${OUTPUT_ASSET}.sha256"

STAMP="${DISTRO}-${SUITE}-node-${BUNDLED_NODE}-dsh-$(date -u +%Y%m%d%H%M)"
echo "$STAMP" > "$VERSION_FILE"

rm -rf "${STAGE_DIR}"
log "Rootfs generado con éxito (Node ${BUNDLED_NODE}):"
ls -lh "$OUTPUT_ASSET"
log "Sello de versión escrito en ${VERSION_FILE}: ${STAMP}"
