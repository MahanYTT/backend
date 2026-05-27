# Backend Testing Strategy

This document describes the tiered test strategy for the modl backend and tells you
which tier a new test belongs in.

## TL;DR — which tier?

```
Is the thing you want to test pure logic, no Spring, no I/O?
  └─ yes → Unit (src/test/java)
  └─ no → Does it touch Mongo, S3, or the Spring filter / message-converter chain?
            └─ yes, but you can isolate one slice → Slice (src/integrationTest/java with @DataMongoTest / @WebMvcTest)
            └─ yes, and you need multiple subsystems together → Integration (src/integrationTest/java with @SpringBootTest + Testcontainers)
            └─ you need to verify a real deployed environment → E2E (src/e2eTest/java)
```

## Tiers

### 1. Unit — `src/test/java/...`

**What.** Pure JUnit 5 + Mockito. No Spring context, no databases, no Docker. Loads in
milliseconds.

**When to use.** Branching logic, edge cases, validation, calculations, format
conversion. Anything where the inputs and outputs are well-defined and the
collaborators can be honestly mocked.

**Conventions.**
- Class name ends with `Test`.
- Use `@ExtendWith(MockitoExtension.class)` plus `@Mock` fields — do not construct mocks
  inline in `@BeforeEach`. (Several existing tests still do; see follow-up below.)
- Do not mock something just to test something else; if you need to mock more than ~3
  collaborators it's usually a sign the test should be a slice instead.

**How to run.** `./gradlew test`

### 2. Slice — `src/integrationTest/java/...`

**What.** Spring slice tests (`@WebMvcTest`, `@DataMongoTest`, `@JsonTest`) running
against Testcontainers infrastructure when persistence is involved.

**When to use.**
- **`@DataMongoTest`** — exercising query semantics, projections, $in, regex, updates,
  index expectations. Mock-based repository tests only verify query *syntax*, not
  *semantics*; this catches the difference.
- **`@WebMvcTest`** — exercising HTTP routing, message converters, validation,
  `@ControllerAdvice` exception handling, and any logic inside a `@RestController`.
  Spring Security filters are off by default (`@AutoConfigureMockMvc(addFilters = false)`)
  unless the test is specifically about authentication wiring.
- **`@JsonTest`** — serialization contracts.

**Conventions.**
- Class name ends with `IntegrationTest`.
- Extends one of `AbstractMongoIntegrationTest` / `AbstractS3IntegrationTest` for
  shared Testcontainers infrastructure.
- Add `@ActiveProfiles("test")` if not inherited (the base classes already declare it).
- Each test class is responsible for cleaning the collections / buckets it touches —
  use `@BeforeEach` to drop the relevant collections.

**Examples.**
- `database/mongo/repository/PlayerMongoRepositoryIntegrationTest.java`
- `database/MongoIndexBootstrapServiceIntegrationTest.java`
- `billing/controller/StripeWebhookControllerIntegrationTest.java`

**How to run.** `./gradlew integrationTest`

### 3. Full integration — `src/integrationTest/java/...`

**What.** `@SpringBootTest` running the full application context against Testcontainers
Mongo + LocalStack S3 + WireMock (for outbound HTTP). Mock the absolute minimum.

**When to use.** Behavior that spans subsystems and where mocking would defeat the
point — replay upload (auth → presign → S3 PUT → confirm → quota), Stripe webhook
end-to-end, multi-tenant filter chain configuration, scheduler wiring.

**Conventions.**
- Class name ends with `IntegrationTest`. Place under `src/integrationTest/java/`
  alongside slice tests; the naming convention is the same.
- Lean on `AbstractMongoIntegrationTest` / `AbstractS3IntegrationTest` so containers
  are JVM-shared.
- Use `@MockitoBean` to replace external collaborators that can't or shouldn't run
  for real (e.g. Stripe live API, Gemini, mail) — but never for Mongo or S3.

**How to run.** `./gradlew integrationTest`

### 4. E2E — `src/e2eTest/java/...`

**What.** Black-box HTTP + Mongo tests against a deployed staging environment
(`support/ApiClient`, `support/TestDatabase`).

**When to use.**
- Smoke tests of deployed contracts.
- Schema/migration verification against the real shared tenant.
- **Not** for catching regressions on a PR — these tests mutate shared state and
  are order-sensitive.

**Conventions.**
- Class name ends with `ApiTest`.
- Use `support/StagingCredentials` and `Assumptions.assumeTrue` to skip gracefully when
  credentials are not available locally.
- In CI the environment variable `MODL_E2E_REQUIRE_CREDENTIALS=true` is set, which
  causes `StagingCredentials` to fail loudly rather than silently skip.

**How to run.** Local: drop a `.env.test` in the repo root with `MODL_BASE_URL`,
`MODL_API_KEY`, `MODL_SESSION_TOKEN`, `MODL_SERVER_DOMAIN`, `MODL_MONGO_URI`, then
`./gradlew e2eTest`. CI runs them nightly via `.github/workflows/e2e.yml`.

## Where things live

