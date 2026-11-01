# Análisis Técnico, Plan por Fases y Decisiones de Arquitectura

## 1. Análisis de `deepseek-harness` y Proyectos de Referencia

### 1.1 `deepseek-ai/deepseek-harness` (`dsh`)
- **Arquitectura microkernel basada en Cordis**: `deepseek-harness` está construido en TypeScript sobre `@deepseek-ai/cordis` bajo el paradigma *"everything is a plugin"*. El adaptador LLM, registro de herramientas (`dsh-tool-bash`, `dsh-tool-fs`, `dsh-tool-str-replace-editor`), sesiones append-only, aprobaciones y la Web UI (`@deepseek-ai/dsh-web-app`) se ensamblan como árbol de plugins.
- **Arranque del motor Web**: se expone mediante `node --expose-internals <ruta>/node_modules/@deepseek-ai/dsh/lib/bin.js web --host 127.0.0.1 --port 3080 --no-open`. El repositorio upstream declara `"engines": { "node": ">=24.9.0" }`, `"packageManager": "pnpm@11.x"` y usa `import.meta.main` en `@deepseek-ai/dsh/lib/bin.js`, por lo que empaquetamos **la versión estable más actual de Node.js (`NODE_VERSION=latest`, actualmente Node.js v26.x+)** con glibc sobre `aarch64`, junto con `corepack` (`pnpm` y `yarn` preconfigurados), `libatomic1` y las cabeceras C/C++ (`include/node`) para compilar futuros complementos nativos (`node-gyp`).
- **Autenticación de sesión del navegador (`dsh` >= 0.1.5 / 0.2.x)**: al arrancar, `dsh web` protege el puerto `3080` con una cookie firmada mediante HMAC-SHA256 (`dsh-auth-<sha256(authority)>`) cuya clave de 32 bytes persiste en `$DSH_HOME/.credentials.yaml` bajo `client-connection/browser-session`, e imprime además una URL de intercambio `http://127.0.0.1:3080/?token=...`.
- ** Particularidades bajo kernels Android + PRoot**:
  1. **Sandbox interno (`bwrap`/`Landlock`)**: los kernels de Android no habilitan *unprivileged user namespaces* ni Landlock para apps normales. Si `dsh` arranca en su modo por defecto, su sandbox interno falla con `SANDBOX_UNAVAILABLE`. Solución: exportar `DSH_PERMISSION_MODE=danger-full-access` dentro del contenedor para delegar el aislamiento al propio contenedor PRoot.
  2. **`os.networkInterfaces()` en Android 11+ (SELinux)**: Android bloquea `bind()` sobre sockets `NETLINK_ROUTE`, haciendo que `os.networkInterfaces()` en Node.js lance `EACCES (errno 13)`. Solución: precargar `/etc/dsh-android-preload.cjs` mediante `NODE_OPTIONS=--require=/etc/dsh-android-preload.cjs` e invocar `module.syncBuiltinESMExports()` para cubrir tanto `require('node:os')` como importaciones nombradas ESM modernas (`import { networkInterfaces } from 'node:os'`) en Node.js 26+.

### 1.2 Proyectos de Referencia Analizados
- **`deepcode-lab/deepseek-harness-mobile`**:
  - Demuestra la superioridad del modelo de **contenedor único embebido** (`rootfs.tar.xz` con Debian + Node.js glibc + `dsh`) frente a wrappers híbridos Bionic/glibc: al correr tanto el motor Node.js como sus subprocesos `bash` dentro del mismo PRoot, desaparecen las incompatibilidades de `LD_PRELOAD` y ABI. En `DeepseekHAndroid` evolucionamos esta base a **Debian 13 (`trixie`, `glibc 2.41`) + última versión estable de Node.js (`NODE_VERSION=latest`, v26.x+)** para garantizar compatibilidad total con herramientas modernas que instale el usuario en el futuro.
  - Aporta patrones probados en producción: eliminación del bit de escritura (`W^X`) al extraer ejecutables/`.so`/`.node`, fallback con `/system/bin/linker64`, servicio en primer plano `dataSync` con watchdog y ventana de enfriamiento de arranque en frío para evitar `EADDRINUSE`, y descargas locales vía `HttpURLConnection` + `MediaStore` para evitar errores `403` por `Origin: null`.
- **`duke5am/deepseek-harness-on-android`**:
  - Documenta los requisitos reales del entorno Ubuntu/Debian sobre PRoot en ARM64: estructura de `$DSH_HOME` (`/root/.dsh`), *skills* de entorno para evitar bloqueos de tuberías en PRoot y manejo de PEP 668 en Python, e incompatibilidad de escrituras atómicas (`rename`) sobre sistemas FUSE `/sdcard`.

---

## 2. Plan de Implementación por Fases

1. **Fase 1 — Infraestructura Gradle Multi-Módulo y Motor Nativo PTY**:
   - Configurar proyecto Gradle Kotlin DSL (`:app`, `:terminal-emulator`, `:terminal-view`) con NDK/CMake (`libtermux.so` compilado con alineación de página de 16 KB `-Wl,-z,max-page-size=16384` para Android 15+).
   - Configurar `jniLibs.useLegacyPackaging = true` y `extractNativeLibs="true"` para ejecutar `libproot.so` y `libproot_loader.so` desde `nativeLibraryDir` (`apk_data_file`) cumpliendo SELinux W^X en Android 10–15+ sin root.
