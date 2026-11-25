# Setting up machines for the NativePath benchmark

Copy-paste guide. Three roles: your **controller** (laptop or any machine you type on), the **server VM**
(runs the server under test) and the **client VM** (runs the load generator). The VMs only need Docker and
ssh key login; the benchmark itself runs in a container image the VMs pull.

> Numbers you plan to quote come from two Linux VMs, not from Docker on a laptop.

## 0. Fill in once, paste into your shell

```sh
export VM1=user@10.0.0.5          # server VM: ssh login
export VM2=user@10.0.0.6          # client VM: ssh login
export VM1_IP=10.0.0.5            # the address VM2 reaches VM1 on (a private address if they share a network)
export IMAGE=ghcr.io/bitboyro/np-bench:0.1.2
export NP_VER=0.1.2
```

## 1. The controller (once)

You need `bash`, `python3`, `ssh`, `curl` and Docker. No git clone needed.

macOS:
```sh
xcode-select --install            # python3, git, ssh (skip if already installed)
# install Docker Desktop from https://docs.docker.com/desktop/setup/install/mac-install/ and start it
```

Linux (Ubuntu shown; use your package manager otherwise):
```sh
sudo apt-get update && sudo apt-get install -y python3 curl openssh-client
curl -fsSL https://get.docker.com | sudo sh && sudo usermod -aG docker $USER   # then log out and in
```

Download the tooling. Any bundle works on any controller (only the scripts inside are used):
```sh
mkdir -p ~/np-bench && cd ~/np-bench
curl -LO https://github.com/bitboyro/nativepath/releases/download/v$NP_VER/np-$NP_VER-linux-x86_64.tar.gz
curl -L  https://github.com/bitboyro/nativepath/releases/download/v$NP_VER/np-$NP_VER-linux-x86_64.tar.gz.sha256
shasum -a 256 np-$NP_VER-linux-x86_64.tar.gz        # the two hashes must match (Linux: sha256sum)
tar xzf np-$NP_VER-linux-x86_64.tar.gz && cd np-$NP_VER-linux-x86_64
./np-bench list                                      # every experiment and every setting
```

Updating an existing install: do the same into the same `~/np-bench`. Each version unpacks into its own
folder (`np-<version>-linux-x86_64`), so the old one is untouched. Move old results across if you want
them in the new dashboard, then delete the old folder:
```sh
mv ~/np-bench/np-0.1.1-linux-x86_64/np-results/* ~/np-bench/np-0.1.2-linux-x86_64/np-results/ 2>/dev/null || true
rm -rf ~/np-bench/np-0.1.1-linux-x86_64 ~/np-bench/np-0.1.1-linux-x86_64.tar.gz
```
The VMs need no update: they pull the image named by `--image`. After a new version, remove the old
container the last run left there so the new image is used:
```sh
ssh $VM1 'docker rm -f np-bench-node'; ssh $VM2 'docker rm -f np-bench-node'
```

## 2. ssh key login to both VMs (once)

`np-bench` never prompts for a password (ssh `BatchMode`), so each VM must accept your key.

```sh
ssh-keygen -t ed25519                # skip if you already have a key; a passphrase is fine if you use ssh-add
ssh-copy-id $VM1                     # asks for the VM's password this one time
ssh-copy-id $VM2
ssh $VM1 true && ssh $VM2 true && echo "ssh ok"      # must not ask for anything
```

Passphrase on your key: `ssh-add` (macOS: `ssh-add --apple-use-keychain ~/.ssh/id_ed25519`) before running.
Cloud VMs usually have your key installed already. An alias works everywhere a host is expected:
```sh
cat >> ~/.ssh/config <<'EOF'
Host vm1
  HostName 10.0.0.5
  User user
Host vm2
  HostName 10.0.0.6
  User user
EOF
```

## 3. Docker on each VM (once)

Run the block for the VM's distribution, as the login user, on **both** VMs (`ssh $VM1`, then `ssh $VM2`).

Ubuntu / Debian:
```sh
curl -fsSL https://get.docker.com | sudo sh
```

RHEL / Rocky / Alma / CentOS Stream (use `rhel` instead of `centos` in the URL on real RHEL):
```sh
sudo dnf -y install dnf-plugins-core
sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
sudo dnf -y install docker-ce docker-ce-cli containerd.io
sudo systemctl enable --now docker
```

Then on each VM:
```sh
sudo usermod -aG docker $USER        # log out and back in afterwards
docker run --rm hello-world          # must work without sudo
```

Optional, so the host tuning below can run without a password prompt (on each VM):
```sh
echo "$USER ALL=(ALL) NOPASSWD:ALL" | sudo tee /etc/sudoers.d/np-bench
```

## 4. Open the network between the VMs

The load generator on VM2 connects to ports **18000-18999** on VM1. On cloud VMs, allow TCP 18000-18999
from VM2 to VM1 in the security group or firewall. On a host with firewalld (RHEL family), on VM1:
```sh
sudo firewall-cmd --add-port=18000-18999/tcp            # until reboot
sudo firewall-cmd --permanent --add-port=18000-18999/tcp && sudo firewall-cmd --reload   # permanent
```
Check from VM2: `nc -vz <VM1_IP> 22` should succeed (ssh); the benchmark ports answer only during a run.

