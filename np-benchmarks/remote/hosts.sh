# Machines of a benchmark run: the server under test and the load generator, each "local" (this
# checkout) or an ssh host (user@ip, prepared once with setup-ubuntu.sh). Source it after setting
# ROOT; it sources lib.sh. Used by two_hosts.sh, ab_lanes.sh and server_ramp.sh.
#
# Settings (set them before calling the functions; parse_host_options reads them from options):
#   SERVER, CLIENT   hosts (default local)
#   SERVER_IP        the address the client reaches the server on (default 127.0.0.1)
#   CORES            pin server and client each to cores 0..CORES-1 of their machine (default: no
#                    pinning; on one machine they would share the same cores)
#   BASE_TREE        a local checkout of a second commit, used as tree "base" (A/B runs)
#
# Each host has up to two trees: "head" (this checkout) and "base". Remotely they live in
# ~/nativepath-bench and ~/nativepath-bench-base.
#
# Runtime (NP_RUNTIME):
#   native   the tree is synced to the host with rsync (or a --bundle copied) and runs there directly
#   docker   each host runs the benchmark image (NP_IMAGE) in one long-lived container, started by
#            sync_tree and reached with `docker exec`; the host needs only Docker (and ssh for a remote
#            one). The tree is the bundle inside the image, in /opt/np. np-bench starts it for any
#            remote host.
RUNTIME=${NP_RUNTIME:-native}
NP_IMAGE=${NP_IMAGE:-np-bench:local}
NODE=np-bench-node
NODES_UP=" "

. "$ROOT/np-benchmarks/scripts/lib.sh"
SERVER=${SERVER:-local}
CLIENT=${CLIENT:-local}
SERVER_IP=${SERVER_IP:-127.0.0.1}
CORES=${CORES:-}

# Reads --server, --client, --server-ip and --cores from the front of the arguments; sets ARGS to
# the rest.
parse_host_options() {
  ARGS=()
  while [ $# -gt 0 ]; do
    case $1 in
      --server) SERVER=$2; shift 2 ;;
      --client) CLIENT=$2; shift 2 ;;
      --server-ip) SERVER_IP=$2; shift 2 ;;
      --cores) CORES=$2; shift 2 ;;
      --*) echo "unknown option $1" >&2; exit 2 ;;
      *) ARGS+=("$1"); shift ;;
    esac
  done
  if [ "$SERVER" != local ] && [ "$SERVER_IP" = 127.0.0.1 ]; then
    echo "a remote --server needs --server-ip (the address the client reaches it on)" >&2
    exit 2
  fi
}

# Runs `docker ARGS` on a host: here, or over ssh (arguments quoted for the far shell).
d() { # host docker-args...
  local host=$1; shift
  if [ "$host" = local ]; then docker "$@"; else ssh -o BatchMode=yes "$host" "$(printf '%q ' docker "$@")"; fi
}

# Makes sure the host has the benchmark image and a running container to exec into: sends the image
# when the host lacks this exact one, then starts the container with the host's network (so the
# server and the load generator talk over the real network), the capability the memory scans need
# (SYS_PTRACE: process_vm_readv) and, with CORES, that many cores. A local host keeps the default
# network: both roles then run in the one container.
ensure_node() { # host
  local host=$1 mine theirs
  case "$NODES_UP" in *" $host "*) return 0 ;; esac
  mine=$(docker image inspect --format '{{.Id}}' "$NP_IMAGE" 2>/dev/null || true)
  if [ "$host" != local ]; then
    # The image must be for the host's CPU. A published image (NP_IMAGE=ghcr.io/...) is pulled by the host
    # itself, for its own architecture; a locally built one is sent, when the architectures agree.
    local harch larch
    harch=$(d "$host" info --format '{{.Architecture}}' 2>/dev/null)
    larch=$(docker image inspect --format '{{.Architecture}}' "$NP_IMAGE" 2>/dev/null || true)
    case $harch in x86_64) harch=amd64 ;; aarch64) harch=arm64 ;; esac
    theirs=$(d "$host" image inspect --format '{{.Id}}' "$NP_IMAGE" 2>/dev/null || true)
    if [ -z "$mine" ] || [ "$larch" != "$harch" ]; then
      case $NP_IMAGE in
        */*) d "$host" pull "$NP_IMAGE" > /dev/null || { echo "$host could not pull $NP_IMAGE" >&2; return 1; } ;;
        *) echo "image $NP_IMAGE is ${larch:-missing} here but $host is $harch: use a published image (NP_IMAGE=ghcr.io/bitboyro/np-bench:<version>) or build one for $harch (docker buildx build --platform linux/$harch ..., slow under emulation)" >&2; return 1 ;;
      esac
    elif [ "$mine" != "$theirs" ]; then
      echo "   sending $NP_IMAGE to $host" >&2
      docker save "$NP_IMAGE" | ssh -o BatchMode=yes "$host" docker load > /dev/null
    fi
  elif [ -z "$mine" ]; then
    case $NP_IMAGE in
      */*) docker pull "$NP_IMAGE" > /dev/null || return 1 ;;
      *) echo "image $NP_IMAGE not found on this machine: build it with np-benchmarks/bench.sh, or set NP_IMAGE" >&2; return 1 ;;
    esac
  fi
  d "$host" rm -f "$NODE" > /dev/null 2>&1 || true
  local run=(run -d --name "$NODE" --init --cap-add SYS_PTRACE --ulimit nofile=1048576:1048576 --entrypoint sleep)
  if [ "$host" != local ]; then
    run+=(--network host)
    [ -n "${CORES:-}" ] && run+=(--cpuset-cpus "0-$((CORES - 1))")
  fi
  d "$host" "${run[@]}" "$NP_IMAGE" infinity > /dev/null
  NODES_UP="$NODES_UP$host "
}

