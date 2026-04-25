---
paths: ["business-service/**/*.java", "business-service/pom.xml"]
---
# Business Service Rules — Strict Hexagonal Architecture + JWT Resource Server

## Hexagonal boundaries (enforced by ArchUnit)
- domain.model, domain.port.in, domain.port.out, domain.service → PURE JAVA ONLY
  - NO Spring annotations (@Component, @Service, @Repository, @Transactional)
  - NO Jakarta/javax annotations (@Entity, @Column, @Table)
  - NO framework imports (org.springframework.*, jakarta.*)
  - ONLY java.* and domain.* imports allowed
- adapter.in.web → Spring @RestController, stateless, depends on infrastructure.service
- adapter.out.persistence → Spring Data JPA, implements domain.port.out
  - JPA entities here (separate from domain models), with MapStruct mappers
- infrastructure.config → Spring @Configuration beans, wiring ports to adapters
- infrastructure.security → ResourceServerSecurityConfig (JWT), DevSecurityConfig (dev bypass)
- infrastructure.service → BoatApplicationService (@Service @Transactional, bridge layer)

## Inbound port design — Command and Query objects
- Mutations → <Action><Entity>Command (e.g. CreateBoatCommand)
- Reads → <Action><Entity>Query (e.g. ListBoatsQuery)
- Command/Query records live in domain.port.in (pure Java)
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
