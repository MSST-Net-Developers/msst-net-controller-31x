#!/usr/bin/env bash
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
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
(( $# )) || set -- "$root" "$root/compose/rust"

# This repository's own packages (path packages too, but not Core's).
# (compose/rust is absent in the Docker build context.)
own=" $(for f in "$root/Cargo.toml" "$root/compose/rust/Cargo.toml"; do
    if [[ -f "$f" ]]; then sed -n '/^\[package\]/,/^\[/s/^name = "\(.*\)"/\1/p' "$f"; fi
done | tr '\n' ' ')"

# `name version` of every package in a lock file.
packages() { awk '/^name = /{n=$3} /^version = /{print n, $3}' "$1" | tr -d '"' | sort; }

for ws in "$@"; do
    [[ -f "$ws/Cargo.lock" ]] || continue
    # Packages without a `source` line are path packages.
    mapfile -t pkgs < <(awk '
        /^\[\[package\]\]/ { if (name != "" && !src) print name; name = ""; src = 0 }
        /^name = /         { name = $3; gsub(/"/, "", name) }
        /^source = /       { src = 1 }
        END                { if (name != "" && !src) print name }
    ' "$ws/Cargo.lock")
    args=()
    for p in "${pkgs[@]}"; do
        [[ "$own" == *" $p "* ]] || args+=(-p "$p")
    done
    (( ${#args[@]} )) || continue
    before=$(packages "$ws/Cargo.lock")
    (cd "$ws" && cargo update "${args[@]}")
    changes=$(diff <(echo "$before") <(packages "$ws/Cargo.lock") | grep '^[<>]' || true)
    if [[ -n "$changes" ]]; then
        echo "sync-core-lock: $ws/Cargo.lock changed:"
        echo "$changes"
    fi
done
