<task>
  <project_conventions>
    Before declaring this phase done, you MUST:

    1. **Code review.** Invoke the `@code-reviewer` subagent on every workflow
       file you wrote or edited (every `.github/workflows/*.yml`, every
       `.github/actions/*/action.yml`, `.github/settings.yml`,
       `.github/dependabot.yml`, `.gitleaks.toml`, the edited
       `ai-scripts/00d-bootstrap-azure.sh`). Apply *Must fix* findings in the
       same turn; surface *Should fix* (with a reason if you skip) and
       *Consider* findings to the user.
    2. **Documentation.** Every new composite action's `action.yml` carries a
       top-of-file `# What this action does / Who calls it` comment. Every new
       job has a one-line YAML comment stating its purpose. Update
       `.github/ENVIRONMENTS.md` with the cosign verify command, the storage-
       cost note, and the GHAS-fallback note from this prompt.
    3. **Self-heal.** If `.claude/agents/code-reviewer.md` is missing or
       `CLAUDE.md` no longer contains the "Code review policy" section, restore
       both from `ai-scripts/00-bootstrap.sh` before proceeding.

    These are non-negotiable per CLAUDE.md › Project conventions.
  </project_conventions>

  <role>You are a senior DevSecOps engineer hardening an existing GitHub
  Actions → Azure pipeline against current state-of-the-art: SLSA Build L3
  provenance, Sigstore keyless signing, action SHA-pinning, container image
  scanning, secret scanning, CodeQL, Terraform plan-as-artifact, deploy
  concurrency control, and codified branch protection.</role>

  <context>
    <prereq>
      Phase 4 (`ai-scripts/04-cicd.md`) has been run. The repo contains
      `.github/workflows/{ci,deploy-staging,deploy-production,terraform-plan}.yml`
      and Azure OIDC is provisioned (`ai-scripts/00d-bootstrap-azure.sh`).
    </prereq>
    <scope>
      EDIT the existing workflows in place. Do NOT rename them, do NOT create
      a parallel hardened-* set. The diff must be reviewable by a human
      against the phase-4 baseline.
    </scope>
    <non-goals>
      - Changing the trigger model (staging on push, prod on Release) — out of scope.
      - Migrating to Azure Pipelines — out of scope.
      - Adding new Azure resources — handled by 02c2 if needed.
      - DAST / load testing / OpenTelemetry — separate phases.
    </non-goals>
    <repo-visibility-note>
      CodeQL is the source-SAST default in this prompt. If the repo is
      PRIVATE and the org does NOT have GitHub Advanced Security, CodeQL
      runs will fail with HTTP 403. Detection (run before generating
      `codeql.yml`):

        gh api "/repos/${OWNER}/${REPO}" \
          --jq '.private and ((.security_and_analysis.advanced_security.status // "disabled") != "enabled")'

      If true → emit a `semgrep` job in `ci.yml` instead, using
      `returntocorp/semgrep-action` with `config: p/owasp-top-ten` (same
      SARIF upload pattern, same job name in branch protection). Document
      the fallback in `.github/ENVIRONMENTS.md`.
    </repo-visibility-note>
  </context>

  <instructions>

    <step order="1" name="SHA-pin every action">
      Replace every `uses: <owner>/<action>@<tag>` with
      `uses: <owner>/<action>@<sha> # <tag>` across:
        - `.github/workflows/ci.yml`
        - `.github/workflows/deploy-staging.yml`
        - `.github/workflows/deploy-production.yml`
        - `.github/workflows/terraform-plan.yml`
        - every `action.yml` under `.github/actions/`

      Use `gh api` to resolve each SHA — DO NOT guess. Example:

        gh api repos/azure/login/git/refs/tags/v2 --jq '.object.sha'
        gh api repos/google/osv-scanner-action/git/refs/tags/v2.3.5 --jq '.object.sha'

      Some actions tag against an annotated tag object — if the resolver
      returns a tag SHA, follow it once more:

        sha=$(gh api repos/${o}/${r}/git/refs/tags/${t} --jq '.object.sha')
        type=$(gh api repos/${o}/${r}/git/tags/${sha} --jq '.object.type' 2>/dev/null || echo commit)
        if [ "${type}" = commit ]; then
          target=$(gh api repos/${o}/${r}/git/tags/${sha} --jq '.object.sha')
          sha=${target}
        fi

      First-party actions (`actions/checkout`, `actions/setup-java`,
      `actions/setup-node`, `actions/upload-artifact`,
      `actions/download-artifact`, `actions/cache`,
      `actions/attest-build-provenance`) get the same treatment — no
      exemption. Keep the human-readable tag as a trailing `# vX.Y.Z`
      comment so Dependabot's `package-ecosystem: github-actions` updater
      can still bump them.
    </step>

    <step order="2" name="Concurrency control on deploys">
      Add to `.github/workflows/deploy-staging.yml` at workflow level
      (top of the file, peer to `on:` and `permissions:`):

        concurrency:
          group: deploy-staging
          cancel-in-progress: false   # never cancel a half-applied terraform

      Add to `.github/workflows/deploy-production.yml`:

        concurrency:
          group: deploy-production-${{ github.event.release.tag_name }}
          cancel-in-progress: false

      Rationale: prevents two pushes to staging from racing a
      `terraform apply` against the same remote state. cancel-in-progress
      is `false` because cancelling mid-apply leaves Azure inconsistent.
    </step>

    <step order="3" name="Container image signing + SLSA provenance (cosign keyless)">
      In `deploy-staging.yml` and `deploy-production.yml`, after the
      `docker push` step:

      1. Job-level `permissions:` add `attestations: write` (the existing
         `id-token: write` is already present and is the OIDC token cosign
         uses).
      2. Install cosign:
           - uses: sigstore/cosign-installer@<sha> # v3.7.0
      3. Capture the IMAGE DIGEST from `docker push` output (NOT the tag —
         tag-signing is an anti-pattern):
           - id: push
             run: |
               docker push ${REGISTRY}/${IMAGE}:${TAG}
               digest=$(docker inspect --format='{{index .RepoDigests 0}}' ${REGISTRY}/${IMAGE}:${TAG} | cut -d@ -f2)
               echo "digest=${digest}" >> "$GITHUB_OUTPUT"
      4. Keyless sign each image (BFF + business-service) using the
         workflow OIDC identity — signature is recorded in the public
         Sigstore Rekor transparency log:
           - run: cosign sign --yes ${REGISTRY}/${IMAGE}@${{ steps.push.outputs.digest }}
      5. Generate SLSA Build L3 provenance attestation, push to ACR:
           - uses: actions/attest-build-provenance@<sha> # v2.1.0
             with:
               subject-name: ${{ env.REGISTRY }}/${{ env.IMAGE }}
               subject-digest: ${{ steps.push.outputs.digest }}
               push-to-registry: true

      Document in `.github/ENVIRONMENTS.md` how to verify locally:

        cosign verify \
          --certificate-identity-regexp 'https://github\.com/<org>/<repo>/\.github/workflows/deploy-(staging|production)\.yml@.*' \
          --certificate-oidc-issuer 'https://token.actions.githubusercontent.com' \
          ${REGISTRY}/${IMAGE}@${DIGEST}

      Also add a one-line note: *"cosign signatures + SLSA provenance are
      stored as additional OCI artifacts in ACR (~5–10 KB per image). They
      do not affect Container Apps pull behaviour."*
    </step>

    <step order="4" name="Container vulnerability scan (Trivy)">
      In `ci.yml`, add a `container-scan` job depending on `docker-build`:

        container-scan:
          # Trivy SARIF on built images, fails HIGH+CRITICAL with fix available.
          needs: [docker-build]
          permissions:
            contents: read
            security-events: write
          runs-on: ubuntu-latest
          strategy:
            fail-fast: false
            matrix:
              image: [bff, business-service]
          steps:
            - uses: actions/checkout@<sha> # v4
            - uses: aquasecurity/trivy-action@<sha> # 0.28.0
              with:
                image-ref: 'local/${{ matrix.image }}:${{ github.sha }}'
                format: sarif
                output: 'trivy-${{ matrix.image }}.sarif'
                severity: 'HIGH,CRITICAL'
                exit-code: '1'
                ignore-unfixed: true
            - uses: github/codeql-action/upload-sarif@<sha> # v3
              if: always()   # upload SARIF even if Trivy failed the job
              with:
                sarif_file: 'trivy-${{ matrix.image }}.sarif'
                category: 'trivy-${{ matrix.image }}'

      `ignore-unfixed: true` avoids blocking on upstream-unfixable findings
      (still logged in the Security tab via SARIF). Job FAILS the run on
      HIGH+CRITICAL with a fix available — same bar as OSV-Scanner on
      source dependencies.
    </step>

    <step order="5" name="CodeQL for Java + JS/TS">
      Create `.github/workflows/codeql.yml`:

        name: CodeQL
        on:
          push:
            branches: [main, staging]
          pull_request:
            branches: [main, staging]
          schedule:
            - cron: '17 4 * * 1'   # weekly Monday 04:17 UTC

        permissions:
          security-events: write
          contents: read
          actions: read

        jobs:
          analyze:
            name: analyze (${{ matrix.language }})
            runs-on: ubuntu-latest
            strategy:
              fail-fast: false
              matrix:
                language: [java-kotlin, javascript-typescript]
            steps:
              - uses: actions/checkout@<sha> # v4
              - uses: github/codeql-action/init@<sha> # v3
                with:
                  languages: ${{ matrix.language }}
                  queries: security-extended,security-and-quality
              - if: matrix.language == 'java-kotlin'
                uses: ./.github/actions/setup-java
              - if: matrix.language == 'java-kotlin'
                run: |
                  cd bff && ./mvnw -B -DskipTests verify
                  cd ../business-service && ./mvnw -B -DskipTests verify
              - uses: github/codeql-action/analyze@<sha> # v3
                with:
                  category: '/language:${{ matrix.language }}'

      Findings appear in the Security tab. The two contexts
      (`CodeQL / analyze (java-kotlin)`,
      `CodeQL / analyze (javascript-typescript)`) join the required-checks
      list (step 9).

      If the visibility check (see `<repo-visibility-note>`) flagged
      private-without-GHAS: skip this file and add a `semgrep` job to
      `ci.yml` instead, with the SAME job name `semgrep` so branch
      protection can require it. Document the fork in `ENVIRONMENTS.md`.
    </step>

    <step order="6" name="Secret scanning (gitleaks)">
      In `ci.yml`, add `secret-scan` job (parallel with `lint`, `sca-scan`):

        secret-scan:
          # gitleaks: scan full history on every push/PR for non-format-known secrets.
          runs-on: ubuntu-latest
          steps:
            - uses: actions/checkout@<sha> # v4
              with:
                fetch-depth: 0   # gitleaks needs full history
            - uses: gitleaks/gitleaks-action@<sha> # v2.3.9
              env:
                GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
                # GITLEAKS_LICENSE only required for org repos (not personal).
                GITLEAKS_LICENSE: ${{ secrets.GITLEAKS_LICENSE }}

      Commit a minimal `.gitleaks.toml` allowlisting:
        - `.env.example` placeholder values (`changeme`, `demo`, `demo123`)
        - `infra/docker/keys/*.example` PEM blocks (not real keys)
        - openapi-generator output paths
          (`bff/src/main/java/.../adapter/in/web/dto/generated/`,
           `bff/src/main/java/.../adapter/out/client/generated/`,
           `business-service/src/main/java/.../adapter/in/web/generated/`,
           `business-service/src/main/java/.../adapter/in/web/dto/generated/`,
           `frontend/src/services/api-client/generated/`)

      GitHub native push-protection covers known token formats (AWS, GCP,
      Stripe, …); gitleaks catches custom keys (BFF signing key, etc).
      Both layers are kept.
    </step>

    <step order="7" name="Terraform plan-as-artifact">
      In `deploy-staging.yml` and `deploy-production.yml`, split the
      existing single Terraform step into TWO jobs:

        plan:
          runs-on: <vnet-runner>
          steps:
            - terraform init -backend-config=...
            - run: |
                terraform plan -out=tfplan.binary -input=false \
                  -var bff_image_tag=$BFF_TAG \
                  -var business_service_image_tag=$BS_TAG
                terraform show -json tfplan.binary > tfplan.json
            - uses: actions/upload-artifact@<sha> # v4
              with:
                name: tfplan-${{ github.run_id }}
                path: |
                  infra/terraform/environments/${{ env.ENV }}/tfplan.binary
                  infra/terraform/environments/${{ env.ENV }}/tfplan.json
                retention-days: 90

        apply:
          needs: plan
          environment: staging   # or production (gates approval here)
          runs-on: <vnet-runner>
          steps:
            - uses: actions/download-artifact@<sha> # v4
              with: { name: tfplan-${{ github.run_id }} }
            - terraform init ...
            - run: terraform apply -input=false -auto-approve tfplan.binary

      The apply runs the EXACT plan that was reviewed — no re-plan inside
      the apply job. Production-environment approval gate sits BETWEEN
      plan and apply (it currently sits before everything).

      Update `terraform-plan.yml` (PR workflow) to upload the same
      artifact and post a summary table. Share a composite action
      `.github/actions/terraform-plan-summary/action.yml` between both
      files (renders `tfplan.json` as a markdown summary).
    </step>

    <step order="8" name="Dependency-Track as gate, not just receipt">
      DT upload currently happens AFTER deploy. Add a pre-deploy GATE
      that runs from GitHub-hosted runners (DT is publicly reachable):

      1. After `build-bff` / `build-business-service` (in `ci.yml`),
         upload BOM to a staging DT project.
      2. Poll `/api/v1/violation/project/{uuid}?suppressed=false` for
         policy violations at FAIL severity (configurable in DT).
      3. Fail the workflow if any FAIL-severity violations are returned.
      4. Post-deploy upload still happens — it tags the deployed version.
         The pre-deploy gate ensures we don't deploy a known-violating BOM.

      Encapsulate in `.github/actions/dtrack-gate/action.yml`:

        # What: uploads a CycloneDX BOM to Dependency-Track and polls until
        #       the project is analysed; fails the step on policy violations
        #       at the configured severity floor.
        # Who calls it: ci.yml (gate), deploy-*.yml (post-deploy receipt).
        name: 'Dependency-Track gate'
        inputs:
          dtrack-url:        { required: true }
          dtrack-api-key:    { required: true }
          project-uuid:      { required: true }
          bom-path:          { required: true }
          poll-timeout-sec:  { required: false, default: '120' }
          fail-on:           { required: false, default: 'FAIL' }
        runs:
          using: composite
          steps:
            - shell: bash
              env:
                DTRACK_URL: ${{ inputs.dtrack-url }}
                DTRACK_API_KEY: ${{ inputs.dtrack-api-key }}
                PROJECT_UUID: ${{ inputs.project-uuid }}
                BOM_PATH: ${{ inputs.bom-path }}
                TIMEOUT: ${{ inputs.poll-timeout-sec }}
                FAIL_ON: ${{ inputs.fail-on }}
              run: |
                # 1. Upload BOM
                curl -fsSL -X POST "${DTRACK_URL}/api/v1/bom" \
                  -H "X-Api-Key: ${DTRACK_API_KEY}" \
                  -F "project=${PROJECT_UUID}" \
                  -F "bom=@${BOM_PATH}"
                # 2. Wait for analysis (project.lastBomImport flips when done)
                deadline=$(( $(date +%s) + TIMEOUT ))
                while [ "$(date +%s)" -lt "${deadline}" ]; do
                  state=$(curl -fsSL "${DTRACK_URL}/api/v1/project/${PROJECT_UUID}" \
                    -H "X-Api-Key: ${DTRACK_API_KEY}" \
                    | jq -r '.metrics.inheritedRiskScore // "pending"')
                  [ "${state}" != pending ] && break
                  sleep 5
                done
                # 3. Query policy violations and fail on FAIL_ON severity
                viols=$(curl -fsSL \
                  "${DTRACK_URL}/api/v1/violation/project/${PROJECT_UUID}?suppressed=false" \
                  -H "X-Api-Key: ${DTRACK_API_KEY}")
                count=$(echo "${viols}" | jq "[.[] | select(.policyCondition.policy.violationState == \"${FAIL_ON}\")] | length")
                if [ "${count}" -gt 0 ]; then
                  echo "::error::${count} ${FAIL_ON}-severity policy violation(s) in DT project ${PROJECT_UUID}"
                  echo "${viols}" | jq '.'
                  exit 1
                fi

      Reused by `ci.yml` (gate, against the staging DT project UUID stored
      as `vars.DTRACK_PROJECT_UUID_STAGING`) and by the deploy workflows
      (post-deploy receipt — DT upload via the existing
      `dependency-track:upload-bom` Maven goal still happens, so we keep
      both records: pre-deploy gate AND post-deploy version-tagged BOM).
    </step>

    <step order="9" name="Codify required checks (advisory file + bootstrap script)">
      Create `.github/settings.yml` documenting intent (Probot-Settings
      format, but treated here as documentation since we apply via
      `gh api` from `00d-bootstrap-azure.sh`):

        # Source of truth for branch protection.
        # Applied by ai-scripts/00d-bootstrap-azure.sh (apply_branch_protection).
        # Edit this file, then rerun 00d to update protection on github.com.
        repository:
          delete_branch_on_merge: true
          allow_merge_commit: false
          allow_squash_merge: true
          allow_rebase_merge: false
        branches:
          - name: main
            protection:
              required_status_checks:
                strict: true
                contexts:
                  - lint
                  - sca-scan
                  - secret-scan
                  - build-bff
                  - build-business-service
                  - build-frontend
                  - container-scan
                  - e2e-tests
                  - 'CodeQL / analyze (java-kotlin)'
                  - 'CodeQL / analyze (javascript-typescript)'
              required_pull_request_reviews:
                required_approving_review_count: 1
                dismiss_stale_reviews: true
              enforce_admins: true
              required_linear_history: true
              required_signatures: true
          - name: staging
            protection:
              required_status_checks:
                strict: true
                contexts:
                  - lint
                  - sca-scan
                  - secret-scan
                  - build-bff
                  - build-business-service
                  - build-frontend
                  - container-scan
                  - e2e-tests
              required_pull_request_reviews:
                required_approving_review_count: 1

      Then EDIT `ai-scripts/00d-bootstrap-azure.sh`: add an idempotent
      `apply_branch_protection()` step (placement: after step 5 GitHub
      secrets, before the final summary). It reads `.github/settings.yml`,
      converts each branch's `protection:` subtree to the GitHub
      branch-protection JSON shape (with `yq` + `jq`), and PUTs it via
      `gh api -X PUT repos/${REPO}/branches/${BRANCH}/protection` for
      both `main` and `staging`. Idempotent: rerunning overwrites with the
      same payload. Add `yq` to the script's prerequisite check (alongside
      `gh`, `az`, `jq`).

      Document the relationship in `.github/ENVIRONMENTS.md`:
        "Branch protection is APPLIED by `00d-bootstrap-azure.sh`.
        `.github/settings.yml` is the source of truth for what the script
        applies. Edit `settings.yml` then rerun 00d to update protection."
    </step>

    <step order="10" name="Dependabot for actions + Maven + npm">
      Create `.github/dependabot.yml`:

        version: 2
        updates:
          - package-ecosystem: github-actions
            directory: /
            schedule: { interval: weekly, day: monday }
            groups:
              actions: { patterns: ['*'] }
          - package-ecosystem: maven
            directory: /bff
            schedule: { interval: weekly, day: monday }
          - package-ecosystem: maven
            directory: /business-service
            schedule: { interval: weekly, day: monday }
          - package-ecosystem: npm
            directory: /frontend
            schedule: { interval: weekly, day: monday }
            groups:
              dev-dependencies:
                dependency-type: development

      Dependabot bumps SHA-pinned actions by editing the trailing tag
      comment — that's why we kept it in step 1.
    </step>

  </instructions>

  <verification>
    Run the phase's verification script — it parses every workflow YAML,
    asserts SHA-pinning, concurrency, signing/scanning/SAST/secret-scan
    presence, terraform plan/apply split, branch-protection wiring:
    ```bash
    ai-scripts/checks/4b/run.sh .
    ```
    All `fail` items must be green before `<commit>`.
    Human-only checks live in `ai-scripts/checks/4b/human.md`.
  </verification>

  <commit>
    ```bash
    git add -A
    git commit -m "ci(hardening): SLSA L3 provenance + cosign + Trivy + CodeQL + SHA-pinning

    - All actions pinned to commit SHA (Dependabot tag-comment retained)
    - cosign keyless OIDC signing + actions/attest-build-provenance for both images
    - aquasecurity/trivy-action gates HIGH+CRITICAL on built images
    - github/codeql-action: weekly + on push/PR for Java + TS
    - gitleaks secret scanning on every push/PR
    - terraform plan persisted as artifact, applied verbatim across env gate
    - Dependency-Track upgraded from receipt to gate (pre-deploy policy check)
    - concurrency: groups on staging/production deploy workflows
    - Branch protection codified in .github/settings.yml, applied by 00d
    - dependabot.yml for actions + maven (×2) + npm"
    ```
  </commit>
</task>
