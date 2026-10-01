# ADR-021: Prod Images, Registry and Secrets

**Status:** Accepted
**Date:** 2026-10-01
**Authors:** Engineering Team

---

## Context

ADR-016 slice (e) covers prod: Secrets, an image registry and a deploy job. Prod is k3s on an
Oracle Always Free **Ampere A1 VM, which is arm64**. Until now every image was built locally
for amd64 and loaded into a dev cluster without a registry (ADR-020).

Facts checked on 2026-10-01:

- **GHCR is currently free, including private images.** The GitHub docs state: "Container image
  storage and bandwidth for the Container registry is currently free." GitHub promises at least
  a month's notice before that changes
  ([GitHub docs](https://docs.github.com/en/billing/concepts/product-billing/github-packages)).
  - The 500 MB / 1 GB allowance of GitHub Free applies to the other GitHub Packages registries,
    not to containers.
  - **ADR-016's statement that private GHCR storage is limited to about 500 MB was wrong** and is
    corrected here.
- **arm64 runners**: `ubuntu-24.04-arm` is a standard GitHub-hosted runner, available in private
  repositories since 2026-01-29
  ([changelog](https://github.blog/changelog/2026-01-29-arm64-standard-runners-are-now-available-in-private-repositories)).
  In public repositories, standard runners are free and have 4 vCPUs.
- **The repository is public.** Its visibility was found to have changed from private.
- **Every base image has an arm64 variant** (checked with `docker manifest inspect`):
  `confluentinc/cp-kafka:7.5.0` and `cp-zookeeper:7.5.0`, `postgres:15-alpine`, `redis:7-alpine`,
  `eclipse-temurin:25-jre`, `maven:3.9-eclipse-temurin-25`, `node:24-alpine` and
  `nginxinc/nginx-unprivileged:1.30-alpine`. Moving Kafka to KRaft is therefore **not** needed for
  arm64. That closes the open question from ADR-018.
- **Oracle halved the Always Free A1 allowance to 2 OCPU / 12 GB on 2026-06-15**
  ([InfoQ](https://www.infoq.com/news/2026/07/oracle-cloud-free-tier-limits/)). That still fits
  the stack, which needs about 3 GB in the dev overlay.

## Options considered

### Order of work
- **e1 images → e2 prod overlay and Secrets → e3 VM and deploy job (chosen).** Each part can be
  verified on its own; e1 and e2 need no VM.
- **The VM first.** Early real arm64 feedback, but everything would wait on the account setup.
- **Everything at once.** This contradicts the testable-slices rule in `docs/plan.md`.

### Registry
- **GHCR, public images (chosen).**
  - The source code is already public, so public images expose nothing new.
  - The cluster needs no `imagePullSecret`, so there is no token that can expire on the VM.
  - GitHub doesn't bill public images.
- **GHCR, private images.** This was chosen first, while the repository was believed to be
  private. It needs a pull secret holding a `read:packages` token.
- **Docker Hub Free.** Only one private repository, and public pulls are rate-limited. It would
  also be another account.

### Multi-arch build
- **Native runners per architecture, then one manifest (chosen).** amd64 builds on
  `ubuntu-latest` and arm64 on `ubuntu-24.04-arm`. Maven never runs under emulation.
- **QEMU emulation** on one runner. Simplest to configure, but Maven under emulation is many
  times slower.
- **arm64 only.** Half the builds, but the registry images could not be tested on the amd64
  dev machine in e2.

### Secrets
- **GitHub Actions secrets, turned into Kubernetes Secrets by the deploy job (chosen).** This
  uses `kubectl create secret … --dry-run=client -o yaml | kubectl apply -f -`. There is no
  controller, and nothing encrypted lives in the repository. It fits one cluster that is only
  deployed from Actions.
- **Sealed Secrets.** GitOps-friendly, but the decryption key lives in the cluster. Losing the VM
  means re-sealing every secret, unless the key is backed up separately.
- **SOPS + age.** Encrypted files in the repository, decrypted in the deploy job. It adds
  another tool and another key to manage.

## Decision (e1)

- **`.github/workflows/release-images.yml`** runs manually (`workflow_dispatch`) or on a `v*`
  tag push. It does not run on every push.
  - A `build` matrix covers 7 targets × {amd64 on `ubuntu-latest`, arm64 on `ubuntu-24.04-arm`}.
    Each job pushes `ghcr.io/comicnerd23/ticketing/<target>:sha-<7>-<arch>`, with
    `provenance: false` and a GitHub Actions layer cache per target and architecture.
  - A `merge` job per target runs `docker buildx imagetools create` to join both into
    **`:sha-<7>`**, plus the Git tag (for example `:v1.0.0`) on a tag push.
- **Immutable tags only:** `sha-<commit>` and release tags, no `latest`. A deploy always names
  exactly what it runs, and a rollback is a redeploy of an older tag.
- The image name stays `ticketing/<target>` with a registry prefix, so the prod overlay can map
  it with kustomize `images: newName/newTag` without touching the base.
- The `org.opencontainers.image.source` label links each package to this repository. That
  gives the workflow's `GITHUB_TOKEN` write access on later runs.

e2 (the prod overlay and Secrets) and e3 (the VM, k3s and the deploy job) follow, using the
decisions above.

## Consequences

**Positive**
- The prod VM pulls native arm64 images; dev machines can pull the same tags as amd64.
- Release tags can be traced to a commit, and older tags remain available for rollback.
- No registry cost and no pull secret.

**Negative / accepted**
- **14 build jobs per release**, plus 7 merge jobs. On a public repository that costs no minutes,
  but a release takes several minutes of wall time.
- **The per-arch tags (`-amd64`, `-arm64`) stay in the registry** next to the multi-arch tag. A
  push-by-digest flow would avoid them, at the cost of passing digests between jobs as artifacts.
- **Package visibility is a GHCR setting, not a workflow setting.** If the first push creates
  the packages as private, they have to be switched to public once in the package settings.
- **GHCR's free status can change**, with at least a month's notice. Images could then move to
  another registry by changing the `images:` mapping.
