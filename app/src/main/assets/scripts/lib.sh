#!/bin/bash
# Shared helpers for the Hermes Loader guest scripts.
# Sourced by init.sh / setup.sh / run.sh.

HERMES_BIN=/opt/hermes/.venv/bin/hermes
HERMES_DIR=/opt/hermes
# A prebuilt bundle ships uv inside /opt/hermes so that a dependency update
# works without fetching the installer again; a source install keeps it in the
# guest home. Never point this at a path that does not exist.
UV_BIN=/opt/hermes/bin/uv
[ -x "$UV_BIN" ] || UV_BIN="$HOME/.local/bin/uv"
GUEST_HOME_STATE="${HERMES_HOME:-/home/hermes/.hermes}"

# ------------------------------------------------------------------ uv @ PRoot
# Android refuses link() inside the app's data directory. dpkg survives that
# because PRoot emulates hard links as symlinks (--link2symlink), but uv does
# not: it links out of its cache, the emulation leaves ".l2s.<name><n>" marker
# files behind, and the next link of those files fails with EPERM
# ("failed to hardlink file … Operation not permitted"). Copy instead, and keep
# the cache on the disposable /tmp bind so it can be reclaimed after a run.
export UV_LINK_MODE=copy
export UV_CACHE_DIR=/tmp/uv-cache
# Never let the installer rewrite our PATH; the app already sets it.
export UV_NO_MODIFY_PATH=1

say()  { printf '\033[1;36m[%s]\033[0m %s\n' "$1" "$2"; }
ok()   { printf '\033[1;32m[%s]\033[0m %s\n' "$1" "$2"; }
warn() { printf '\033[1;33m[%s]\033[0m %s\n' "$1" "$2"; }
die()  { printf '\033[1;31m[%s]\033[0m %s\n' "ERROR" "$1"; exit 1; }

have() { command -v "$1" >/dev/null 2>&1; }

