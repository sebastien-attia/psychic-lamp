---
paths: ["business-service/**/*.java", "business-service/pom.xml"]
---
# Business Service Rules — Strict Hexagonal Architecture + JWT Resource Server

## Maven topology (four submodules, dependency direction is one-way)

```
business-service/  parent (packaging=pom)
├── domain/         pure Java jar, ZERO Spring/Jakarta deps
├── application/    depends on domain + spring-context + spring-tx
├── infrastructure/ depends on application + Spring web/JPA/security
└── bootstrap/      @SpringBootApplication, runnable jar (finalName=business-service)
```

The Maven graph physically enforces "domain has no Spring on its classpath" — `import org.springframework.*` inside the domain jar fails to compile.

## Hexagonal boundaries (enforced by Maven graph + ArchUnit)
- domain.model, domain.exception, domain.service.validation → PURE JAVA ONLY
  - Lives in the `domain` Maven module (no Spring/Jakarta deps on classpath).
  - NO Spring annotations (@Component, @Service, @Repository, @Transactional)
  - NO Jakarta annotations (@Entity, @Column, @Table)
  - ONLY java.* imports allowed (slf4j is also forbidden — domain has no logging)
- application.port.in, application.port.out → pure-Java interfaces and Command/Query records
  - Lives in the `application` Maven module. ArchUnit forbids Spring/Jakarta imports here too.
- application.service → use-case implementations
  - BoatApplicationService (@Service @Transactional bridge), BoatDomainService and UserDomainService (pure-Java orchestrators, wired as beans by BeanConfig).
- adapter.in.web → Spring @RestController, stateless, depends on application.service
- adapter.out.persistence → Spring Data JPA, implements application.port.out
  - JPA entities here (separate from domain records), with hand-written @Component mappers — no MapStruct, no annotation processor
- infrastructure.config → Spring @Configuration beans, wiring ports to adapters
- infrastructure.security → ResourceServerSecurityConfig (JWT), DevSecurityConfig (dev bypass)

## Inbound port design — Command and Query objects
- Mutations → <Action><Entity>Command (e.g. CreateBoatCommand)
- Reads → <Action><Entity>Query (e.g. ListBoatsQuery)
- Command/Query records live in application.port.in (pure Java)
- Value objects BoatId(UUID) and UserId(UUID) in domain.model

## Security
- Non-dev: spring-oauth2-resource-server validates JWT Bearer tokens
- JWT sub claim = keycloakId → used to sync/find AppUser
- Stateless: no session, no CSRF
- Dev: permitAll(), dummy AppUser auto-created on startup

## ArchUnit extra rule
- Business Service must NOT import org.springframework.security.oauth2.client.* (only resource-server allowed)

## Other rules
- Constructor injection only — never @Autowired on fields
- Liquibase for migrations (YAML) — never ddl-auto=update
- Boat: id (UUID), name (max 64), description (max 256), createdAt (OffsetDateTime UTC), version
- BoatAudit: INSERT-ONLY, FK to APP_USER
- APP_USER: synced from JWT claims (sub, preferred_username, email, given_name, family_name)
- Optimistic locking: @Version on JPA entity + ETag/If-Match in web adapter

## Jackson
- Spring Boot 4.0.6 auto-configures Jackson 3, NOT Jackson 2. The `ObjectMapper`
  bean is `tools.jackson.databind.ObjectMapper`. Wiring `com.fasterxml.jackson.databind.ObjectMapper`
  fails at startup with `NoSuchBeanDefinitionException`.
- Use Jackson 3 imports: `tools.jackson.databind.ObjectMapper`, `tools.jackson.core.JacksonException`.
- `JacksonException` is unchecked (extends `RuntimeException`) and exposes
  `getOriginalMessage()` — use it in place of Jackson 2's `JsonProcessingException`.
