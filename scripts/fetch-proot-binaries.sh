#!/usr/bin/env bash
# ==============================================================================
# fetch-proot-binaries.sh
#
# Descarga los binarios nativos de PRoot para Android (proot, loader, libtalloc,
# libandroid-shmem) desde el repositorio oficial de paquetes de Termux (con espejo
# de respaldo), verifica sus sumas SHA-256 y los coloca en:
#   app/src/main/jniLibs/<abi>/
#     ├── libproot.so
#     ├── libproot_loader.so
#     ├── libproot_loader32.so (si aplica)
#     ├── libtalloc.so
#     └── libandroid-shmem.so
#
# ¿Por qué jniLibs/lib*.so?
# En Android 10–15+ (targetSdk >= 29), SELinux W^X impide ejecutar binarios ELF
# extraídos en filesDir (app_data_file), pero SÍ permite ejecutar binarios
# extraídos por PackageManager en nativeLibraryDir (apk_data_file).
#
# Uso:
#   ./scripts/fetch-proot-binaries.sh
#   ./scripts/fetch-proot-binaries.sh --with-x86_64
# ==============================================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WITH_X86_64=0

for arg in "$@"; do
  case "$arg" in
    --with-x86_64) WITH_X86_64=1 ;;
    -h|--help)
      echo "Uso: $0 [--with-x86_64]"
      exit 0
      ;;
    *)
      echo "Argumento desconocido: $arg" >&2
      exit 1
      ;;
  esac
done

MIRRORS=(
  "https://packages.termux.dev/apt/termux-main"
  "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"
)