2. **Fase 2 — Extracción Atómica del Rootfs y Contenedor PRoot Compartido**:
   - Implementar `RootfsExtractor` con barra de progreso real sobre el flujo comprimido, protección `TarSlip`, preservación de symlinks/hardlinks, permisos W^X y respaldo automático de `/root/.dsh` en actualizaciones.
   - Implementar `ProotEnvironment`, `ProotCommandBuilder` y `ContainerSmokeTester` asegurando que `dsh web` y `bash --login` compartan idéntico rootfs, montajes (`/tmp`, `/dev/shm`, `/root/workspace`, `/mnt/saf`) y variables de entorno.
3. **Fase 3 — Servicio en Primer Plano, Watchdog y Autenticación de `dsh web`**:
   - Implementar `DshForegroundService` con notificación persistente (`Abrir`, `Reiniciar`, `Detener`), `WakeLock` parcial y Watchdog cada 5s con ventana de gracia de arranque frío (`75s`) y protección contra bucles infinitos de fallo.
   - Implementar `DshAuthCookieMinter` (firma HMAC-SHA256 desde `.credentials.yaml` + captura de URL con `?token=` de respaldo) y `DshHealthProbe`.
4. **Fase 4 — Interfaz Principal (WebView + Terminal Integrada + Puente SAF)**:
   - Implementar `DshWebViewHolder` con aislamiento de origen, polyfills, CSS móvil, selector de archivos, descargas `MediaStore` y puente JS `window.androidBridge`.
   - Implementar `TerminalPanel` + `TerminalSessionRegistry` + `ExtraKeysBar` (con soporte tanto de pestaña completa como de **pantalla dividida** junto a la WebView).
   - Implementar `SafBridgeManager` con doble estrategia: enlace simbólico POSIX directo (`DIRECT_SYMLINK`) cuando hay acceso directo al almacenamiento y espejo bidireccional `DocumentFile` (`DOCUMENT_MIRROR`) cuando opera bajo *Scoped Storage* estricto o unidades USB/SD.
5. **Fase 5 — Scripts de Construcción de Rootfs, CI/CD, Pruebas Unitarias y Documentación**:
   - Scripts `fetch-proot-binaries.sh`, `build-rootfs.sh`, `Dockerfile.rootfs`, `build-rootfs-docker.sh`, `update-dsh.sh`, `create-stub-rootfs.sh` y `verify-shared-container.sh`.
   - Suite de pruebas unitarias JUnit (`ProotCommandBuilderTest`, `RootfsExtractorTest`, `DshAuthCookieMinterTest`, `SafPathResolverTest`) y documentación completa en `README.md`.

---

## 3. Decisiones Técnicas Relevantes ante Ambigüedades

| Aspecto | Decisión adoptada | Justificación técnica |
|---|---|---|
| **Distribución base del rootfs y versión de Node.js** | **Debian 13 (Trixie, `glibc 2.41`) `minbase`** + **última versión estable de Node.js (`NODE_VERSION=latest`, actualmente Node.js v26.x+)** resuelta automáticamente desde `nodejs.org/dist/index.json` con verificación SHA-256, más `corepack` (`pnpm`/`yarn`), `libatomic1`, cabeceras `include/node` y actualizador en vivo `dsh-update-node`. Soporta también Ubuntu 24.04+ (`DISTRO=ubuntu SUITE=noble`). | `deepseek-harness` upstream exige `node >= 24.9.0` e `import.meta.main`, y los programas/CLIs modernos que el usuario instale en el futuro suelen requerir el último runtime de Node.js (ESM nativo, TypeScript stripping, WebStreams/Fetch actuales) así como `glibc >= 2.38` para binarios precompilados en runners modernos. |
| **Ubicación de binarios PRoot** | `app/src/main/jniLibs/arm64-v8a/lib*.so` (`nativeLibraryDir`) + symlink `libtalloc.so.2` en `filesDir/proot-libs`. | En Android 10+ (`targetSdk >= 29`), SELinux bloquea `execve()` desde `filesDir` (`app_data_file`), mientras que `nativeLibraryDir` (`apk_data_file`) tiene permiso de ejecución nativo en Android 8–16 sin modificar bytes ELF. |
| **Exposición de carpetas SAF en PRoot** | Montar el directorio padre `<filesDir>/saf-mounts` en `/mnt/saf` al arrancar PRoot y poblar cada carpeta elegida como `/mnt/saf/<alias>` (modo symlink directo o modo espejo `DocumentFile`). | Si se montara cada carpeta SAF con un `-b` estático de PRoot, elegir una carpeta nueva obligaría a reiniciar el motor `dsh`. Al montar el directorio padre `/mnt/saf`, las nuevas carpetas aparecen instantáneamente en el motor y en las terminales ya abiertas. |
| **Integración de librería PTY** | Módulos locales `:terminal-emulator` y `:terminal-view` basados en Termux `v0.118.3` (licencia Apache-2.0) compilados con CMake. | Elimina dependencias externas frágiles de JitPack, incluye alineación de memoria de 16 KB para Android 15+ y ofrece emulación xterm-256color completa con redimensionamiento `TIOCSWINSZ`. |
