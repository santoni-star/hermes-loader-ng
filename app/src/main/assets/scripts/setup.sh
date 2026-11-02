#!/bin/bash
# Installs everything inside the container: Debian packages, the Hermes
# checkout and its Python environment.
#
# Idempotent: safe to re-run at any time. Never touches /home/hermes state.
set -uo pipefail

. /opt/loader/lib.sh

export DEBIAN_FRONTEND=noninteractive
APT_OPTS=(-y -o Acquire::Retries=3 -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold --no-install-recommends)

# A previous run may have been interrupted in the middle of dpkg (a hard-link
# error, a killed session, a flat battery). dpkg then refuses to continue until
# its journal is replayed, so do that before touching apt again.
if [ -d /var/lib/dpkg/updates ] && [ -n "$(ls -A /var/lib/dpkg/updates 2>/dev/null)" ]; then
    say PKG "Recovering an interrupted dpkg run:"
    dpkg --configure -a || true
fi

say PKG "Refreshing Debian package index…"
if ! apt-get update; then
    die "apt-get update failed. Check that the device has working DNS/network."
fi

say PKG "Installing Git, Python, Node.js, curl and friends…"
if ! apt-get install "${APT_OPTS[@]}" \
    ca-certificates curl git openssh-client xz-utils procps util-linux \
    less nano ripgrep tzdata locales \
    python3 python3-venv python3-pip \
    nodejs npm; then
    die "Could not install the base packages."
fi
ok PKG "Base packages installed."

# --------------------------------------------------------------------- agent
if [ -d "$HERMES_DIR/.git" ]; then
    say HERMES "Hermes checkout found, refreshing it…"
    git -C "$HERMES_DIR" remote set-url origin "$LOADER_REPO" 2>/dev/null || true
    if git -C "$HERMES_DIR" fetch --depth 1 origin "$LOADER_BRANCH"; then
        git -C "$HERMES_DIR" reset --hard FETCH_HEAD || warn HERMES "Could not reset to the fetched revision."
        ok HERMES "Repository updated."
    else
        warn HERMES "Fetch failed — keeping the local checkout."
    fi
else
    say HERMES "Cloning $LOADER_REPO (branch $LOADER_BRANCH)…"
    mkdir -p "$HERMES_DIR"
    # Clear leftovers without removing the bind-mount point itself.
    find "$HERMES_DIR" -mindepth 1 -maxdepth 1 -exec rm -rf {} + 2>/dev/null
    if ! git clone --depth 1 --branch "$LOADER_BRANCH" "$LOADER_REPO" "$HERMES_DIR"; then
        die "Could not clone the Hermes repository."
    fi
    ok HERMES "Repository cloned."
fi

# -------------------------------------------------------------------- python
# Older builds linked out of the cache and PRoot's link() emulation left
# ".l2s." marker files inside the cached packages, which then leaked into
# built wheels. Never reuse such a cache.
for cache in "$UV_CACHE_DIR" "$HOME/.cache/uv" "$GUEST_HOME_STATE/cache/uv"; do
    if find "$cache" -name '.l2s.*' -print -quit 2>/dev/null | grep -q .; then
        warn HERMES "Discarding a uv cache created with hard links:"
        rm -rf "$cache"
    fi
done

ensure_uv || die "Could not install uv."
say HERMES "uv link mode: $UV_LINK_MODE (PRoot cannot hard-link)."
# The checkout exists from here on, so PM's uv environment can be patched.
prepare_guest

if ! venv_python 3.14; then
    warn HERMES "Python 3.14 is unavailable, trying 3.13…"
    if ! venv_python 3.13; then
        warn HERMES "Falling back to the system Python ($(python3 -V 2>&1))."
        python3 -m venv "$HERMES_DIR/.venv" || die "Could not create a virtualenv."
    fi
fi
[ -x "$HERMES_DIR/.venv/bin/python" ] || die "Virtualenv was not created."
ok HERMES "Virtualenv ready: $("$HERMES_DIR/.venv/bin/python" -V 2>&1)."

# Hermes declares ">=3.11,<3.15", but only resolves its runtime dependencies on
# 3.14+: everything in pyproject.toml is marked `python_version >= '3.14'`. An
# older interpreter still installs, it just comes out nearly empty and skips
# the packages the agent needs, so say so instead of failing later at runtime.
PYVER="$(venv_version || true)"
if [ -n "$PYVER" ] && [ "$(printf '%s\n3.14\n' "$PYVER" | sort -V | head -n1)" != "3.14" ]; then
    warn HERMES "Python $PYVER is below the 3.14 Hermes targets: most pinned"
    warn HERMES "dependencies are skipped on it. Press MENU → Reinstall rootfs"
    warn HERMES "if the agent starts but cannot import its providers."
fi

EXTRA="$(profile_extra)"
deps_failed() {
    # A half-written cache is the most common reason a retry fails in exactly
    # the same way, so never keep it after a failure.
    warn HERMES "Discarding the uv cache so that a retry starts clean:"
    rm -rf "$UV_CACHE_DIR" "$HOME/.cache/uv"
    die "Dependency installation failed. See the first error in the log above."
}
if ! install_deps "$EXTRA"; then
    warn HERMES "Dependency install failed — adding build tools and retrying…"
    apt-get install "${APT_OPTS[@]}" build-essential python3-dev libffi-dev libssl-dev pkg-config || true
    if ! install_deps "$EXTRA"; then
        if [ -n "$EXTRA" ]; then
            warn HERMES "Retrying with core dependencies only."
            install_deps "" || deps_failed
        else
            deps_failed
        fi
    fi
fi

if [ ! -x "$HERMES_BIN" ]; then
    warn HERMES "Console script missing, trying a repair install…"
    install_deps "$(profile_extra)" || true
fi
[ -x "$HERMES_BIN" ] || die "Hermes entry point $HERMES_BIN was not created."

# Record the dependency fingerprint used by the updater.
cat "$HERMES_DIR/pyproject.toml" "$HERMES_DIR/uv.lock" 2>/dev/null \
    | sha256sum | cut -d' ' -f1 > "$HERMES_DIR/.venv/.deps-hash"

printf 'installed=%s\nprofile=%s\nrepo=%s\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "${LOADER_PROFILE:-all}" "$LOADER_REPO" \
    > "$HERMES_DIR/.hermes-agent"

clean_uv_cache
ok HERMES "Hermes Agent installed."
printf '\n'
say NEXT "Press SETUP to choose a provider/API key, then START to chat."
