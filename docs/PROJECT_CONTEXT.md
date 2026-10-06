# PROJECT_CONTEXT.md

## Project Summary

This repository contains the backend for an urgent job service.

The project is being designed as an MSA-based backend where each service owns its own database boundary and communicates with other services through API or event contracts.

The current repository is in an early backend setup stage. Implemented service directories are:

- `auth-service`
- `member-service`
- `job-service`
- `matching-service`
- `work-service`
- `chat-service`
- `payment-service`
- `notification-service`

The broader domain and service boundaries are currently represented in:

- `docs/urgent_job_service_msa_erd_v1.drawio`

Architecture notes:

- `docs/architecture/service-package-structure.md`
- `docs/architecture/mvp-domain-flow.md`
- `docs/architecture/matching-application-design.md`

## Domain

The service domain is urgent job matching.

The system is expected to support users who need urgent work to be filled, workers who can accept jobs, and the operational flows around matching, work progress, payment, notifications, chat, and support.

Expected domain areas from the ERD:

- authentication and account access
- user profiles and user-owned information
- job posting and job search
- matching between jobs and workers
- work execution and work status tracking
- payment and settlement
- notifications
- chat
- customer support

## Current Stage

The project is currently in the initial backend scaffolding stage.

Current state:

- `auth-service` exists as a Spring Boot service.
- `auth-service` has implemented the initial LOCAL/OAuth authentication and signup flow.
- `member-service` exists as a Spring Boot service.
- `member-service` has implemented the initial member, owner, worker, and location profile flow.
- `job-service` has implemented the initial job posting domain.
- `job-service` protects internal service-to-service endpoints under `/api/jobs/internal/**` with the shared `X-Internal-Secret` header, matching auth-service and member-service. The application-admission endpoint (`POST /api/jobs/internal/{jobPostId}/application-admissions`) is implemented behind this protection; it locks the job row, checks `OPEN` and the application deadline, and returns the same admission for a repeated `Idempotency-Key`. The matching-seat reservation, confirmation, and release endpoints and expired-reservation recovery are implemented. The last seat confirmation closes the job for recruitment completion and durably notifies matching-service. New jobs are stored privately as `PAYMENT_PENDING` and request a payment-service order through a durable command. The internal funding-status endpoint publishes them to `OPEN` after verified funding and blocks new admissions and seat reservations after a verified cancellation. Owners can change the payment terms of a `PAYMENT_PENDING` job or re-pay a failed order; the new order replaces the current one only after payment-service accepts the replacement and job-service links it. Owners can close their own jobs, and a scheduler moves jobs past the application deadline or work start; closes of published jobs notify matching-service through the recruitment-completion command. Marking admissions `CONSUMED` from `ApplicationSubmitted` events is not yet implemented.
- `job-service` tests use Testcontainers with MySQL for the test datasource.
- `matching-service` has service-owned MySQL and Flyway configuration, the application persistence model, and the worker-facing application create, read, list, and cancel APIs.
- The repository has a first ERD draft for the MSA design.
- The matching application domain has a focused ERD and implementation design that supersede the application and scoring tables in the first ERD draft.
- A repository-wide verification script exists at `docs/scripts/verify.sh`.
- A local Docker Compose file exists at `compose.local.yml` for auth/member/job/matching/work/chat/payment/notification MySQL instances and auth/matching Redis instances.
- `.env.example` documents the local runtime environment variables; the real `.env` file is ignored by Git.
- `scripts/local-run.sh` loads `.env` and runs each service locally.
- The service package structure is defined in `docs/architecture/service-package-structure.md`.
- `auth-service` tests use Testcontainers with MySQL and Redis for integration-test dependencies.
- `member-service` tests use Testcontainers with MySQL for the test datasource.
- Agent work instructions exist in `AGENTS.md`.
- Agent failure memory, decision memory, and checklist memory exist under `docs/agent/`.
- Repository-specific CodeRabbit review settings exist in `.coderabbit.yaml`.

## Current Technical State

Current `auth-service` scaffold:

- Java 17
- Spring Boot 4.1.0
- Gradle
- Spring Web MVC
- Spring Security
- Spring Data JPA
- MySQL
- Redis
- Flyway
- Testcontainers MySQL for integration-test datasource
- Testcontainers Redis for integration-test Redis access
- Spring Boot Actuator
- Bean Validation
- Lombok
- Springdoc OpenAPI

Current `member-service` scaffold:

- Java 17
- Spring Boot 4.1.0
- Gradle
- Spring Web MVC
- Spring Security
- Spring Data JPA
- MySQL
- Flyway
- Testcontainers MySQL for integration-test datasource
- Spring Boot Actuator
- Bean Validation
- Lombok

