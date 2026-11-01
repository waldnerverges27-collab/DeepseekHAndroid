# /etc/zsh/dsh-zshrc.zsh
# Configuración interactiva de Zsh + Oh My Zsh + colores + autocompletado inteligente
# para el contenedor PRoot compartido de DeepSeek Harness Android.

# 1. Cargar variables y rutas compartidas con el motor `dsh` y `bash`
[ -f /etc/profile.d/dsh-android.sh ] && . /etc/profile.d/dsh-android.sh

# 2. Historial compartido entre pestañas y opciones ergonómicas de Zsh
export HISTFILE="${HOME:-/root}/.zsh_history"
export HISTSIZE=10000
export SAVEHIST=10000
setopt APPEND_HISTORY SHARE_HISTORY HIST_IGNORE_ALL_DUPS HIST_REDUCE_BLANKS
setopt AUTO_CD INTERACTIVE_COMMENTS COMPLETE_IN_WORD ALWAYS_TO_END

# 3. Colores LS_COLORS para `ls` y para el menú interactivo de autocompletado (TAB)
if command -v dircolors >/dev/null 2>&1; then
  eval "$(dircolors -b)"
fi
export CLICOLOR=1

# 4. Configuración de Oh My Zsh + plugins (100% offline y optimizado para PRoot)
export ZSH="/root/.oh-my-zsh"
export ZSH_DISABLE_COMPFIX="true"
export DISABLE_UNTRACKED_FILES_DIRTY="true"

# Estrategia de autosugerencias en gris mientras escribes (flecha → o FIN para aceptar):
# usa primero el historial y, si aún no hay coincidencias, el motor de completado de Zsh.
export ZSH_AUTOSUGGEST_HIGHLIGHT_STYLE="fg=244"
export ZSH_AUTOSUGGEST_STRATEGY=(history completion)
export ZSH_AUTOSUGGEST_BUFFER_MAX_SIZE=40
export ZSH_HIGHLIGHT_HIGHLIGHTERS=(main brackets)

if [ -s "${ZSH}/oh-my-zsh.sh" ]; then
  zstyle ':omz:update' mode disabled
  ZSH_THEME="robbyrussell"

  if [ -d "${ZSH}/custom/plugins/zsh-completions/src" ]; then
    fpath=("${ZSH}/custom/plugins/zsh-completions/src" $fpath)
  fi

  plugins=(
    git
    npm
    node
    python
    colored-man-pages
    extract
    z
    zsh-autosuggestions
    zsh-syntax-highlighting
  )

  source "${ZSH}/oh-my-zsh.sh"
else
  # Respaldo si Oh My Zsh no estuviera presente: autocompletado y prompt con colores nativos
  autoload -Uz compinit colors
  colors
  compinit -i -d "${HOME:-/root}/.zcompdump"
fi

# 5. Menú visual de autocompletado con colores, navegación con flechas e insensible a mayúsculas
zstyle ':completion:*' menu select
zstyle ':completion:*' matcher-list 'm:{a-zA-Z}={A-Za-z}' 'r:|[._-]=* r:|=*' 'l:|=* r:|=*'
if [ -n "${LS_COLORS:-}" ]; then
  zstyle ':completion:*' list-colors "${(s.:.)LS_COLORS}"
fi
zstyle ':completion:*:descriptions' format '%F{cyan}-- %d --%f'
zstyle ':completion:*:warnings' format '%F{red}-- sin coincidencias --%f'
zstyle ':completion:*' group-name ''

# 6. Ajustar estilos de resaltado de sintaxis en tiempo real (zsh-syntax-highlighting)
if (( ${+ZSH_HIGHLIGHT_STYLES} )); then
  ZSH_HIGHLIGHT_STYLES[command]='fg=green,bold'
  ZSH_HIGHLIGHT_STYLES[alias]='fg=green,bold'
  ZSH_HIGHLIGHT_STYLES[builtin]='fg=cyan,bold'
  ZSH_HIGHLIGHT_STYLES[function]='fg=green,bold'
  ZSH_HIGHLIGHT_STYLES[unknown-token]='fg=red,bold'
  ZSH_HIGHLIGHT_STYLES[single-hyphen-option]='fg=cyan'
  ZSH_HIGHLIGHT_STYLES[double-hyphen-option]='fg=cyan'
  ZSH_HIGHLIGHT_STYLES[single-quoted-argument]='fg=yellow'
  ZSH_HIGHLIGHT_STYLES[double-quoted-argument]='fg=yellow'
  ZSH_HIGHLIGHT_STYLES[path]='underline'
fi

# 7. Prompt colorido estilo Oh My Zsh adaptado a pantalla móvil
if typeset -f git_prompt_info >/dev/null 2>&1; then
  PROMPT='%(?:%{$fg_bold[green]%}➜ :%{$fg_bold[red]%}➜ )%{$fg[blue]%}[dsh]%{$reset_color%} %{$fg_bold[cyan]%}%c%{$reset_color%} $(git_prompt_info)'
else
  PROMPT='%(?:%F{green}➜ :%F{red}➜ )%F{blue}[dsh]%f %B%F{cyan}%1~%f%b %# '
fi

# 8. Atajos de teclado compatibles con la barra móvil de teclas extra (Inicio, Fin, Supr, flechas)
bindkey '^[[H' beginning-of-line 2>/dev/null || true
bindkey '^[OH' beginning-of-line 2>/dev/null || true
bindkey '^[[F' end-of-line 2>/dev/null || true
bindkey '^[OF' end-of-line 2>/dev/null || true
bindkey '^[[3~' delete-char 2>/dev/null || true
bindkey '^[[1;5C' forward-word 2>/dev/null || true
bindkey '^[[1;5D' backward-word 2>/dev/null || true

# 9. Alias con colores activados por defecto
alias ls='ls --color=auto'
alias ll='ls -lah --color=auto --group-directories-first'
alias la='ls -A --color=auto'
alias l='ls -CF --color=auto'
alias grep='grep --color=auto'
alias egrep='grep -E --color=auto'
alias fgrep='grep -F --color=auto'
alias diff='diff --color=auto'
alias ip='ip -color=auto'
alias cdw='cd /root/workspace'
alias cds='cd /mnt/saf'
