#!/bin/bash
# Runs the agent, and applies code updates only when the user asks for them
# (⋮ → Update agent now), without ever touching user state (which lives in
# /home/hermes, bind-mounted from Android storage/).
set -u

. /opt/loader/lib.sh

export HERMES_HOME="${HERMES_HOME:-/home/hermes/.hermes}"
export PATH="/opt/hermes/.venv/bin:/opt/hermes/bin:/home/hermes/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

# `git fetch` takes seconds on a phone and blocks the session before the first
# prompt; it used to run on every boot. Now only `run.sh update` calls this.
update_agent() {
    [ -d "$HERMES_DIR/.git" ] || return 0

    say HERMES "Fetching updates from GitHub…"
    local old new
    old="$(git -C "$HERMES_DIR" rev-parse HEAD 2>/dev/null || true)"
    git -C "$HERMES_DIR" remote set-url origin "$LOADER_REPO" 2>/dev/null || true

    if ! git -C "$HERMES_DIR" fetch --depth 1 origin "$LOADER_BRANCH" >/dev/null 2>&1; then
        warn HERMES "Update check failed (offline?). Continuing with the local copy."
        return 0
    fi

    new="$(git -C "$HERMES_DIR" rev-parse FETCH_HEAD 2>/dev/null || true)"
    if [ -n "$new" ] && [ "$old" = "$new" ]; then
        say HERMES "Already on the latest revision (${new:0:7})."
        return 0
    fi

    if ! git -C "$HERMES_DIR" reset --hard FETCH_HEAD >/dev/null 2>&1; then
        warn HERMES "Could not apply the update."
        return 0
    fi
    new="$(git -C "$HERMES_DIR" rev-parse HEAD 2>/dev/null || true)"
    say HERMES "Updated ${old:0:7} -> ${new:0:7}."
    # `reset --hard` restored upstream files, including PM's uv settings.
    prepare_guest

    local sum
    sum="$(cat "$HERMES_DIR/pyproject.toml" "$HERMES_DIR/uv.lock" 2>/dev/null | sha256sum | cut -d' ' -f1)"
    if [ "$sum" != "$(cat "$HERMES_DIR/.venv/.deps-hash" 2>/dev/null)" ]; then
        say HERMES "Dependencies changed, syncing…"
        if ensure_uv && install_deps "$(profile_extra)"; then
            printf '%s\n' "$sum" > "$HERMES_DIR/.venv/.deps-hash"
            ok HERMES "Dependencies updated."
        else
            warn HERMES "Dependency sync failed — press SETUP to repair the environment."
        fi
    fi
}

# Gateway management.
#
# The old design waited for the gateway synchronously (30-60 s on a phone)
# before starting the console/dashboard. That made the UI appear only after
# the full Python/FastAPI warm-up.
#
# New design: start the gateway in the background, then immediately launch
# the console or dashboard. They will connect to the gateway when it becomes
# ready (hermes CLI has its own connection retry logic). This decouples UI
# startup from gateway warm-up.
#
# LOADER_GATEWAY_PREWARM=0 (Settings → "Wait for gateway before START")
# preserves the old synchronous behaviour for comparison.

# Start the gateway daemon in the background without waiting for it to be ready.
# Returns immediately. The gateway will create its socket and lock files;
# clients (hermes console, hermes dashboard) will retry until the socket
# accepts connections.
start_gateway_background() {
    # Check if gateway is already running (by lock file + process).
    if [ -e "$GUEST_HOME_STATE/gateway.lock" ] && \
       pgrep -f 'hermes_cli.*gateway run' >/dev/null 2>&1; then
        say GATEWAY "Gateway already running (lock + process found)."
        return 0
    fi
    say GATEWAY "Starting gateway in background…"
    # Run gateway daemon detached. It will daemonize itself and write PID to
    # gateway.lock. stdout/stderr go to the gateway log via hermes internals.
    nohup "$HERMES_BIN" gateway run </dev/null >/dev/null 2>&1 &
    local gw_pid=$!
    # Give it a moment to create the lock file so we don't race with callers
    # that check for it.
    sleep 0.5
    if [ -e "$GUEST_HOME_STATE/gateway.lock" ]; then
        say GATEWAY "Gateway daemon started (PID $gw_pid)."
    else
        warn GATEWAY "Gateway daemon may have failed to start (no lock file)."
    fi
    return 0
}