Current `matching-service` scaffold:

- Java 17
- Spring Boot 4.1.0
- Gradle
- Spring Web MVC
- Spring Data JPA
- MySQL
- Redis
- Flyway
- Testcontainers MySQL for integration-test datasource
- Testcontainers Redis for integration-test Redis access
- Spring Boot Actuator
- Bean Validation
- Lombok
- Spring Security
- Springdoc OpenAPI

Current matching application implementation:

- JWT-authenticated `WORKER` members can create, read, list, and cancel their own applications.
- Application creation validates an `ACTIVE` `WORKER` through the existing member-service internal API.
- The matching-service client for the job-service application-admission contract is implemented, and the job-service endpoint is available. matching-service maps both the documented symbolic codes and job-service domain codes (`JOB-409-001`~`003`) to application errors.
- Application creation and cancellation persist status history and a `PENDING` Outbox event in the same local transaction.
- After the application transaction commits, the matching-service projects `APPLIED` application IDs into a job-specific Redis Set and synchronizes the current score ranking. Cancellation removes the ID and recalculates the remaining ranking.
- A scheduled recovery rebuilds each application Set from MySQL, which remains the source of truth. It restores the latest current-policy `READY` score batch or recalculates when the active applications and score snapshots differ. Score calculation commits before Redis projection, and Redis failures are logged without rolling back an application or completed score batch.
- The exact cancellation deadline and penalty policy remain undecided. The current API allows cancellation only while the application is `APPLIED`.
- An owner can select an `APPLIED` applicant through the manual matching endpoint. This creates one `MANUAL`, `PENDING` matching per application, records matching status history, and preserves the selected score batch and `READY` snapshot when available.
- Manual candidate selection does not reserve recruitment capacity or complete a match. A worker cancellation atomically cancels a related `PENDING` matching before canceling the application.
- A worker can list and read only their own matching proposals and can idempotently decline a `PENDING` proposal. Declining records a `DECLINED` matching history while the application remains `APPLIED`.
- A worker matching acceptance endpoint and a persisted confirmation Saga now coordinate recruitment-seat reservation, payment locking, scheduled-work creation, chat-room creation, and reverse-order compensation. Only a fully completed Saga changes the matching to `CONFIRMED` and the application to `SELECTED` and stores `ApplicationSelected` and `MatchConfirmed` Outbox events.
- Confirmation commands persist separate stable idempotency keys for forward and compensation steps and retain external resource IDs before advancing. A lease prevents concurrent coordinators. Unknown outcomes resume the same forward command without premature compensation, while definitively rejected commands compensate known resources and start a new attempt only after compensation succeeds.
- The payment-service implements deposit-backed lock/release commands, and Toss test-payment verification/ingestion. job-service seat reservation, private `PAYMENT_PENDING` creation, order provisioning, and funding-driven publication are implemented. Acceptance for an unfunded (pre-V10) job is rejected at the payment-lock step and compensated, and a seat confirmation after a funding block is rejected with a 4xx that the Saga compensates; no temporary successful confirmation is fabricated. A 2026-10-06 local cross-process run verified job creation, test-double-funded publication, application, manual selection, confirmation Saga, scheduled work and GPS check-in over real HTTP and the matching Redis Stream. Member creation used a local NTS double and generated test JWTs; real signup/login, product frontend and actual provider checkout were not exercised.
- A well-formed seat-reservation response whose `expiresAt` has passed is treated as a definitive rejection: the Saga records and releases that reservation, then starts a new attempt with new command keys. Network failures, 5xx, and malformed responses remain unknown outcomes. The seat snapshot's `endTimeNextDay` is stored on the Saga and passed to scheduled-work creation.
- A shared-secret internal recruitment-completion endpoint rejects remaining `APPLIED` applications and cancels their `PENDING` matching proposals in one transaction. It stores the completed job version as a durable barrier against late applications, rebuilds the Redis queues once per job, persists status histories and `ApplicationRejected` Outbox events, and refuses completion while a confirmation Saga has an unknown outcome or unfinished compensation. A higher job version permits applications after reopening. The job-service producer now calls it after the last seat confirmation and retries the same command until it succeeds.
- A database-leased Outbox relay publishes domain-event envelopes to the `matching:domain-events` Redis Stream. It preserves aggregate revision order, retries failures with capped exponential backoff, recovers expired leases, and provides at-least-once delivery with stable `eventId` values for consumer deduplication. Local Redis uses synchronous AOF persistence; production event Redis must provide equivalent durability and a no-eviction policy.
- Versioned score batch and application score snapshot persistence is implemented with `CALCULATING`/`READY`/`FAILED` lifecycle states. Missing external inputs remain nullable and are distinguished through `missing_inputs` instead of fabricated zero scores.
- The initial `application-time-v1` policy calculates a relative score from deterministic application order and creates a new immutable batch on recalculation. Ranked MySQL reads use total score, application time, and application ID order.
- The latest current-policy `READY` score batch is projected into a batch-specific Redis Sorted Set with `scoreBatchId` and `policyVersion` metadata. Replacing a ranking atomically removes the previous batch key, and a per-job fencing token rejects stale replacements after a distributed lock lease expires.
- JWT-authenticated `OWNER` members can query their own job's active applicants through list and detail APIs. The list pins an immutable `READY` score batch across pages, returns scored applicants first, and retains failed or unscored applicants with nullable score fields.
- Application admissions now preserve the job owner member ID as an external snapshot for applicant-query isolation. The database column stays nullable only for pre-migration rows; those rows remain hidden from owner queries until a job-service ownership contract supports backfill.
- `member-service` provides a batch internal contract that returns only active worker IDs and names for applicant display; missing summaries remain nullable without exposing email, phone, or location.
- Multi-factor scoring and its input adapters remain later implementation units. Missing rating, experience, no-show, online-status, and ETA inputs must be connected when their owning service contracts become available.

