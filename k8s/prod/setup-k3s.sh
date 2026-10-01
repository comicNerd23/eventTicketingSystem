#!/usr/bin/env bash
# One-time setup of the prod VM: Oracle Always Free Ampere A1, Ubuntu 24.04, arm64 (ADR-016,
# ADR-021 slice (e3)). Run it on the VM as the default "ubuntu" user:
#
#   curl -fsSL https://raw.githubusercontent.com/comicNerd23/eventTicketingSystem/master/k8s/prod/setup-k3s.sh | bash
#
# Safe to run again. It
#   1. opens TCP 80 and 443 in the host firewall. Oracle's Ubuntu images ship iptables rules that
#      reject everything except SSH, in addition to the VCN security list;
#   2. removes the image's blanket FORWARD reject, which would otherwise drop pod-to-pod traffic;
#   3. installs k3s (with its bundled Traefik) at the version the dev clusters use;
#   4. prints what to copy into the repository's GitHub Actions secrets.
#
# The Kubernetes API (6443) is NOT opened: the deploy job reaches it through an SSH tunnel.
set -euo pipefail

K3S_VERSION="v1.36.4+k3s1"

if [ "$(uname -m)" != "aarch64" ]; then
  echo "WARN: expected an arm64 (aarch64) VM, got $(uname -m) — continuing anyway" >&2
fi

echo ">>> Host firewall: allow TCP 80 and 443"
sudo apt-get update -qq
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq iptables-persistent >/dev/null
for port in 80 443; do
  if ! sudo iptables -C INPUT -p tcp -m state --state NEW --dport "$port" -j ACCEPT 2>/dev/null; then
    # Insert right before the image's first REJECT rule; appended rules would never be reached.
    reject_line=$(sudo iptables -L INPUT --line-numbers -n | awk '$2 == "REJECT" { print $1; exit }')
    if [ -n "$reject_line" ]; then
      sudo iptables -I INPUT "$reject_line" -p tcp -m state --state NEW --dport "$port" -j ACCEPT
    else
      sudo iptables -A INPUT -p tcp -m state --state NEW --dport "$port" -j ACCEPT
    fi
  fi
done

echo ">>> Host firewall: drop the image's blanket FORWARD reject (pod traffic is forwarded)"
while sudo iptables -C FORWARD -j REJECT --reject-with icmp-host-prohibited 2>/dev/null; do
  sudo iptables -D FORWARD -j REJECT --reject-with icmp-host-prohibited
done
sudo netfilter-persistent save >/dev/null

echo ">>> Installing k3s ${K3S_VERSION}"
if ! command -v k3s >/dev/null || ! k3s --version | grep -qF "${K3S_VERSION}"; then
  curl -sfL https://get.k3s.io | INSTALL_K3S_VERSION="${K3S_VERSION}" sh -
fi

echo ">>> Waiting for the node to be Ready"
for _ in $(seq 1 60); do
  if sudo k3s kubectl get nodes --no-headers 2>/dev/null | grep -q " Ready "; then break; fi
  sleep 5
done
sudo k3s kubectl get nodes -o wide

cat <<'EOF'

>>> Done. Next, on your own machine (see docs/runbooks/prod-vm.md):
  - PROD_KUBECONFIG: the output of `sudo cat /etc/rancher/k3s/k3s.yaml` on this VM. Keep
    "server: https://127.0.0.1:6443" as it is: the deploy job tunnels that port over SSH.
  - PROD_SSH_KNOWN_HOSTS: `ssh-keyscan -t ed25519 <public IP>` run from your machine.
EOF