# Removes the containers ensure_node started (np-bench calls it on exit).
cleanup_nodes() {
  [ "$RUNTIME" = docker ] || return 0
  local h
  for h in $(printf '%s\n' "$SERVER" "$CLIENT" | sort -u); do d "$h" rm -f "$NODE" > /dev/null 2>&1 || true; done
}

# Copies a file into / out of a host's tree (the path is relative to the tree).
host_put() { # host local-file tree-relative-path
  if [ "$RUNTIME" = docker ]; then d "$1" exec -i "$NODE" sh -c "cat > /opt/np/$3" < "$2"
  elif [ "$1" = local ]; then cp "$2" "$(tree_dir local)/$3"
  else scp -q "$2" "$1:$(tree_dir "$1")/$3"; fi
}
host_get() { # host tree-relative-path local-file
  if [ "$RUNTIME" = docker ]; then d "$1" exec "$NODE" cat "/opt/np/$2" > "$3"
  elif [ "$1" = local ]; then cp "$(tree_dir local)/$2" "$3"
  else scp -q "$1:$(tree_dir "$1")/$2" "$3"; fi
}

tree_dir() { # host [head|base]
  [ "$RUNTIME" = docker ] && { echo /opt/np; return; }
  case "$1:${2:-head}" in
    local:head) echo "$ROOT" ;;
    local:base) echo "$BASE_TREE" ;;
    *:head) echo nativepath-bench ;;
    *:base) echo nativepath-bench-base ;;
  esac
}