The current scaffold matches the decided technology baseline in `docs/agent/decisions.md`.

## Service Map

Implemented:

- `auth-service`: authentication service
- `member-service`: member profile service
- `job-service`: job posting service
- `matching-service`: application and matching service
- `work-service`: scheduled work and Saga compensation service
- `chat-service`: internal chat room creation and Saga compensation, matching-confirmation consumption, participant room queries, and persistent text message sending/history
- `payment-service`: deposit-backed payment lock and Saga compensation service with Toss test deposit ingestion
- `notification-service`: matching-result in-app notifications and participant read state

Planned or represented in the ERD:

- `support-service`

This service map is provisional. Use `docs/agent/decisions.md` for confirmed decisions that override this document.

## Current ERD Notes

The ERD describes service ownership and relationship types using:

- `PK`: primary key
- `FK`: foreign key inside the same service database
- `EXT`: external service ID reference, not a physical database foreign key
- `UQ`: unique constraint

For the matching application domain, use `docs/architecture/matching-application-erd.drawio` and `docs/architecture/matching-application-design.md`. The application and scoring tables in the first repository-wide ERD are retained as an early draft and are not the implementation contract.

## Auth Service Context

`auth-service` is currently the authentication service.

Expected responsibilities:

- account authentication
- login/logout flow
- JWT access token issuance and validation
- refresh token renewal flow
- security configuration
- password hashing
- refresh token storage, rotation, expiration, and revocation

Current implementation state:

- `auth-service` owns authentication state, LOCAL credentials, OAuth connections, verification flows, JWT issuance, refresh token rotation, and logout.
- Initial auth account, credential, OAuth connection, refresh token entities, and related enums have been added.
- Initial auth account, credential, OAuth connection, and refresh token repositories have been added.
- Initial auth request/response DTOs and member-service client DTOs have been added.
- Redis-backed verification code service has been added.
- Initial JWT access token issuance/validation, refresh token rotation, and refresh token hashing support have been added.
- Initial member-service REST client support has been added.
- Initial member-service REST client calls include a shared internal secret header for service-to-service APIs.
- LOCAL signup/login, token reissue, and logout service layer support has been added.
- Email-code password reset is available for active accounts with LOCAL credentials. Reset codes are atomically consumed once, and password changes revoke every device's refresh tokens in the same DB transaction. Account locks serialize reset with LOCAL login and token issuance/reissue; existing access tokens remain valid until expiration.
- LOCAL signup requires email verification, SMS verification, password input, and role-specific additional information before final account creation.
- Signup calls member-service first and compensates by deleting the created member if auth-service persistence fails afterward.
- Signup service-to-service calls run outside the auth database transaction; auth account, credential or OAuth connection, and refresh token persistence use a separate short transaction.
- Initial auth controllers, Swagger/OpenAPI documentation, and basic stateless security configuration have been added.
- Auth API responses and controller-level errors use the common `ApiResponse` envelope.
- Auth security 401/403 responses are written as the common `ApiResponse` envelope.
- Core auth logic tests cover refresh token rotation, LOCAL login, signup verification checks, token hashing, and JWT validation.
- Refresh token reissue checks account status and locks the refresh token row during rotation.
- Verification code sending has a Redis-backed short rate limit, and verification attempts are limited before the code is invalidated.
- Verification email delivery uses Gmail SMTP and SMS delivery uses SOLAPI. Definite email authentication failures and provider rejections return a safe 502 and restore any still-valid previous code; uncertain delivery errors retain the new code until its TTL. The one-minute send limit remains active after either failure. Production delivery requires a Gmail app password and SOLAPI credentials and registered sender number.
- Initial KAKAO/NAVER OAuth2 login support has been added.
- OAuth2 login connects to an existing OAuth connection, links same-email accounts when no connection exists, or issues a Redis-backed signup ticket for new users.
- OAuth2 signup ticket completion supports OWNER/WORKER signup without creating `LocalCredential`.
- OAuth2 signup tickets retain their original 30-minute expiration when restored after a retryable owner-signup rejection.
- OAuth2 provider access currently uses provider authorization code exchange through backend API calls rather than Spring Security's redirect-based OAuth2 login flow.
- Initial Flyway schema migration has been added.

