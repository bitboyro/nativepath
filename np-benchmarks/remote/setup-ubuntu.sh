#!/bin/bash
# Prepares a fresh Ubuntu (22.04 or 24.04) machine for NativePath benchmarks. Run it on both the
# server and the client machine, as a user with sudo:
#
#   ssh user@host 'bash -s' < np-benchmarks/remote/setup-ubuntu.sh
#
# Installs build tools, a JDK 22+ (Temurin; JDK_PACKAGE to choose), Rust, perf, and raises the
# network limits a load test needs. The sysctl and CPU-governor settings last until reboot.
#
# RUNTIME_ONLY=1 installs only what a release bundle needs (a JDK, rsync, openssl, perf), not the
# build tools, Rust or Maven:  ssh user@host 'RUNTIME_ONLY=1 bash -s' < np-benchmarks/remote/setup-ubuntu.sh
set -eu
RUNTIME_ONLY=${RUNTIME_ONLY:-0}
JDK_PACKAGE=${JDK_PACKAGE:-temurin-23-jdk}   # keep in step with np-benchmarks/versions.env

sudo apt-get update -y
if [ "$RUNTIME_ONLY" = 1 ]; then
  sudo apt-get install -y curl wget gpg rsync python3 openssl util-linux
else
  sudo apt-get install -y build-essential pkg-config curl wget gpg rsync git python3 maven openssl util-linux
fi

# perf, for instruction counts per request (the package name follows the kernel; best effort).
sudo apt-get install -y linux-tools-common "linux-tools-$(uname -r)" 2>/dev/null \
  || sudo apt-get install -y linux-tools-generic 2>/dev/null \
  || echo "perf not installed: instruction counts will be unavailable"

# Temurin JDK from Adoptium's apt repository.
if ! java -version 2>&1 | grep -qE 'version "(2[2-9]|[3-9][0-9])'; then
  wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo gpg --dearmor --yes -o /usr/share/keyrings/adoptium.gpg
  echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo "$VERSION_CODENAME") main" \
    | sudo tee /etc/apt/sources.list.d/adoptium.list >/dev/null
  sudo apt-get update -y
  sudo apt-get install -y "$JDK_PACKAGE"
fi

# Rust (user install).
if [ "$RUNTIME_ONLY" != 1 ] && ! command -v cargo >/dev/null 2>&1 && [ ! -x "$HOME/.cargo/bin/cargo" ]; then
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal
fi
[ "$RUNTIME_ONLY" = 1 ] || grep -q 'cargo/env' "$HOME/.bashrc" 2>/dev/null || echo '. "$HOME/.cargo/env"' >> "$HOME/.bashrc"

# Benchmark-friendly settings until reboot.
echo performance | sudo tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor >/dev/null 2>&1 || true
sudo sysctl -q -w kernel.perf_event_paranoid=1 || true
sudo sysctl -q -w net.ipv4.ip_local_port_range="1024 65000" || true
sudo sysctl -q -w net.ipv4.tcp_tw_reuse=1 || true
sudo sysctl -q -w net.core.somaxconn=4096 || true
sudo sysctl -q -w net.ipv4.tcp_max_syn_backlog=4096 || true

echo "ready: $(java -version 2>&1 | head -1); $(rustc --version 2>/dev/null || echo "no rust"); $(nproc) cores"
