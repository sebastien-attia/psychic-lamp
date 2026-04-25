# Contributing to the Boat App

This document explains how branches, PRs, and deployments work in this
repository. The CI/CD pipeline is documented in `.github/ENVIRONMENTS.md`.

---

## Branch strategy

| Branch        | Purpose                                                      | Deploys to               |
|---------------|--------------------------------------------------------------|--------------------------|
| `main`        | Stable, release-eligible code. Source of release tags.       | nothing (release-only)   |
| `staging`     | Pre-production verification.                                 | staging environment, automatic |
| `feature/**`  | All work in progress.                                        | nothing — CI only        |

The promotion path is:

```
feature/<topic>  ── PR ──►  main  ── merge or cherry-pick ──►  staging
                                                                   │
                                                                   ▼
                                                       Auto-deploys to staging
```

Production is reached via a separate, deliberate step:

```
main  ──  GitHub Release (tag vX.Y.Z)  ──►  Auto-deploys to production
                                            (after manual approval)
```

You should never push directly to `main` or `staging`. The
bootstrap-installed branch protection (`./ai-scripts/00d-bootstrap-azure.sh`)
requires PRs.

---

## Pull request requirements

- **CI must pass.** `.github/workflows/ci.yml` runs on every PR. The
  `lint`, `sca-scan`, `build-bff`, `build-business-service`,
  `build-frontend`, `docker-build`, and `e2e-tests` jobs must all be
  green before merge.
- **One approval.** Repository settings require at least one reviewer to
  approve before the *Merge* button is available.
- **Conventional commit titles.** PR titles (and individual commits)
  follow the `<type>(<scope>): <subject>` pattern. Allowed types:
  - `feat`     — new user-visible behaviour
  - `fix`      — bug fix
  - `chore`    — non-functional repo housekeeping
  - `docs`     — documentation only
  - `infra`    — infrastructure / build / CI tooling
  - `test`     — new or changed tests, no production-code changes
  - `refactor` — refactor with no behaviour change
- **Explain *why*.** Commit messages should describe the motivation, not
  just the diff. The diff is right there; the reasoning isn't.
- **Atomic commits.** Each commit should leave the repo in a working
  state. Use `git rebase -i` to clean up before opening the PR.

If your change touches `infra/terraform/**`, the
`terraform-plan.yml` workflow will post a plan diff on the PR.
Read it carefully — the plan is the contract for what `terraform
apply` will do once the change reaches `staging` or a release.

---

## How staging works

1. Open a PR from `feature/<topic>` to `main`.
2. After review + merge, cherry-pick or fast-forward `main` onto
   `staging`:
   ```bash
   git checkout staging
   git merge --ff-only main
   git push origin staging
   ```
3. `deploy-staging.yml` triggers automatically:
   - Re-runs the full CI suite.
   - Builds and pushes `bff` and `business-service` images to ACR with
     tags `staging` and `staging-<short-sha>`.
   - Runs `terraform apply` against the staging environment, pinning
     the Container App image tag to the new short-SHA tag.
   - Runs Ansible to migrate the database, reconcile the Keycloak
     realm, and verify health.
   - Smoke-tests the staging BFF FQDN.
   - Uploads CycloneDX BOMs for both modules to Dependency-Track.

No manual approval required — staging is the safety net.

---

## How production works

Production deploys are triggered by **publishing a GitHub Release**, never
by a branch push.

1. Confirm the change is healthy on `staging` (smoke checks green,
   manual exploratory testing if applicable).
2. Open the repository on GitHub → **Releases** → **Draft a new release**.
3. Choose a tag like `v1.2.0` (use `vMAJOR.MINOR.PATCH`); target `main`.
4. Write the release notes (or click *Generate release notes*).
5. Click **Publish release**.

`deploy-production.yml` then runs. **A required reviewer must approve
the deployment** from the run's *Environments* tab — this is the
single line of defence against a fat-finger release.

After approval, the workflow:

- Checks out the tag (not `main`-tip — the tag is the source of truth).
- Re-runs the full CI suite from the tagged commit.
- Builds and pushes images with tags `<release-tag>`, `latest`, and
  `production`.
- Applies Terraform against the production environment with the release
  tag pinned to the Container App revision.
- Runs Ansible against the production inventory.
- Smoke-tests the production BFF FQDN.
- Uploads BOMs to the production Dependency-Track project, tagged with
  the release version.
- Appends a "Deployed at <timestamp>" line to the release body.

If anything fails after the images are pushed, you can re-trigger the
release deploy from `Actions → Deploy to Production → Re-run failed
jobs` once the underlying issue is fixed.

---

## Local development

See `CLAUDE.md` (project root) for the full architecture overview and the
two main local workflows:

- `docker compose -f docker-compose.dev.yml up` — fast iteration mode
  (Postgres + business-service in `dev` profile, no auth, no Keycloak).
  Frontend with `cd frontend && npm run dev`.
- `docker compose up` — full local-intg stack (BFF + business-service +
  Postgres + Keycloak + keycloak-config sidecar). Frontend with
  `cd frontend && npm run dev:intg`.

Before opening a PR, run the project's verification commands:

```bash
cd bff             && ./mvnw verify
cd business-service && ./mvnw verify
cd frontend        && npm ci && npm run type-check && npm run test && npm run build
```

The same commands run in CI; running them locally first short-circuits
slow CI failure loops.