Auth-related decisions are recorded in `docs/agent/decisions.md`.

## Account Management Update (2026-10-06)

- ACTIVE members can patch their own name and role-specific profile through `PATCH /api/members/me`.
- Authenticated contact-change APIs use separate, atomically consumed CONTACT_CHANGE verification codes. Auth stores durable commands, blocks new login/token issuance while UPDATING, revokes all refresh sessions, and synchronizes member-service with stable idempotent results and retry backoff.
- Member withdrawal is implemented with durable prepare/release/commit coordination, per-service creation barriers, and ongoing job/application/matching/work/payment checks. Successful withdrawal erases member profiles and auth credentials, revokes existing JWT access through local gates, and leaves reference tombstones. Unknown payment completion remains a blocker until refund/settlement contracts exist. Domain history retention expiry automation is not implemented.
- API contracts and the withdrawal proposal are documented in `docs/architecture/member-account-management.md`.

## Member Service Context

`member-service` stores member profile data owned by the member domain.

Expected responsibilities:

- member basic information storage
- owner profile storage
- worker profile storage
- location storage
- member information lookup

Current implementation state:

- `member-service` owns member basic information, role-specific profile data, worker preferences, worker available times, and location data.
- Initial member, owner, worker, and location entities have been added.
- Initial repository, service, and controller layers have been added.
- Basic member-service security configuration permits Swagger and internal member APIs.
- `member-service` verifies auth-service JWT access tokens directly for the current `/api/members/me` flow.
- Internal member APIs under `/api/members/internal/**` require the shared `X-Internal-Secret` header.
- An internal signup compensation endpoint can delete a member created before auth-service persistence fails.
- Signup compensation deletion is covered by an integration test that verifies member, profile, location, and worker child records are deleted.
- Member API responses, controller-level errors, internal API secret failures, and security 401/403 responses use the common `ApiResponse` envelope.
- Location data stores both address and latitude/longitude so address can be used for display and coordinates can support future radius-based search.
- OWNER signup stores a user-entered store name and accepts only business numbers that the National Tax Service status API reports as operating.
- Business status lookup does not verify representative identity or business ownership.
- Initial Flyway schema migration has been added.

Member signup design notes are recorded in `docs/architecture/auth-member-signup-design.md`.

## Job Service Context

