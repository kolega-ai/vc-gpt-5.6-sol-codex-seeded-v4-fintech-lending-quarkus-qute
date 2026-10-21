# Northstar Capital — Loan Origination Portal

A server-rendered small-business lending application built with Java 21, Quarkus, Qute, Hibernate ORM/Panache, Flyway, and PostgreSQL. It covers borrower onboarding, document collection, review, underwriting decisions, operational administration, reports, and audit.

## Run locally

Prerequisites: Docker Desktop with Compose v2. No SaaS account or external service is used.

```bash
cp .env.example .env
# Replace both signing keys in .env with different random values for any shared environment.
docker compose up --build
```

Open <http://localhost:8080>. Flyway creates the schema and loads representative mock data on first startup. Uploaded files use the `loan-files` Docker volume. PostgreSQL uses the `lending-db` volume.

For development with a local Java 21 and PostgreSQL:

```bash
./gradlew clean build
./gradlew :decision:quarkusDev   # port 8081
./gradlew :core-mock:quarkusDev  # port 8082
./gradlew :channel:quarkusDev    # port 8080
```

Run checks with `./gradlew test` or the full build with `./gradlew clean build`. Jenkins runs the same build and publishes JUnit and JAR artifacts.

## Demo accounts

All identities and data are fictional. The demo password is `Welcome123!`.

| Role | Email |
|---|---|
| Admin | `admin@northstar.local` |
| Underwriter | `underwriter@northstar.local` |
| Support specialist | `support@northstar.local` |
| Borrower | `borrower@northstar.local` |

Eleven additional borrower accounts (`borrower2@northstar.local` through `borrower12@northstar.local`) exercise ownership filters. Do not reuse the demo password outside local development.

Password reset is a real single-use, 30-minute workflow. Because email is locally mocked, inspect the most recent message with:

```bash
docker compose exec postgres psql -U lending -d lending \
  -c "select recipient, body from outbox order by created_at desc limit 1"
```

## Trust boundaries

`channel` authenticates users using Quarkus form authentication and BCrypt. It exchanges the authenticated principal through a short-lived HMAC assertion for an opaque decision session. `decision` authorizes: on every request it hashes the opaque token, reloads the enabled user, role, ownership, assignment, and applicable server-owned policy from its own PostgreSQL data. It ignores client-supplied role, owner, scope, and limits.

`core-mock` is a separate network service and represents an external banking/KYB core. It only accepts short-lived, HMAC-signed scope tokens minted by `decision`; raw scope parameters are not trusted. Development signing defaults exist only to make one-command local startup possible and are overridden through environment variables.

## Main workflows and screens

- Public login, borrower registration, single-use password recovery, logout, and audited profile update.
- Role-specific overview with recent credit files, document actions, notifications, and activity.
- Borrower application wizard, list, credit-file detail, submit/withdraw/resubmit flow, file replacement, messages, and decision summary.
- Underwriter queue with status/risk/search filters, claim, document review, restricted notes, counteroffer, approval, and rejection.
- Support document/message workflow; decision endpoints reject this role server-side.
- Admin user/role/enable controls, audit viewer, bounded CSV lead import, and date-bounded decision/application export.
- Local document storage for financial, tax, identity, and business categories; upload validation and scoped download.
- Scheduled expiration reminders/outbox delivery plus local webhook/core integration records.

Application transitions are declared as an allowed edge list in `WorkflowService`; document transitions are likewise explicit. Final decisions require every required document to be accepted. Amounts are `BigDecimal` plus ISO currency, and product/policy limits come from seeded reference tables.

## Security controls

- Server-side role and object checks in the decision tier on details, mutations, downloads, lists, imports, and exports.
- Opaque, hashed, revocable decision sessions; strict same-site, HTTP-only form/session cookies; CSRF signatures on state-changing UI forms.
- Explicit DTO validation; bound Panache/JPA parameters; whitelisted sort columns; output escaped by Qute.
- PDF/PNG/JPEG allowlist, 10 MB limit, randomized storage names, SHA-256 integrity metadata, safe response headers, and canonical base-directory enforcement.
- Append-only audit rows protected by a database trigger. Sensitive reads are one audit event per request, including result count.
- CSV formula neutralization, bounded imports/date ranges, generic password-reset responses, and session revocation after reset.
- No committed production secret, raw tax identifier, unmasked bank account, or real personal data.

The five required benign precision locations are present: shared-service ownership, `java.util.Random` cache suffix, MD5 reference ETag, parameterized/whitelisted sorting, and canonicalized downloads. The Random and MD5 uses are explicitly non-cryptographic.

## UI system

The responsive Qute interface uses a lending-specific “credit file” shell: mineral navy navigation, coral workflow actions, accessible semantic status colors, serif document headings, dense operational tables, and a vertical application-stage rail. All controls have labels and visible keyboard focus; status text accompanies color.

## Completion note

All requested functional areas are implemented. The external banking, email, and webhook services are deliberately local mocks, as required. The named Quarkus/Qute stack takes precedence over the contradictory shared sentence that names Spring Boot; Java 21, Gradle, PostgreSQL, Docker Compose, and Jenkins requirements remain satisfied.