## 5. Check the hosts and tune them (each time after a reboot)

```sh
./np-bench setup $VM1 $VM2
```

It checks Docker on each VM and tunes the machine (CPU governor, port range, TIME_WAIT reuse, backlogs;
needs passwordless sudo; lasts until reboot). It installs nothing. Without sudo it skips tuning and says so.

## 6. Run

Quick check first: proves the setup works, measures nothing worth quoting.
```sh
./np-bench check --server $VM1 --client $VM2 --server-ip $VM1_IP --image $IMAGE
```

The real run (4 cores each, pinned):
```sh
./np-bench run --server $VM1 --client $VM2 --server-ip $VM1_IP --cores 4 --image $IMAGE
```

Pick experiments and tune settings:
```sh
./np-bench run --server $VM1 --client $VM2 --server-ip $VM1_IP --cores 4 --image $IMAGE \
    --only throughput,ramp,soak --set soak.minutes=120 --set ramp.slo_ms=5
# experiments: preflight memory throughput ramp scan correct client soak   (capacity and db only with --only)
# (db is not part of a two-VM run: it runs on the controller with Docker; see how-to-run-the-benchmark.md)
```

Capacity (how many virtual users each server sustains within an SLA, then overload and recovery). Hours at
the defaults, not part of a normal run; the server gets 1 core and 2 GB:
```sh
./np-bench run --only capacity --server $VM1 --client $VM2 --server-ip $VM1_IP --image $IMAGE \
    --set capacity.variants="engine_java raw_netty raw_tomcat spring_nativepath spring_netty"
# shorter: --set capacity.seconds=120 --set capacity.max_bisect=2    (settings: ./np-bench list)
```

The HTTP client experiment (java.net.http, RestClient, NativePath client) is part of a normal run; alone:
```sh
./np-bench run --only client --server $VM1 --client $VM2 --server-ip $VM1_IP --cores 4 --image $IMAGE
```

A 2-hour soak, alone:
```sh
./np-bench run --only soak --set soak.minutes=120 --server $VM1 --client $VM2 --server-ip $VM1_IP --cores 4 --image $IMAGE
```

## 7. Watch it and read the results

In a second terminal, from the same folder:
```sh
./np-bench serve                     # http://127.0.0.1:8765/  (Live, Results, New run tabs)
```
Or serve it with the run: add `--serve` to the `run` command above.

Results land on the controller in `np-results/<timestamp>-<mode>/`: `events.jsonl` (everything, as it
happened), `results.json` (the summary, safe to share; open it in the dashboard's Results tab), `run.json`
(settings and machines), and one `.log` per experiment. `machines.txt` and `results.json` record what Docker
reports about each VM (kernel, cores, memory) and the image id.

## 8. A single machine (no VMs)

Only Docker needed:
```sh
mkdir -p np-results && docker run --rm --cap-add SYS_PTRACE -v "$PWD/np-results:/work/np-results" $IMAGE check
docker run --rm --cap-add SYS_PTRACE -v "$PWD/np-results:/work/np-results" $IMAGE check --only memory,correct,soak
```
`--cap-add SYS_PTRACE` is what lets the memory scans read the process. Timing from a laptop is wiring only.

## Troubleshooting

| You see | Do |
|---|---|
| `cannot reach … over ssh (BatchMode: key login required)` | section 2: `ssh $VM1 true` must work without a prompt; `ssh-add` for a passphrase key |
| `could not start the container on … (is Docker running there …)` | section 3: Docker running, user in the `docker` group, logged in again |
| `no passwordless sudo, so the host was not tuned` | the sudoers line in section 3, or run the tuning yourself; the check still works |
| `image … is arm64 here but … is x86_64` | use `--image $IMAGE`, a published multi-arch image the VMs pull themselves |
| `could not pull ghcr.io/…` | the VM has no internet or registry access; `docker login ghcr.io` if the package is ever made private |
| load generator cannot connect | section 4: ports 18000-18999 open from VM2 to VM1; `--server-ip` is the address VM2 reaches VM1 on |
| `ABORT: something already listens on port …` | a leftover container: `ssh $VM1 docker rm -f np-bench-node` (and `$VM2`) |
| `machine still busy (… idle)` | something else uses the VM; wait, or `--set global.quiet_idle=0` for a check |
| numbers jump around between rounds | pin cores (`--cores`), keep the VMs otherwise idle, use more rounds (`--set throughput.rounds=5`) |

Where the load generator is the limit, the report and dashboard say "generator-bound" (it used 85% or more of its machine); use a bigger client VM or fewer connections then.

Leftover containers after an interrupted run (safe any time):
```sh
ssh $VM1 'docker rm -f np-bench-node' ; ssh $VM2 'docker rm -f np-bench-node'
```
