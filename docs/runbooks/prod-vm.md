# Runbook: Production deployment on Oracle Always Free (ADR-021, slice e3)

This runbook covers everything from an empty Oracle account to a running production deployment:

| Part | What | How often | Who |
|---|---|---|---|
| [A](#part-a--server-setup-once) | Oracle account, VM, network, k3s | once | you, in the Oracle console and over SSH |
| [B](#part-b--github-environment-production-once) | GitHub environment `production` with secrets and variables | once (again when the VM changes) | you, with `gh` |
| [C](#part-c--deploy-a-release-every-deploy) | Build images, deploy a tag, verify, roll back | every deploy | you start it, GitHub Actions runs it |
| [D](#part-d--seed-demo-data-optional) | Seed the demo catalog | optional | you, over an SSH tunnel |

How a deploy reaches the VM:

```
GitHub Actions runner ──SSH (port 22)──► VM: tunnel to 127.0.0.1:6443 (k3s API, never public)
        │                                      │
        └── deploy-prod.js ── kubectl apply ───┘   images pulled from ghcr.io (public)
Browser ──HTTP (port 80)──► Oracle security list ──► host firewall ──► Traefik ──► frontend / api-gateway
```

## Before you start

- Run every command in **Git Bash** from the repository root, not in PowerShell. The `<` and `|`
  redirections below assume a POSIX shell.
- Tools: `ssh`, `ssh-keygen`, `ssh-keyscan` and `openssl` ship with Git Bash. You also need the
  GitHub CLI `gh`, logged in to an account with admin rights on the repository.
- If `gh` is installed but Git Bash cannot find it:

  ```bash
  alias gh='"/c/Program Files/GitHub CLI/gh.exe"'
  gh auth status        # expect: Logged in to github.com account <you>
  ```

- Commands use `$IP` for the VM's public IP. Set it once per shell, after step A5:

  ```bash
  IP=<public IP of the VM>
  ```

---

## Part A — Server setup (once)

### A1. Create the Oracle Cloud account

1. Sign up for Oracle Cloud Free Tier. A card is needed for identity verification only.
2. Choose the **home region** carefully: it **cannot be changed later**, and Always Free compute
   runs only in the home region.
3. Stay on the Free Tier. Do **not** upgrade to Pay As You Go, so nothing is ever billed.

### A2. Create the deploy key (your machine)

A key used only for deploys, separate from your personal SSH key. GitHub Actions gets its private
half in Part B.

```bash
ssh-keygen -t ed25519 -f ~/.ssh/ticketing-deploy -C "ticketing deploy" -N ""
cat ~/.ssh/ticketing-deploy.pub     # copy this line for A3
```

### A3. Create the instance (Oracle console)

**Compute → Instances → Create instance**, with these values:

| Field | Value |
|---|---|
| Name | e.g. `ticketing-prod` (becomes the Kubernetes node name) |
| Image | **Canonical Ubuntu 24.04**, the regular image, not "Minimal". The aarch64 build is picked automatically for A1. |
| Shape | **Ampere → VM.Standard.A1.Flex**, **2 OCPU**, **12 GB** memory. This is the whole Always Free A1 allowance since 2026-06-15; larger instances get shut down. The shape must show "Always Free-eligible". |
| Networking | Create a new VCN with a **public subnet** (not private) |
| Public IPv4 | Tick **Assign a public IPv4 address**. If it is greyed out, see A4. |
| SSH keys | **Paste public key**: the `ticketing-deploy.pub` line from A2 |
| Boot volume | Default |

If creation fails with **"Out of capacity"**, pick another availability domain of the home region
or try again later.

### A4. Public IP, if the checkbox was greyed out

The checkbox is only active when the selected subnet is public. If the instance was created
without a public IP:

1. Open the instance → **Attached VNICs** → the primary VNIC.
2. **IPv4 Addresses** → the primary private IP → **⋮ → Edit**.
3. **Public IP type: Ephemeral public IP** → Save.

An ephemeral IP is free and stays the same until the instance is deleted or the IP is removed.
The subnet must still be public (a route to an Internet Gateway); a public IP alone is not enough.

### A5. Open port 80 (Oracle console)

**Networking → Virtual cloud networks → (your VCN) → Security Lists → Default Security List →
Add Ingress Rules**:

| Field | Value |
|---|---|
| Source CIDR | `0.0.0.0/0` |
| IP Protocol | TCP |
| Destination Port Range | `80` |

Port 22 is open by default. Port 443 is added together with the TLS slice. **Never open 6443**:
the Kubernetes API is only reached through the SSH tunnel.

Note the instance's **public IP** from its details page and set `IP=...` in your shell (see
"Before you start").

### A6. Check SSH access

The VM only knows the deploy key, so always pass `-i`:

```bash
ssh -i ~/.ssh/ticketing-deploy ubuntu@$IP 'uname -m; lsb_release -ds; nproc; free -g | head -2'
```

The first connection asks to trust the host key; answer `yes`. Expected:

```
aarch64
Ubuntu 24.04.x LTS
2
Mem:   11 ...        # 12 GB minus kernel reservation
```

### A7. Install k3s

```bash
ssh -i ~/.ssh/ticketing-deploy ubuntu@$IP \
  'curl -fsSL https://raw.githubusercontent.com/comicNerd23/eventTicketingSystem/master/k8s/prod/setup-k3s.sh | bash'
```

`k8s/prod/setup-k3s.sh`:

- opens TCP 80 and 443 in the host firewall (Oracle's Ubuntu image rejects everything except SSH),
- removes the image's blanket FORWARD reject rule (pod traffic is forwarded),
- installs k3s `v1.36.4+k3s1` with Traefik and waits for the node.

It is safe to run again. It ends with the node table and `>>> Done.`:

```
NAME             STATUS   ROLES           ...   VERSION
ticketing-prod   Ready    control-plane   ...   v1.36.4+k3s1
```

### A8. Verify the server from outside

Give Traefik about a minute to start, then:

```bash
ssh -i ~/.ssh/ticketing-deploy ubuntu@$IP sudo kubectl get pods -A     # traefik ... 1/1 Running
curl -s -o /dev/null -w "%{http_code}\n" http://$IP/                      # expect 404
curl -sk --max-time 6 https://$IP:6443/ || echo "6443 closed (good)"     # expect "closed"
```

A **404** on port 80 is correct: it comes from Traefik, and nothing is deployed yet. A timeout
means the security list (A5) or the host firewall (A7) is blocking port 80.

---

## Part B — GitHub environment "production" (once)

The deploy workflow reads these values from the environment `production`
(`.github/workflows/deploy-prod.yml`). Every secret is piped from a file or the VM straight into
`gh`, so none of them appears on screen or in shell history. Each `gh secret set` prints
`✓ Set Actions secret <NAME> for comicNerd23/eventTicketingSystem`.

### B1. Create the environment

```bash
gh api -X PUT repos/comicNerd23/eventTicketingSystem/environments/production > /dev/null && echo OK
```

### B2. `PROD_SSH_PRIVATE_KEY` — the deploy key's private half

```bash
gh secret set PROD_SSH_PRIVATE_KEY --env production < ~/.ssh/ticketing-deploy
```

### B3. `PROD_SSH_KNOWN_HOSTS` — the VM's host key

Pins the VM's identity, so the runner refuses to talk to anything else at that IP.

```bash
ssh-keyscan -t ed25519 $IP | gh secret set PROD_SSH_KNOWN_HOSTS --env production
```

`ssh-keyscan` also prints a `# <IP>:22 SSH-2.0-...` comment line; that is normal.

### B4. `PROD_KUBECONFIG` — the k3s admin credential

```bash
ssh -i ~/.ssh/ticketing-deploy ubuntu@$IP sudo cat /etc/rancher/k3s/k3s.yaml \
  | gh secret set PROD_KUBECONFIG --env production
```

Keep `server: https://127.0.0.1:6443` as it is: the workflow tunnels exactly that port. This file
is full admin access to the cluster, so treat it like a root password.

### B5. `DB_PASSWORD` — the Postgres password

Generate it, and **store it in your password manager first**:

```bash
openssl rand -hex 24
```

Then set it. `read -s` takes the value without echoing it; paste it and press Enter:

```bash
read -rs -p "DB password: " P; echo
printf '%s' "$P" | gh secret set DB_PASSWORD --env production
unset P
```

Don't rely on `gh secret set DB_PASSWORD --env production` prompting by itself: in Git Bash `gh`
often doesn't see an interactive terminal, so no prompt appears and no secret is set.

Hex avoids characters that would need escaping in URLs or YAML. Postgres only takes the password
when its volume is first created; see [Notes](#notes) for changing it later.

### B6. Variables (not secret)

```bash
gh variable set PROD_SSH_TARGET              --env production --body "ubuntu@$IP"
gh variable set PROD_BASE_URL                --env production --body "http://$IP"
gh variable set GATEWAY_CORS_ALLOWED_ORIGINS --env production --body "http://$IP"
```

### B7. Check

```bash
gh secret list   --env production
gh variable list --env production
```

Expected: **4 secrets** (`DB_PASSWORD`, `PROD_KUBECONFIG`, `PROD_SSH_KNOWN_HOSTS`,
`PROD_SSH_PRIVATE_KEY`) and **3 variables** (`GATEWAY_CORS_ALLOWED_ORIGINS`, `PROD_BASE_URL`,
`PROD_SSH_TARGET`).

---

## Part C — Deploy a release (every deploy)

### C1. Pick or build a release tag

Deploys only accept immutable tags: `sha-<7-char commit>` or `vX.Y.Z`. There is no `latest`.

- **Existing images:** every successful **Release images** run has published
  `sha-<its commit>`. List the runs:

  ```bash
  gh run list --workflow release-images.yml --limit 5
  ```

- **New images for the current `master`:** start a release and wait for it (about 3 minutes):

  ```bash
  gh workflow run release-images.yml --ref master
  gh run watch "$(gh run list --workflow release-images.yml --limit 1 --json databaseId -q '.[0].databaseId')"
  git rev-parse --short=7 origin/master        # the tag is sha-<this>
  ```

- **A versioned release:** `git tag v1.0.0 && git push origin v1.0.0` builds `sha-<commit>` and
  `v1.0.0`.

### C2. Run the deploy

```bash
gh workflow run deploy-prod.yml -f tag=sha-1234567
gh run watch "$(gh run list --workflow deploy-prod.yml --limit 1 --json databaseId -q '.[0].databaseId')"
```

Or in the browser: **Actions → Deploy prod → Run workflow**, enter the tag.

The job:

1. writes the deploy key, the pinned host key and the kubeconfig (context renamed to `prod`),
2. opens the SSH tunnel and prints the node (`kubectl --context prod get nodes`),
3. runs `deploy-prod.js --context=prod`, which
   - checks that the tag exists in GHCR for every image,
   - writes the Secret and ConfigMap from `DB_PASSWORD` and `GATEWAY_CORS_ALLOWED_ORIGINS`,
   - applies `k8s/overlays/prod` with the tag and waits for every rollout,
   - smoke-tests `PROD_BASE_URL`, including that `/api/actuator/health` returns 404,
4. closes the tunnel, also on failure.

The first deploy takes longer: the VM pulls every image and Postgres initialises its volume.

### C3. Verify

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://$IP/                     # 200, the frontend
curl -s http://$IP/api/events | head -c 300; echo                        # JSON event page
curl -s -o /dev/null -w "%{http_code}\n" http://$IP/api/actuator/health  # 404, closed on purpose
ssh -i ~/.ssh/ticketing-deploy ubuntu@$IP sudo kubectl -n ticketing get pods
```

Then open `http://<IP>` in the browser. The catalog is empty until Part D.

### C4. Roll back

Run C2 again with the previous tag. Images are immutable, so the older tag is exactly what ran
before.

---

## Part D — Seed demo data (optional)

`seed-events.js --k8s` posts the catalog through the public URL, but first truncates the event
tables with `kubectl exec` in the **current kube-context**. Point it at prod deliberately with a
separate kubeconfig and a local tunnel. Port 16443 avoids clashing with a local Rancher Desktop or
kind API on 6443.

> Not yet run against the real VM; verify the context in step 3 before step 4.

```bash
# 1. A local copy of the kubeconfig, pointing at the tunnel port
ssh -i ~/.ssh/ticketing-deploy ubuntu@$IP sudo cat /etc/rancher/k3s/k3s.yaml > ~/.kube/ticketing-prod.yaml
export KUBECONFIG="$(cygpath -w ~/.kube/ticketing-prod.yaml)"
kubectl config set-cluster default --server=https://127.0.0.1:16443

# 2. Tunnel in the background
ssh -i ~/.ssh/ticketing-deploy -fN -L 16443:127.0.0.1:6443 ubuntu@$IP

# 3. Make sure this is prod
kubectl get nodes                          # expect: ticketing-prod

# 4. Seed
node seed-events.js --k8s --base-url=http://$IP

# 5. Clean up
unset KUBECONFIG
pkill -f "16443:127.0.0.1:6443" || taskkill //F //IM ssh.exe
```

Delete `~/.kube/ticketing-prod.yaml` afterwards if you don't need it again; it is the admin
credential.

---

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| "Assign a public IPv4 address" is greyed out | The subnet is private. Select a public subnet, or add an ephemeral IP afterwards (A4). |
| "Out of capacity" when creating the instance | No free A1 capacity right now. Try another availability domain or later. |
| B7 lists only 3 secrets, `DB_PASSWORD` missing | `gh` didn't prompt in Git Bash. Set it with the `read -rs` pipe from B5. |
| `ssh: Permission denied (publickey)` | `-i ~/.ssh/ticketing-deploy` missing, or the instance was created with a different key: add `ticketing-deploy.pub` to `~/.ssh/authorized_keys` on the VM. |
| `curl http://$IP/` times out | Port 80 blocked: check the ingress rule (A5), then re-run `setup-k3s.sh` (A7). |
| Deploy job fails at "Tunnel" with `Host key verification failed` | The VM was recreated or its IP changed. Redo B3, and B6 for a new IP. |
| Deploy job fails at `kubectl ... get nodes` | Wrong `PROD_KUBECONFIG`, or its `server` was changed. Redo B4 unchanged. |
| Deploy fails with "tag not found" | The tag was never built. Run **Release images** first (C1). |
| Pods crash with authentication errors after `DB_PASSWORD` changed | Postgres kept the old password; see Notes. |

## Notes

- **Changing `DB_PASSWORD`:** Postgres sets it only when its volume is first created. To change it,
  run `ALTER USER ... PASSWORD ...` inside the Postgres pod **and** update the secret (B5), then
  deploy again.
- **New VM or new IP:** redo A6–A8, then B3, B4 and B6.
- **Rotating the deploy key:** create a new key (A2), add its public half to
  `~/.ssh/authorized_keys` on the VM, redo B2, then remove the old line.
- **Not covered yet:** TLS on 443 with a domain (next slice), and Rancher Manager (optional).