# Runs a command on a host, in one of its trees, with cargo and lib.sh available. The script is
# written to a file and run with stdin from /dev/null: bash reading a script from stdin can rewind
# it when a background job starts, and would run the rest twice.
on() { # host command [tree]
  local host=$1 dir
  dir=$(tree_dir "$host" "${3:-head}")
  local script="cd $dir || exit 1
[ -f \"\$HOME/.cargo/env\" ] && . \"\$HOME/.cargo/env\"
. np-benchmarks/scripts/lib.sh
$2"
  if [ "$RUNTIME" = docker ]; then
    d "$host" exec -i "$NODE" bash -c 'f=$(mktemp); cat > "$f"; bash "$f" < /dev/null; rc=$?; rm -f "$f"; exit $rc' <<< "$script"
  elif [ "$host" = local ]; then
    local f rc=0
    f=$(mktemp)
    printf '%s\n' "$script" > "$f"
    bash "$f" < /dev/null || rc=$?
    rm -f "$f"
    return $rc
  else
    ssh -o BatchMode=yes "$host" 'f=$(mktemp); cat > "$f"; bash "$f" < /dev/null; rc=$?; rm -f "$f"; exit $rc' <<< "$script"
  fi
}

sync_tree() { # host [tree]
  if [ "$RUNTIME" = docker ]; then ensure_node "$1"; return; fi
  [ "$1" = local ] && return 0
  local src=$ROOT
  [ "${2:-head}" = base ] && src=$BASE_TREE
  if [ -n "${NP_BUNDLE:-}" ]; then   # a release bundle for the host's platform instead of this tree
    local d; d=$(tree_dir "$1" "${2:-head}")
    scp -q "$NP_BUNDLE" "$1:np-bundle.tgz"
    ssh -o BatchMode=yes "$1" "rm -rf $d && mkdir -p $d && tar xzf np-bundle.tgz --strip-components=1 -C $d && rm -f np-bundle.tgz"
    return 0
  fi
  local keep_target=(--exclude target)
  prebuilt "$src" && keep_target=()   # a bundle's target/ holds the native library and load generator
  rsync -az --delete "${keep_target[@]}" --exclude .git --exclude .claude --exclude docs/results \
    --exclude bench-results --exclude lanes-results "$src/" "$1:$(tree_dir "$1" "${2:-head}")/"
}

# Syncs and builds a tree on the server: the native library, engine_bench, the Java modules and
# their classpath (bench.cp).
prepare_server() { # [tree]
  sync_tree "$SERVER" "${1:-head}"
  on "$SERVER" "[ -f .prebuilt ] || { cargo build -q --release -p np-ffi && cargo build -q --release -p np-http-server --example engine_bench; } \
    && bench_classpath \$PWD > bench.cp" "${1:-head}"
}

prepare_client() {
  [ "$CLIENT" = "$SERVER" ] && [ "$CLIENT" != local ] || sync_tree "$CLIENT"
  on "$CLIENT" "[ -f .prebuilt ] || cargo build -q --release -p np-http-server --example loadgen"
  # The Java client experiment runs its JVM here.
  on "$CLIENT" "[ -f bench.cp ] || bench_classpath \$PWD > bench.cp"
}

# "exec" or "exec_pinned N": how a started process replaces its shell (so its PID is the server's).
exec_prefix() { if [ -n "$CORES" ]; then echo "exec_pinned $CORES"; else echo exec; fi; }

# Starts a server on the server host, listening on all interfaces; prints its PID. The command
# runs in the tree's np-benchmarks/ directory (the JVM finds that tree's native library from there),
# with "\$cp" set to the tree's classpath. Its output goes to <log> in the tree.
start_server() { # tree log env-assignments command
  on "$SERVER" "cd np-benchmarks
cp=\$(cat ../bench.cp)
export BENCH_BIND=0.0.0.0 $3
nohup bash -c \". scripts/lib.sh; ulimit -n 1048576 2>/dev/null || ulimit -n 65536 2>/dev/null; $(exec_prefix) $4\" < /dev/null > ../$2 2>&1 &
echo \$!" "$1"
}

# The server started last, stopped by stop_on_exit if the script ends early.
RUNNING_PID=""

stop_server() { # pid
  RUNNING_PID=""
  on "$SERVER" "kill $1 2>/dev/null; for _ in \$(seq 1 40); do kill -0 $1 2>/dev/null || exit 0; sleep 0.25; done; kill -9 $1 2>/dev/null || true"
}

server_cpu() { on "$SERVER" "cpu_s $1"; }

# Call once: on exit (a failure included) stops a server still running and runs "$@" (cleanup).
stop_on_exit() {
  ON_EXIT=("$@")
  trap '[ -n "$RUNNING_PID" ] && stop_server "$RUNNING_PID"; [ ${#ON_EXIT[@]} -gt 0 ] && "${ON_EXIT[@]}"; true' EXIT
}

server_quiet() { on "$SERVER" "QUIET_IDLE=$QUIET_IDLE QUIET_MAX_WAIT_S=$QUIET_MAX_WAIT_S wait_quiet"; }

require_server_port() { on "$SERVER" "require_free_port $1"; }

# The load generator on the client host (pinned when CORES is set): loadgen arguments, optionally
# preceded by environment assignments (LOADGEN_RATE=...).
loadgen() {
  local envs="" pin=""
  while [ $# -gt 0 ] && [[ $1 =~ ^[A-Z_]+= ]]; do envs="$envs $1"; shift; done
  [ -n "$CORES" ] && pin="pinned $CORES"
  # With NP_TAG set (by the experiment) and np-bench collecting events, the load generator streams
  # one event per second, tagged, over stderr (ssh forwards it).
  [ -n "${NP_TAG:-}" ] && [ -n "${NP_EVENTS:-}" ] && envs="$envs LOADGEN_EVENTS=- LOADGEN_TAG='$NP_TAG'"
  # Thousands of connections need more than the usual 1024 descriptors.
  on "$CLIENT" "ulimit -n 1048576 2>/dev/null || ulimit -n 65536 2>/dev/null; $pin env $envs target/release/examples/loadgen $*"
}

server_url() { echo "http://$SERVER_IP:$1"; }

# Waits until the server answers /hello (up to 40 s).
wait_up() { # port
  on "$CLIENT" "for _ in \$(seq 1 160); do curl -sf -o /dev/null $(server_url "$1")/hello && exit 0; sleep 0.25; done; exit 1"
}

# JSON from the server's /__stats, or null where it has none.
server_stats() { # port
  local s
  s=$(on "$CLIENT" "curl -sf $(server_url "$1")/__stats" 2>/dev/null || true)
  case "$s" in \{*) echo "$s" ;; *) echo null ;; esac
}

# What a host's Docker says about the machine under it (the container sees the same kernel, but
# not the VM it may run in, nor the memory and core limits Docker applies).
docker_host_info() { # host
  d "$1" info --format 'Docker {{.ServerVersion}}, {{.OperatingSystem}}, kernel {{.KernelVersion}}, {{.Architecture}}, {{.NCPU}} cores, {{.MemTotal}} bytes of memory' 2>/dev/null || echo unknown
}

describe_hosts() {
  echo "date: $(date -u +%FT%TZ)  commit: $(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || cat "$ROOT/VERSION" 2>/dev/null || echo unknown)  cores pinned: ${CORES:-none}  runtime: $RUNTIME${NP_IN_CONTAINER:+ (np-bench itself runs in a container)}"
  [ -n "${NP_HOST_INFO:-}" ] && echo "docker host: $NP_HOST_INFO"
  if [ "$RUNTIME" = docker ]; then
    local h
    for h in $(printf '%s\n' "$SERVER" "$CLIENT" | sort -u); do
      echo "docker on $h: $(docker_host_info "$h") | image $(d "$h" image inspect --format '{{.Id}}' "$NP_IMAGE" 2>/dev/null | cut -c1-19)"
    done
  fi
  echo "server ($SERVER, $SERVER_IP): $(on "$SERVER" 'echo "$(uname -srm), $(nproc 2>/dev/null || sysctl -n hw.ncpu) cores, $(java -version 2>&1 | head -1)"')"
  echo "client ($CLIENT): $(on "$CLIENT" 'echo "$(uname -srm), $(nproc 2>/dev/null || sysctl -n hw.ncpu) cores"')"
}

# --- helpers for the experiments that run a TLS server and a background load -------------------

# A certificate valid for the server's address (the test fixtures only cover localhost): made on the
# server host, its certificate copied to the client host and to <out-dir>.
# The command that writes bench-cert.pem and bench-key.pem for the server's address. It uses a config
# file, not -addext: OpenSSL 1.1.1 (RHEL 8 and Ubuntu 20.04) adds the default CA extensions next to an
# -addext one, giving a certificate rustls rejects as having an invalid extension. CA:FALSE and
# serverAuth matter too: rustls refuses a CA certificate as a server certificate.
bench_cert_cmd() {
  printf '%s' "printf '[req]\ndistinguished_name=dn\nx509_extensions=ext\nprompt=no\n[dn]\nCN=bench\n[ext]\nsubjectAltName=IP:$SERVER_IP,DNS:localhost\nbasicConstraints=critical,CA:FALSE\nextendedKeyUsage=serverAuth\n' > bench-cert.cnf && openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 7 -config bench-cert.cnf -keyout bench-key.pem -out bench-cert.pem 2>/dev/null"
}

make_bench_cert() { # out-dir
  local out=$1
  on "$SERVER" "$(bench_cert_cmd)"
  host_get "$SERVER" bench-cert.pem "$out/bench-cert.pem"
  [ "$CLIENT" != "$SERVER" ] && host_put "$CLIENT" "$out/bench-cert.pem" bench-cert.pem
  return 0
}
# The environment that makes a benchmark server (started by start_server) use that certificate.
TLS_SERVER_ENV='BENCH_CERT=$PWD/../bench-cert.pem BENCH_KEY=$PWD/../bench-key.pem'

# Waits until https://SERVER_IP:port/hello answers on the client host (up to 40 s).
wait_up_tls() { # port
  on "$CLIENT" "for _ in \$(seq 1 160); do curl -sf --cacert bench-cert.pem -o /dev/null https://$SERVER_IP:$1/hello && exit 0; sleep 0.25; done; exit 1"
}

# Starts np_sampler.py for a process on the server host; its events stream back on stderr (ssh forwards
# it). Sets SAMPLER_PID, to kill it with.
start_sampler() { # pid role tag-fields stats-url interval
  local args="--pid $1 --role $2 --interval $5 --tag '$3'"
  [ -n "${4:-}" ] && args="$args --stats-url $4 --cacert bench-cert.pem"
  if [ "$RUNTIME" = docker ]; then
    d "$SERVER" exec -i "$NODE" bash -c "cd /opt/np && exec python3 np-benchmarks/scripts/np_sampler.py $args" &
  elif [ "$SERVER" = local ]; then
    (cd "$ROOT" && exec bash -c "exec python3 np-benchmarks/scripts/np_sampler.py $args") &
  else
    ssh -o BatchMode=yes "$SERVER" "cd $(tree_dir "$SERVER") && python3 np-benchmarks/scripts/np_sampler.py $args" &
  fi
  SAMPLER_PID=$!
}

