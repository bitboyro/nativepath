#!/bin/bash
# np-bench in Docker: the one-command way. The only thing it needs on your machine is Docker.
#
#   np-benchmarks/bench.sh check                      quick pass of every experiment, in a container
#   np-benchmarks/bench.sh run --only soak            any np-bench command and options
#   np-benchmarks/bench.sh check --with-db            the database experiment (PostgreSQL as a service)
#   np-benchmarks/bench.sh serve                      the dashboard, on http://127.0.0.1:8765/ (single-machine
#                                                     runs; two-machine runs are started from the command line)
#   np-benchmarks/bench.sh run --server user@vm1 --client user@vm2 --server-ip 10.0.0.5
#                                                     two machines: each runs the same image in a
#                                                     container (they need Docker and ssh key login)
#
# The image is built from this checkout (Rocky Linux 8, JDK, the release bundle) and reused until the
# sources change; NP_NO_BUILD=1 skips the build. NP_IMAGE=ghcr.io/bitboyro/np-bench:<version> uses a
# published image instead (nothing is built; the hosts pull it, which also gets the right CPU
# architecture when your machine and the hosts differ, as an Apple Silicon laptop and x86_64 VMs do).
# Results land in ./np-results.
#
# Numbers from Docker on a laptop prove that things work, not how fast they are: Docker Desktop runs a
# Linux virtual machine. Release numbers come from two Linux hosts (the last form above).
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
. np-benchmarks/versions.env
IMAGE=${NP_IMAGE:-np-bench:local}
COMPOSE="docker compose -f np-benchmarks/docker/compose.yml"
export NP_IMAGE=$IMAGE BASE_IMAGE JDK_MAJOR RUST_VERSION MAVEN_VERSION NP_RESULTS=${NP_RESULTS:-$ROOT/np-results}

command -v docker >/dev/null 2>&1 || { echo "Docker is needed: https://docs.docker.com/get-docker/" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "Docker is installed but not running (start Docker Desktop or the docker service)" >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "Docker Compose v2 is needed (docker compose ...): https://docs.docker.com/compose/install/" >&2; exit 1; }

NP_VERSION=$(cat VERSION 2>/dev/null || git rev-parse --short HEAD 2>/dev/null || echo dev)
export NP_VERSION
case $IMAGE in */*) NP_NO_BUILD=1 ;; esac   # a published image (ghcr.io/...): pulled, not built
if [ "${NP_NO_BUILD:-0}" != 1 ]; then
  echo "== image $IMAGE" >&2
  DOCKER_BUILDKIT=1 $COMPOSE build bench >&2
fi

# Two machines: np-bench runs here and drives containers on the hosts, over ssh. It needs bash,
# python3, ssh and docker on this machine, and nothing else.
remote=0
prev=""
for a in "$@"; do
  case $prev in --server|--client) [ "$a" != local ] && remote=1 ;; esac
  prev=$a
done
if [ "$remote" = 1 ]; then
  exec "$ROOT/np-benchmarks/np-bench" "$@" --runtime docker --image "$IMAGE"
fi

# What the container cannot see about its host.
export NP_HOST_INFO="$(docker info --format 'Docker {{.ServerVersion}}, {{.OperatingSystem}}, kernel {{.KernelVersion}}, {{.Architecture}}, {{.NCPU}} cores, {{.MemTotal}} bytes of memory' 2>/dev/null) | image $(docker image inspect --format '{{.Id}}' "$IMAGE" 2>/dev/null | cut -c1-19)"

mkdir -p "$NP_RESULTS"
run_args=(--rm)
[ -t 0 ] || run_args+=(-T)
[ "$(uname -s)" = Linux ] && run_args+=(--user "$(id -u):$(id -g)")

withdb=0
for a in "$@"; do case $a in --with-db|db|*,db|db,*|*,db,*) withdb=1 ;; esac; done
if [ "$withdb" = 1 ]; then
  $COMPOSE --profile db up -d --wait postgres >&2
  trap '$COMPOSE --profile db down -v >/dev/null 2>&1 || true' EXIT
  run_args+=(-e NP_PG_HOST=postgres -e NP_PG_PORT=5432 -e NP_PG_DIR=/pg)
fi

case "${1:-}" in
  serve) run_args+=(-p "127.0.0.1:${NP_DASHBOARD_PORT:-8765}:8765"); set -- serve --bind 0.0.0.0 --trust-bind --results /work/np-results "${@:2}" ;;
esac
$COMPOSE run "${run_args[@]}" bench "$@"
