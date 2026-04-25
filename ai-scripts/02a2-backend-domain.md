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

  <role>You are a senior Java architect implementing a strict hexagonal domain layer where the domain is 100% framework-free.</role>

  <context>
    <project>The Boat App — domain layer + persistence adapter</project>
    <existing-code>Hexagonal skeleton from Step 2A.1. Read existing package structure.</existing-code>
    <hexagonal-rule>
      CRITICAL: domain.model, domain.port.in, domain.port.out, domain.service
      must contain ZERO Spring/Jakarta annotations. Only java.* and domain.* imports.
      JPA annotations (@Entity, @Column, @Version, @Table) belong ONLY in
      adapter.out.persistence.entity — NOT in domain.model.
      The domain model and JPA entity are SEPARATE classes, mapped by MapStruct.
    </hexagonal-rule>
    <scope>only modify files under business-service/</scope>
  </context>

  <instructions>
    <step order="1">
      Create DOMAIN MODELS (ch.owt.boatapp.domain.model) — PURE JAVA:

      Boat.java:
      - UUID id, String name (max 64), String description (max 256, nullable)
      - java.time.OffsetDateTime createdAt, Long version
      - Constructor, getters, setters (or use a plain class — NOT a record if mutable)
      - NO annotations from Spring, Jakarta, Lombok, JPA. ONLY java.* imports.

      AppUser.java:
      - UUID id, String keycloakId, String username, String email
      - String firstName, String lastName
      - OffsetDateTime firstLogin, OffsetDateTime lastLogin
      - Pure Java.

      BoatAudit.java:
      - Long id, UUID boatId, String action (CREATED/UPDATED/DELETED)
      - String name, String description, Long version (snapshots)
      - UUID performedByUserId, OffsetDateTime performedAt
      - Pure Java.

      AuditAction.java (enum): CREATED, UPDATED, DELETED — pure Java.

      Also create VALUE OBJECTS (ch.owt.boatapp.domain.model) — pure Java records
      with compact-constructor INVARIANTS. These invariants are unconditional:
      the domain MUST be valid even when invoked by a non-REST caller (CLI,
      queue, test). The REST adapter's Jakarta Bean Validation is the *primary*
      syntactic gate, but value-object invariants remain the last line of defense.

      BoatId.java:
        public record BoatId(UUID value) {
            public BoatId {
                if (value == null) throw new IllegalArgumentException("BoatId.value must not be null");
                if (value.equals(new UUID(0L, 0L))) throw new IllegalArgumentException("BoatId.value must not be the nil UUID");
            }
        }

      UserId.java:
        public record UserId(UUID value) {
            public UserId {
                if (value == null) throw new IllegalArgumentException("UserId.value must not be null");
                if (value.equals(new UUID(0L, 0L))) throw new IllegalArgumentException("UserId.value must not be the nil UUID");
            }
        }

      These wrap raw UUIDs to provide compile-time type safety across port
      boundaries AND reject obvious invariant violations at construction time.
      No Spring, no Jakarta imports — plain java.util.UUID + IllegalArgumentException.

      Also create VALIDATION TYPES (ch.owt.boatapp.domain.model.validation) — PURE JAVA, zero Spring/Jakarta imports:

      Severity.java (enum): ERROR, WARNING, INFO
        Exactly three values. Matches the wire enum in contracts/openapi.yaml —
        no WARN/WARNING bridge is needed.

      MessageType.java (enum): CANNOT_BE_BLANK, CANNOT_BE_EMPTY, SIZE_EXCEEDED, INVALID_FORMAT
        Each enum value has a canonical stable APPLICATION CODE that is safe
        to emit on the wire. The mapping is an authoritative table — keep it
        in sync with messages.properties (see 02a3):
          | MessageType       | Application code          | messages.properties key   |
          | CANNOT_BE_BLANK   | field.required            | field.required            |
          | CANNOT_BE_EMPTY   | field.required            | field.required            |
          | SIZE_EXCEEDED     | field.size.invalid        | field.size.invalid        |
          | INVALID_FORMAT    | field.format.invalid      | field.format.invalid      |
        Expose the code via an `applicationCode()` method on MessageType so
        mappers and tests read one source:
          public enum MessageType {
              CANNOT_BE_BLANK("field.required"),
              CANNOT_BE_EMPTY("field.required"),
              SIZE_EXCEEDED("field.size.invalid"),
              INVALID_FORMAT("field.format.invalid");
              private final String applicationCode;
              MessageType(String code) { this.applicationCode = code; }
              public String applicationCode() { return applicationCode; }
          }
        NEVER emit MessageType.name() ("CANNOT_BE_BLANK", "SIZE_EXCEEDED") on
        the wire — the wire contract uses application codes only.

      ValidationMessage.java (record):
        public record ValidationMessage(Severity severity, MessageType type, String field) {}

      ServiceResponse.java (ch.owt.boatapp.domain.model — NOT in the validation sub-package):
      - Generic class: T data (null on failure), List&lt;ValidationMessage&gt; messages
      - static &lt;T&gt; ServiceResponse&lt;T&gt; success(T data)         → new ServiceResponse&lt;&gt;(data, List.of())
      - static &lt;T&gt; ServiceResponse&lt;T&gt; failure(List&lt;ValidationMessage&gt; msgs) → new ServiceResponse&lt;&gt;(null, msgs)
      - boolean hasErrors()  → messages.stream().anyMatch(m -&gt; m.severity() == Severity.ERROR)
      - NO Spring, NO Jakarta imports — only java.util.List and java.util.Collections.
    </step>
    <step order="2">
      Create INBOUND PORTS (domain.port.in) — command/query records + interfaces, pure Java:

      First, create COMMAND and QUERY records (pure Java records, co-located in domain.port.in).
      Naming convention (enforced by ArchUnit):
        mutations → &lt;Action&gt;&lt;Entity&gt;Command  (e.g. CreateBoatCommand, UpdateBoatCommand)
        reads     → &lt;Action&gt;&lt;Entity&gt;Query    (e.g. ListBoatsQuery, GetBoatQuery)
      All records must have ZERO Spring/Jakarta imports.

      CreateBoatCommand.java:
        public record CreateBoatCommand(String name, String description, UserId performedBy) {}

      UpdateBoatCommand.java:
        public record UpdateBoatCommand(BoatId id, String name, String description,
                                        Long expectedVersion, UserId performedBy) {}

      DeleteBoatCommand.java:
        public record DeleteBoatCommand(BoatId id, UserId performedBy) {}

      ListBoatsQuery.java:
        public record ListBoatsQuery(int page, int size, String sortBy, String sortDir, String search) {}

      GetBoatQuery.java:
        public record GetBoatQuery(BoatId id) {}

      SyncUserCommand.java:
        public record SyncUserCommand(String keycloakId, String username, String email,
                                      String firstName, String lastName) {}

      ManageBoatsUseCase.java:
      - PageResult&lt;Boat&gt;      listBoats(ListBoatsQuery query)
        (use domain PageResult&lt;T&gt; record defined in step 3 — NOT Spring's Page)
      - Boat                  getBoat(GetBoatQuery query)
      - ServiceResponse&lt;Boat&gt; createBoat(CreateBoatCommand command)
      - ServiceResponse&lt;Boat&gt; updateBoat(UpdateBoatCommand command)
      - void                  deleteBoat(DeleteBoatCommand command)
      Note: listBoats, getBoat, deleteBoat throw domain exceptions (BoatNotFoundException) — no ServiceResponse needed.

      GetUserUseCase.java:
      - AppUser syncUser(SyncUserCommand command)
      - AppUser getUserByKeycloakId(String keycloakId)   // single-arg lookup — primitive acceptable
    </step>
    <step order="3">
      Create OUTBOUND PORTS (domain.port.out) — interfaces, pure Java:

      BoatRepositoryPort.java:
      - Optional&lt;Boat&gt; findById(UUID id)
      - PageResult&lt;Boat&gt; findAll(int page, int size, String sortBy, String sortDir)
      - PageResult&lt;Boat&gt; search(String query, int page, int size, String sortBy, String sortDir)
      - Boat save(Boat boat)
      - void deleteById(UUID id)

      AppUserRepositoryPort.java:
      - Optional&lt;AppUser&gt; findByKeycloakId(String keycloakId)
      - AppUser save(AppUser user)

      BoatAuditRepositoryPort.java:
      - BoatAudit save(BoatAudit audit)

      Create PageResult&lt;T&gt; as a simple domain record:
      ```java
      public record PageResult<T>(List<T> content, long totalElements, int totalPages, int size, int number) {}
      ```

      Create DOMAIN VALIDATORS (ch.owt.boatapp.domain.service.validation) — PURE JAVA, zero Spring/Jakarta imports.

      ROLE OF THE DOMAIN VALIDATORS (read this carefully — the flow differs from the pre-RFC 9457 design):
        - Jakarta Bean Validation at the REST adapter (BFF controller + Business
          Service controller, both with @Valid, see 02a3) is the PRIMARY syntactic
          gate. Syntactic violations arriving via HTTP produce HTTP 400 with
          ProblemDetail.messages populated, using application codes.
        - SyntacticValidator below is DEFENSE-IN-DEPTH: it is still called by
          BoatDomainService so the domain stays self-protecting when invoked
          from a non-REST adapter (CLI, queue consumer, integration test) where
          Bean Validation never ran.
        - When a message produced by SyntacticValidator reaches a BoatDomainService
          caller, the service returns ServiceResponse.failure(messages), which
          BoatApplicationService translates to ValidationFailureException → HTTP
          422 (per the design: any domain-origin error is 422, regardless of
          whether the underlying rule is "syntactic" or "semantic"). This is the
          correct behavior — if syntactic input reached the domain from a
          non-adapter path, there is no "request" to call 400 on.

      SyntacticValidator.java:
      - List&lt;ValidationMessage&gt; validate(String name, String description):
          * if name is null or blank → add ValidationMessage(ERROR, CANNOT_BE_BLANK, "Boat.name")
          * else if name.length() &gt; 64 → add ValidationMessage(ERROR, SIZE_EXCEEDED, "Boat.name")
          * if description != null &amp;&amp; description.length() &gt; 256
              → add ValidationMessage(ERROR, SIZE_EXCEEDED, "Boat.description")
          * Returns an unmodifiable list.

      SemanticValidator.java:
      - List&lt;ValidationMessage&gt; validate(String name, String description):
          → always returns List.of()
          // Placeholder for business rules (e.g. uniqueness checks). Extend here when needed.
          // Any rule added here surfaces as HTTP 422 via ValidationFailureException.
    </step>
    <step order="4">
      Create PERSISTENCE ADAPTER (adapter.out.persistence):

      entity/BoatJpaEntity.java:
      - @Entity @Table(name = "boats")
      - All JPA annotations: @Id, @GeneratedValue, @Column, @Version
      - This is the ONLY place where JPA annotations exist

      entity/AppUserJpaEntity.java:
      - @Entity @Table(name = "app_user")

      entity/BoatAuditJpaEntity.java:
      - @Entity @Table(name = "boat_audit")
      - @ManyToOne for performedBy → AppUserJpaEntity

      mapper/BoatPersistenceMapper.java (MapStruct):
      - toDomain(BoatJpaEntity) → Boat (domain model)
      - toJpaEntity(Boat) → BoatJpaEntity
      - Similar for AppUser and BoatAudit

      repository/BoatJpaRepository.java:
      - extends JpaRepository&lt;BoatJpaEntity, UUID&gt;
      - search query method

      repository/BoatRepositoryAdapter.java:
      - @Repository, implements BoatRepositoryPort
      - Uses BoatJpaRepository + BoatPersistenceMapper
      - Converts between JPA entities and domain models
      - Similarly for AppUserRepositoryAdapter and BoatAuditRepositoryAdapter
    </step>
    <step order="5">
      Create Business Service Liquibase changelogs (business-service/src/main/resources/db/changelog/),
      applied against the `boatapp` database as role `business_service`:
      - 001-create-app-user-table.yaml (APP_USER)
      - 002-create-boats-table.yaml (BOATS: id UUID PK, name VARCHAR 64, description VARCHAR 256, created_at TIMESTAMPTZ, version BIGINT)
      - 003-create-boat-audit-table.yaml (BOAT_AUDIT: FK to APP_USER)
      Business Service MUST NOT create SPRING_SESSION tables — those belong to the BFF
      and live in a different database (`bff_session`) owned by role `bff`. See step 5b.
    </step>
    <step order="5b">
      Create BFF Liquibase changelogs (bff/src/main/resources/db/changelog/),
      applied against the `bff_session` database as role `bff`.
      Numbering restarts at 001 under BFF ownership — this is a fresh, BFF-owned history.
      - db.changelog-master.yaml (includes changes/001-create-spring-session-tables.yaml)
      - changes/001-create-spring-session-tables.yaml (Spring Session JDBC — exact DDL,
        spring.session.jdbc.initialize-schema: never):
          SPRING_SESSION:
            PRIMARY_ID CHAR(36) NOT NULL PRIMARY KEY,
            SESSION_ID CHAR(36) NOT NULL UNIQUE,
            CREATION_TIME BIGINT NOT NULL,
            LAST_ACCESS_TIME BIGINT NOT NULL,
            MAX_INACTIVE_INTERVAL INT NOT NULL,
            EXPIRY_TIME BIGINT NOT NULL,
            PRINCIPAL_NAME VARCHAR(100)
          SPRING_SESSION_ATTRIBUTES:
            SESSION_PRIMARY_ID CHAR(36) NOT NULL (FK → SPRING_SESSION.PRIMARY_ID ON DELETE CASCADE),
            ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
            ATTRIBUTE_BYTES BYTEA NOT NULL,
            PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME)
          Indexes: SPRING_SESSION_IX1 on SESSION_ID, SPRING_SESSION_IX2 on EXPIRY_TIME, SPRING_SESSION_IX3 on PRINCIPAL_NAME
    </step>
  </instructions>

  <verification>
    Run the phase's verification script — it compiles both services, greps
    domain for Spring/Jakarta imports, confirms @Entity is in
    adapter.out.persistence (not domain.model), and checks Liquibase
    changelogs for app_user/boats/boat_audit tables:
    ```bash
    ai-scripts/checks/02a2/run.sh .
    ```
    All `fail` items must be green before `<commit>`.
    Human-only checks live in `ai-scripts/checks/02a2/human.md`.
  </verification>

  <commit>
    ```bash
    git add -A
    git commit -m "feat(backend): hexagonal domain layer (pure Java) + persistence adapter

    - Domain models: Boat, AppUser, BoatAudit — ZERO framework imports
    - Validation types: Severity, MessageType, ValidationMessage, ServiceResponse<T> (pure Java)
    - Domain validators: SyntacticValidator (name/description rules), SemanticValidator (placeholder)
    - ManageBoatsUseCase.createBoat/updateBoat now return ServiceResponse<Boat>
    - Outbound ports: repository port interfaces (pure Java)
    - Persistence adapter: JPA entities (separate), MapStruct mappers, Spring Data repos
    - Liquibase (business-service → boatapp): APP_USER, BOATS, BOAT_AUDIT
    - Liquibase (BFF → bff_session): SPRING_SESSION, SPRING_SESSION_ATTRIBUTES (spring.session.jdbc.initialize-schema: never)"
    ```
  </commit>
</task>