- `job-service` uses Java 17, Spring Boot 4.1.0, service-owned MySQL, Flyway, Spring Security, and Springdoc.
- It owns job posts and industry categories. Owners create jobs with `POST /api/jobs` (`OWNER` JWT). Search returns only `OPEN` jobs that are not funding-blocked. Detail reads are public (including funding-blocked jobs) for jobs that were ever published (`job_posts.published`, V19). `PAYMENT_PENDING` jobs and jobs closed before publication can be read only by the authenticated owner (by JWT `memberId`); others get 404.
- New jobs are created as `PAYMENT_PENDING` (private). `PAYMENT_PENDING`/`OPEN`/`MATCHING`/`CLOSED` are defined. Implemented transitions are `PAYMENT_PENDING -> OPEN` after verified funding, recruitment completion to `CLOSED`, the deadline-based transitions, and owner manual close, all recorded in `job_status_histories`. Existing jobs created before V10 keep their status and have no payment order; they are not deposit-backed, so matching acceptance fails at the payment-lock step.
- Job creation stores a `job_payment_order_commands` row (V10) in the same transaction with an immutable snapshot (job ID, payment job version, owner, total deposit in integer KRW >= 100, `KRW`, UUID `Idempotency-Key`, per-job issue sequence). A leased dispatcher (after-commit trigger plus scheduler, JDK `HttpClient` with a total `PAYMENT_SERVICE_CALL_TIMEOUT`) calls `POST /api/payments/internal/orders` outside any transaction, verifies the `ApiResponse` envelope, required fields, snapshot echo, and linkable order status (`READY`/`CONFIRMING`/`DEPOSITED`/`FAILED`/`REVIEW_REQUIRED`), then records the command and links `payment_order_id` plus the original payment snapshot to the job in one transaction, fenced by lease token and latest issue sequence. Timeouts, network errors, 429 and 5xx retry with backoff using the same key and body; authentication, 409, contract, snapshot mismatch, and `SUPERSEDED` responses stay pending at the maximum delay with operator error logs, except that a 409 for a replacement command is a definitive rejection (see payment changes below). Order creation never publishes a job.
- Funding status: `POST /api/jobs/internal/{jobPostId}/funding-status` (V11) locks the job row, replays stored responses for a repeated `Idempotency-Key` or the same order/revision before any version or status check, and rejects key reuse (`JOB-409-004`) and same order/revision content conflicts (`JOB-409-012`). It compares the order ID, payment job version (not `@Version`), owner, integer KRW amount (scale-insensitive), and currency with the linked order snapshot, stores the last applied revision per order, and records receipts, revision, block flag, publication, and history in one transaction. A notification that arrives before order linking gets a retryable 409 (`JOB-409-013`) without being recorded, and payment-service's unlimited same-key retries deliver it after linking; unrelated or mismatched orders get 409 (`JOB-409-011`). Lower or equal revisions and earlier-order notifications return 200 (`STALE_REVISION`/`STALE_ORDER`) without changing state. `funded=true` publishes a `PAYMENT_PENDING` job only before its application deadline and work start; closed or expired jobs are not published, return 200 `PUBLICATION_SKIPPED`, and are flagged for refund review. `funded=false` sets `funding_blocked` without changing status; new admissions (`JOB-409-001`), new seat reservations, and first confirmations of `RESERVED` seats (`JOB-409-005`) are rejected, while idempotent replays and `CONSUMED` seats are preserved. A higher `funded=true` revision clears the block but never reopens a closed job. No refund is executed.
- Owners list their own jobs with `GET /api/jobs/me` (`OWNER` JWT; owner from `memberId` only). It includes every status and funding-blocked jobs, filters by an optional `status`, sorts by `created_at DESC, id DESC`, and pages in the database (page 0 / size 20 / max 100) using the V15 `(owner_id, created_at, id)` index. Each card has the confirmed (`CONSUMED` seat) count aggregated once per page; `applicantCount` is `null` and payment progress stays in the payment-order query.
- Owners read the order creation state with `GET /api/jobs/{id}/payment-order` (`PENDING` with `paymentOrderId=null`, `CREATED` with the verified ID, or `NOT_REQUESTED` for legacy jobs) and the latest payment change request (`latestChange`).
- Payment changes (V13): `PUT /api/jobs/{id}/payment-terms` (work date/start/end/next-day, base and hourly extra wage, recruit count, application deadline) and `POST /api/jobs/{id}/payment-order/retries` are `OWNER`-only, authorize by JWT `memberId` (others get 404), and require an `Idempotency-Key` (same key and request returns the current state; a different request is `JOB-409-004`). Only `PAYMENT_PENDING` jobs with a linked order, no in-flight order command (`JOB-409-015`), no funding state applied to the current order, and no admission or seat history can change (`JOB-409-014`). The request stores a pending terms snapshot and the next order command in one transaction without changing the job. A terms change uses the highest command payment version + 1; a same-terms re-payment keeps the linked version and amount with a new key, so payment-service replaces only a `FAILED` order. Linking the verified new order applies the terms in the same transaction and never publishes or unblocks; a payment-service 409 rejects the change and preserves the current terms and order; timeouts, 5xx, and malformed responses stay pending and converge with the same key. A new order created for a job that can no longer be replaced is not linked and its later funding is treated as an earlier order.
- Funding receipts that publish nothing (`PUBLICATION_SKIPPED`, earlier-order `funded=true`) create one refund-review record per order (V12) linked to the first such receipt. No refund is executed.
- Business ownership goes through the `BusinessValidator` port; the current `StubBusinessValidator` only logs and must not be treated as real verification.
- Search returns `OPEN`, non-funding-blocked jobs before their application deadline and filters, sorts, and pages in memory. The detail `applicantCount` is `null` until a matching-service count contract exists.
- `job_posts.version` is the JPA optimistic-lock value, starts at 1, and is copied into application admissions as `jobVersion`.
- The internal application-admission API locks the job row, checks `OPEN`, the funding block, and the deadline, and returns the original admission for a repeated `Idempotency-Key`. It rejects key reuse for a different job or worker with `JOB-409-004`.
- Internal matching-seat reservation/confirm/release APIs lock the job row then the reservation, accept `OPEN`/`MATCHING` jobs before work start, keep `CONSUMED + valid RESERVED <= recruitCount`, and return issued snapshots (including `endTimeNextDay` and the per-worker `lockedAmount`) for repeated keys. Reservations expire after a configurable 10-minute TTL; a scheduler and each new reservation recover expired seats. Reservation timestamps are truncated to microseconds before storage, so a repeated key returns the same times that were first issued.
- `JobWageCalculator` computes the per-worker expected wage as floor(work minutes × (base + hourly extra wage) / 60) in integer KRW and the total deposit as per-worker × `recruitCount`; totals below 100 KRW are rejected, not rounded up. Break-time deduction and time-band premiums are undecided.
- Recruitment completion: when `CONSUMED` seats reach `recruitCount`, the last seat confirmation transaction also moves the job from `OPEN`/`MATCHING` to `CLOSED`, writes `job_status_histories` (`SYSTEM:RECRUITMENT_FILLED`), and stores a `job_recruitment_completion_commands` row (V9) with a UUID command ID and the post-transition job version. Any failure rolls back the seat confirmation too. Confirm retries on a closed job still succeed.
- A leased dispatcher sends `POST /api/applications/internal/jobs/{jobPostId}/recruitment-completion` outside the transaction: claim in one short transaction, call over HTTP with a total per-call deadline (JDK `HttpClient`; the exchange is cancelled and its connection closed when `MATCHING_SERVICE_CALL_TIMEOUT` expires, even while the body is still arriving), then record the result only if the lease token still matches. The lease must exceed that deadline by at least one second. An after-commit trigger attempts immediate delivery and a scheduler recovers pending commands. 409 (Saga still finishing), timeouts and network errors (including failures while receiving the body, which keep the received status only as diagnostics), 429, and 5xx retry with capped exponential backoff; a fully received but malformed or non-success 2xx body is a contract failure; authentication and contract failures stay pending at the maximum delay and are logged as errors. Commands are never deleted or marked successful on failure.
- A bounded reconciler closes jobs whose seats were all consumed before this implementation, using the same lock and criteria.
- Job close and deadline transitions (V17, V18): a 1-minute scheduler moves `PAYMENT_PENDING` past the application deadline to `CLOSED` and `OPEN` to `MATCHING` (both `SYSTEM:APPLICATION_DEADLINE_PASSED`, no notification), and `OPEN`/`MATCHING` past work start to `CLOSED` (`SYSTEM:WORK_STARTED`). Each job is re-checked under the row lock with the `Clock` bean, and boundary times count as passed. Owners close their own jobs with `POST /api/jobs/{id}/close` (`OWNER` JWT, `Idempotency-Key`; `OWNER:MANUAL_CLOSE`). The same key returns the first result, key reuse is `JOB-409-004`, and an already closed job returns 200 `ALREADY_CLOSED`. Closing an `OPEN`/`MATCHING` job stores the recruitment-completion command with the post-close version in the same transaction. After a close, the first confirmation of a `RESERVED` seat is `JOB-409-005`; `CONSUMED` same-key retries and seat release still succeed. Cleanup of unpaid orders, refunds of remaining deposits, reopening, and deletion are not implemented.
- A local cross-process run with throwaway MySQL/Redis containers verified the immediate 409, backoff retries, and convergence after the Saga completed. Reopen and admission `CONSUMED` handling remain future work. Refund-review tooling and actual refunds, post-confirmation recovery after a funding block, edits after publication, and a real Toss checkout E2E also remain future work.
- On 2026-10-06 job-service and payment-service ran as separate processes with throwaway MySQL containers, generated test-only secrets, and a local Toss double; real HTTP verified private creation, order linking, pre-publication blocking, deposit, funding-status delivery, `OPEN` publication, post-publication admission and seat reservation, a READY terms change, a FAILED re-payment, and a CONFIRMING rejection. The real Toss test checkout was not exercised.
- Local HTTP/MySQL ports are 8083/3309. `./scripts/local-run.sh job` runs the service; repository verification includes its MySQL integration tests.
- See `docs/architecture/job-post-design.md`.

