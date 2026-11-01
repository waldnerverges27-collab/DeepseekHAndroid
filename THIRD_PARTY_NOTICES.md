# Third-Party Notices

This repository integrates or packages the following open-source components:

1. **DeepSeek Harness (`@deepseek-ai/dsh`)**
   - Upstream: https://github.com/deepseek-ai/deepseek-harness
   - License: MIT License
   - Packaged inside `rootfs.tar.xz` by `scripts/build-rootfs.sh`.

2. **Termux `terminal-emulator` and `terminal-view` modules (`v0.118.3`)**
   - Upstream: https://github.com/termux/termux-app (`terminal-emulator/` and `terminal-view/` libraries, derived from Jack Palevich's Android Terminal Emulator)
   - License: Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0)
   - Included in `:terminal-emulator` and `:terminal-view` for POSIX PTY (`/dev/ptmx`) management and VT100/xterm-256color rendering.

3. **PRoot, talloc, and libandroid-shmem (fetched at build time by `scripts/fetch-proot-binaries.sh`)**
   - Upstream: https://github.com/termux/proot, https://talloc.samba.org/, https://github.com/termux/libandroid-shmem
   - Licenses: GPL-2.0 (PRoot, executed as a standalone user-space process via `execve`), LGPL-3.0+ (libtalloc), BSD-2-Clause (libandroid-shmem).

4. **Debian GNU/Linux 13 (Trixie) / Ubuntu Base Rootfs & Latest Stable Node.js (`v26.x+`)**
   - Upstream: https://www.debian.org/ / https://nodejs.org/
   - Licenses: Respective DFSG-compliant open-source package licenses and MIT License (Node.js).
