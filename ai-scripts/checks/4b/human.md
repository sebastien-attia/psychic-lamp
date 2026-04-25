# Human checks — Phase 4b CI/CD hardening
□ A Sigstore Rekor entry exists for the most recent staging push: `cosign tree ${ACR}.azurecr.io/bff:staging` lists a signature AND an SLSA provenance attestation.
□ `cosign verify --certificate-identity-regexp '...deploy-staging\.yml@.*' --certificate-oidc-issuer 'https://token.actions.githubusercontent.com' ${ACR}.azurecr.io/bff@sha256:<digest>` returns "Verified OK".
□ Branch-protection rules are actually applied on github.com (Settings → Branches → main + staging) with the contexts from `.github/settings.yml` — verify with `gh api repos/${OWNER}/${REPO}/branches/main/protection --jq '.required_status_checks.contexts'`.
□ Production environment still has 1+ required reviewer (carried over from phase 4 — confirm 4b did not remove it).
□ A test PR with a synthetic vulnerable Dockerfile (e.g. `FROM python:3.4` or a known-CVE base) fails the `container-scan` job with a HIGH+CRITICAL Trivy finding.
□ A test PR introducing a fake `AKIA...` key triggers gitleaks AND GitHub native push-protection.
□ First Dependabot PR opens within a week of merging this phase (actions, maven-bff, maven-business-service, or npm).
□ CodeQL "Code scanning" tab shows results for both `java-kotlin` and `javascript-typescript` after the first push to `main` or `staging`.
□ The terraform plan artifact `tfplan-<run_id>` is visible in the deploy run's Artifacts panel and the apply step downloads + applies it (no `terraform plan` re-runs in the apply job).
□ Pre-deploy Dependency-Track gate fired at least once (look for "policy violation" in the workflow log); confirm DT project URL in the gate output matches `vars.DTRACK_PROJECT_UUID_STAGING`.