## Work Service Context

- `work-service` uses Java 17, Spring Boot 4.1.0, service-owned MySQL, Flyway, Spring Security, and Springdoc.
- Internal scheduled-work creation and compensation APIs match the matching confirmation Saga contract and require `X-Internal-Secret` plus `Idempotency-Key`.
- Creation stores external matching/job/member/payment references, work schedule including `endTimeNextDay` (inferred from the times when an older request omits it), `SCHEDULED` status, and status history in one transaction.
- Durable command keys return the original result on retries and reject reuse with a different operation or payload.
- A per-matching database lock and unique active-matching constraint prevent duplicate active work. Compensation cancels only `SCHEDULED` work, keeps history, and permits a new creation command after cancellation.
- Late compensation for an older canceled work never cancels the replacement work. This internal API is Saga compensation, not the user-facing work cancellation flow.
- work-service consumes `MatchConfirmed` v1 from the matching Redis Stream, persists confirmation and deduplicated event receipts atomically, and acknowledges only after commit. Pending events recover after failure or restart; canceled attempts cannot confirm their replacement.
- JWT-authenticated owners and workers can list and read only their own confirmed work through `/api/works/me`. Unconfirmed and unrelated work returns no list entry or 404 detail. Confirmation is separate from `SCHEDULED` and blocks Saga compensation.
- Confirmed workers can GPS check in; owners confirm work start and, by default, completion. Each transition locks the work row and writes actor history atomically; retries retain the first timestamp. Default attendance policy is 100 meters and start ±30 minutes in Asia/Seoul. Completion is allowed after scheduled end. Radius, windows, time zone, completion role and early completion are configurable; `AttendancePolicy` is replaceable.
- Matching persists and forwards optional job latitude/longitude into scheduled work. Legacy work remains queryable but cannot GPS check in without a trusted location snapshot. job-service seat reservations now persist immutable latitude/longitude snapshots from the stored job and replay them on the original reservation key. Pre-V16 reservations retain null coordinates without backfill.
- WorkCheckedIn, WorkStarted and WorkCompleted v1 are written to a transactional Outbox with work status/history, then relayed to a dedicated Redis Stream using fenced leases and retries. Duplicate deliveries retain the event ID; consumers must deduplicate and use work revision to handle reordering. The notification consumer is connected; settlement consumers are not yet connected.
- User cancellation, no-show handling and settlement remain future work. MySQL and Redis integration tests cover confirmation, pending recovery, query authorization and attendance transitions.
- MySQL integration tests cover command retries, conflicts, concurrent creation/cancellation, internal authentication, and Swagger access.
- Local execution is available through `./scripts/local-run.sh work`; the service uses port 8086 and its local MySQL uses port 3311.

