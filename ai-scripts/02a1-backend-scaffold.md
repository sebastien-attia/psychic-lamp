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

  <role>You are a senior Java architect scaffolding two Spring Boot projects with strict hexagonal architecture.</role>

  <context>
    <project>The Boat App — BFF service + Business Service (two independent Spring Boot applications)</project>
    <contract>contracts/openapi.yaml (read it first)</contract>
    <architecture>
      TWO separate Spring Boot applications:

      1. BFF (bff/) — port 8080
         Thin proxy. OAuth2 session management. Serves Vue SPA. Forwards Bearer token to Business Service.
         - adapter.in.web → Spring @RestController. Depends on infrastructure.service.
         - adapter.out.client → BusinessServiceClient (Spring HTTP Interface). Calls Business Service.
         - infrastructure.service → BoatBffService (thin, no @Transactional).
         - infrastructure.security → SecurityConfig (OAuth2 session, CSRF), DevSecurityConfig.
         - infrastructure.config → BffConfig (wires HTTP client beans).

      2. Business Service (business-service/) — port 8081
         STRICT hexagonal, packaged as a four-module Maven reactor (parent POM
         + domain / application / infrastructure / bootstrap submodules).
         The Maven graph physically prevents domain from depending on Spring.
         JWT resource server.
         - domain.model → pure Java domain objects. ZERO Spring/Jakarta imports. Lives in `domain` jar.
         - domain.service.validation → SyntacticValidator + SemanticValidator (pure Java, in `domain`).
         - application.port.in → inbound port interfaces (use cases) and Command/Query records. In `application` jar.
         - application.port.out → outbound port interfaces (repos). In `application` jar.
         - application.service → use case implementations. BoatApplicationService (@Service @Transactional bridge), plus pure-Java BoatDomainService / UserDomainService wired by BeanConfig. In `application` jar.
         - adapter.in.web → Spring @RestController. Stateless. In `infrastructure` jar.
         - adapter.out.persistence → JPA entities + Spring Data repos. Implements application.port.out. In `infrastructure` jar.
         - infrastructure.config → BeanConfig (wires pure-Java application services as beans). In `infrastructure` jar.
         - infrastructure.security → ResourceServerSecurityConfig (JWT), DevSecurityConfig. In `infrastructure` jar.
         - BusinessServiceApplication + application*.yml → in `bootstrap` jar (the only one that produces a runnable artifact, finalName=business-service).
    </architecture>
    <environments>
      - dev: NO BFF. Business Service only (auth bypass via permitAll). PostgreSQL. No Keycloak.
      - local-intg: BFF (:8080) + Business Service (:8081) + PostgreSQL + Keycloak. Full OAuth2 + JWT.
      - staging/prod: Azure. Same as local-intg but with Azure URLs.
    </environments>
    <scaffolding>
      Both services are scaffolded by the official Spring Initializr (start.spring.io)
      via `ai-scripts/00c-initializr.sh` with:
        - type=maven-project, language=java, packaging=jar
        - bootVersion=4.0.6, javaVersion=25
        - config format: application.yml (renamed from Initializr's .properties output)
        - groupId=ch.owt.boatapp; artifactId=bff | business-service
      This phase EXTENDS — never overwrites — those Initializr outputs:
        - DO NOT regenerate pom.xml, mvnw, the main *Application.java, or application.yml.
        - DO add the extra dependencies and the openapi-generator-maven-plugin listed below.
        - DO verify the parent Spring Boot version is 4.0.6 and <java.version> is 25; if not, STOP and re-run 00c-initializr.sh.
    </scaffolding>
  </context>

  <instructions>
    <step order="1">Read contracts/openapi.yaml.</step>

    <step order="2">
      EXTEND bff/pom.xml (produced by 00c-initializr.sh — DO NOT overwrite).

      First verify Initializr's output:
      - <parent> is spring-boot-starter-parent, <version>4.0.6</version>
      - <java.version>25</java.version>
      - <packaging>jar</packaging>
      - Maven wrapper (mvnw, mvnw.cmd, .mvn/) present
      - Main class ch.owt.boatapp.bff.BffApplication present (rename from any
        Initializr default like DemoApplication if needed)
      If any of the above is wrong, STOP and re-run ai-scripts/00c-initializr.sh.

      Initializr already brought in these starters (do NOT re-declare):
        spring-boot-starter-web, -security, -oauth2-client, -actuator,
        spring-session-core (via `session`), spring-boot-starter-data-jdbc,
        liquibase-core (BFF owns its own Liquibase changelog for SPRING_SESSION tables — see
        02a2 step 5b), postgresql driver, lombok, spring-boot-starter-test, spring-boot-testcontainers.
      Add spring-session-jdbc explicitly (Initializr's `session` starter only wires the
      abstraction — JDBC backing needs to be declared).

      Add these extra dependencies that Initializr does not offer:
      - springdoc-openapi-starter-webmvc-ui
      - com.nimbusds:nimbus-jose-jwt (transitive via spring-security-oauth2-jose, but pin
        explicitly: BffConfig builds an RSAKey and JwksController serializes a JWKSet)
      - archunit-junit5 (test scope)
      - spring-security-test (test)
      - com.github.tomakehurst:wiremock-spring-boot (test) — mock Business Service in BFF tests
      - com.github.dasniko:testcontainers-keycloak (test) — real Keycloak for OAuth2 flow tests

      Build plugin — openapi-generator-maven-plugin (v7.x) to generate the BFF's
      outbound client interface and request/response DTOs from contracts/openapi.yaml.
      Bind to the `generate-sources` phase. Configuration:
      ```xml
      <plugin>
        <groupId>org.openapitools</groupId>
        <artifactId>openapi-generator-maven-plugin</artifactId>
        <version>7.x</version>
        <executions>
          <execution>
            <id>generate-bff-client</id>
            <goals><goal>generate</goal></goals>
            <configuration>
              <inputSpec>${project.basedir}/../contracts/openapi.yaml</inputSpec>
              <generatorName>spring</generatorName>
              <library>spring-http-interface</library>
              <apiPackage>ch.owt.boatapp.bff.adapter.out.client.generated</apiPackage>
              <modelPackage>ch.owt.boatapp.bff.adapter.in.web.dto.generated</modelPackage>
              <!-- Only generate the outbound client for endpoints actually proxied to Business Service. -->
              <!-- The User tag (/api/me) is BFF-only: its DTO is generated, but no client is needed. -->
              <apiNameSuffix>Client</apiNameSuffix>
              <configOptions>
                <useSpringBoot3>true</useSpringBoot3>
                <useBeanValidation>true</useBeanValidation>   <!-- MANDATORY: emits @NotBlank/@Size/@NotNull from OpenAPI constraints; see .claude/rules/validation-and-errors.md -->
                <useResponseEntity>true</useResponseEntity>
                <dateLibrary>java8</dateLibrary>
                <openApiNullable>false</openApiNullable>
                <useOptional>false</useOptional>
                <generatedAnnotation>true</generatedAnnotation>
                <documentationProvider>none</documentationProvider>
                <hideGenerationTimestamp>true</hideGenerationTimestamp>
              </configOptions>
              <!-- Tag=BusinessService → generated interface is `BusinessServiceClient` (via apiNameSuffix=Client). -->
              <!-- Tag=User is filtered out of the client (BFF serves /api/me itself, does not proxy it). -->
              <globalProperties>
                <apis>BusinessService</apis>
                <models/>
                <supportingFiles>false</supportingFiles>
              </globalProperties>
              <generateApiTests>false</generateApiTests>
              <generateModelTests>false</generateModelTests>
              <generateApiDocumentation>false</generateApiDocumentation>
              <generateModelDocumentation>false</generateModelDocumentation>
            </configuration>
          </execution>
        </executions>
      </plugin>
      ```
      Output lands under `target/generated-sources/openapi/` and is auto-added to the
      compile path. The generated `BusinessServiceClient` is the Spring @HttpExchange
      interface wired by BffConfig (see 02a4).
    </step>

    <step order="3">
      Create bff/src/main/resources/application.yml (shared BFF config):
      - server.port: 8080
      - spring.session.store-type: jdbc
      - spring.session.jdbc.initialize-schema: never  (BFF's own Liquibase creates the tables — see 02a2 step 5b)
      - spring.liquibase.change-log: classpath:db/changelog/db.changelog-master.yaml
      - server.servlet.session.cookie: http-only=true, same-site=lax, name=SESSION
      - server.servlet.session.timeout: 30m

      Note: BFF uses Spring Session JDBC to store sessions (and OAuth2AuthorizedClient tokens) in
      its OWN PostgreSQL database `bff_session`, owned by role `bff`. No cross-service DB access:
      the `bff` role has no CONNECT privilege on `boatapp` (business-service) or `keycloak`.
      Business Service and BFF share only the PostgreSQL instance, not the database.

      bff/src/main/resources/application-dev.yml:
      - spring.datasource.url: jdbc:postgresql://localhost:5432/${BFF_DB_NAME:bff_session}
      - spring.datasource.username: ${BFF_DB_USER:bff}
      - spring.datasource.password: ${BFF_DB_PASSWORD:bff}
      - server.servlet.session.cookie.secure: false
      - No spring.security.oauth2.client config (dev mode: BFF not used)

      bff/src/main/resources/application-local-intg.yml:
      - spring.datasource.url: jdbc:postgresql://localhost:5432/${BFF_DB_NAME:bff_session}
      - spring.datasource.username: ${BFF_DB_USER:bff}
      - spring.datasource.password: ${BFF_DB_PASSWORD:bff}
      - spring.security.oauth2.client.registration.keycloak:
          client-id: ${KEYCLOAK_CLIENT_ID}
          client-authentication-method: private_key_jwt   # NO client-secret — signed JWT assertion
          scope: [openid, profile, email, offline_access]
          authorization-grant-type: authorization_code
          redirect-uri: "{baseUrl}/login/oauth2/code/keycloak"
      - spring.security.oauth2.client.provider.keycloak:
          issuer-uri: ${KEYCLOAK_ISSUER_URI}
          user-name-attribute: preferred_username
      - bff.signing-key:                # Used by BffConfig to sign client_assertion JWTs
          path: ${BFF_SIGNING_KEY_PATH}
          id:   ${BFF_SIGNING_KEY_ID}
      - business-service.url: ${BUSINESS_SERVICE_URL:http://localhost:8081}
      - server.servlet.session.cookie.secure: false

      bff/src/main/resources/application-staging.yml:
      - spring.datasource.url: jdbc:postgresql://${POSTGRES_FQDN}:5432/${BFF_DB_NAME:bff_session}?sslmode=require
      - spring.datasource.username: ${BFF_DB_USER}
      - spring.datasource.password: ${BFF_DB_PASSWORD}
      - spring.security.oauth2.client: Azure Keycloak URL
        - client-authentication-method: private_key_jwt   (same as local-intg)
      - bff.signing-key.path / .id: PEM mounted from Azure Key Vault secret-volume
      - business-service.url: ${BUSINESS_SERVICE_URL}
      - server.servlet.session.cookie.secure: true

      bff/src/main/resources/application-prod.yml:
      - Same structure as staging with production URLs
      - server.servlet.session.cookie.secure: true
    </step>

    <step order="4">
      Create the BFF package structure with placeholder classes:
      ch.owt.boatapp.bff.adapter.in.web/          → BoatController.java, AuthController.java, JwksController.java, GlobalExceptionHandler.java
                                                  (JwksController serves /.well-known/jwks.json — the public half of the
                                                  RSAKey defined in BffConfig, so Keycloak can verify private_key_jwt
                                                  client_assertions. SecurityConfig must permitAll this path.)
                                                  (GlobalExceptionHandler uses Jackson 3 — tools.jackson.databind.ObjectMapper
                                                  and tools.jackson.core.JacksonException. Spring Boot 4 auto-configures
                                                  Jackson 3, NOT Jackson 2. See .claude/rules/bff-java.md#Jackson.)
                                                  (DTOs live in dto.generated, generated from contracts/openapi.yaml — do NOT create by hand)
      ch.owt.boatapp.bff.adapter.in.web.dto.generated/  → generated at build time (do not create; gitignored)
      ch.owt.boatapp.bff.adapter.out.client.generated/  → generated at build time (do not create; contains BusinessServiceClient)
      ch.owt.boatapp.bff.infrastructure.service/  → BoatBffService.java
      ch.owt.boatapp.bff.infrastructure.security/ → SecurityConfig.java, DevSecurityConfig.java
      ch.owt.boatapp.bff.infrastructure.config/   → BffConfig.java (wires HTTP client beans)
    </step>

    <step order="5">
      Verify bff/src/main/java/ch/owt/boatapp/bff/BffApplication.java exists
      (produced by Initializr). If Initializr emitted a different default name
      (e.g. DemoApplication), rename the class + file to BffApplication. No other edits.
    </step>

    <step order="6">
      EXTEND business-service/pom.xml (produced by 00c-initializr.sh — DO NOT overwrite).

      First verify Initializr's output:
      - <parent> is spring-boot-starter-parent, <version>4.0.6</version>
      - <java.version>25</java.version>
      - <packaging>jar</packaging>
      - Maven wrapper (mvnw, mvnw.cmd, .mvn/) present
      - Main class ch.owt.boatapp.BusinessServiceApplication present (rename from any
        Initializr default like DemoApplication if needed)
      If any of the above is wrong, STOP and re-run ai-scripts/00c-initializr.sh.

      Initializr already brought in these starters (do NOT re-declare):
        spring-boot-starter-web, -data-jpa, -validation, -oauth2-resource-server,
        -security, -actuator, postgresql driver, liquibase-core, lombok,
        spring-boot-starter-test, spring-boot-testcontainers,
        testcontainers (junit-jupiter + postgresql via the testcontainers starter).

      Add these extra dependencies that Initializr does not offer:
      - springdoc-openapi-starter-webmvc-ui
      - archunit-junit5 (test scope)
      - spring-security-test (test)
      (NO MapStruct — persistence mappers are hand-written @Component classes; see 02a2.)

      NOTE: NO spring-session-jdbc (Business Service is stateless — no session)
      NOTE: NO spring-boot-starter-oauth2-client (resource server only, never OAuth2 client)

      Build plugin — openapi-generator-maven-plugin (v7.x) to generate the Business
      Service's controller API interface and request/response DTOs from
      contracts/openapi.yaml. Bind to the `generate-sources` phase. Configuration:
      ```xml
      <plugin>
        <groupId>org.openapitools</groupId>
        <artifactId>openapi-generator-maven-plugin</artifactId>
        <version>7.x</version>
        <executions>
          <execution>
            <id>generate-business-service-api</id>
            <goals><goal>generate</goal></goals>
            <configuration>
              <inputSpec>${project.basedir}/../contracts/openapi.yaml</inputSpec>
              <generatorName>spring</generatorName>
              <apiPackage>ch.owt.boatapp.adapter.in.web.generated</apiPackage>
              <modelPackage>ch.owt.boatapp.adapter.in.web.dto.generated</modelPackage>
              <configOptions>
                <interfaceOnly>true</interfaceOnly>
                <useSpringBoot3>true</useSpringBoot3>
                <useBeanValidation>true</useBeanValidation>   <!-- MANDATORY: syntactic rules enforced at the REST adapter via Jakarta Bean Validation → 400; SyntacticValidator stays as domain-side defense-in-depth for non-REST callers (CLI, queues, tests). See .claude/rules/validation-and-errors.md -->
                <useResponseEntity>true</useResponseEntity>   <!-- preserves ETag/Location headers -->
                <dateLibrary>java8</dateLibrary>
                <openApiNullable>false</openApiNullable>
                <useOptional>false</useOptional>
                <generatedAnnotation>true</generatedAnnotation>
                <documentationProvider>none</documentationProvider>
                <hideGenerationTimestamp>true</hideGenerationTimestamp>
                <useTags>true</useTags>
              </configOptions>
              <!-- Only generate the BusinessService API (/api/v1/boats/**). /api/me (tag=User) is BFF-only -->
              <!-- and MUST NOT be generated here — Business Service does not host it. -->
              <globalProperties>
                <apis>BusinessService</apis>
                <models/>
                <supportingFiles>false</supportingFiles>
              </globalProperties>
              <generateApiTests>false</generateApiTests>
              <generateModelTests>false</generateModelTests>
              <generateApiDocumentation>false</generateApiDocumentation>
              <generateModelDocumentation>false</generateModelDocumentation>
            </configuration>
          </execution>
        </executions>
      </plugin>
      ```
      The plugin emits `BusinessServiceApi` (interface) under the apiPackage. The
      hand-written BoatController `implements BusinessServiceApi` — any contract
      drift becomes a compile error. Output goes to `target/generated-sources/openapi/`.
    </step>

    <step order="7">
      Create business-service/bootstrap/src/main/resources/application.yml (shared config):
      - server.port: 8081
      - spring.liquibase.change-log: classpath:db/changelog/db.changelog-master.yaml
      - spring.jpa.open-in-view: false
      NOTE: No session config (stateless JWT resource server)

      business-service/bootstrap/src/main/resources/application-dev.yml:
      - spring.datasource.url: jdbc:postgresql://localhost:5432/${BUSINESS_DB_NAME:boatapp}
      - spring.datasource.username: ${BUSINESS_DB_USER:business_service}
      - spring.datasource.password: ${BUSINESS_DB_PASSWORD:business_service}
      - spring.jpa.hibernate.ddl-auto: none
      - spring.liquibase.enabled: true
      - NO spring.security.oauth2.resourceserver config (dev: permitAll, no JWT needed)
      - server.servlet.session.cookie.secure: false (not used but harmless)

      business-service/bootstrap/src/main/resources/application-local-intg.yml:
      - spring.datasource.url: jdbc:postgresql://localhost:5432/${BUSINESS_DB_NAME:boatapp}
      - spring.datasource.username: ${BUSINESS_DB_USER:business_service}
      - spring.datasource.password: ${BUSINESS_DB_PASSWORD:business_service}
      - spring.security.oauth2.resourceserver.jwt.issuer-uri: ${KEYCLOAK_ISSUER_URI}
        (Keycloak publishes JWKS at {issuer-uri}/protocol/openid-connect/certs)

      business-service/bootstrap/src/main/resources/application-staging.yml:
      - spring.datasource.url: jdbc:postgresql://${POSTGRES_FQDN}:5432/${BUSINESS_DB_NAME:boatapp}?sslmode=require
      - spring.datasource.username: ${BUSINESS_DB_USER}
      - spring.datasource.password: ${BUSINESS_DB_PASSWORD}
      - spring.security.oauth2.resourceserver.jwt.issuer-uri: Azure Keycloak URL

      business-service/bootstrap/src/main/resources/application-prod.yml:
      - Same structure as staging with production URLs
    </step>

    <step order="8">
      Create the Business Service hexagonal package structure with placeholder classes:
      ch.owt.boatapp.domain.model/       → Boat.java, AppUser.java, BoatAudit.java (pure Java)
      ch.owt.boatapp.application.port.in/     → ManageBoatsUseCase.java, GetUserUseCase.java (interfaces)
      ch.owt.boatapp.application.port.out/    → BoatRepositoryPort.java, AppUserRepositoryPort.java, BoatAuditRepositoryPort.java (interfaces)
      ch.owt.boatapp.application.service/     → BoatApplicationService.java (@Service @Transactional bridge),
                                                BoatDomainService.java, UserDomainService.java (pure Java, wired by BeanConfig)
      ch.owt.boatapp.adapter.in.web/     → BoatController.java (implements BusinessServiceApi from the generated package)
                                         (DTOs live in dto.generated — do NOT create by hand)
      ch.owt.boatapp.adapter.in.web.dto.generated/ → generated at build time (do not create; gitignored)
      ch.owt.boatapp.adapter.in.web.generated/     → generated at build time (contains BusinessServiceApi interface)
      ch.owt.boatapp.adapter.out.persistence/ → entity/, mapper/, repository/
      ch.owt.boatapp.infrastructure.config/   → BeanConfig.java
      ch.owt.boatapp.infrastructure.security/ → ResourceServerSecurityConfig.java, DevSecurityConfig.java, SecurityHelper.java
    </step>

    <step order="9">
      Verify business-service/bootstrap/src/main/java/ch/owt/boatapp/BusinessServiceApplication.java exists
      (produced by Initializr). If Initializr emitted a different default name
      (e.g. DemoApplication), rename the class + file to BusinessServiceApplication. No other edits.
    </step>

    <step order="10">
      Create empty Liquibase master changelogs in both services:
      - business-service/infrastructure/src/main/resources/db/changelog/db.changelog-master.yaml
        (Migrations 001-APP_USER, 002-BOATS, 003-BOAT_AUDIT added in step 02a2.)
      - bff/src/main/resources/db/changelog/db.changelog-master.yaml
        (Migration 001-SPRING_SESSION added in step 02a2 step 5b.)
      Each service owns its own Liquibase history against its own database:
      business-service against `boatapp`, BFF against `bff_session`. No cross-DB access.
    </step>
  </instructions>

  <verification>
    Run the phase's verification script — it runs `mvnw generate-sources compile`
    for both services, checks hexagonal package layout, 4 application-*.yml
    profiles, generated API artefacts, and no committed client_secret:
    ```bash
    ai-scripts/checks/02a1/run.sh .
    ```
    All `fail` items must be green before `<commit>`.
    Human-only checks live in `ai-scripts/checks/02a1/human.md`.
  </verification>

  <commit>
    ```bash
    git add bff/ business-service/
    git commit -m "feat: scaffold BFF + Business Service with hexagonal architecture

    - BFF (port 8080): OAuth2 client, session-jdbc, Liquibase (owns bff_session DB), HTTP Interface client to business-service
    - Business Service (port 8081): JWT resource server, strict hexagonal, JPA, Liquibase (owns boatapp DB)
    - Profiles: dev (business-service only, no auth), local-intg (both services, full OAuth2+JWT)
    - Business Service domain (pure Java) enforced by ArchUnit
    - 1 PostgreSQL instance, 3 isolated databases (bff_session, boatapp, keycloak) with 3 dedicated roles"
    ```
  </commit>
</task>
