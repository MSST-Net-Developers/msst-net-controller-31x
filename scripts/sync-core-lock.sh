#!/bin/sh
# Point the lock files at the Core checked out next to this repository.
#
#   scripts/sync-core-lock.sh            both workspaces (this crate, compose/rust)
#   scripts/sync-core-lock.sh <dir>...   the given workspaces
#
# Core's crates are path dependencies whose version follows Core's
# releases, so each Core release changes them in Cargo.lock, while CI
# builds with --locked against Core's newest release (or MSST_CORE_REF).
# This updates Core's crates (and adds whatever new dependencies they
# bring); other locked versions stay as they are.  Like any `cargo
# update`, it also fills in entries missing for other reasons, so every
# change it makes is printed: anything besides Core's crates means the
# committed lock file is out of date.
#
# Plain POSIX sh: macOS runners have bash 3.2 (no mapfile).
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
[ $# -gt 0 ] || set -- "$root" "$root/compose/rust"

# This repository's own packages (path packages too, but not Core's);
# compose/rust is absent in the Docker build context.
own=" "
for f in "$root/Cargo.toml" "$root/compose/rust/Cargo.toml"; do
    if [ -f "$f" ]; then
        own="$own$(sed -n '/^\[package\]/,/^\[/s/^name = "\(.*\)"/\1/p' "$f") "
    fi
done

# `name version` of every package in a lock file.
packages() {
    awk '/^name = /{n=$3} /^version = /{print n, $3}' "$1" | tr -d '"' | sort
}

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

for ws in "$@"; do
    [ -f "$ws/Cargo.lock" ] || continue
    # Packages without a `source` line are path packages; Core's are
    # those that are not this repository's.  (Package names have no spaces.)
    args=""
    for p in $(awk '
        /^\[\[package\]\]/ { if (name != "" && !src) print name; name = ""; src = 0 }
        /^name = /         { name = $3; gsub(/"/, "", name) }
        /^source = /       { src = 1 }
        END                { if (name != "" && !src) print name }
    ' "$ws/Cargo.lock"); do
        case "$own" in
            *" $p "*) ;;
            *) args="$args -p $p" ;;
        esac
    done
    [ -n "$args" ] || continue
    packages "$ws/Cargo.lock" > "$tmp/before"
    # $args is a list of `-p <name>`: split on purpose.
    (cd "$ws" && cargo update $args)
    packages "$ws/Cargo.lock" > "$tmp/after"
    if ! cmp -s "$tmp/before" "$tmp/after"; then
        echo "sync-core-lock: $ws/Cargo.lock changed:"
        diff "$tmp/before" "$tmp/after" | grep '^[<>]' || true
    fi
done
