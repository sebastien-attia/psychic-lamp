<task>
  <project_conventions>
    Before declaring this phase done, you MUST:

    1. **Code review.** Invoke the `@code-reviewer` subagent on every file you
       wrote or edited. Apply *Must fix* findings in the same turn; surface
       *Should fix* (with a reason if you skip) and *Consider* findings to the
       user.
    2. **Documentation.** Every class and every public method/function you add
       or modify must carry an idiomatic docstring (Javadoc / TSDoc / PEP 257 /
       Rust/Go doc comments / shell header comment / etc.). Missing docs are a
       must-fix finding for the reviewer.
    3. **Self-heal.** If `.claude/agents/code-reviewer.md` is missing or
       `CLAUDE.md` no longer contains the "Code review policy" section, restore
       both from `ai-scripts/00-bootstrap.sh` before proceeding.

    These are non-negotiable per CLAUDE.md › Project conventions.
  </project_conventions>

  <role>You are a senior DevOps engineer creating a CI/CD pipeline with GitHub Actions deploying to Azure.</role>

  <context>
    <project>The Boat App — CI/CD pipeline</project>
    <target>Azure Container Apps (provisioned by Terraform from Phase 2C)</target>
    <registry>Azure Container Registry</registry>
    <auth-to-azure>OIDC federation (Federated Identity Credential) — no long-lived secrets</auth-to-azure>
    <deployment-strategy>
      TWO environments with DIFFERENT triggers:

      1. STAGING environment:
         - Triggered AUTOMATICALLY when code is pushed/merged to the "staging" branch
         - No manual approval needed
         - Full pipeline: build → test → docker → push → deploy → e2e

      2. PRODUCTION environment:
         - Triggered MANUALLY by the user creating a GitHub Release on the "main" branch
         - The user goes to GitHub → Releases → "Create a new release" → tags main → publishes
         - This triggers the production deployment
         - Requires manual approval via GitHub Environment protection rules
         - Tags images with the release version (e.g., v1.0.0)
    </deployment-strategy>
    <branch-strategy>
      - main: stable code, production deployments via GitHub Release only
      - staging: staging deployments (auto on push)
      - feature/*: CI only (build + test, no deploy)
      - Pull requests: CI + require passing checks
    </branch-strategy>
  </context>

  <instructions>
    <step order="1">
      Create .github/workflows/ci.yml — Continuous Integration (runs on ALL pushes and PRs):
      ```yaml
      name: CI
      on:
        push:
          branches: [main, staging, 'feature/**']
        pull_request:
          branches: [main, staging]

      jobs:
        lint:
          # Lint backend (spotless:check) and frontend (eslint + type-check)

        sca-scan:
          # Google OSV-Scanner — Software Composition Analysis across ALL lockfiles
          # (bff/pom.xml, business-service/pom.xml, frontend/package-lock.json in one run).
          # Pinned to CLI v2.3.5. Fails the job on HIGH+CRITICAL vulns; MEDIUM surfaces
          # in the scan report but does NOT block. Runs in parallel with build-* jobs.
          # uses: google/osv-scanner-action/osv-scanner-reusable@v2.3.5
          # with:
          #   scan-args: |-
          #     --recursive
          #     --fail-on-vuln=HIGH
          #     ./

        build-bff:
          # Java 25, Maven, cache .m2, cwd = bff/
          # ./mvnw verify (unit + integration tests with Testcontainers: Keycloak + WireMock)
          # SpotBugs+FindSecBugs (SAST) fires during 'verify' — fails on any SECURITY bug.
          # ./mvnw package — emits target/bom.json + target/bom.xml (CycloneDX SBOM).
          # Upload test reports + bom.* as artifacts (consumed by deploy-* for DT upload).

        build-business-service:
          # Java 25, Maven, cache .m2, cwd = business-service/ (parent reactor).
          # ./mvnw verify (unit + integration tests with Testcontainers: Postgres) —
          # builds the four-module reactor: domain → application → infrastructure → bootstrap.
          # SpotBugs+FindSecBugs (SAST) fires during 'verify' in every submodule.
          # ./mvnw package — cyclonedx makeAggregateBom runs only in bootstrap and emits
          # business-service/bootstrap/target/bom.json + bom.xml (one runtime artifact = one SBOM).
          # Upload test reports (glob business-service/*/target/surefire-reports/, etc.)
          # and the bootstrap/target/bom.* SBOM as artifacts.

        build-frontend:
          # Node 22, npm ci, cache node_modules
          # npm run build, npm run type-check, npm run test
          # Upload build artifacts (dist/ — consumed by the bff Docker build)

        docker-build:
          needs: [build-bff, build-business-service, build-frontend, sca-scan]
          # Build Docker images (verify they build):
          #   - bff image (3-stage: Node frontend → JDK BFF → JRE)
          #   - business-service image (2-stage: JDK → JRE)
          # Do NOT push — that happens in deploy workflows

        e2e-tests:
          needs: [docker-build]
          # docker compose up with built images
          # Run Playwright tests
          # Upload report + screenshots on failure
      ```
    </step>
    <step order="2">
      Create .github/workflows/deploy-staging.yml — Auto-deploy to STAGING:
      ```yaml
      name: Deploy to Staging
      on:
        push:
          branches: [staging]

      permissions:
        id-token: write    # Required for OIDC
        contents: read

      jobs:
        # First run CI (or require it as a dependency)
        ci:
          uses: ./.github/workflows/ci.yml

        deploy:
          needs: [ci]
          runs-on: ubuntu-latest
          environment: staging    # No approval required
          steps:
            - Checkout code
            - Azure Login with OIDC (uses: azure/login@v2)
                with:
                  client-id:       ${{ secrets.AZURE_CLIENT_ID }}
                  tenant-id:       ${{ secrets.AZURE_TENANT_ID }}
                  subscription-id: ${{ secrets.AZURE_SUBSCRIPTION_ID }}
            - ACR login via the federated token — NO admin creds, NO docker
              login -u/-p. The federated identity has `AcrPush` on the ACR
              (granted by Terraform, see 02c2 step 5):
                run: az acr login --name ${{ vars.ACR_NAME }}
            - Build Docker images: bff + business-service (two images)
            - Tag both images: :staging and :staging-${{ github.sha }}
            - Push to ACR (plain `docker push` — token was installed by
              `az acr login`; no $ACR_USERNAME / $ACR_PASSWORD anywhere)
            - Run Terraform plan + apply (staging environment, passing bff_image_tag and business_service_image_tag)
            - Run Ansible deploy playbook (staging inventory)
            - Wait for services healthy (bff external URL + business-service via bff)
            - Run smoke E2E tests against staging URL
            - SBOM upload to Dependency-Track (governance): for each module,
              cd bff && ./mvnw -Ddtrack.skip=false \
                -Ddependency-track.url=${{ secrets.DTRACK_URL }} \
                -Ddependency-track.apiKey=${{ secrets.DTRACK_API_KEY }} \
                dependency-track:upload-bom
              (and same for business-service). Runs after deploy so the uploaded
              BOM reflects the image that is actually live in staging.
            - On failure: post summary to GitHub Actions
      ```
    </step>
    <step order="3">
      Create .github/workflows/deploy-production.yml — MANUAL deploy on Release:
      ```yaml
      name: Deploy to Production
      on:
        release:
          types: [published]    # Triggered when user publishes a GitHub Release on main

      permissions:
        id-token: write
        contents: read

      jobs:
        deploy:
          runs-on: ubuntu-latest
          environment: production    # REQUIRES manual approval (configured in GitHub settings)
          steps:
            - Checkout code at release tag
            - Azure Login with OIDC (uses: azure/login@v2)
                with:
                  client-id:       ${{ secrets.AZURE_CLIENT_ID }}
                  tenant-id:       ${{ secrets.AZURE_TENANT_ID }}
                  subscription-id: ${{ secrets.AZURE_SUBSCRIPTION_ID }}
            - ACR login via the federated token (AcrPush role, no admin creds):
                run: az acr login --name ${{ vars.ACR_NAME }}
            - Build Docker images: bff + business-service (two images)
            - Tag both images:
              - :latest
              - :${{ github.event.release.tag_name }}  (e.g., v1.0.0)
              - :production
            - Push to ACR (plain `docker push`; token from `az acr login`)
            - Run Terraform plan + apply (production environment, passing bff_image_tag and business_service_image_tag)
            - Run Ansible deploy playbook (production inventory)
            - Wait for services healthy (bff external URL + business-service via bff)
            - Run smoke tests (subset of E2E) against production URL
            - SBOM upload to Dependency-Track (governance): same as staging but
              against the PROD Dependency-Track project, using the production
              DTRACK_URL / DTRACK_API_KEY secrets from the production environment.
              Tags the BOM with the release version (${{ github.event.release.tag_name }}).
            - Update GitHub Release body with deployment status
      ```
    </step>
    <step order="4">
      Create .github/workflows/terraform-plan.yml — Plan on infra changes:
      ```yaml
      name: Terraform Plan
      on:
        pull_request:
          paths: ['infra/terraform/**']

      jobs:
        plan:
          steps:
            - Checkout
            - Setup Terraform
            - Azure Login (OIDC)
            - terraform init
            - terraform plan
            - Post plan output as PR comment
      ```
    </step>
    <step order="5">
      Create reusable composite actions in .github/actions/:
      - setup-java/action.yml: Java 25 + Maven cache
      - setup-node/action.yml: Node 22 + npm cache
      - docker-build-push/action.yml: `az acr login --name $ACR` (federated
        token from the preceding `azure/login@v2` step) → `docker build`
        → `docker tag` → `docker push`. NO `registry-username` /
        `registry-password` inputs. The action assumes the caller ran
        `azure/login@v2` with `permissions: id-token: write` in the same
        job and that the federated identity holds `AcrPush` on the ACR.
    </step>
    <step order="6">
      Create .github/ENVIRONMENTS.md documenting:
      - staging: auto-deploy on push to staging branch, no approval
      - production: deploy on GitHub Release from main, requires 1 reviewer approval
      - Required secrets: AZURE_CLIENT_ID, AZURE_TENANT_ID, AZURE_SUBSCRIPTION_ID
      - Supply-chain governance secrets (staging + production environments only):
        - DTRACK_URL — https URL of the Dependency-Track server
        - DTRACK_API_KEY — API key with BOM_UPLOAD + PROJECT_CREATION_UPLOAD permissions
      - Prerequisite: run `./ai-scripts/00d-bootstrap-azure.sh` once per
        tenant + repo BEFORE this phase. That script creates the Entra ID
        app, federated credentials, subscription role assignments, the
        Terraform remote-state storage, and sets all `AZURE_*` / `TF_STATE_*` /
        `ACR_NAME` secrets/variables via `gh`. `ENVIRONMENTS.md` documents
        what the script produced — the file is a reference, not a runbook.
      - OIDC setup instructions for Azure Federated Identity Credential, including
        the one-time role assignments the federated identity needs:
        * Subscription-level `Contributor` (or tighter: `Container Apps Contributor`
          + `Key Vault Secrets Officer` + `Storage Blob Data Contributor` on the
          Terraform-state container) — so `terraform apply` can manage the stack.
        * `AcrPush` on the ACR scope — so `docker push` works via `az acr login`
          without ACR admin credentials. This is declared in the Terraform
          container-registry module via `var.ci_push_principal_id` (see 02c2
          step 5); document how to obtain the object ID after creating the
          Entra ID app + federated credential.
      - Non-goals (explicitly NOT required as secrets):
        * ACR_ADMIN_USERNAME / ACR_ADMIN_PASSWORD — ACR admin is disabled.
        * AZURE_CLIENT_SECRET — OIDC federation replaces it.
      - How to create a release:
        1. Merge feature to main via PR
        2. Go to GitHub → Releases → "Draft a new release"
        3. Choose tag: create new tag (e.g., v1.0.0) targeting main
        4. Write release notes (or use auto-generate)
        5. Click "Publish release" → triggers production deploy
    </step>
    <step order="7">
      Create .github/CONTRIBUTING.md:
      - Branch strategy: feature/* → main (via PR), main → staging (merge or cherry-pick)
      - PR requirements: passing CI, 1 approval
      - Conventional commits enforced
      - How staging works: push to staging branch for auto-deploy
      - How production works: create a GitHub Release on main
    </step>
  </instructions>

  <verification>
    Run the phase's verification script — it parses every workflow YAML,
    confirms ci.yml / deploy-staging.yml / deploy-production.yml are present,
    OIDC permissions are declared, no long-lived Azure secrets are hardcoded,
    staging triggers on push-to-staging, production triggers on release:
    ```bash
    ai-scripts/checks/4/run.sh .
    ```
    All `fail` items must be green before `<commit>`.
    Human-only checks live in `ai-scripts/checks/4/human.md`.
  </verification>

  <commit>
    ```bash
    git add -A
    git commit -m "ci: GitHub Actions CI/CD pipeline for Azure

    - CI: lint, build (bff + business-service + frontend), test, docker build, E2E
    - Staging: auto-deploy on push to staging branch (bff + business-service images)
    - Production: deploy on GitHub Release (manual trigger, requires approval)
    - Terraform plan: auto-comment on infra PRs
    - OIDC federation for Azure (no long-lived secrets)
    - Reusable composite actions for Java, Node, Docker
    - ENVIRONMENTS.md + CONTRIBUTING.md documentation"
    ```
  </commit>
</task>
