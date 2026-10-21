# MSST-Net 3.1 on Docker

Core and the WebUI Controller run as two containers on a Linux host
(`docker/docker-compose.yml`, the layout the installer uses).  They share
`./run` for Core's IPC socket; nothing else connects them.

## How the WebUI reaches Core

- **Socket.**  Core, given `CORE_SOCKET_PATH=/run/msst/core.sock` (3.0.x
  name), listens on `/run/msst/ipc.sock`; the WebUI, given
  `CORE_SOCKET=/run/msst/core.sock`, connects there (`MSST_IPC_PATH` names
  the socket directly instead).  Share the *directory*, not the socket file:
  Core re-creates the socket when it restarts.
- **Permission.**  Core decides each connection's role from the peer's
  UID: root, Core's own user and members of the `msst-net` group are admin.
  Both containers run as root in the host's user namespace, so the WebUI is
  admin.  A non-root WebUI must use a UID that is in `msst-net` *in the
  Core container's* group database; `--group-add` does not help.
- **Not supported:** rootless Docker, user-namespace remapping and rootless
  Podman (container root is a subordinate UID on the host, which Core
  refuses), and Docker Desktop on macOS / Windows (containers run in a VM).

## WebUI container

| Setting | Purpose |
|---|---|
| `ADMIN_PASSWORD` or `PASSWORD_FILE` (default `/run/secrets/admin_password`) | Access password, read on the first start only (when `passwd` doesn't exist).  Without either and without a terminal, the WebUI exits with an explanation. |
| `MSST_WEBUI_CONFIG_DIR` (image default `/var/lib/msst-webui`) | `passwd`, `config.toml` (port, IP allowlist), `tls-cert.pem` / `tls-key.pem`.  Mount a volume. |
| `TLS_CERT_FILE` + `TLS_KEY_FILE` | Your own PEM certificate and key.  Otherwise a self-signed certificate (for `localhost`) is generated once and kept in the config directory. |
| `WEBUI_BIND` | Listen address (default `0.0.0.0:<port from config.toml>`, port 18080). |
| `CORE_SOCKET` / `MSST_IPC_PATH` | Core's socket, see above. |

- **Host networking** (`network_mode: host`) is recommended.  With published
  ports, connections Docker proxies (from the host's own loopback, or IPv6
  to an IPv4-only container) arrive from the bridge gateway, e.g.
  `172.17.0.1`, which the default allowlist (`172.16.0.0/12`) admits — the
  allowlist could no longer tell clients apart.
- **MSST-Net's virtual network** (`172.22.0.0/16`, `fd22:2222:2222:2222::/64`)
  is refused even though the default `172.16.0.0/12` / `fc00::/7` cover it;
  an allowlist entry inside it (e.g. `172.22.0.0/16`) admits it.
- **Health check:** `msst-net-webui healthcheck` (the image's `HEALTHCHECK`)
  requests `GET /healthz` from the local listener.  `/healthz` needs no
  session, is exempt from the allowlist and reveals nothing but "ok".
- **Stopping:** SIGTERM stops the WebUI within ~5 s (open requests may
  finish).

## Building the WebUI image

```sh
(cd compose && ./gradlew :webApp:wasmJsBrowserDistribution)   # web frontend first
docker buildx build -f docker/webui/Dockerfile \
    --build-context core=../msst-net-core-31x -t abjust/msstnet31x-webui .
```

The frontend is built outside Docker because the Gradle build configures the
Android modules too (it needs the Android SDK).  The Rust build runs in
`rust:1.90-bookworm`, so the binary matches the `debian:bookworm-slim`
runtime.

## Core container

Built from Core's repository (`Dockerfile` there:
`docker buildx build -t abjust/msstnet31x-core .` in `msst-net-core-31x`).

- `NET_ADMIN`, `/dev/net/tun`, host networking (as in 3.0.x);
- the image has `iproute2` and `iptables` (Core runs `ip`, and without
  systemd-resolved redirects DNS with `iptables`);
- `CORE_MODE=container` (set by the image) keeps `core.toml` and the data
  directory (identity, caches) in the `/var/lib/msst` volume, so a
  re-created container keeps its device identity; a 3.0.x volume is used
  as is.  A 3.1 container started before that change kept its files under
  `/root`; Core keeps using them while they exist there and the volume has
  none.
