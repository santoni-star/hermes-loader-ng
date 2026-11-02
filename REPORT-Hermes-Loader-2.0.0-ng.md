# Hermes Loader 2.0.0-ng — validation report

**Build:** version code 106 (`2.0.0-ng`)  
**APK:** `releases/hermes-loader-2.0.0-ng-vc106-debug.apk`  
**SHA-256:** `f78f09885153d0ed186ab49675008e38befe9b99af9476a0588f6f3d2bb97b00`

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

## Web dashboard fix

The WebView and auxiliary dashboard now honor `LOADER_WEB_HOST`.  With no
external-dashboard credentials configured, `Prefs.webHost()` supplies
`127.0.0.1`; this prevents Hermes from rejecting the old forced `0.0.0.0` bind
because no authentication provider is registered.  A configured authenticated
network dashboard can still deliberately select `0.0.0.0`.

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
