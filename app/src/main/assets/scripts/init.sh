#!/bin/bash
# Entry point executed inside the PRoot container.
#
#   init.sh install        - install/refresh system packages + Hermes agent
#   init.sh boot           - start the agent (no update check: see run.sh update)
#   init.sh boot-fast      - start the agent, skipping banner/storage/prepare
#   init.sh web            - serve the dashboard as the session itself
#   init.sh web-fast       - serve dashboard, skipping banner/storage/prepare
#   init.sh webaux         - serve the dashboard next to a live console session
#   init.sh webaux-ready   - like webaux, but console already confirmed gateway
#   init.sh setup          - run `hermes setup` (provider / API key)
#   init.sh login          - Nous Portal sign-in only (device code)
#   init.sh update         - fetch agent updates (only when the user asks)
#   init.sh shell          - interactive root shell
set -u

. /opt/loader/lib.sh

MODE="${1:-boot}"
export HERMES_HOME="${HERMES_HOME:-/home/hermes/.hermes}"
export PATH="/opt/hermes/.venv/bin:/opt/hermes/bin:/home/hermes/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

# Fast modes skip the heavy bootstrap (banner, ensure_storage, prepare_guest)
# and go straight to run.sh. They assume the agent is already installed.
case "$MODE" in
    boot-fast|web-fast)
        # Minimal init: just stop stale dashboard, then delegate to run.sh.
        # Pass no argument so run.sh defaults to "run" (not "boot"/"web").
        stop_stale_dashboard
        exec /opt/loader/run.sh
        ;;
esac

now_ms
T0="$NOW"
banner
ensure_storage
now_ms
T1="$NOW"
stty_size

# A previous dashboard may still own the port (see stop_stale_dashboard). An
# auxiliary dashboard shares the console's gateway, so it must not sweep it.
case "$MODE" in
    boot|web) stop_stale_dashboard ;;
    webaux|webaux-ready) stop_stale_dashboard keep-gateway ;;
esac
now_ms
T2="$NOW"

# uv settings for `hermes pm`, which ignores the ones the app passes in. Runs
# after the stale dashboard is gone: it may drop a runtime that one still uses.
prepare_guest
now_ms
T3="$NOW"
report_startup_timing "$T0" "$T1" "$T2" "$T3"

needs_install() {
    [ ! -d "$HERMES_DIR/.git" ] && return 0
    [ ! -x "$HERMES_BIN" ] && return 0
    return 1
}

case "$MODE" in
    install)
        exec /opt/loader/setup.sh
        ;;
    setup)
        if needs_install; then exec /opt/loader/setup.sh; fi
        exec /opt/loader/run.sh setup
        ;;
    login)
        if needs_install; then exec /opt/loader/setup.sh; fi
        exec /opt/loader/run.sh login
        ;;
    boot)
        if needs_install; then exec /opt/loader/setup.sh; fi
        # No `run.sh update` here on purpose: a git fetch on every start made
        # the app look hung for seconds and could replace the checkout under a
        # running gateway. Updates are explicit (⋮ → Update agent now).
        exec /opt/loader/run.sh
        ;;
    update)
        exec /opt/loader/run.sh update-only
        ;;
    web)
        if needs_install; then exec /opt/loader/setup.sh; fi
        exec /opt/loader/run.sh web
        ;;
    webaux|webaux-ready)
        # Same server, started while another session keeps running. Skipping
        # the install check is deliberate: the console next to it already
        # proved that the agent is there, and a setup run cannot share the
        # guest with a live session.
        #
        # The flag makes run.sh attach the dashboard to the console's gateway
        # instead of starting a second one (see the web) branch).
        LOADER_AUX=1
        export LOADER_AUX
        if [ "$MODE" = "webaux-ready" ]; then
            LOADER_GATEWAY_READY=1
            export LOADER_GATEWAY_READY
        fi
        exec /opt/loader/run.sh web
        ;;
    shell)
        say SHELL "Starting root shell inside the Debian container."
        exec /bin/bash -l
        ;;
    *)
        die "Unknown mode: $MODE"
        ;;
esac
