# Changelog

## 2.0.0-ng — 2026-11-02

First release of the chroot-ng fork. It is a separate repository and a separate
application build; the UI, storage contract and scripts come from the 1.7.13-fast
loader, the container and userland are new.

### Container

* Replaced PRoot with **chroot-ng** — ptrace-free `chroot`/bind emulation
  (seccomp `RET_TRAP` → `SIGSYS`, own ELF loader). Guest syscalls are no longer
  trapped one at a time, which removes PRoot's per-syscall overhead.
* `chroot-ng` ships as `jniLibs/arm64-v8a/libchrootng.so` (2.5 MB, static,
  aarch64-linux-android21) so Android extracts it to `nativeLibraryDir`, the
  only directory an app targeting API 29+ may execute from.
* The native binary's `PT_LOAD` segments use 16 KiB alignment (`0x4000`),
  matching Android devices that use 16 KiB memory pages.
* Command line: `--fake-id=0:0 --link2symlink --shared-proc -w /home/hermes`,
  `-b` for every persistent path, `/sys` bound, `/proc` and `/dev` left to
  chroot-ng's own synthesized views.
* `--shared-proc` keeps the console and the auxiliary dashboard in one guest
  process view, so `ps` in either session sees the shared gateway.
* No `--kill-on-exit`: the app owns process lifetime. `Session.killLeftovers()`
  sweeps `[c]hroot-ng` in addition to the gateway and dashboard patterns.
* `--kill-on-exit`-era `PROOT_*` environment variables and the PRoot loader
  binaries are gone.

### Userland

* Rootfs is **Debian 13 Trixie** (`debian:trixie-slim` from Docker Hub, LXC
  `images/debian/trixie/` as fallback) on **arm64 with glibc**.
* `ROOTFS_MARKER` is now `.hermes-rootfs-trixie`, so an existing Bookworm tree
  installed by the PRoot build is replaced rather than reused.
* The chroot-ng build is arm64-only; other ABIs are rejected at startup with a
  clear message.

### UI

* About dialog, launcher status line and hints refer to chroot-ng.
* Settings → *chroot-ng compatibility mode* maps to `-R` (ahead-of-time `svc`
  rewriting) instead of `PROOT_NO_SECCOMP`.
* Banner reads “Debian Trixie (chroot-ng)”.
* WEB and the auxiliary dashboard honor the configured loopback host. They no
  longer force a `0.0.0.0` bind, which Hermes correctly rejects without a
  configured dashboard authentication provider.

### Carried over from 1.7.13-fast

* Async gateway start: TUI in a few hundred milliseconds, gateway connects in
  the background.
* `boot-fast` / `web-fast` guest modes (skip banner/storage/prepare).
* WebView dashboard with the extra keys panel, software-rendered WebView layer,
  instant TUI ↔ WEB switching.
* SIGTERM-then-SIGKILL session sweep (exit code 0, no ghost gateway locks).

### Known issues

* chroot-ng is early-stage; the `-R` fallback exists for hosts where the
  seccomp tier misbehaves.
* `xterm.js` in the WebView may not be ready the instant the dashboard pane
  opens; the extra keys work once `window.term` exists.
