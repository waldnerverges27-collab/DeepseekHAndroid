# /etc/profile.d/dsh-android.sh
# Entorno compartido entre el motor `dsh` y las sesiones interactivas `bash` en la terminal nativa.

export HOME="${HOME:-/root}"
export USER="${USER:-root}"
export LOGNAME="${LOGNAME:-root}"
export TERM="${TERM:-xterm-256color}"
export LANG="${LANG:-C.UTF-8}"
export LC_ALL="${LC_ALL:-C.UTF-8}"
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

export DSH_HOME="${DSH_HOME:-/root/.dsh}"
export DSH_WORKSPACE="${DSH_WORKSPACE:-/root/workspace}"
export DSH_SAF_ROOT="${DSH_SAF_ROOT:-/mnt/saf}"
export DSH_PERMISSION_MODE="${DSH_PERMISSION_MODE:-danger-full-access}"
export DEBIAN_FRONTEND="noninteractive"
export COREPACK_ENABLE_DOWNLOAD_PROMPT="0"

if [ -f /etc/dsh-android-preload.cjs ]; then
  case ":${NODE_OPTIONS:-}:" in
    *dsh-android-preload.cjs*) ;;
    *) export NODE_OPTIONS="${NODE_OPTIONS:+$NODE_OPTIONS }--require=/etc/dsh-android-preload.cjs" ;;
  esac
fi

alias ll='ls -lah --color=auto'
alias cdw='cd /root/workspace'
alias cds='cd /mnt/saf'
