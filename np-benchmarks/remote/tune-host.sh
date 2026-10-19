#!/bin/bash
# Checks that a host can run the benchmark containers and applies the machine-wide settings a load test
# needs. Works on any Linux with Docker (Ubuntu, RHEL family, ...): it installs nothing. Run it as a
# user with sudo, on the server and on the client machine:
#
#   ssh user@host 'bash -s' < np-benchmarks/remote/tune-host.sh
#
# The settings (CPU governor, ephemeral port range, TIME_WAIT reuse, listen backlogs) apply to the host,
# containers with the host network included, and last until reboot. Nothing is changed without sudo.
set -u
ok=1
if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is not installed. Install it first: https://docs.docker.com/engine/install/"; ok=0
elif ! docker info >/dev/null 2>&1; then
  echo "Docker is installed but this user cannot reach it. Start it (sudo systemctl enable --now docker) and"
  echo "add this user to the docker group (sudo usermod -aG docker \$USER, then log in again)."; ok=0
else
  echo "Docker $(docker version --format '{{.Server.Version}}' 2>/dev/null), $(nproc) cores, kernel $(uname -r)"
fi
[ -f /etc/os-release ] && . /etc/os-release && echo "OS: ${PRETTY_NAME:-unknown}"

if command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null || [ "$(id -u)" = 0 ]; then
  S=""; [ "$(id -u)" = 0 ] || S=sudo
  echo performance | $S tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor >/dev/null 2>&1 || echo "note: CPU governor not changed (no cpufreq here: a virtual machine, usually)"
  $S sysctl -q -w net.ipv4.ip_local_port_range="1024 65000" || true
  $S sysctl -q -w net.ipv4.tcp_tw_reuse=1 || true
  $S sysctl -q -w net.core.somaxconn=4096 || true
  $S sysctl -q -w net.ipv4.tcp_max_syn_backlog=4096 || true
  echo "tuned until reboot: port range $(cat /proc/sys/net/ipv4/ip_local_port_range), tw_reuse $(cat /proc/sys/net/ipv4/tcp_tw_reuse), somaxconn $(cat /proc/sys/net/core/somaxconn)"
else
  echo "note: no passwordless sudo, so the host was not tuned; results will be noisier"
fi
# A firewall between the machines would stop the load: the benchmarks use ports 18000-18999.
command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state 2>/dev/null | grep -q running \
  && echo "firewalld is running: open the benchmark ports on the server (sudo firewall-cmd --add-port=18000-18999/tcp)"
[ "$ok" = 1 ] && echo "ready" || { echo "not ready"; exit 1; }