# Synchronous wait for gateway readiness — kept for the "prewarm" setting
# and for callers that explicitly need to block until ready (e.g. tests).
wait_gateway() {
    if [ "${LOADER_GATEWAY_PREWARM:-1}" = "0" ]; then
        say GATEWAY "Gateway wait is off (Settings), console/dashboard will connect async."
        return 0
    fi
    local log="$GUEST_HOME_STATE/cache/loader/gateway-wait.log"
    local tick_file="$GUEST_HOME_STATE/cache/loader/gateway-wait.tick"
    local t0 t1 tick rc
    now_ms
    t0="$NOW"
    say GATEWAY "Waiting for gateway to become ready (synchronous prewarm):"
    printf '       '
    rm -f "$tick_file"
    (
        while [ ! -e "$tick_file" ]; do
            sleep 1
            [ -e "$tick_file" ] || printf '.'
        done
    ) &
    tick=$!
    ensure_gateway 300
    rc=$?
    : >"$tick_file"
    wait "$tick" 2>/dev/null
    rm -f "$tick_file"
    printf '\n'
    now_ms
    t1="$NOW"
    printf '%s ensure rc=%s %s ms\n' "${EPOCHSECONDS:-0}" "$rc" "$((t1 - t0))" \
        >>"$log" 2>/dev/null || true
    if [ "$rc" -eq 0 ]; then
        ok GATEWAY "Gateway ready in $(((t1 - t0) / 1000)) s."
    else
        warn GATEWAY "Gateway did not answer in time; clients will retry on their own."
    fi
    return 0
}

# Internal: ensure_gateway wraps the CLI command. Kept for wait_gateway().
ensure_gateway() {
    "$HERMES_BIN" gateway ensure --timeout "${1:-300}" </dev/null >/dev/null 2>&1
}

ACTION="${1:-run}"

case "$ACTION" in
    update|update-only)
        update_agent
        ok HERMES "Update pass finished."
        ;;
    setup)
        exec "$HERMES_BIN" setup
        ;;
    login)
        printf '\n'
        ok HERMES "Signing in to Nous Portal…"
        printf '       The agent prints a link and a short code; the link opens in\n'
        printf '       your browser automatically. Confirm the code there and return.\n\n'
        # --no-browser: there is no browser inside the container, and letting
        # the CLI try wastes the first seconds of the flow. The app opens the
        # link on the Android side instead.
        "$HERMES_BIN" auth add nous --no-browser
        rc=$?
        printf '\n'
        if [ "$rc" -eq 0 ]; then
            ok HERMES "Signed in. Press START to chat."
            printf '       If the default model is not on your plan, pick another free\n'
            printf '       one with `hermes model` in SHELL, then START again.\n'
        else
            warn HERMES "Sign-in did not finish. Retry with menu → Sign in to Nous."
        fi
        ;;
    run)
        printf '\n'
        ok HERMES "Starting Hermes Agent…"
        printf '\033[2m----------------------------------------'
        printf -- '--------------------------------------\033[0m\n\n'
        # Start gateway in background so console appears immediately.
        # The console will connect to gateway when ready (async retry).
        start_gateway_background
        exec "$HERMES_BIN"
        ;;
    web)
        # The SPA is pre-built and bind-mounted by the app (HERMES_WEB_DIST),
        # so --skip-build keeps npm/Node out of the phone entirely.
        # For WebView modes (MODE_WEB_FAST, MODE_WEB_AUX), bind to 0.0.0.0 so the
        # WebView (running in host Android process) can reach the dashboard.
        # For standalone MODE_WEB, respect the "Reachable from other devices" setting.
        if [ "${LOADER_AUX:-0}" = "1" ] || [ "${LOADER_MODE:-}" = "web-fast" ]; then
            host="0.0.0.0"
        else
            host="${LOADER_WEB_HOST:-127.0.0.1}"
        fi
        # Credentials for a bind that is not loopback-only arrive as a sourced
        # file: the password then never shows up in PRoot's argv (or in `ps`).
        # The app owns that file and rewrites it on every start.
        if [ -f /opt/loader/web-auth.env ]; then
            . /opt/loader/web-auth.env 2>/dev/null || true
        fi
        # A dashboard started next to a console must attach to the console's
        # gateway. If the console already signalled readiness (LOADER_GATEWAY_READY=1)
        # and the gateway process is alive, we can skip starting another.
        # Otherwise, start gateway in background so dashboard appears fast.
        if [ "${LOADER_AUX:-0}" = "1" ]; then
            if [ "${LOADER_GATEWAY_READY:-0}" = "1" ] \
               && { pgrep -f 'hermes_cli.*gateway run' >/dev/null 2>&1 \
                    || pgrep -f 'gateway[.]run' >/dev/null 2>&1; }; then
                say GATEWAY "Using the console's live gateway; skipping duplicate start."
            else
                start_gateway_background
            fi
        else
            # Standalone dashboard (WEB button without console): start gateway in background.
            start_gateway_background
        fi
        printf '\n'
        if [ "$host" = "127.0.0.1" ]; then
            ok HERMES "Starting the web dashboard on http://127.0.0.1:${LOADER_WEB_PORT:-9119}/"
        else
            ok HERMES "Starting the web dashboard on port ${LOADER_WEB_PORT:-9119} (login required)."
            printf '       The app shows the address to open from another device.\n'
        fi
        printf '       Stop it with STOP; the terminal stays available for logs.\n\n'
        exec "$HERMES_BIN" dashboard \
            --host "$host" \
            --port "${LOADER_WEB_PORT:-9119}" \
            --no-open \
            --skip-build
        ;;
    *)
        exec "$HERMES_BIN" "$@"
        ;;
esac