```
backend/
├── src/
│   ├── main/java/                       # production code
│   ├── test/                            # tier 1 (unit)
│   │   ├── java/                        # *Test.java, pure Mockito
│   │   └── resources/
│   │       └── application-test.properties
│   ├── integrationTest/                 # tiers 2 & 3 (slice + integration)
│   │   ├── java/
│   │   │   ├── gg/modl/backend/support/ # base classes (Mongo, S3)
│   │   │   └── gg/modl/backend/.../*IntegrationTest.java
│   │   └── resources/                   # integrationTest-specific config
│   └── e2eTest/                         # tier 4 (staging)
│       ├── java/
│       │   ├── gg/modl/backend/support/ # ApiClient, TestDatabase, StagingCredentials
│       │   ├── gg/modl/backend/maintenance/  # @Disabled one-shot data routines
│       │   └── gg/modl/backend/.../*ApiTest.java
│       └── resources/
└── .github/workflows/
    ├── build.yml   # PR: ./gradlew build -x integrationTest && ./gradlew integrationTest
    └── e2e.yml     # nightly + workflow_dispatch: ./gradlew e2eTest
```

## Running locally

| Command                              | Tiers run                  |
|--------------------------------------|----------------------------|
| `./gradlew test`                     | Unit                       |
| `./gradlew integrationTest`          | Slice + Integration        |
| `./gradlew check`                    | Unit + Slice + Integration |
| `./gradlew e2eTest`                  | E2E (staging)              |

Slice + integration tests require Docker (Testcontainers). If Docker is unavailable
locally, run only the unit tier; CI runs the rest.

## Authoring conventions

- **Naming.** Pick the right suffix — `*Test` for unit, `*IntegrationTest` for slice
  and integration, `*ApiTest` for E2E. Avoid `*IntegrationTest` on tests that don't
  actually start a Spring context.
- **Dependencies.** Add new Testcontainers / WireMock deps only to
  `integrationTestImplementation`; do not pollute the unit tier with Docker-requiring
  libraries.
- **Profile.** All Spring-loading tests run under the `test` profile; overrides live
  in `src/test/resources/application-test.properties`. Add new placeholders there
  when introducing required `${VAR}` references in `application.properties`.
- **Shared containers.** Use the static-container pattern in
  `AbstractMongoIntegrationTest` / `AbstractS3IntegrationTest`. Do not spin up a fresh
  container per class — startup amortizes across the JVM.
- **Cleanup.** Test classes own the cleanup of their collections / buckets in
  `@BeforeEach`. Never assume a clean container.
- **No silent skips.** If a precondition is missing, fail loudly (CI) or skip with a
  visible message (locally). Avoid `assumeTrue` patterns that mask broken tests in CI.

## Integration test inventory

Currently in `src/integrationTest/java`:

- `database/mongo/repository/PlayerMongoRepositoryIntegrationTest` — `@DataMongoTest` against real Mongo; exercises 12 query scenarios.
- `database/MongoIndexBootstrapServiceIntegrationTest` — runs the index bootstrap against real Mongo, asserts unique/sparse/TTL/compound indexes match expectations.
- `billing/controller/StripeWebhookControllerIntegrationTest` — `@SpringBootTest` against real Mongo with mocked Stripe service. Computes real HMAC-SHA256 signatures; covers valid signature, tampered payload, wrong secret, expired timestamp, malformed header, missing header, not-configured.
- `infrastructure/filter/ApiKeyFilterSpringSecurityIntegrationTest` — `@WebMvcTest` with real `V1SecurityConfig`'s `SecurityFilterChain`; verifies `ApiKeyFilter` runs through the production chain (not standalone) for missing-key, invalid-key, and valid-key paths.
- `infrastructure/scheduling/ScheduledTaskWiringIntegrationTest` — loads every `@Scheduled` worker and queries `ScheduledAnnotationBeanPostProcessor.getScheduledTasks()` to assert each is registered with the expected `cron` / `fixedRate` / `fixedDelay`. All 9 assertions pass without Docker.
- `auth/PanelAuthSessionIntegrationTest` — `@SpringBootTest` with Testcontainers Mongo; drives `/v1/panel/auth/me` through the full security chain for missing/invalid/valid session cookies, plus cross-tenant session isolation.
- `auth/WebAuthnIntegrationTest` — covers `register/options` (requires session + persists challenge), `register/verify` (Yubico rejects forged attestation), `login/start` (discoverable challenge issued without session), `login/options` (anti-enumeration on unknown email + known email without passkeys), `login/verify` (garbage rejected without session cookie issued).
- `replaylite/ReplayLiteUploadFlowIntegrationTest` — full security-critical E2E with LocalStack S3 + Mongo: happy path (presign → PUT → confirm → quota), idempotent confirm, cross-server isolation, confirm-before-PUT (404 from missing S3 object), oversized PUT rejection.

## Open items

A full WebAuthn registration + assertion round-trip is not in this suite because
Yubico's `RelyingParty` performs real attestation cryptography and is constructed
inline in `WebAuthnService`. Implementing it requires either adding the
`webauthn4j-test` virtual-authenticator dependency or refactoring `WebAuthnService`
to inject `RelyingParty` so a test fake can substitute it. The current
`WebAuthnIntegrationTest` covers every controller path reachable without that
infrastructure: challenge issuance, persistence, and rejection of forged
attestations / assertions.

The stale-pending boundary (>15 minute old) is not directly covered in
`ReplayLiteUploadFlowIntegrationTest` because the production `Clock` bean is fixed
to system time. A dedicated test would require either swapping the `Clock` bean via
`@Primary` in a test config or directly waiting >15 minutes, neither of which fits
the existing pattern.