## Chat Service Context

- `chat-service` uses Java 17, Spring Boot 4.1.0, service-owned MySQL, Flyway, Spring Security, and Springdoc.
- Internal room creation and compensation closure implement the existing matching Saga contract with `X-Internal-Secret` and `Idempotency-Key`.
- Durable command records, per-matching locks, and a unique active matching constraint prevent duplicate rooms. Closed rooms remain as history, and late closure cannot affect a replacement room.
- Business errors use `ChatRoomErrorCode` and `BusinessException`; unexpected runtime failures return a safe 500 rather than being classified as invalid input.
- OPEN denotes a provisioned internal room, not proof of confirmed matching. The service consumes `MatchConfirmed` v1, records confirmation and event receipts atomically, and exposes only confirmed OPEN rooms to their JWT-authenticated owner or worker through list/detail APIs. Pending Redis events recover after failure or restart; canceled attempts cannot confirm a replacement room.
- Confirmed OPEN room participants can send persisted text messages and query history with an exclusive descending message-ID cursor. Per-room serialization and a room/sender/client-key unique constraint make retries idempotent; changed content for a reused key returns 409.
- STOMP WebSocket connections authenticate with an access token on CONNECT and allow only exact participant-room subscriptions. Committed message IDs are broadcast through Redis Pub/Sub across chat instances; best-effort notifications contain no message text. REST ascending-ID synchronization recovers missed messages, and clients must periodically reconcile while a room is open. Expired connections cannot receive notifications.
- Read receipts, system messages, and general user room closure remain future work.
- Local HTTP/MySQL ports are 8087/3312. `./scripts/local-run.sh chat` runs the service; repository verification includes its MySQL integration tests.
- See `docs/architecture/chat-room-design.md` and `docs/architecture/error-handling-review.md`.

## Payment Service Context