log() { printf '\033[1;34m[fetch-proot]\033[0m %s\n' "$*"; }
err() { printf '\033[1;31m[fetch-proot][ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

command -v curl >/dev/null 2>&1 || err "Se requiere curl"
command -v python3 >/dev/null 2>&1 || err "Se requiere python3"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

# Extractor portable de paquetes .deb (usa dpkg-deb si está disponible para soportar .xz/.zst/.gz;
# de lo contrario recurre a Python + tar para compatibilidad con macOS)
extract_deb_data() {
  local deb_file="$1"
  local out_dir="$2"
  mkdir -p "$out_dir"
  if command -v dpkg-deb >/dev/null 2>&1; then
    dpkg-deb -x "$deb_file" "$out_dir"
    return 0
  fi
  python3 - "$deb_file" "$out_dir" <<'PYEOF'
import os, subprocess, sys, tarfile

deb_path, out_dir = sys.argv[1], sys.argv[2]
with open(deb_path, "rb") as f:
    magic = f.read(8)
    if magic != b"!<arch>\n":
        raise RuntimeError(f"Archivo .deb inválido: {deb_path}")
    while True:
        header = f.read(60)
        if len(header) < 60:
            break
        name = header[0:16].decode("ascii", errors="ignore").strip().rstrip("/")
        size = int(header[48:58].decode("ascii").strip())
        payload = f.read(size)
        if size % 2 == 1:
            f.read(1)
        if name.startswith("data.tar"):
            data_tar = os.path.join(out_dir, name)
            with open(data_tar, "wb") as out:
                out.write(payload)
            try:
                with tarfile.open(data_tar) as tf:
                    tf.extractall(out_dir)
            except tarfile.ReadError:
                subprocess.check_call(["tar", "-xf", data_tar, "-C", out_dir])
            os.remove(data_tar)
            sys.exit(0)
raise RuntimeError(f"No se encontró miembro data.tar.* en {deb_path}")
PYEOF
}

fetch_for_arch() {
  local termux_arch="$1"   # aarch64 | x86_64
  local android_abi="$2"   # arm64-v8a | x86_64
  local dest_dir="${PROJECT_ROOT}/app/src/main/jniLibs/${android_abi}"
  local arch_work="${WORK_DIR}/${termux_arch}"
  mkdir -p "$dest_dir" "$arch_work"

  local selected_mirror=""
  for mirror in "${MIRRORS[@]}"; do
    local pkg_url="${mirror}/dists/stable/main/binary-${termux_arch}/Packages"
    log "Consultando índice APT (${termux_arch}): ${pkg_url}"
    if curl -fsSL --retry 2 --connect-timeout 15 --max-time 90 -o "${arch_work}/Packages" "$pkg_url"; then
      selected_mirror="$mirror"
      break
    fi
  done
  [ -n "$selected_mirror" ] || err "No se pudo descargar el índice APT de Termux desde ningún espejo."

  python3 - "${arch_work}/Packages" > "${arch_work}/resolved.tsv" <<'PYEOF'
import sys

pkgs_text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
wanted = {"proot", "libtalloc", "libandroid-shmem"}
found = {}

for stanza in pkgs_text.split("\n\n"):
    name = filename = sha256 = None
    for line in stanza.splitlines():
        if line.startswith("Package: "):
            name = line[9:].strip()
        elif line.startswith("Filename: "):
            filename = line[10:].strip()
        elif line.startswith("SHA256: "):
            sha256 = line[8:].strip()
    if name in wanted and filename and sha256:
        found[name] = (filename, sha256)

missing = wanted - set(found.keys())
if missing:
    raise SystemExit(f"Paquetes no encontrados en índice: {sorted(missing)}")

for pkg in ("proot", "libtalloc", "libandroid-shmem"):
    fn, digest = found[pkg]
    print(f"{pkg}\t{fn}\t{digest}")
PYEOF

  while IFS=$'\t' read -r pkg rel_url expected_sha; do
    local deb_file="${arch_work}/${pkg}.deb"
    log "Descargando ${pkg} (${android_abi})..."
    curl -fsSL --retry 3 --connect-timeout 20 -o "$deb_file" "${selected_mirror}/${rel_url}"

    local actual_sha
    if command -v sha256sum >/dev/null 2>&1; then
      actual_sha="$(sha256sum "$deb_file" | awk '{print $1}')"
    else
      actual_sha="$(shasum -a 256 "$deb_file" | awk '{print $1}')"
    fi
    [ "$actual_sha" = "$expected_sha" ] || err "SHA-256 inválido para ${pkg}: esperado=${expected_sha} obtenido=${actual_sha}"

    extract_deb_data "$deb_file" "${arch_work}/extracted-${pkg}"
  done < "${arch_work}/resolved.tsv"

  local p_root="${arch_work}/extracted-proot/data/data/com.termux/files/usr"
  local t_root="${arch_work}/extracted-libtalloc/data/data/com.termux/files/usr"
  local s_root="${arch_work}/extracted-libandroid-shmem/data/data/com.termux/files/usr"

  [ -f "${p_root}/bin/proot" ] || err "No se encontró usr/bin/proot"
  [ -f "${p_root}/libexec/proot/loader" ] || err "No se encontró usr/libexec/proot/loader"

  cp "${p_root}/bin/proot" "${dest_dir}/libproot.so"
  cp "${p_root}/libexec/proot/loader" "${dest_dir}/libproot_loader.so"
  if [ -f "${p_root}/libexec/proot/loader32" ]; then
    cp "${p_root}/libexec/proot/loader32" "${dest_dir}/libproot_loader32.so"
  fi

  local talloc_so
  talloc_so="$(find "${t_root}/lib" -maxdepth 1 -name 'libtalloc.so.2*' -type f | head -n 1)"
  [ -n "$talloc_so" ] || err "No se encontró libtalloc.so.2 en paquete libtalloc"
  cp "$talloc_so" "${dest_dir}/libtalloc.so"

  local shmem_so
  shmem_so="$(find "${s_root}/lib" -maxdepth 1 -name 'libandroid-shmem.so*' -type f | head -n 1)"
  [ -n "$shmem_so" ] || err "No se encontró libandroid-shmem.so en paquete libandroid-shmem"
  cp "$shmem_so" "${dest_dir}/libandroid-shmem.so"

  chmod 0755 "${dest_dir}"/*.so
  log "Binarios instalados correctamente en ${dest_dir}:"
  ls -lh "${dest_dir}"/*.so
}

fetch_for_arch "aarch64" "arm64-v8a"
if [ "$WITH_X86_64" -eq 1 ]; then
  fetch_for_arch "x86_64" "x86_64"
fi

log "Completado exitosamente."