# EPOCHREALTIME is a bash builtin, so timing costs no process spawn — and every
# spawn matters here: PRoot intercepts syscalls, and a fork/exec takes tens of
# times longer than on bare Linux. Sets NOW (milliseconds); no subshell.
now_ms() {
    local t="${EPOCHREALTIME:-}"
    if [ -z "$t" ]; then
        NOW="$(($(date +%s) * 1000))"
        return 0
    fi
    t="${t//[!0-9]/}"
    NOW=$((10#$t / 1000))
}

# One line before the agent starts, so "still slow" can be attributed to the
# loader or to the agent instead of guessed at.
report_startup_timing() {
    local t0="${1:-}" t1="${2:-}" t2="${3:-}" t3="${4:-}"
    [ -n "$t0" ] && [ -n "$t3" ] || return 0
    local total=$((t3 - t0))
    [ "$total" -ge 0 ] || return 0
    ok INIT "loader ready in ${total} ms (storage $((t1 - t0)), sweep $((t2 - t1)), prepare $((t3 - t2)))."
}

banner() {
    printf '\033[1;32m'
    cat <<'EOF'
  _   _                       _                    _
 | | | | ___ _ __ _ __ ___   ___  ___    __ _  __ _  ___ _ __ | |_
 | |_| |/ _ \ '__| '_ ` _ \ / _ \/ __|  / _` |/ _` |/ _ \ '_ \| __|
 |  _  |  __/ |  | | | | | |  __/\__ \ | (_| | (_| |  __/ | | | |_
 |_| |_|\___|_|  |_| |_| |_|\___||___/  \__,_|\__, |\___|_| |_|\__|
                                              |___/
EOF
    printf '\033[0m'
    printf '  Debian Trixie (chroot-ng) :: persistent storage :: no root required\n\n'
}

ensure_storage() {
    mkdir -p /home/hermes/.config/hermes \
             /home/hermes/.local/state/hermes \
             /home/hermes/.hermes/skills \
             /home/hermes/.hermes/data \
             /home/hermes/.cache \
             /tmp
    ok INIT "Persistent storage mounted:"
    printf '       config  -> ~/.config/hermes\n'
    printf '       history -> ~/.local/state/hermes\n'
    printf '       skills  -> ~/.hermes/skills\n'
    printf '       db      -> ~/.hermes/data\n'
    link_phone
}

# Android folders the user picked in Settings arrive as a *copy*: SAF exposes
# content:// URIs, which PRoot cannot bind. The app binds the copy here; give
# it the two names the UI promises, /mnt/android/<name> and ~/projects/<name>.
link_phone() {
    local base="${LOADER_ANDROID:-/home/hermes/phone}"
    [ -d "$base" ] || return 0
    mkdir -p "$HOME/projects" 2>/dev/null || true
    if [ -d /mnt ]; then
        ln -sfn "$base" /mnt/android 2>/dev/null || true
    fi
    local found=0
    local d name
    for d in "$base"/*/; do
        [ -d "$d" ] || continue
        name=$(basename "$d")
        ln -sfn "$base/$name" "$HOME/projects/$name" 2>/dev/null || true
        found=1
    done
    # A folder removed in Settings leaves a dangling link behind. Only touch
    # links that point into the phone mirror: ~/projects is the user's own
    # directory and may hold links of their own.
    for d in "$HOME"/projects/*; do
        [ -L "$d" ] || continue
        case "$(readlink "$d" 2>/dev/null)" in
            "$base"/*) [ -d "$d" ] || rm -f "$d" ;;
        esac
    done
    if [ "$found" = 1 ]; then
        ok ANDROID "Phone folders (copies — sync them from Settings):"
        for d in "$base"/*/; do
            [ -d "$d" ] && printf '       /mnt/android/%s\n' "$(basename "$d")"
        done
    fi
}

stty_size() {
    if [ -t 0 ]; then
        stty cols "${LOADER_COLUMNS:-100}" rows "${LOADER_ROWS:-40}" 2>/dev/null || true
    fi
}

profile_extra() {
    case "${LOADER_PROFILE:-all}" in
        core|none|""|null) printf '' ;;
        *)                 printf '%s' "$LOADER_PROFILE" ;;
    esac
}

ensure_uv() {
    if [ -x "$UV_BIN" ]; then
        return 0
    fi
    if have uv; then
        UV_BIN="$(command -v uv)"
        return 0
    fi
    say PKG "Installing uv (Python runner)…"
    curl -fsSL https://astral.sh/uv/install.sh | sh || return 1
    [ -x "$HOME/.local/bin/uv" ] || return 1
    UV_BIN="$HOME/.local/bin/uv"
}

venv_python() {
    local v="$1"
    [ -n "$v" ] || return 0
    say HERMES "Creating virtualenv with Python $v…"
    "$UV_BIN" venv --python "$v" "$HERMES_DIR/.venv" >/dev/null 2>&1
}

install_deps() {
    local extra="$1"
    local spec="."
    if [ -n "$extra" ]; then
        spec=".[$extra]"
    fi
    say HERMES "Installing Python dependencies ($spec)…"
    ( cd "$HERMES_DIR" && "$UV_BIN" pip install --link-mode copy \
        --python "$HERMES_DIR/.venv/bin/python" -e "$spec" )
}

# uv's cache holds unpacked wheels plus its build environments. After a
# successful install it is pure duplicated data (several hundred MB), so give
# the space back. It lives in /tmp (binding of storage/tmp), not in the rootfs.
clean_uv_cache() {
    local dir mb
    for dir in "$UV_CACHE_DIR" "$HOME/.cache/uv"; do
        [ -d "$dir" ] || continue
        mb="$(du -sm "$dir" 2>/dev/null | cut -f1)"
        rm -rf "$dir" 2>/dev/null || continue
        say HERMES "Reclaimed ${mb:-?} MB of uv cache."
    done
}

# ------------------------------------------------------------- Hermes PM (own uv)
# `hermes pm` - the package manager behind dashboard chat sessions, plugin
# installs and the pinned "pm-runtime" - does not inherit the settings above.
# pm/environment.py rebuilds uv's environment from scratch and keeps only an
# index/transport allowlist of UV_* variables, so it never sees UV_LINK_MODE; it
# also points uv at ~/.hermes/cache/uv. The pinned runtime is built with
# --no-config on top of that, so a config file alone cannot reach it. Two
# independent guards, cheapest first:
#
#   1. /etc/uv/uv.toml - uv's system-level configuration, read by every uv
#      process that does not pass --no-config (the chat environments, plugin
#      installs, `uv` from the shell).
#   2. one line in pm/environment.py, the only way into the --no-config builds.
#      The updater does `git reset --hard`, so it is re-applied after each
#      update (prepare_guest).
uv_system_config() {
    local dir=/etc/uv file=/etc/uv/uv.toml want
    want='# Written by Hermes Loader: PRoot cannot hard-link inside the app sandbox.
link-mode = "copy"'
    [ -f "$file" ] && [ "$(<"$file")" = "$want" ] && return 0
    mkdir -p "$dir" 2>/dev/null || return 0
    printf '%s\n' "$want" > "$file" 2>/dev/null || return 0
    # A fresh rootfs can carry PM runtimes that were built elsewhere; make the
    # one-time link scan run again instead of trusting a previous result.
    rm -f "$GUEST_HOME_STATE/cache/loader/pm-links-clean" 2>/dev/null
    say HERMES "uv copy mode configured for the whole container (/etc/uv/uv.toml)."
}

patch_pm_uv() {
    local f="$HERMES_DIR/pm/environment.py" text
    [ -f "$f" ] || return 0
    text="$(<"$f")" 2>/dev/null || return 0
    [[ "$text" == *UV_LINK_MODE* ]] && return 0
    if [[ "$text" == *'UV_PYTHON_DOWNLOADS="never")'* ]]; then
        sed -i 's/UV_PYTHON_DOWNLOADS="never")/UV_PYTHON_DOWNLOADS="never",\n                   UV_LINK_MODE="copy")/' \
            "$f" 2>/dev/null || true
    fi
    text="$(<"$f")" 2>/dev/null || true
    if [[ "$text" == *UV_LINK_MODE* ]]; then
        say HERMES "Hermes PM is set to copy packages instead of hard-linking."
    else
        warn HERMES "Could not patch PM's uv settings (upstream changed)."
        warn HERMES "Chat sessions may fail with «failed to hardlink … os error 1»."
    fi
}

# The local gateway daemon is behind every session (TUI and dashboard). Its
# control socket authenticates the peer through SO_PEERCRED and compares that
# uid with os.getuid(): PRoot fakes getuid() as 0 but the kernel reports the
# real Android app uid on the socket, so every client would be refused with
# "Gateway inaccessible: unsafe_control_peer". The faked root owns every file
# in the container anyway, and the socket's path/mode checks still run, so
# report the local uid when this container is the PRoot guest we configured.
patch_gateway_peer() {
    local f="$HERMES_DIR/hermes_cli/gateway_runtime_discovery.py" text
    [ -f "$f" ] || return 0
    text="$(<"$f")" 2>/dev/null || return 0
    [[ "$text" == *LOADER_FAKE_ROOT* ]] && return 0
    [[ "$text" == *'def socket_peer_uid'* ]] || return 0
    sed -i 's|^    if hasattr(socket, "SO_PEERCRED"):|    if os.environ.get("LOADER_FAKE_ROOT") == "1":\n        # PRoot reports the Android app uid here and fakes 0 from getuid().\n        # One uid owns every guest process; the path checks already passed.\n        return os.getuid()\n    if hasattr(socket, "SO_PEERCRED"):|' "$f" 2>/dev/null || true
    text="$(<"$f")" 2>/dev/null || true
    if [[ "$text" == *LOADER_FAKE_ROOT* ]]; then
        say HERMES "Gateway control socket accepts the container's root id."
    else
        warn HERMES "Could not patch the gateway peer check (upstream changed)."
        warn HERMES "Sessions may fail with «Gateway inaccessible: unsafe_control_peer»."
    fi
}

# State written before copy mode existed is poisoned: PRoot's hard-link
# emulation leaves ".l2s.<name><n>" files holding the content plus symlinks to
# them. uv copies that shape into every chat environment, and the symlinks die
# with the generation they point into. Drop such generations (PM rebuilds them
# from the store on next use) and the cache that fed them.
scrub_pm_links() {
    local gen cache="$GUEST_HOME_STATE/cache/uv" dropped=0
    local stamp="$GUEST_HOME_STATE/cache/loader/pm-links-clean"
    # With copy mode in force PM cannot create new .l2s markers, so a clean
    # scan stays clean until the rootfs (and /etc/uv) is replaced; uv_system_config
    # drops this stamp in that case. The scan walks every generation, which is
    # expensive under PRoot, so it must not run on each start.
    [ -f "$stamp" ] && return 0
    for gen in "$GUEST_HOME_STATE"/installs/*/pm-runtime/generations/*; do
        [ -d "$gen" ] || continue
        # site-packages/<package>/.l2s.<name><n> sits at depth 5; one level of
        # slack so a deeper layout still matches.
        find "$gen" -maxdepth 6 -name '.l2s.*' -print -quit 2>/dev/null | grep -q . || continue
        rm -rf "$gen" 2>/dev/null && dropped=1
    done
    if [ "$dropped" = "1" ]; then
        say HERMES "Rebuilding PM runtimes that were built with hard links…"
        if [ -d "$cache" ] \
           && find "$cache" -maxdepth 4 -name '.l2s.*' -print -quit 2>/dev/null | grep -q .; then
            rm -rf "$cache" 2>/dev/null \
                && say HERMES "Discarded a PM download cache built with hard links."
        fi
    fi
    mkdir -p "$GUEST_HOME_STATE/cache/loader" 2>/dev/null \
        && : > "$stamp" 2>/dev/null
    return 0
}

# Everything that has to hold before a session starts, in every mode.
prepare_guest() {
    uv_system_config
    patch_pm_uv
    patch_gateway_peer
    scrub_pm_links
}

# Major.minor of the interpreter inside the virtualenv, e.g. "3.14".
venv_version() {
    [ -x "$HERMES_DIR/.venv/bin/python" ] || return 1
    "$HERMES_DIR/.venv/bin/python" -c 'import sys;print("%d.%d"%sys.version_info[:2])' 2>/dev/null
}

# ----------------------------------------------------------------- dashboard
# A dashboard can outlive the Android process that started it: the system kills
# the app, not the guest tree, and PRoot's --kill-on-exit does not run when the
# tracer is SIGKILLed. The leftover keeps port 9119 and its rendezvous record,
# so the next `hermes dashboard` only attaches to it and exits 0 — from the UI
# that looks like the session "died a few seconds after starting". Take the
# port back before launching a new server.
port_open() {
    (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null || return 1
    exec 3>&- 2>/dev/null || true
    return 0
}

#
# Second argument/flag `keep-gateway` is for the dashboard that runs *next to*
# a live console session: there the gateway is the console's own daemon and
# killing it would end the agent the user is trying to keep.
stop_stale_dashboard() {
    local port="${LOADER_WEB_PORT:-9119}"
    local pattern='hermes dashboard|hermes_cli.*dashboard|hermes_cli.*web_server'
    if [ "${1:-}" != "keep-gateway" ]; then
        # A leftover gateway holds the control socket and the profile lock, and
        # it runs code from an older checkout after an update.
        pattern="$pattern|hermes gateway|hermes_cli.*gateway run|gateway[.]run"
    fi
    local pids="" pid killed=0 i=0
    if command -v pgrep >/dev/null 2>&1; then
        # One pgrep with alternation instead of six: every process spawn is slow
        # under PRoot.
        pids="$(pgrep -f "$pattern" 2>/dev/null || true)"
    fi
    for pid in $pids; do
        [ "$pid" = "$$" ] && continue
        kill "$pid" 2>/dev/null && killed=1
    done
    if [ "$killed" = "1" ]; then
        say HERMES "Stopped a server left over from an earlier session."
    fi
    while port_open "$port"; do
        i=$((i + 1))
        if [ "$i" -eq 40 ]; then
            for pid in $pids; do kill -9 "$pid" 2>/dev/null || true; done
        elif [ "$i" -ge 48 ]; then
            warn HERMES "Port ${port} is still busy — the dashboard may fail to bind."
            return 0
        fi
        sleep 0.25
    done
    # A socket file outlives its listener: the next client would connect to a
    # dead endpoint and report "Gateway inaccessible" instead of starting a
    # daemon. Only remove it once nothing can be listening any more, and only
    # look for a gateway when a socket is actually on disk: on a clean start
    # there is nothing to wait for.
    #
    # The console's gateway is alive and must stay that way, so an auxiliary
    # dashboard stops here: it only needed the port.
    [ "${1:-}" = "keep-gateway" ] && return 0
    if [ -e "$GUEST_HOME_STATE/gateway.sock" ] \
       || [ -e "$GUEST_HOME_STATE/gateway.sock.path" ]; then
        if command -v pgrep >/dev/null 2>&1; then
            i=0
            while pgrep -f 'hermes_cli.*gateway run' >/dev/null 2>&1 \
                  || pgrep -f 'gateway[.]run' >/dev/null 2>&1; do
                i=$((i + 1))
                [ "$i" -ge 16 ] && break
                sleep 0.25
            done
            if ! pgrep -f 'hermes_cli.*gateway run' >/dev/null 2>&1 \
               && ! pgrep -f 'gateway[.]run' >/dev/null 2>&1; then
                rm -f "$GUEST_HOME_STATE/gateway.sock" "$GUEST_HOME_STATE/gateway.sock.path" 2>/dev/null
                rm -rf /tmp/hermes-gw-* 2>/dev/null
            fi
        fi
    fi
    return 0
}