- Java 17, Spring Boot 4.1.0, service-owned MySQL/Flyway, internal-secret security, and accessible Springdoc.
- Implements matching Saga lock/release APIs with stable command fingerprints, persistent successful and rejected outcomes, per-matching serialization, and per-job deposit locking.
- Requires an existing deposit with matching owner/currency and sufficient available balance. Released attempts retain their original IDs; late release never unlocks a replacement.
- Toss Payments test-card/KRW integration now creates immutable orders from trusted job-service snapshots, supports JWT owner-only approval/query, verifies provider results, and credits deposits atomically with durable funding notifications.
- Orders remain recoverable across uncertain provider outcomes with stable confirmation keys and fenced leases. Webhooks only schedule authenticated provider re-query for already-bound payment keys. Verified cancellations block new matching locks.
- The decided policy is payment-before-publication. job-service now creates `PAYMENT_PENDING` jobs, provisions internal orders, replaces READY/FAILED orders for terms changes and re-payment, and consumes revision-aware funding status to publish to `OPEN` or block new recruitment. Order replacement is decided under payment-service's order-row lock and serialized with approval preparation. A 2026-10-06 cross-process run verified order creation, funding notification, publication, and replacement over real HTTP with a local PG double (`TOSS_API_BASE_URL`, loopback HTTP only); a real Toss checkout has not been run for publication.
- On 2026-09-22, private Toss test keys and a temporary local checkout verified a KRW 1,000 test-card payment through provider approval and DB deposit credit (DEPOSITED; one deposited history entry). Synthetic local owner/job IDs and a local test JWT were used; real login, product frontend and job creation were not exercised.
- In that 2026-09-22 manual run the funding notification remained pending (funded=true, delivered=false) because the job receiver did not exist yet. The receiver is now implemented and was exercised in the 2026-10-06 cross-process run with a PG double; actual Toss webhook delivery remains unverified. Production escrow, payouts and refunds are outside this unit.
- HTTP/MySQL ports are 8085/3313. Local execution and repository verification include payment-service.
- See `docs/architecture/payment-lock-design.md` for the boundary and follow-up work. See `docs/architecture/toss-deposit-design.md` for the provider and job/frontend contracts. Full matching acceptance still requires product frontend and job-service integration.

## Notification Service Context

- WorkCheckedIn, WorkStarted and WorkCompleted v1 from the dedicated work Stream notify the opposite participant using the declared actor role. Work notifications carry workId instead of applicationId, retain event-id deduplication and pending recovery, and preserve existing matching event fingerprints.

- `notification-service` owns MySQL/Flyway notification storage and consumes matching Redis Stream events using a dedicated group.
- `MatchConfirmed` v1 creates OWNER and WORKER in-app notifications; `ApplicationRejected` v1 creates a WORKER notification only. Unsupported event types are skipped, while malformed supported events remain pending.
- Event receipts and recipient notifications commit together before ACK. Stable fingerprints and recipient unique constraints handle concurrent redelivery without resetting read state.
- JWT-authenticated members can list only their own role's notifications, query unread counts and idempotently mark individual notifications read. Responses omit other participants and payment identifiers.
- Local HTTP/MySQL ports are 8088/3314. Runtime configuration, local-run and repository verification include the new service. Notification delivery currently uses REST queries; push and other event types remain future work.
- See `docs/architecture/notification-design.md`.

## Error Handling State

- Member and auth signup/login/token business rejections expose domain codes. Member/owner/worker missing-resource lookups return 404, duplicate checks return 409, and verification resend limits return 429.
- Auth recognizes the new member/worker error codes through a status/code whitelist; matching retains its member-404 eligibility mapping and Saga unknown-outcome handling.
- Auth/member/job/matching JWT filters parse once and distinguish invalid tokens from engine failures; engine errors produce safe 500 responses through the MVC resolver.
- All six services preserve standard MVC protocol statuses and sanitize binding and unexpected error responses. OAuth signup-ticket serialization failures are server errors with retained causes.
- See `docs/architecture/error-handling-review.md` for the error contract and internal guards that intentionally remain runtime exceptions.

## Current Persistence Dependencies

Current persistence-related dependencies:

- MySQL for relational data
- Redis for cache/session/token-related use cases where appropriate
- Flyway for database migrations

## Near-Term Priorities

Likely next steps:

- Replace direct member-service JWT validation with API Gateway verified identity propagation when the gateway is introduced.
- Keep project and agent documents aligned as decisions are made.

## Open Questions

These questions are not yet settled in code:

- What deployment target and environment strategy will be used?
