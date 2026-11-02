# Hermes Loader 2.0.0-ng — validation report

**Build:** version code 110 (`2.0.0-ng`)  
**APK:** `releases/hermes-loader-2.0.0-ng-vc110-debug.apk`  
**SHA-256:** `428bfbb045ae5e80f6d99c182d53057f0b313e1c885ef27caf108312c0e2549b`

## Target device

* Xiaomi 2210132G (`nuwa`), Android 17
* arm64-v8a; display override 1080 × 2400

## Results

| Check | Result |
| --- | --- |
| Build + Android lint | Passed |
| APK size | 4.02 MB |
| Debian rootfs | Debian 13.7 Trixie arm64 glibc |
| Guest runtime | chroot-ng, no PRoot libraries |
| Cold activity launch | 1.1 s (`am start -W`) |
| Guest's first output | ~80 ms after process launch |
| TUI | Rendered; `/help` and keyboard input work |
| WEB from a running TUI | Passed; dashboard ready in ~13.8 s |
| TERM after WEB | Passed; original live TUI remains available |
| STOP | Passed; `[EXIT] session finished (code 0)` |
| Gateway after STOP | No `gateway.run` process remained |
| WEB Chat PTY | Passed: chat input accepted, `ping` answered `ready | step 3.7 flash:free xhigh | 6 sessions`; `webaux.log` shows no reconnect traceback |

## Web dashboard fix

The WebView and auxiliary dashboard now honor `LOADER_WEB_HOST`.  With no
external-dashboard credentials configured, `Prefs.webHost()` supplies
`127.0.0.1`; this prevents Hermes from rejecting the old forced `0.0.0.0` bind
because no authentication provider is registered.  A configured authenticated
network dashboard can still deliberately select `0.0.0.0`.

## Dashboard chat PTY fix

Hermes' downloaded arm64 Node runtime links `libatomic.so.1`.  Debian's minimal
Trixie image does not include it, so the dashboard chat WebSocket dropped
immediately and the UI looped "Chat connection interrupted / Reconnecting… /
Reconnect now".

* `setup.sh` installs `libatomic1` alongside `nodejs npm`.
* `init.sh` gained `ensure_node_atomic()` (one-shot `apt-get update &&
  apt-get install -y --no-install-recommends libatomic1`) invoked in
  `boot`, `web`, `boot-fast`, `web-fast`, `webaux` and `webaux-ready`.

## Extra-keys bar

The ≡ toggle button now hides both the header and the extra-keys panel in every
mode (TUI and WEB), leaving a single ≡ row to bring them back — Termux-style
two-row layout when expanded.

## Dashboard log Clear button

The LOG dialog gains a CLEAR LOG button (bottom left, next to CLOSE) that
truncates `webaux.log` and refreshes the view. Copy and Close keep their
existing behaviour.

## 16 KiB memory-page compatibility

`libchrootng.so` was rebuilt with
`-Wl,-z,max-page-size=16384`.  Its two `PT_LOAD` segments now use `Align
0x4000`, with 16 KiB-aligned offsets and virtual addresses:

```text
LOAD  offset 0x000000  vaddr 0x1000000000  align 0x4000
LOAD  offset 0x080000  vaddr 0x1000080000  align 0x4000
```

`zipalign -c -P 16 -v 4 app-debug.apk` also passed.

## Notes

* The APK is intentionally a **debug** build for device validation; Android may
  display its normal debug-app compatibility notice. This is independent of the
  corrected 16 KiB ELF segment alignment.
* The app is arm64-v8a-only because chroot-ng is packaged for that ABI.
