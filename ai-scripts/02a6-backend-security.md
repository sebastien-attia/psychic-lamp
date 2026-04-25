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

  <role>You are a senior application-security engineer wiring supply-chain gates (SCA, SAST, SBOM, DT upload) into two Maven modules.</role>

  <context>
    <project>The Boat App — security build gates for BFF and Business Service</project>
    <existing-state>
      Backend (BFF + Business Service) is complete through phase 02a5.
      Both modules are independent Maven projects inheriting from
      spring-boot-starter-parent:4.0.6 (Java 25). The only non-standard
      plugin today is openapi-generator-maven-plugin:7.10.0, declared
      per-module in `<build><plugins>`. There is NO root aggregator pom.
      ArchUnit + integration tests run on `./mvnw verify`.
    </existing-state>
    <tools>
      - SCA (Software Composition Analysis): Google OSV-Scanner CLI v2.3.5
        — NO first-party Maven plugin. We invoke it from CI via the
        google/osv-scanner-action, plus an optional `exec-maven-plugin`
        profile for local runs.
      - SAST (Static Application Security Testing):
        com.github.spotbugs:spotbugs-maven-plugin:4.9.8.3 (Java 25 compatible)
        with com.h3xstream.findsecbugs:findsecbugs-plugin:1.14.0 loaded as a
        SpotBugs detector pack.
      - SBOM + Governance:
        org.cyclonedx:cyclonedx-maven-plugin:2.9.1 generates CycloneDX
        bom.json/bom.xml on `package`.
        io.github.pmckeown:dependency-track-maven-plugin:1.11.0 uploads
        the BOM to an existing Dependency-Track server on `deploy` (skipped
        by default; CI overrides -Ddtrack.skip=false).
    </tools>
    <key-constraints>
      * Java 25 bytecode — SpotBugs must be 4.9.7 or later.
      * ArchUnit already fails the build on Spring imports inside
        business-service/.../domain/** — SpotBugs filters MUST exclude
        **/generated/** (OpenAPI output) to keep the SAST signal clean.
      * Dependency-Track hosting is assumed to exist out-of-band.
        This phase ONLY wires the client; provisioning the server is
        out of scope (could be added to 02c2-terraform later).
      * OSV-Scanner CI threshold: HIGH + CRITICAL block CI; MEDIUM is
        reported but does not fail the build.
      * Per-module plugin duplication matches the existing convention
        (openapi-generator is duplicated too) — do NOT introduce a root
        parent pom in this phase.
    </key-constraints>
  </context>

  <instructions>
    <step order="1">
      Add the SpotBugs + FindSecBugs plugin to BOTH `bff/pom.xml` and
      `business-service/pom.xml` inside `<build><plugins>` (next to the
      existing openapi-generator-maven-plugin declaration):

      ```xml
      <plugin>
        <groupId>com.github.spotbugs</groupId>
        <artifactId>spotbugs-maven-plugin</artifactId>
        <version>4.9.8.3</version>
        <configuration>
          <effort>Max</effort>
          <threshold>Low</threshold>
          <failOnError>true</failOnError>
          <includeFilterFile>spotbugs-security-include.xml</includeFilterFile>
          <excludeFilterFile>spotbugs-exclude-generated.xml</excludeFilterFile>
          <plugins>
            <plugin>
              <groupId>com.h3xstream.findsecbugs</groupId>
              <artifactId>findsecbugs-plugin</artifactId>
              <version>1.14.0</version>
            </plugin>
          </plugins>
        </configuration>
        <executions>
          <execution>
            <id>spotbugs-check</id>
            <phase>verify</phase>
            <goals><goal>check</goal></goals>
          </execution>
        </executions>
      </plugin>
      ```
    </step>

    <step order="2">
      Create the SpotBugs include filter at
      `bff/spotbugs-security-include.xml` AND
      `business-service/spotbugs-security-include.xml`:

      ```xml
      <?xml version="1.0" encoding="UTF-8"?>
      <FindBugsFilter xmlns="https://github.com/spotbugs/filter/3.0.0">
        <!-- Only raise findings in the SECURITY category (FindSecBugs pack). -->
        <Match><Category name="SECURITY"/></Match>
      </FindBugsFilter>
      ```

      And the exclude filter at
      `bff/spotbugs-exclude-generated.xml` AND
      `business-service/spotbugs-exclude-generated.xml`:

      ```xml
      <?xml version="1.0" encoding="UTF-8"?>
      <FindBugsFilter xmlns="https://github.com/spotbugs/filter/3.0.0">
        <!-- OpenAPI-generated DTOs and API interfaces — not our code. -->
        <Match><Class name="~.*\.generated\..*"/></Match>
        <Match><Class name="~.*\.dto\.generated\..*"/></Match>
      </FindBugsFilter>
      ```
    </step>

    <step order="3">
      Add the CycloneDX SBOM plugin to BOTH pom.xml files:

      ```xml
      <plugin>
        <groupId>org.cyclonedx</groupId>
        <artifactId>cyclonedx-maven-plugin</artifactId>
        <version>2.9.1</version>
        <configuration>
          <outputFormat>all</outputFormat>
          <outputName>bom</outputName>
          <schemaVersion>1.5</schemaVersion>
          <includeBomSerialNumber>true</includeBomSerialNumber>
          <includeLicenseText>false</includeLicenseText>
        </configuration>
        <executions>
          <execution>
            <id>make-sbom</id>
            <phase>package</phase>
            <goals><goal>makeAggregateBom</goal></goals>
          </execution>
        </executions>
      </plugin>
      ```

      This writes `target/bom.json` and `target/bom.xml` on every `mvn package`.
    </step>

    <step order="4">
      Add the Dependency-Track upload plugin to BOTH pom.xml files.
      Bind to `deploy` phase and SKIP by default so local builds do not
      attempt to contact a server:

      ```xml
      <plugin>
        <groupId>io.github.pmckeown</groupId>
        <artifactId>dependency-track-maven-plugin</artifactId>
        <version>1.11.0</version>
        <configuration>
          <dependencyTrackBaseUrl>${dependency-track.url}</dependencyTrackBaseUrl>
          <apiKey>${dependency-track.apiKey}</apiKey>
          <projectName>${project.artifactId}</projectName>
          <projectVersion>${project.version}</projectVersion>
          <bomLocation>${project.build.directory}/bom.xml</bomLocation>
          <updateParent>true</updateParent>
          <skip>${dtrack.skip}</skip>
          <failOnError>true</failOnError>
        </configuration>
        <executions>
          <execution>
            <id>dtrack-upload</id>
            <phase>deploy</phase>
            <goals><goal>upload-bom</goal></goals>
          </execution>
        </executions>
      </plugin>
      ```

      Add to each `<properties>` block:
      ```xml
      <dtrack.skip>true</dtrack.skip>
      <dependency-track.url>https://dtrack.example.invalid</dependency-track.url>
      <dependency-track.apiKey>unset</dependency-track.apiKey>
      ```

      CI overrides via: `-Ddtrack.skip=false -Ddependency-track.url=... -Ddependency-track.apiKey=...`.
    </step>

    <step order="5">
      Add an `osv` profile to BOTH pom.xml files — local convenience
      wrapper for developers who have the `osv-scanner` binary on PATH:

      ```xml
      <profiles>
        <profile>
          <id>osv</id>
          <build>
            <plugins>
              <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.5.0</version>
                <executions>
                  <execution>
                    <id>osv-scan</id>
                    <phase>verify</phase>
                    <goals><goal>exec</goal></goals>
                    <configuration>
                      <executable>osv-scanner</executable>
                      <arguments>
                        <argument>scan</argument>
                        <argument>source</argument>
                        <argument>--fail-on-vuln=HIGH</argument>
                        <argument>${project.basedir}</argument>
                      </arguments>
                    </configuration>
                  </execution>
                </executions>
              </plugin>
            </plugins>
          </build>
        </profile>
      </profiles>
      ```

      Primary SCA still runs in CI via the google/osv-scanner-action
      (wired in phase 04). Locally: `./mvnw -Posv verify` once the binary
      is installed (`brew install osv-scanner` or equivalent).
    </step>

    <step order="6">
      Update `.env.example` at the project root. Add a new section:

      ```bash
      # ── Supply-chain governance (staging/prod CI only) ─────────────────
      # Populate via GitHub repository secrets; NOT required for local builds.
      # DTRACK_URL=https://dependency-track.internal.example.com
      # DTRACK_API_KEY=change-me-api-key-from-dependency-track-admin
      ```

      Leave commented so `docker compose up` does not error on missing vars.
    </step>

    <step order="7">
      Update `CLAUDE.md` — append a new section after "Non-obvious gotchas":

      ```markdown
      ## Security build gates

      Three layers of supply-chain tooling run on every build:

      - **SAST** — `spotbugs-maven-plugin` + `findsecbugs-plugin` fire during
        `./mvnw verify` (goal `check`, `failOnError=true`). Only the
        `SECURITY` FindBugs category is raised; generated OpenAPI code is
        excluded via `spotbugs-exclude-generated.xml`.
      - **SBOM** — `cyclonedx-maven-plugin` emits `target/bom.json` +
        `target/bom.xml` on `mvn package`.
      - **SCA** — OSV-Scanner runs in CI (`sca-scan` job in
        `.github/workflows/ci.yml`) and fails the build on `HIGH`+`CRITICAL`
        findings. Local devs can opt-in via `./mvnw -Posv verify` after
        installing the binary.
      - **Governance** — `dependency-track-maven-plugin` uploads the
        CycloneDX BOM to Dependency-Track during the `deploy` phase; skipped
        by default (`-Ddtrack.skip=true`), CI override: `-Ddtrack.skip=false`.
        Required secrets: `DTRACK_URL`, `DTRACK_API_KEY` (staging + prod
        GitHub environments only).
      ```
    </step>
  </instructions>

  <verification>
    Run the phase's verification script — it greps both pom.xml files for
    the three plugin coordinates, runs `./mvnw verify` (SpotBugs must pass)
    and `./mvnw package` (BOM must be emitted), then validates the filter
    files and CLAUDE.md snippet:
    ```bash
    ai-scripts/checks/02a6/run.sh .
    ```
    All `fail` items must be green before `<commit>`.
    Human-only checks live in `ai-scripts/checks/02a6/human.md`.
  </verification>

  <commit>
    ```bash
    git add -A
    git commit -m "security: add SAST (SpotBugs+FindSecBugs), SBOM (CycloneDX), SCA wiring

    Both modules (bff + business-service) now run three supply-chain gates:
    - SAST: spotbugs-maven-plugin 4.9.8.3 + findsecbugs-plugin 1.14.0
      bound to 'verify', SECURITY-only filter, excludes generated/**
    - SBOM: cyclonedx-maven-plugin 2.9.1 emits bom.json/bom.xml on 'package'
    - Governance: dependency-track-maven-plugin 1.11.0 uploads BOM on
      'deploy' (skip=true by default; CI overrides with -Ddtrack.skip=false)
    - SCA (local): optional 'osv' profile wraps osv-scanner via exec-maven-plugin
    - Docs: CLAUDE.md 'Security build gates' section; .env.example DTRACK vars"
    ```
  </commit>
</task>
