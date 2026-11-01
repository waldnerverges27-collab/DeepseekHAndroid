# DeepSeek Harness para Android (`DeepseekHAndroid`)

Aplicación nativa de Android (**Kotlin + Jetpack Compose + JNI PTY + PRoot**) totalmente autónoma que empaqueta el agente de IA [**DeepSeek Harness (`dsh`)**](https://github.com/deepseek-ai/deepseek-harness) junto con un entorno Linux completo (**Debian 13 Trixie `glibc 2.41` + la versión estable más actual de Node.js `v26.x+`**, con `npm`, `npx`, `pnpm`, `yarn` y cabeceras C/C++ listas para instalar futuras herramientas) y una terminal nativa integrada.

Funciona **sin acceso root**, **sin depender de Termux externo** y, una vez extraído el `rootfs.tar.xz` embebido en el primer arranque, el entorno completo opera **100 % offline** (salvo las peticiones del agente al endpoint LLM local o remoto que configures).

---

## Características Principales

- **APK único y autónomo**: embebe `rootfs.tar.xz` en `assets/` y lo extrae atómicamente en el almacenamiento privado de la app en el primer inicio mostrando progreso en tiempo real y respaldando `/root/.dsh` ante futuras actualizaciones.
- **Contenedor PRoot único y compartido**: tanto el motor web `dsh` (`127.0.0.1:3080`) como todas las sesiones `bash` de la terminal integrada se ejecutan sobre **exactamente el mismo rootfs, los mismos montajes (`/tmp`, `/dev/shm`, `/root/workspace`, `/mnt/saf`) y los mismos binarios glibc**.
- **Interfaz WebView optimizada y autenticada**:
  - Carga automática de `http://127.0.0.1:3080` cuando el servidor está disponible.
  - Firma automática de cookies HMAC-SHA256 (`dsh-auth-*`) desde `/root/.dsh/.credentials.yaml` + captura de URL `?token=...` de respaldo (compatible con `dsh` 0.1.x y 0.2.x sin errores `401`).
  - Subida de archivos nativa, descargas directas a `Downloads` mediante `MediaStore` (evitando bloqueos `403 Origin: null`), aislamiento estricto de navegación externa y puente JS `window.androidBridge`.
- **Terminal Linux nativa integrada (PTY real)**:
  - Basada en los módulos Apache-2.0 `terminal-emulator` y `terminal-view` (`libtermux.so` sobre `/dev/ptmx` con redimensionamiento `TIOCSWINSZ` y soporte de páginas de 16 KB para Android 15+).
  - Disponible como **pestaña completa** o en **pantalla dividida** debajo de la WebView para observar e interactuar con el contenedor mientras trabaja el agente.
  - **Barra de teclas extra móvil de dos filas**: `ESC`, `TAB`, `CTRL` (toque simple o pulsación larga para bloquear), `ALT`, flechas (`←`, `↓`, `↑`, `→`), `INICIO` (`Home`), `FIN` (`End`), `REPÁG`, `AVPÁG`, señales rápidas (`^C`, `^D`, `^Z`, `^L`) y símbolos habituales (`-`, `/`, `|`, `~`, `_`, `$`).
  - Soporte completo para selección táctil de texto, copiar/pegar y zoom de fuente (`A-` / `A+` o pellizco en pantalla).
- **Puente SAF (Storage Access Framework)**:
  - Selector nativo de directorios externos expuesto dentro del contenedor en `/mnt/saf/<alias>` (y atajo `/root/saf/<alias>`).
  - **Doble estrategia automática**: enlace simbólico POSIX directo en tiempo real cuando existe acceso directo al almacenamiento, o espejo bidireccional `DocumentFile` cuando se opera bajo *Scoped Storage* estricto, tarjetas SD restringidas o unidades USB OTG.
- **Servicio en primer plano (`ForegroundService`) + Watchdog**:
  - Notificación persistente con acciones **Abrir**, **Reiniciar** y **Detener**.
  - Watchdog cada 5 segundos que supervisa el proceso y el puerto `3080`, respetando la ventana de arranque en frío de Node.js/Cordis y reiniciando automáticamente el motor ante caídas inesperadas.

---

## Estructura del Proyecto

```text
DeepseekHAndroid/
├── app/                          # Aplicación Android principal (Kotlin + Jetpack Compose)
│   └── src/
│       ├── main/
│       │   ├── assets/           # Scripts de integración PRoot/Node, polyfills WebView y rootfs.tar.xz
│       │   ├── jniLibs/          # Binarios PRoot ARM64 (libproot.so, libproot_loader.so, libtalloc.so...)
│       │   └── java/io/deepseek/harness/android/
│       │       ├── container/    # RootfsExtractor, ProotEnvironment, ProotCommandBuilder, SmokeTester
│       │       ├── core/         # AppPaths, AppLogger, AppPreferences
│       │       ├── engine/       # DshEngineController, DshForegroundService, Watchdog, CookieMinter
│       │       ├── saf/          # SafBridgeManager, SafPathResolver, SafMountEntry
│       │       ├── terminal/     # TerminalSessionRegistry, DshTerminalClients, ExtraKeysController
│       │       ├── ui/           # Pantallas Compose (Boot, MainShell, TerminalPanel, SAF, Diagnóstico)
│       │       └── webview/      # DshWebViewHolder, DshWebViewBridge, DshDownloadHandler
│       └── test/                 # Tests unitarios JUnit (PRoot, Extractor, Cookies HMAC, SAF)
├── terminal-emulator/            # Librería PTY nativa C11 (/dev/ptmx, TIOCSWINSZ) + emulador VT100/xterm
├── terminal-view/                # Vista nativa Android de terminal con selección táctil y gestos
├── scripts/                      # Scripts de empaquetado de rootfs, binarios PRoot y actualización de dsh
├── docs/ARCHITECTURE.md          # Análisis detallado, plan por fases y decisiones de diseño
└── .github/workflows/            # Pipelines CI para tests/APK y construcción ARM64 completa en la nube
```

---

## Requisitos Previos

### En la máquina de compilación
1. **JDK 17** (o JDK 21) — ej. Adoptium Temurin 17.
2. **Android SDK** con:
   - `platforms;android-35`
   - `build-tools;35.0.0`
   - `ndk;27.2.12479018`
   - `cmake;3.22.1`
3. **`curl`, `python3`, `tar` y `xz`**.
4. **Para construir el `rootfs.tar.xz`**:
   - **Opción A (recomendada en macOS, Linux x86_64/arm64 o Windows WSL2)**: **Docker** con `docker buildx` (no requiere privilegios root en el host ni `debootstrap` local).
   - **Opción B (Linux nativo ARM64 o x86_64 con QEMU)**: `sudo apt-get install -y debootstrap xz-utils qemu-user-static binfmt-support`.

### En el dispositivo Android de destino
- **Arquitectura**: `arm64-v8a` (dispositivos de 64 bits; también soporta `x86_64` para emulador si pasas `--with-x86_64` y `-PabiFilters=arm64-v8a,x86_64`).
- **Versión de Android**: Android 8.0+ (API 26+), optimizado y verificado para las restricciones de almacenamiento y ejecución W^X de **Android 10, 11, 12, 13, 14 y 15+**.
- **Espacio libre**: ~450 MB en almacenamiento interno para extraer el rootfs y cachés de plugins.

---

## Paso 1: Construir o Actualizar el Rootfs (`rootfs.tar.xz`)

Por defecto, los scripts consultan automáticamente `https://nodejs.org/dist/index.json` (verificando firmas `SHASUMS256.txt`) para instalar **la versión estable más actual de Node.js (`NODE_VERSION=latest`, actualmente Node.js v26.x+)** sobre **Debian 13 Trixie (`glibc 2.41`)**, dejando preconfigurados `npm`, `npx`, `corepack` (`pnpm` activado offline + `yarn`), `libatomic1` y las cabeceras C/C++ (`/usr/local/include/node`) para que puedas instalar cualquier CLI o paquete futuro que dependa del Node.js más moderno.

### Opción A — Construcción reproducible con Docker (recomendada)
Funciona desde cualquier sistema operativo (macOS Apple Silicon/Intel, Linux x86_64/ARM64 o WSL2):

```bash
chmod +x scripts/*.sh gradlew

# Construir Debian 13 Trixie ARM64 + última versión estable de Node.js (v26.x+) + última versión de @deepseek-ai/dsh
./scripts/build-rootfs-docker.sh
```

También puedes elegir canal `lts` o fijar versiones concretas si lo deseas:

```bash
DSH_VERSION=0.2.1-rc.5 NODE_VERSION=26.13.0 ./scripts/build-rootfs-docker.sh
```

### Opción B — Construcción directa con `debootstrap` en Linux
En un servidor o equipo Linux ARM64 (o x86_64 con `qemu-user-static`):

```bash
# Debian 13 Trixie + Node.js más actual (por defecto)
sudo ./scripts/build-rootfs.sh

# O bien Ubuntu 24.04+ compilando deepseek-harness desde su código fuente Git:
sudo DISTRO=ubuntu SUITE=noble DSH_INSTALL_MODE=git ./scripts/build-rootfs.sh
```

Ambos métodos generan automáticamente:
- `app/src/main/assets/rootfs.tar.xz`
- `app/src/main/assets/rootfs.tar.xz.sha256`
- `app/src/main/assets/rootfs.version`

### Actualizar Node.js y `deepseek-harness` (`dsh`) o instalar nuevos programas en el futuro
1. **Instalar nuevos programas dependientes de Node.js desde la Terminal integrada**:
   Como la terminal comparte exactamente el mismo contenedor Debian 13 + Node.js actual, puedes instalar directamente cualquier CLI moderna:
   ```bash
   # Paquetes globales con npm o pnpm
   npm install -g <paquete-moderno>
   pnpm add -g <paquete-moderno>

   # Si un paquete futuro necesita compilar complementos C/C++ nativos (node-gyp):
   apt-get update && apt-get install -y build-essential python3 make g++
   npm install -g <paquete-con-addon-nativo>
   ```
2. **Actualizar Node.js en vivo desde dentro del teléfono (sin recompilar el APK)**:
   El contenedor incluye el comando `/usr/local/bin/dsh-update-node`. Cuando salga una nueva versión de Node.js (p. ej. v27.x o v28.x), abre la pestaña **Terminal** y ejecuta:
   ```bash
   dsh-update-node latest      # Descarga, verifica SHA-256 e instala el último Node.js estable
   # o bien: dsh-update-node 27.0.0
   ```
   También puedes actualizar el Node.js empaquetado en `rootfs.tar.xz` desde tu PC antes de compilar con:
   ```bash
   ./scripts/update-node.sh latest
   ```
3. **Actualizar `dsh` en el APK o en caliente**:
   - En tu PC para generar un nuevo APK (respaldando automáticamente `/root/.dsh` al actualizar):
     ```bash
     ./scripts/update-dsh.sh latest
     ./gradlew assembleDebug
     ```
   - En caliente desde la **Terminal** integrada de la app (y luego pulsando **Reiniciar** en la notificación):
     ```bash
     npm install --prefix /opt/dsh --omit=dev @deepseek-ai/dsh@latest
     ```

---

## Paso 2: Descargar Binarios PRoot y Compilar el APK

1. **Descargar los binarios nativos de PRoot (`libproot.so`, `libproot_loader.so`, `libtalloc.so`, `libandroid-shmem.so`)**:
   ```bash
   ./scripts/fetch-proot-binaries.sh
   # (Opcional: añade --with-x86_64 si vas a probar en un emulador Android x86_64)
   ```

2. **Compilar el APK instalable**:
   ```bash
   # APK de depuración (firmado con clave debug, listo para instalar con adb install)
   ./gradlew assembleDebug

   # Ejecutar la batería de pruebas unitarias JUnit
   ./gradlew testDebugUnitTest
   ```
   El APK resultante se encontrará en:
   ```text
   app/build/outputs/apk/debug/app-debug.apk
   ```

3. **(Opcional) APK firmado de producción (`Release`)**:
   Coloca tu almacén de claves en `release.keystore` en la raíz del repositorio y ejecuta:
   ```bash
   RELEASE_STORE_PASSWORD="tu_password" \
   RELEASE_KEY_ALIAS="tu_alias" \
   RELEASE_KEY_PASSWORD="tu_key_password" \
   ./gradlew assembleRelease
   ```

4. **Instalar en un dispositivo Android conectado por USB/ADB**:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

---

## Guía de Uso: Terminal Integrada y Puente SAF

### Uso de la Terminal Linux Integrada
- **Pestaña completa o pantalla dividida**:
  - Toca **Terminal** en la barra inferior para usar la consola a pantalla completa.
  - Cuando estés en **Agente Web**, toca el icono de **pantalla dividida** (arriba a la derecha) para abrir la terminal en la mitad inferior sin salir de la conversación con `dsh`.
- **Múltiples sesiones**: pulsa **`+`** en la cabecera de la terminal para abrir shells `bash` adicionales dentro del mismo contenedor PRoot.
- **Barra de teclas extra móvil**:
  - Toca **`CTRL`** o **`ALT`** una vez para aplicarlo a la siguiente tecla que escribas (ej. `CTRL` + `x` en `nano`).
  - **Mantén pulsado `CTRL` o `ALT`** para bloquear el modificador (`CTRL•`) y vuelve a tocarlo para soltarlo.
  - Incluye accesos directos dedicados `^C` (`SIGINT`), `^D` (`EOF`), `^Z` (`SIGTSTP`), `^L` (limpiar), `ESC`, `TAB`, flechas, `INICIO` y `FIN`.
- **Copiar, pegar y redimensionar**:
  - Mantén pulsado sobre el texto de la terminal para abrir los selectores táctiles nativos de copia, o usa los iconos superiores de **Copiar salida** y **Pegar**.
  - Pellizca con dos dedos o pulsa **`A-` / `A+`** para ajustar el tamaño de fuente; el PTY propaga automáticamente las nuevas filas/columnas (`TIOCSWINSZ` / `SIGWINCH`) a programas como `vim`, `nano` o `htop`.

### Uso del Puente SAF (Storage Access Framework)
1. Abre la pestaña **SAF** en la barra inferior y pulsa **«Seleccionar carpeta externa (SAF)»**.
2. Elige cualquier carpeta del almacenamiento del teléfono, tarjeta SD o memoria USB.
3. La carpeta queda expuesta **inmediatamente** dentro del contenedor PRoot en dos rutas equivalentes:
   - Ruta canónica: **`/mnt/saf/<alias>`**
   - Atajo en `$HOME`: **`/root/saf/<alias>`**
4. **Cómo se expone internamente**:
   - **Modo Enlace Directo (`DIRECT_SYMLINK`)**: si la carpeta reside en el almacenamiento compartido del dispositivo y concedes el permiso opcional *«Acceso a todos los archivos»* (Android 11+), se crea un enlace simbólico POSIX directo hacia la carpeta real (`/storage/emulated/0/...`). Cualquier cambio hecho por `dsh`, `git` o `bash` se refleja al instante sin copiar datos.
   - **Modo Espejo Bidireccional (`DOCUMENT_MIRROR`)**: si prefieres no conceder acceso global al almacenamiento (o seleccionas una unidad USB OTG / proveedor externo), la app crea automáticamente una réplica local dentro del contenedor en `/mnt/saf/<alias>` compatible con operaciones POSIX atómicas (`rename`, `chmod`, `git`) y te permite sincronizar cambios en ambos sentidos pulsando **«Sincronizar»**.

---

## Verificación del Contenedor PRoot Compartido (`dsh` + `bash`)

Para comprobar empíricamente que el agente `deepseek-harness` y la terminal del usuario corren dentro del **mismo contenedor PRoot**:

### 1. Diagnóstico automático integrado
Abre la pestaña **Terminal** (o pulsa el icono de escudo en la barra superior de la terminal) y ejecuta:

```bash
dsh-doctor
```

Verás la versión de Debian/Ubuntu, la versión de `glibc`, `node`, `dsh`, los montajes `/root/workspace` y `/mnt/saf`, el proceso `node ... dsh web` activo y el código HTTP del puerto `3080`.

### 2. Prueba cruzada de archivos y procesos entre `dsh` y `bash`
1. En la **Terminal** integrada, crea un archivo de prueba en el espacio compartido y en `/tmp`:
   ```bash
   echo "Hola desde la terminal nativa $(date)" > /root/workspace/prueba_compartida.txt
   echo "Token temporal IPC" > /tmp/ipc_check.txt
   ```
2. Ve a la pestaña **Agente Web** (`http://127.0.0.1:3080`) y pídele a `dsh`:
   > *"Lee `/root/workspace/prueba_compartida.txt` y `/tmp/ipc_check.txt`, y escribe un script `/root/workspace/generado_por_dsh.py` que imprima la versión de Python y glibc."*
3. Vuelve a la **Terminal** (o usa el modo de pantalla dividida) y ejecuta:
   ```bash
   ls -la /root/workspace
   python3 /root/workspace/generado_por_dsh.py
   ```
   Verás al instante el script creado por `dsh` y podrás ejecutarlo con el mismo intérprete `python3` / `node` del contenedor.

### 3. Verificar variables, skills y binarios compartidos
```bash
# Comprobar que ambos usan la misma configuración y credenciales de dsh:
ls -la "$DSH_HOME"
cat "$DSH_HOME/settings.yaml" 2>/dev/null || true

# Verificar el socket local del motor web desde dentro de bash:
curl -i http://127.0.0.1:3080/
```

---

## Limitaciones Conocidas y Solución de Problemas

1. **Rendimiento de E/S bajo PRoot (`ptrace`)**:
   - PRoot intercepta llamadas al sistema en espacio de usuario sin requerir root, lo que añade sobrecarga en operaciones con miles de archivos pequeños (como `npm install` masivos). Por ello, `dsh` y todas sus dependencias vienen **preinstaladas en el rootfs**.
2. **Ahorro agresivo de batería en capas OEM (Xiaomi HyperOS, Huawei EMUI, Samsung One UI, OPPO/vivo)**:
   - Android puede suspender servicios en segundo plano tras varios minutos con la pantalla apagada. Entra en la pestaña **Estado -> «Sin restricción batería»** para excluir la app de la optimización de batería.
3. **Conflictos `seccomp` en ciertos kernels Android 12–15**:
   - Algunos kernels OEM rechazan el filtro BPF `seccomp` interno de PRoot. La opción **`PROOT_NO_SECCOMP=1` viene activada por defecto** en la pestaña **Estado** para garantizar compatibilidad universal.
4. **Tuberías (`pipes`) colgadas en subprocesos multihilo bajo PRoot**:
   - En kernels ARM64 bajo PRoot, encadenar procesos complejos con tuberías (`comando_pesado | head -n 5`) puede bloquearse ocasionalmente. Redirige primero a un archivo temporal (`comando > /tmp/out.txt && head -n 5 /tmp/out.txt`). Las *skills* preinstaladas en `/root/.dsh/skills` ya instruyen al agente `dsh` sobre esta regla.
5. **Python PEP 668 (`externally-managed-environment`)**:
   - En Debian 12 / Ubuntu 24.04, `pip` global está bloqueado por PEP 668. Usa paquetes del sistema (`apt-get update && apt-get install -y python3-<paquete>`) o crea un entorno virtual (`python3 -m venv .venv && source .venv/bin/activate`).
6. **El puerto 3080 tarda en responder en el primer arranque**:
   - El primer inicio de Node.js + ensamblado del árbol de plugins Cordis puede tardar entre 15 y 45 segundos según el SoC móvil. El Watchdog respeta una ventana de gracia de 75 segundos antes de considerar que el proceso se ha bloqueado.

---

## Licencia

Distribuido bajo licencia **MIT** (consulta [`LICENSE`](LICENSE) y [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)).
