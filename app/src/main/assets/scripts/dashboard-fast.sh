#!/bin/bash
# Minimal dashboard-only entrypoint for Hermes Loader's fast WEB mode.
# chroot-ng invokes this directly (without script(1), init.sh or run.sh).
set -u

. /opt/loader/lib.sh

export HERMES_HOME="${HERMES_HOME:-/home/hermes/.hermes}"
export PATH="/opt/hermes/.venv/bin:/opt/hermes/bin:/home/hermes/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

ensure_storage
stop_stale_dashboard
prepare_guest

if [ ! -x "$HERMES_BIN" ]; then
    die "Hermes is not installed. Run START or SETUP first."
fi

host="${LOADER_WEB_HOST:-127.0.0.1}"
if [ "$host" = "127.0.0.1" ]; then
    ok HERMES "Starting the fast dashboard on http://127.0.0.1:${LOADER_WEB_PORT:-9119}/"
else
    ok HERMES "Starting the fast dashboard on port ${LOADER_WEB_PORT:-9119} (login required)."
fi

exec "$HERMES_BIN" dashboard \
    --host "$host" \
    --port "${LOADER_WEB_PORT:-9119}" \
    --no-open \
    --skip-build
