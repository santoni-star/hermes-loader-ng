# Hermes Loader NG

An Android loader for the [Hermes Agent](https://github.com/NousResearch/hermes-agent)
that runs a full Debian userland inside an ordinary, unrooted app.

This is the **chroot-ng fork** of
[hermes-loader](https://github.com/santoni-star/hermes-loader):

| | upstream (1.7.x) | this fork (2.0.0-ng) |
|---|---|---|
| Container | PRoot (`ptrace` on every syscall) | **chroot-ng** (seccomp `RET_TRAP` + `SIGSYS`, no ptrace) |
| Userland | Debian 12 Bookworm | **Debian 13 Trixie, arm64, glibc** |
| Exec from sandbox | PRoot's in-process loader | chroot-ng's own ELF loader |
| ABI | arm64, armhf, x86_64 | **arm64-v8a only** |
| Scope | app + PTY + dashboard | same UI, same storage contract |

`chroot-ng` emulates `chroot(2)` and `mount --bind` by translating only the
path-bearing syscalls, in-process. There is no tracer process and no ptrace
round-trip per syscall, which is where PRoot spent most of its time on a phone.

## What still works

* `storage/` keeps the same layout and is never touched by an update:
  `config/`, `history/`, `skills/`, `db/`, `hermes/`, `home/`, `agent/`,
  `android/`, `etc/`, `tmp/`.
* The bind map is unchanged:
  `storage/config → ~/.config/hermes`, `storage/history → ~/.local/state/hermes`,
  `storage/skills → ~/.hermes/skills`, `storage/db → ~/.hermes/data`,
  `storage/hermes → ~/.hermes`, `storage/home → /home/hermes`.
* Terminal (ANSI screen + PTY), Termux-style two-row extra keys, `≡` focus mode,
  WEB dashboard with the same extra keys, TUI ↔ WEB switching that keeps one
  agent alive, LOG pane, SAF import/export, versioned APK archive.
* Async gateway start: START shows the TUI in a few hundred milliseconds and the
  gateway connects when it is ready.

## Differences you may notice

* **No `--kill-on-exit`.** chroot-ng has no tracer process to outlive; the app
  kills the session process group itself and sweeps leftovers (`[c]hroot-ng`,
  gateway, dashboard) on the next start.
* **`/proc` and `/dev` are synthesized.** They are not host bind mounts: the
  guest sees its own process list and a whitelist of device nodes.
* **Fake identity is uid 0** (`--fake-id=0:0`), same as PRoot's `-0`. Files are
  still owned by the Android app uid.
* **arm64 only.** The bundled `libchrootng.so` is built for `aarch64-linux-android21`.

## Build

Requirements: JDK 17, Android SDK platform 34 + build-tools 34.0.0 (the
`build.ps1` wrapper finds them, or pass `-Sdk`).

```powershell
.\build.ps1 -Task ':app:assembleDebug :app:lintDebug'
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`; each build is also
archived to `releases/hermes-loader-<version>-vc<code>-debug.apk` with a
SHA-256 in `releases/SHA256SUMS.txt`.

### Rebuilding chroot-ng

The emulator source is [santoni-star/fake-chroot-ng](https://github.com/santoni-star/fake-chroot-ng).
Cross-compile it with the NDK and drop it in place as a native library:

```powershell
git clone https://github.com/santoni-star/fake-chroot-ng D:\build-chroot-ng\src
# build with the NDK clang (aarch64-linux-android21-clang), then:
Copy-Item build\chroot-ng app\src\main\jniLibs\arm64-v8a\libchrootng.so
```

It must be named `lib*.so` and packaged with `useLegacyPackaging = true`:
`nativeLibraryDir` is the only directory an app targeting API 29+ may `execve()`
from.

## Storage / rollback

* `files/rootfs` is disposable: uninstall, reinstall, or "Reinstall Linux
  rootfs" in Settings re-downloads Debian Trixie. The marker file
  `.hermes-rootfs-trixie` names the distro, so a rootfs installed by the PRoot
  build is replaced instead of reused.
* Rolling back to an upstream APK (`releases/hermes-loader-1.7.13-fast-...apk`)
  keeps `storage/` but needs its own Bookworm rootfs again.

## Known limitations

* `chroot-ng` is early-stage software. If START fails with a syscall error,
  Settings → *chroot-ng compatibility mode* switches to ahead-of-time `svc`
  rewriting (`-R`).
* GPU/`/sys` reads depend on the host kernel; only `/sys` is bound.
* Debug builds are signed with the debug key and are not intended for
  distribution.

## Licence

GPL-2.0-or-later, like upstream. `chroot-ng` is GPL-2.0+; Debian userland
packages keep their own licences. See `NOTICE.md`.
