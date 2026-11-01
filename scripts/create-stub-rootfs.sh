#!/usr/bin/env bash
# ==============================================================================
# create-stub-rootfs.sh
#
# Genera un `rootfs.tar.xz` mínimo (~5 KB) ÚNICAMENTE para compilaciones rápidas
# de verificación de interfaz / CI (`assembleDebug`) cuando aún no se ha generado
# el rootfs completo con `./scripts/build-rootfs-docker.sh`.
# ==============================================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-${PROJECT_ROOT}/app/src/main/assets/rootfs.tar.xz}"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

mkdir -p \
  "${TMP_DIR}/bin" \
  "${TMP_DIR}/etc/profile.d" \
  "${TMP_DIR}/usr/bin" \
  "${TMP_DIR}/usr/local/bin" \
  "${TMP_DIR}/opt/dsh/node_modules/@deepseek-ai/dsh/lib" \
  "${TMP_DIR}/root/workspace" \
  "${TMP_DIR}/mnt/saf" \
  "${TMP_DIR}/tmp" \
  "${TMP_DIR}/dev" \
  "${TMP_DIR}/proc" \
  "${TMP_DIR}/sys"

cat > "${TMP_DIR}/usr/local/bin/node" <<'EOF'
#!/bin/sh
echo "v26.13.0-stub"
EOF
chmod 0755 "${TMP_DIR}/usr/local/bin/node"

cat > "${TMP_DIR}/opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js" <<'EOF'
console.log("Stub rootfs: reemplaza con ./scripts/build-rootfs-docker.sh para producción");
EOF

cp "${PROJECT_ROOT}/app/src/main/assets/container/dsh-android-preload.cjs" "${TMP_DIR}/etc/dsh-android-preload.cjs"
cp "${PROJECT_ROOT}/app/src/main/assets/container/profile-dsh.sh" "${TMP_DIR}/etc/profile.d/dsh-android.sh"
cp "${PROJECT_ROOT}/app/src/main/assets/container/verify-shared-env.sh" "${TMP_DIR}/usr/local/bin/dsh-doctor"
cp "${PROJECT_ROOT}/app/src/main/assets/container/update-node.sh" "${TMP_DIR}/usr/local/bin/dsh-update-node"
chmod 0755 "${TMP_DIR}/usr/local/bin/dsh-doctor" "${TMP_DIR}/usr/local/bin/dsh-update-node"
ln -s /mnt/saf "${TMP_DIR}/root/saf"

mkdir -p "$(dirname "$OUT")"
XZ_OPT="-6" tar -cJf "$OUT" -C "$TMP_DIR" .
echo "[create-stub-rootfs] Stub generado en ${OUT} ($(wc -c < "$OUT") bytes)."
