# Runbook: Prod VM on Oracle Always Free (ADR-021, slice e3)

The prod VM is set up once. After that, every deploy is the **Deploy prod** workflow with a
release tag. Steps marked **(you)** need your own accounts or keys and are done by hand.

## 1. Create the VM (you, Oracle Cloud console)

1. Sign up for Oracle Cloud Free Tier (needs a card for identity verification) and pick your
   home region. Always Free A1 capacity exists only in the home region.
2. **Compute → Instances → Create instance**:
   - Image: **Canonical Ubuntu 24.04** (the aarch64 build is chosen automatically for A1).
   - Shape: **VM.Standard.A1.Flex, 2 OCPU, 12 GB**. This is the whole Always Free A1 allowance
     since 2026-06-15.
   - Networking: a public subnet with **Assign a public IPv4 address**.
   - SSH keys: paste the public half of the deploy key from step 2, or your personal key and add
     the deploy key later.
   - The boot volume can stay at the default (Always Free covers 200 GB of block storage).
3. **Networking → Virtual cloud networks → (VCN) → Security Lists → Default** → *Add Ingress
   Rule*: source `0.0.0.0/0`, TCP, destination port **80**. Add 443 together with the TLS
   slice. Port 22 is open by default; **do not open 6443**.

If creation fails with "Out of capacity", try again later or in another availability domain of
the home region.

## 2. Deploy key (you, on your machine)

A key used only by GitHub Actions, separate from your personal key:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/ticketing-deploy -C "ticketing deploy" -N ""
```

If the VM was created with your personal key, add the deploy key's public half to
`~/.ssh/authorized_keys` on the VM.

## 3. Install k3s (on the VM)

```bash
ssh ubuntu@<public IP>
curl -fsSL https://raw.githubusercontent.com/comicNerd23/eventTicketingSystem/master/k8s/prod/setup-k3s.sh | bash
```

The script opens 80/443 in the host firewall (Oracle's Ubuntu image rejects everything except
SSH), removes the image's FORWARD reject rule, installs k3s `v1.36.4+k3s1` with Traefik and
waits for the node. It is safe to run again.

## 4. GitHub environment "production" (you)

Run these from the repository on your machine. `gh secret set` reads the value from stdin, so
nothing ends up in your shell history:

```bash
gh api -X PUT repos/comicNerd23/eventTicketingSystem/environments/production >/dev/null

gh secret set PROD_SSH_PRIVATE_KEY --env production < ~/.ssh/ticketing-deploy
ssh-keyscan -t ed25519 <public IP> | gh secret set PROD_SSH_KNOWN_HOSTS --env production
ssh ubuntu@<public IP> sudo cat /etc/rancher/k3s/k3s.yaml | gh secret set PROD_KUBECONFIG --env production
gh secret set DB_PASSWORD --env production        # prompts; use a long random value

gh variable set PROD_SSH_TARGET --env production --body "ubuntu@<public IP>"
gh variable set PROD_BASE_URL --env production --body "http://<public IP>"
gh variable set GATEWAY_CORS_ALLOWED_ORIGINS --env production --body "http://<public IP>"
```

Keep the kubeconfig's `server: https://127.0.0.1:6443`: the workflow tunnels that port over SSH.
It is the k3s admin credential, so treat it like a root password.

## 5. Deploy

```bash
gh workflow run deploy-prod.yml -f tag=sha-1234567     # a tag built by "Release images"
```

The job checks that the tag exists in GHCR, writes the Secret and ConfigMap, applies
`k8s/overlays/prod`, waits for the rollouts and smoke-tests `PROD_BASE_URL`. To roll back, run it
again with an older tag.

## Notes

- `DB_PASSWORD` is set in Postgres only when its volume is first created. To change it later,
  run `ALTER USER` in Postgres as well as updating the secret.
- Reseeding demo data: `seed-events.js --k8s --base-url=http://<IP>`. Its truncate uses the
  *current* kube-context, so point that at prod deliberately, or skip seeding.
