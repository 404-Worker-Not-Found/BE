# 지원 도메인 설계

## 목적

이 문서는 `matching-service`의 지원 생성·조회·취소와 이후 점수 기반 정렬을 구현하기 위한 기준을 정의한다. 전체 상태와 서비스 소유권은 [MVP 도메인 흐름 및 상태 전이](./mvp-domain-flow.md)를 따르고, 이 문서는 지원 도메인에 필요한 세부 규칙만 구체화한다.

정책 수치와 아직 원본 데이터가 없는 점수 항목은 임의로 완성된 것처럼 처리하지 않는다. 먼저 지원 흐름을 구현하고, 데이터 제공 서비스가 준비되면 같은 점수 계약에 연결한다.

## 구현 범위

### 먼저 구현

- 지원 생성, 단건·목록 조회, 지원 취소
- 동일 공고 중복 지원 방지
- 지원 상태 이력
- 회원과 공고의 지원 가능 여부 검증
- 사업주의 공고별 지원자 목록 조회와 권한 검증
- MySQL을 기준으로 한 Redis 대기열 복구
- 점수 계산 상태와 점수 스냅샷 확장 구조
- 내구성 있는 Outbox relay와 전달 재시도

### 다음 구현

- 점수 공식과 긴급도별 가중치
- 평점, 업종 경력, 근무 이력, 노쇼 위험도 입력 연동
- 온라인 상태와 예상 도착 시간 입력 연동
- 점수 기반 자동 매칭과 폴백 매칭
- WebSocket 상태 갱신

## 데이터 소유권

| 데이터 | 원본 소유 서비스 | `matching-service`의 역할 |
| --- | --- | --- |
| 지원과 지원 상태 이력 | `matching-service` | 원본 저장 및 상태 변경 |
| 점수와 계산 입력 스냅샷 | `matching-service` | 계산 결과와 당시 입력값 저장 |
| 회원 역할·상태·기본 프로필 | `member-service` | 지원 시 조회하여 검증 |
| 평점·신뢰 점수 | `member-service` | 원본을 조회하거나 이벤트로 전달받아 점수에 반영 |
| 근무·노쇼 이력 | `work-service` | 집계값을 조회하거나 이벤트로 전달받아 점수에 반영 |
| 공고 상태·마감·모집 인원 | `job-service` | 지원 가능 여부 확인에 사용 |
| 온라인 상태 | 추후 결정 | TTL이 있는 보조 상태를 점수 입력으로 사용 |
| 예상 도착 시간 | 추후 결정할 경로 계산 제공자 | 계산 결과를 점수 입력으로 사용 |

다른 서비스의 식별자는 외부 ID로만 저장하고 물리적 외래 키를 만들지 않는다. 회원 식별자는 `worker_member_id`로 명명하고 인증 주체의 `memberId`를 사용한다.

## 지원 상태와 전이

| 현재 상태 | 명령 또는 사건 | 다음 상태 | 조건 |
| --- | --- | --- | --- |
| 없음 | 지원 | `APPLIED` | 회원과 공고가 지원 가능하고 동일 공고 지원 이력이 없음 |
| `APPLIED` | 본인 취소 | `CANCELED` | 매칭 확정 전이며 취소 정책을 충족함 |
| `APPLIED` | 매칭 확정 | `SELECTED` | 매칭 확정 Saga가 완료됨 |
| `APPLIED` | 모집 완료 | `REJECTED` | 모집 인원이 모두 확정되었고 해당 지원이 선택되지 않음 |

후보에게 매칭을 제안해 `matchings.status=PENDING`인 동안 지원 상태는 `APPLIED`로 유지한다. 후보가 거절하거나 응답 기한이 만료되면 해당 매칭 시도만 종료한다. 지원은 매칭이 최종 확정될 때 `SELECTED`가 된다.

`job-service`가 모집 인원 충족 또는 공고 마감으로 모집 완료를 확정하면 다음 내부 계약으로 `matching-service`에 알린다.

| 항목 | 값 |
| --- | --- |
| Method/Path | `POST /api/applications/internal/jobs/{jobPostId}/recruitment-completion` |
| Header | `X-Internal-Secret: {configured secret}` |
| Header | `X-Job-Version: {completion job version}` |
| Header | `Idempotency-Key: {recruitment completion command id}` |

`matching-service`는 공고별 모집 상태 행을 먼저 잠그고 완료된 공고 버전과 명령 ID를 영구 저장한다. 그 뒤 해당 공고의 남은 `APPLIED` 지원을 ID 순서로 잠가 `REJECTED`로 변경하고, 연결된 `PENDING` 매칭 제안은 `CANCELED`로 종료한다. 지원·매칭 상태 이력과 `ApplicationRejected` Outbox 이벤트를 같은 트랜잭션에 저장한다. 같은 버전의 완료 명령이 다시 들어오면 이미 종료된 상태를 유지하고 이력이나 이벤트를 추가하지 않는다.

지원 저장도 같은 공고별 모집 상태 행을 잠근다. 완료와 같은 버전에서 늦게 도착한 지원 승인은 저장하지 않으므로 완료 응답 뒤에 `APPLIED` 지원이 다시 생기지 않는다. 공고가 재오픈되어 더 높은 `jobVersion`으로 발급된 승인은 정상 접수한다. 여러 지원을 종료한 뒤 Redis 지원·점수 대기열은 공고당 한 번만 최종 DB 상태로 재구성한다.

결과가 확정되지 않은 외부 명령이나 미완료 보상이 남은 확정 Saga가 하나라도 있으면 전체 모집 완료 반영을 거부한다. 이 경우 `job-service`는 같은 명령 ID로 재시도하며, 모든 Saga가 안전하게 재개 또는 보상된 뒤에만 미선정 처리를 완료한다. 현재 저장소는 수신 계약만 제공하며 모집 완료 판단과 호출 구현은 공고 담당 범위에 둔다.

## 수동 매칭 후보 선택

점주는 `POST /api/jobs/{jobPostId}/applications/{applicationId}/matchings`로 본인 공고의 `APPLIED` 지원자를 선택한다. 요청의 선택적인 `scoreBatchId`에는 지원자 목록에서 사용한 `READY` 점수 묶음 ID를 전달한다. 생략하면 최신 `READY` 묶음을 사용하고 완료된 묶음이 없으면 점수 참조를 `NULL`로 둔다. 선택한 지원자의 `READY` 스냅샷이 없을 때도 점수를 만들거나 0점으로 바꾸지 않고 스냅샷 참조를 `NULL`로 둔다.

후보 선택은 `MANUAL`, `PENDING` 매칭과 최초 상태 이력을 같은 트랜잭션에 저장한다. 동일 지원에 대한 같은 수동 선택 요청은 기존 `PENDING` 매칭을 반환한다. 지원 행의 비관적 잠금과 `matchings.application_id` 유일 제약으로 중복 선택을 방지한다.

`PENDING`은 모집 자리 확정이나 최종 매칭을 의미하지 않는다. 이 단계에서는 `expires_at`을 `NULL`로 두며 후보 응답 제한시간 정책이 확정될 때 채운다. 알바생이 지원을 취소하면 같은 지원 행 잠금 안에서 매칭을 `CANCELED`, 지원을 `CANCELED`로 바꾸고 각각의 이력을 남긴다. 따라서 후보 선택과 지원 취소가 경쟁해도 취소된 지원에 `PENDING` 매칭이 남지 않는다.

## 알바생 매칭 제안 조회와 거절

알바생은 다음 API로 본인에게 생성된 매칭 제안을 조회하고 거절한다.

| Method/Path | 설명 |
| --- | --- |
| `GET /api/matchings` | 선택 시각 내림차순으로 본인 매칭 목록 조회 |
| `GET /api/matchings/{matchingId}` | 본인 매칭 상세 조회 |
| `PATCH /api/matchings/{matchingId}/decline` | `PENDING` 제안 거절 |

거절은 매칭을 `DECLINED`로 변경하고 `MATCHING_DECLINED_BY_WORKER` 이력을 같은 트랜잭션에 저장한다. 지원은 `APPLIED`로 유지하지만 해당 지원에는 이미 매칭 레코드가 있으므로 같은 공고의 매칭 후보로 다시 선택하지 않는다. 동일 사용자의 반복 거절은 기존 `DECLINED` 결과를 반환하고 이력을 추가하지 않는다. 다른 회원의 매칭 접근은 거부하며, `CANCELED`, `CONFIRMED`, `EXPIRED` 등 다른 종료 상태에서는 상태 충돌로 처리한다.

거절과 지원 취소는 모두 지원 행을 먼저 잠그고 매칭 행을 나중에 잠근다. 거절이 먼저 처리되면 지원 취소는 지원만 `CANCELED`로 바꾸고 매칭은 `DECLINED`로 보존한다. 지원 취소가 먼저 처리되면 매칭과 지원이 `CANCELED`가 되어 이후 거절은 상태 충돌이 된다. 어느 순서에서도 취소된 지원에 `PENDING` 매칭이 남지 않는다.

알바생은 `PATCH /api/matchings/{matchingId}/accept`로 본인의 `PENDING` 제안을 수락한다. 수락 요청은 아래 모집 자리 예약과 결제·예정 근무·채팅방 생성 단계를 순서대로 실행하며, 모든 단계와 자리 소비가 성공한 뒤에만 매칭을 `CONFIRMED`, 지원을 `SELECTED`로 변경한다. 같은 알바생의 반복 수락은 이미 확정된 결과를 반환한다.

확정 진행 상태와 각 단계의 명령 ID·외부 리소스 ID는 `matching_confirmation_sagas`에 저장한다. 외부 호출 전에 안정적인 명령 ID를 먼저 저장하므로 호출 성공 직후 프로세스가 중단되어도 같은 멱등 키로 재개할 수 있다. Saga 실행권은 만료 시간이 있는 lease로 보호해 동시에 들어온 수락 요청이 같은 단계를 중복 조정하지 않게 한다.

자리 예약 후 외부 서비스가 명령을 명확히 거절한 경우 채팅방, 예정 근무, 결제 잠금, 자리 예약 순서로 보상한다. 네트워크 오류나 5xx처럼 명령 결과를 알 수 없는 경우에는 보상하지 않고 같은 단계 명령 ID로 실행을 재개한다. 보상에 실패한 외부 리소스 ID는 삭제하지 않고 Saga를 `FAILED`로 남기며, 다음 수락 요청이 같은 보상 명령 ID로 남은 보상부터 재개한다. 모든 리소스가 해제된 뒤에만 새 시도와 새 단계 명령 ID를 만든다. 이미 자리를 소비한 뒤 로컬 확정이 중단된 경우에도 보상하지 않고 같은 Saga를 재개해 로컬 상태와 이벤트를 완성한다.

### 공고 담당 범위에 요청할 매칭 확정 계약

최종 확정 전에 `job-service`가 공고별 남은 모집 자리를 원자적으로 예약해야 한다. 공개 공고 조회나 `matching-service` 내부 집계로 모집 인원을 판단하지 않는다. 다음 계약을 사용한다.

| 항목 | 값 |
| --- | --- |
| Method/Path | `POST /api/jobs/internal/{jobPostId}/matching-seat-reservations` |
| Header | `X-Internal-Secret: {configured secret}` |
| Header | `Idempotency-Key: {seat reservation command id}` |
| Body | `matchingId`, `applicationId`, `workerMemberId` |

`job-service`는 공고 행 잠금 또는 같은 효과의 조건부 갱신으로 공고가 매칭 가능한 상태인지와 `confirmed + reserved < recruitCount`인지 확인한다. 성공 응답에는 `reservationId`, `jobPostId`, `ownerMemberId`, `jobVersion`, `reservedAt`, `expiresAt`을 포함한다. 예약은 `RESERVED`, `CONSUMED`, `RELEASED`, `EXPIRED` 상태를 보관하고 같은 멱등 키에는 같은 결과를 반환한다. 확정과 해제는 예약 명령과 구분되는 각각의 안정적인 멱등 키를 사용한다.

`matching-service`에는 이 계약과 payment/work/chat 계약을 호출하는 클라이언트 및 확정 Saga가 구현되어 있다. `work-service`의 예정 근무 생성·취소와 `chat-service`의 채팅방 생성·종료 계약은 구현되어 있다. payment 서비스와 `job-service`의 자리 예약 API는 아직 구현되지 않았으므로 실제 수락 호출은 의존 서비스가 준비되기 전까지 실패 닫힘 방식으로 종료된다. 외부 계약이 준비되지 않았는데도 `PENDING`을 확정 상태로 바꾸거나 임시 성공 응답을 사용하지 않는다.

자리 예약 응답은 후속 명령에 필요한 `workDate`, `startTime`, `endTime`, `lockedAmount`, `currency` 공고 스냅샷도 포함한다. payment/work/chat 내부 명령은 각각 아래 경계를 사용한다.

| 서비스 | 실행 계약 | 보상 계약 |
| --- | --- | --- |
| `payment-service` | `POST /api/payments/internal/locks` | `POST /api/payments/internal/locks/{paymentId}/release` |
| `work-service` | `POST /api/works/internal/scheduled` | `POST /api/works/internal/{workId}/cancel` |
| `chat-service` | `POST /api/chat-rooms/internal` | `POST /api/chat-rooms/internal/{chatRoomId}/close` |

모든 명령과 보상 요청은 `X-Internal-Secret`과 단계별 `Idempotency-Key`를 사용한다. 실행 명령과 보상 명령도 서로 다른 키를 가지며 재시도할 때만 같은 키를 재사용한다. 외부 서비스의 4xx는 부수 효과 없이 명령을 거절했다는 계약이고, 네트워크 오류와 5xx는 결과 미확정으로 취급한다. 외부 식별자는 문자열로 저장하며 서비스 간 물리 FK를 만들지 않는다.

초기 구현에서는 한 알바생이 같은 공고에 한 번만 지원할 수 있다. 취소한 지원도 기록으로 보존하며 재지원할 수 없다. 따라서 `applications(job_post_id, worker_member_id)`에 유일 제약을 둔다. 재지원을 허용하는 정책으로 바뀌면 기존 레코드를 재사용하지 않고 지원 회차와 활성 지원 유일성 설계를 별도로 추가한다.

## 지원 생성 흐름

1. 인증된 `memberId`와 요청의 공고 ID를 받는다.
2. `member-service`에서 회원이 `ACTIVE` 상태의 `WORKER`인지 확인한다.
3. `job-service`의 내부 계약으로 지원 접수 승인(`application admission`)을 발급받는다.
4. 승인 만료 전에 `applications`에 승인 ID와 `APPLIED` 지원을 저장하고 최초 상태 이력을 같은 트랜잭션에 기록한다.
5. 같은 트랜잭션에서 Outbox 이벤트를 저장한다.
6. 커밋 후 `ApplicationSubmitted`를 전달한다. `job-service`는 승인을 사용 완료로 표시하고 Redis 대기열은 지원을 반영한다.

같은 공고와 회원의 요청이 재전송되면 새 레코드를 만들지 않는다. 이미 `APPLIED`인 경우 기존 지원 결과를 반환하고, 종료 상태라면 재지원 불가 오류를 반환한다. 애플리케이션 계층의 선조회와 별개로 데이터베이스 유일 제약을 최종 동시성 방어선으로 사용한다.

## 지원 취소 흐름

1. 지원 ID와 인증된 `memberId`로 본인 지원인지 확인한다.
2. `APPLIED` 상태이고 취소 가능 조건을 만족할 때만 `CANCELED`로 변경한다.
3. 상태 변경과 이력, Outbox 이벤트를 같은 트랜잭션에 저장한다.
4. `ApplicationCanceled` 전달 후 Redis 대기열에서 제거한다.

이미 `CANCELED`인 지원에 같은 사용자가 취소를 다시 요청하면 성공으로 처리한다. `SELECTED` 또는 `REJECTED` 지원은 지원 취소 API로 변경하지 않는다. 확정 이후 취소는 매칭 또는 근무 취소 흐름에서 처리한다.

## 서비스 간 계약

### 현재 사용할 수 있는 회원 계약

현재 `GET /api/members/internal/{memberId}`에서 `memberId`, `role`, `status`를 확인할 수 있다. 첫 지원 구현은 이 계약으로 `ACTIVE`와 `WORKER`를 검증한다.

점주용 지원자 조회는 `POST /api/members/internal/workers/summaries`에 회원 ID를 최대 100개까지 전달해 활성 `WORKER`의 `memberId`와 `name`만 일괄 조회한다. 응답에 없는 회원은 지원·점수 데이터를 숨기지 않고 이름을 `NULL`로 표시한다. 이메일, 휴대전화, 위치는 이 계약에서 제공하지 않는다.

`matching-service`는 내부 호출마다 `X-Internal-Secret` 헤더를 보낸다. 양쪽 서비스는 `INTERNAL_API_SECRET` 환경 변수로 같은 값을 주입하고 코드, 로그, 저장소에 값을 남기지 않는다. 현재 `member-service`는 헤더가 없거나 값이 다르면 HTTP 401과 `GLOBAL-401-001` 응답을 반환한다. `matching-service`는 이 응답을 회원 인증 실패로 바꾸지 않고 지원 저장을 중단한 뒤 외부에는 의존 서비스 오류로 응답한다. 설정이 고쳐지기 전까지 같은 호출을 무의미하게 재시도하지 않는다.

평점, 경력, 근무·노쇼 요약을 지원자 목록에 표시하려면 각 원본 소유 서비스의 별도 내부 조회 계약이 필요하다. 이메일, 휴대전화, 정밀 위치 등 지원자 평가에 필요하지 않은 개인정보는 반환하지 않는다.

### 공고 담당 범위에 요청할 계약

단순 상세 조회 후 지원을 저장하면 조회 직후 공고가 마감되는 경쟁 조건이 생긴다. 이를 막기 위해 `job-service`가 단기 지원 접수 승인을 발급한다. 이 승인은 지원 접수 시점만 확정하며 모집 자리나 확정 인원을 차감하지 않는다. 모집 인원 동시성은 매칭 확정 계약에서 별도로 처리한다.

요청 계약:

| 항목 | 값 |
| --- | --- |
| Method/Path | `POST /api/jobs/internal/{jobPostId}/application-admissions` |
| Header | `X-Internal-Secret: {configured secret}` |
| Header | `Idempotency-Key: {application command id}` |
| Body | `workerMemberId` |

성공 응답에는 다음 값을 포함한다.

- `admissionId`
- `jobPostId`, `jobVersion`
- `ownerMemberId`
- 업종 ID, 근무 일시, 위도·경도 등 점수 계산용 공고 스냅샷
- `admittedAt`, `expiresAt`

`job-service`는 공고 행을 잠그거나 같은 효과의 조건부 갱신을 사용해 `OPEN` 상태와 `applicationDeadline > now`를 확인한 뒤 승인을 저장한다. 승인 생성 시각을 지원 접수의 선형화 시점으로 사용한다. 승인 직후 공고가 닫혀도 만료 전에 저장한 지원은 유효하다.

같은 멱등 키의 재요청에는 같은 승인과 공고 스냅샷을 반환한다. `matching-service`는 `admissionId`를 지원 레코드에 저장하고 중복 사용을 막는다. 지원 저장이 실패하면 승인은 만료되며 모집 인원에는 영향을 주지 않는다. `ApplicationSubmitted`의 발생 시각이 승인 만료 시각 이전이면 이벤트 전달이 늦어져도 `job-service`는 승인을 사용 완료로 변경할 수 있다.

`job-service`의 승인 레코드는 최소한 승인 ID, 공고 ID, 알바생 회원 ID, 멱등 키, 공고 버전, `RESERVED`·`CONSUMED`·`EXPIRED` 상태, 승인·만료·사용 시각을 보관한다. 공고 ID에는 서비스 내부 FK를 사용하고 알바생 회원 ID는 EXT로 저장한다. 멱등 키와 승인 ID에는 각각 유일 제약을 둔다.

실패 계약:

| HTTP | 오류 코드 | 처리 |
| --- | --- | --- |
| 401 | `GLOBAL-401-001` | 지원 저장 중단, 외부에는 의존 서비스 오류 반환 |
| 404 | `JOB_NOT_FOUND` | 존재하지 않는 공고로 응답 |
| 409 | `JOB_NOT_OPEN` | 마감된 공고로 응답 |
| 409 | `APPLICATION_DEADLINE_PASSED` | 지원 마감으로 응답 |
| 409 | `ADMISSION_EXPIRED` | 공고 조건을 다시 확인해 새 명령 ID로 재시도 |

`job-service`에 이 계약과 승인 저장 구조가 구현되어 있다. 오류 응답의 `code`는 서비스 공통 형식(`JOB-404-001`, `JOB-409-001`~`003`)을 사용하며 `matching-service`는 위 표의 이름과 함께 이 코드를 인식한다. 같은 멱등 키를 다른 공고·회원 요청에 재사용하면 `JOB-409-004`로 거절한다. `ApplicationSubmitted` 수신에 따른 승인 `CONSUMED` 처리는 아직 구현되지 않았다.

## 상태 전이 동시성

`applications.version`은 낙관적 잠금 값이고 `applications.revision`은 외부 이벤트에 공개하는 지원 상태 버전이다. 최초 지원은 `revision=1`로 저장한다. 이후 상태가 바뀔 때마다 `revision`을 1 증가시킨다.

취소와 매칭 확정처럼 서로 다른 종료 전이가 경쟁하면 두 트랜잭션 모두 기대 상태 `APPLIED`와 읽은 `version`을 조건으로 갱신한다. 먼저 커밋한 전이만 상태, `version`, `revision`을 변경하고 같은 트랜잭션에서 상태 이력과 Outbox 이벤트를 기록한다. 조건부 갱신에 실패한 트랜잭션은 상태 이력과 이벤트를 남기지 않고 최신 상태를 다시 조회한다.

- 최신 상태가 자신이 요청한 상태면 멱등 성공으로 처리한다.
- 최신 상태가 다른 종료 상태면 `APPLICATION_STATE_CONFLICT`로 처리한다.

## 데이터 모델

세부 관계는 [지원 도메인 ERD](./matching-application-erd.drawio)에 표시한다.

### `applications`

지원 사실과 현재 상태만 보관한다. 계속 변하는 순위, 예상 도착 시간, 점수 JSON은 이 테이블에 저장하지 않는다.

주요 제약과 인덱스:

- `UNIQUE(job_post_id, worker_member_id)`
- `UNIQUE(job_application_admission_id)`
- 지원 승인 응답의 `owner_member_id`를 EXT 스냅샷으로 저장하고 점주 조회 범위를 제한한다.
- `status`는 `VARCHAR(20)`으로 저장하고 `APPLIED`, `CANCELED`, `SELECTED`, `REJECTED`만 허용
- 알바생의 지원 내역: `(worker_member_id, applied_at, id)`
- 공고의 지원자 목록: `(job_post_id, status, applied_at, id)`
- 점주의 공고별 지원자 목록: `(owner_member_id, job_post_id, status)`
- 낙관적 잠금을 위한 `version`과 이벤트 순서를 위한 `revision`

`owner_member_id`는 V4 이전 지원을 안전하게 보존하기 위해 데이터베이스에서 `NULL`을 허용한다. 새 지원은 승인 응답에 소유자 ID가 없으면 저장하지 않는다. 기존 `NULL` 행은 공고 소유권 내부 계약이 준비되면 `job-service`의 원본 값으로 보정하며, 보정 전에는 점주 조회에 노출하지 않는다.

### `application_status_histories`

최초 지원과 모든 상태 변경을 기록한다. `from_status`, `to_status`, 변경 주체 유형·ID, 사유 코드, 상세 사유, 변경 시각, 지원별 단조 증가 `revision`을 저장한다. 지원 레코드를 삭제해 이력을 잃지 않는다.

변경 주체 유형은 `WORKER`, `OWNER`, `SYSTEM`을 사용한다. `WORKER`와 `OWNER`는 `actor_member_id`를 반드시 저장하고, `SYSTEM`은 회원 ID를 저장하지 않는다.

`applications`를 참조하는 내부 외래 키는 `ON DELETE RESTRICT`를 사용한다. 데이터 보존 기간이 끝난 뒤 삭제나 익명화가 필요하면 상태 이력과 점수 스냅샷을 포함한 별도 정책으로 처리한다.

### `matching_score_snapshots`

`matching_score_batches`는 한 공고에 대해 같은 정책으로 계산한 점수 묶음을 나타낸다. 자동 매칭과 지원자 목록은 완료된 하나의 묶음을 사용해 조회 도중 순서가 바뀌지 않게 한다. 점수를 다시 계산할 때 기존 결과를 덮어쓰지 않고 새 묶음과 스냅샷을 추가한다.

계산 중에는 점수 묶음을 `CALCULATING`으로 유지한다. 계산 대상마다 `READY` 또는 `FAILED` 스냅샷이 생겨 `PENDING`이 하나도 남지 않고, 하나 이상의 `READY` 스냅샷이 있으면 묶음을 `READY`로 바꾼다. 모든 스냅샷이 `FAILED`이거나 재시도 한도를 넘겨 계산을 끝내지 못하면 묶음을 `FAILED`로 바꾼다.

각 점수 스냅샷은 다음 내용을 보관한다.

- 계산 상태: `PENDING`, `READY`, `FAILED`
- 총점과 요소별 점수
- 지원 시각, 평점, 업종 경력, 활동 상태, 도착 시간, 노쇼 위험도 입력
- 소속 점수 묶음의 정책 버전과 선택적 모델 버전
- 계산 당시 원본 입력 JSON과 누락 입력 목록
- 계산 시각

점수 순위와 자동 매칭은 `READY` 상태인 점수 묶음의 `READY` 스냅샷만 사용한다. `PENDING`과 `FAILED` 스냅샷은 점수 순위와 자동 매칭 후보에서 제외한다. 전체 지원자 목록에는 해당 지원자를 점수 미계산 또는 계산 실패 상태로 별도 표시하고 0점 지원자처럼 정렬하지 않는다.

점수 정책은 입력 항목별 필수 여부와 누락 처리 방식을 버전에 포함한다. 필수 입력이 없거나 계산이 실패하면 스냅샷을 `FAILED`로 저장한다. 선택 입력이 없더라도 정책에 명시된 기본값이나 가중치 재분배로 계산을 마쳤다면 `READY`로 저장하고 `missing_inputs`에 누락 항목을 남긴다.

총점 정렬은 `total_score DESC, applied_at ASC, application_id ASC`로 고정한다. `priority_rank`는 조회 시 계산하는 파생값이며 지원 원본에 저장하지 않는다.

### `outbox_events`

지원 상태 변경과 이벤트 저장을 같은 트랜잭션에 묶는다. Outbox는 `event_id`, 집계 유형·ID, 이벤트 유형, `correlation_id`, 집계 `revision`, payload 스키마 버전, payload, 발행 상태, 발생·발행 시각, 재시도 횟수, 다음 시도 시각, lease와 마지막 오류를 저장한다. 집계 ID는 논리 참조이므로 물리적 외래 키를 만들지 않는다.

relay는 아직 발행되지 않은 이벤트를 발생 시각과 ID 순으로 조회하고 조건부 갱신으로 짧은 DB lease를 획득한다. 같은 aggregate의 앞 revision이 발행되기 전에는 뒤 revision을 선택하지 않는다. 성공하면 Redis Stream `matching:domain-events`에 `eventId`, `eventType`, `occurredAt`, `aggregateId`, `correlationId`, `revision`, `version` 공통 envelope와 원본 payload를 기록하고 `PUBLISHED`로 바꾼다. 실패하면 `FAILED`와 오류를 기록하고 상한이 있는 지수 backoff 뒤 다시 시도한다. lease가 만료된 작업은 다른 인스턴스가 인계한다.

Redis 기록 성공과 MySQL의 `PUBLISHED` 변경 사이에 장애가 나면 같은 이벤트가 재전송될 수 있으므로 전달 보장은 at-least-once다. 소비자는 안정적인 `eventId`로 중복을 제거하고 aggregate별 `revision`으로 중복·역순 상태 갱신을 막는다. Stream은 자동으로 trim하지 않으며, 운영 Redis는 AOF 등 승인된 쓰기를 보존하는 내구성 설정과 `noeviction` 정책을 사용해야 한다. 로컬 Compose는 `appendonly yes`, `appendfsync always`로 실행한다.

`ApplicationSubmitted`와 `ApplicationCanceled`에는 `revision`을 포함한 공통 이벤트 필드와 함께 `applicationId`, `admissionId`, `jobPostId`, `workerMemberId`, 지원 상태, 상태 변경 시각을 넣는다. 공통 `revision`은 지원 집계의 상태 버전이며 별도의 `applicationRevision`을 추가하지 않는다. 최초 지원 이벤트는 `revision=1`이고 상태가 바뀔 때마다 1 증가한다.

`job-service`는 저장된 마지막 지원 `revision`보다 큰 이벤트만 반영한다. 같은 `revision`의 재전송은 `eventId`로 한 번만 처리하고 더 작은 `revision`은 무시한다. 이 기준으로 지원자 수 projection의 중복·역순 갱신을 막는다. projection 복구 방식은 공고 계약을 구현할 때 함께 확정한다.

## 아직 없는 점수 데이터의 후속 연동

현재 제공되지 않는 입력값을 임의의 0점으로 저장하면 “실제 0점”과 “데이터 없음”을 구분할 수 없다. 해당 요소 점수는 `NULL`로 두고, 스냅샷의 `missing_inputs`와 계산 상태로 이유를 남긴다.

| 입력값 | 현재 처리 | 연결 조건 | 후속 작업 위치 |
| --- | --- | --- | --- |
| 평점·신뢰 점수 | 미제공으로 기록 | `member-service` 집계와 내부 조회 또는 이벤트 계약 구현 | 회원·리뷰 기능 이후 |
| 업종별 경력·근무 이력 | 미제공으로 기록 | 경력의 원본 소유권과 업종별 집계 계약 확정 | 회원·근무 기능 이후 |
| 노쇼 이력·위험도 | 미제공으로 기록 | `work-service` 노쇼 결과와 규칙 기반 위험도 입력 계약 구현 | 근무·노쇼 기능 이후 |
| 온라인·최근 활동 상태 | 미제공으로 기록 | 상태 소유 주체, heartbeat 주기와 TTL 확정 | 실시간 인프라 이후 |
| 예상 도착 시간 | 미제공으로 기록 | 출발 위치 공개 범위와 경로 계산 제공자 확정 | 위치·지도 연동 이후 |

후속 데이터가 준비되면 `matching-service`의 점수 입력 어댑터만 연결하고 기존 지원 모델은 변경하지 않는다. 규칙 기반 노쇼 위험도와 향후 ML 모델은 같은 점수 입력 인터페이스를 사용하며 `policy_version`과 `model_version`으로 결과를 구분한다.

첫 점수 정책은 `application-time-v1`을 사용한다. 현재 `APPLIED` 지원을 `applied_at ASC, application_id ASC`로 정렬하고, 0부터 시작하는 순번을 사용해 `((지원자 수 - 순번) / 지원자 수) * 100`을 소수점 넷째 자리까지 반올림한 값을 `applied_time_score`와 `total_score`에 저장한다. 아직 연동되지 않은 평점, 업종 경력, 온라인 상태, 예상 도착 시간, 노쇼 위험도는 점수와 입력값을 `NULL`로 유지하고 `missing_inputs`에 기록한다.

외부 입력이 준비되면 기존 배치나 스냅샷을 수정하지 않고 새 정책 버전으로 새 배치를 계산한다. 긴급도별 가중치와 다요소 총점 공식은 필요한 공고·회원·근무 계약과 함께 후속 정책으로 추가한다.

### `matchings`와 `matching_status_histories`

`matchings`는 지원별 매칭 시도와 현재 상태를 저장한다. 한 지원에는 매칭 레코드를 하나만 만들며 자동·폴백 재시도 세부 이력은 후속 시도 모델로 분리한다. 공고·점주·알바생 ID는 선택 시점 스냅샷인 외부 ID이고 물리 FK를 만들지 않는다. 선택 근거가 있는 경우 점수 묶음과 점수 스냅샷을 서비스 내부 FK로 참조하며, 점수가 없으면 두 값 모두 또는 스냅샷만 `NULL`일 수 있다.

`matching_status_histories`는 최초 `PENDING`과 이후 모든 상태 변경을 지원 상태 이력과 같은 actor, reason, revision 방식으로 기록한다. 매칭의 `version`은 동시성 제어, `revision`은 상태 이력과 후속 이벤트 순서에 사용한다.

### `matching_confirmation_sagas`

`matching_confirmation_sagas`는 매칭별 확정 조정 상태를 한 건 저장한다. `PROCESSING`, `COMPENSATING`, `COMPLETED`, `FAILED` 상태와 시도 번호를 보관하고 자리 예약·결제·근무·채팅 명령 ID 및 성공한 외부 리소스 ID를 기록한다. 모집 자리 응답에서 받은 근무 일시와 잠금 금액 스냅샷은 최종 `MatchConfirmed` 이벤트를 구성하는 기준으로 사용한다.

`lease_token`과 `lease_expires_at`은 확정 실행의 동시 조정자를 하나로 제한한다. lease가 만료된 `PROCESSING` Saga는 저장된 명령 ID와 단계 결과를 사용해 재개한다. `version`은 Saga 행의 낙관적 동시성 제어에 사용한다.

## Redis와 복구

- MySQL의 `applications`가 최종 기준이다.
- 지원 저장과 Redis 쓰기를 하나의 성공 조건으로 묶지 않는다.
- 지원 트랜잭션에서 Outbox를 저장하고, 커밋 후 인프로세스 이벤트 리스너는 로컬 Redis 대기열을 갱신한다. 서비스 간 도메인 이벤트는 별도의 Outbox relay가 Redis Stream으로 전달한다.
- 공고별 지원 Set에는 현재 `APPLIED`인 `applicationId`를 저장한다. 이 Set은 점수 계산 전 지원과 점수 계산에 실패한 지원도 포함한다.
- 점수 대기열은 공고 ID와 점수 묶음 ID를 키에 포함한 Sorted Set으로 구성한다. member는 `applicationId`, score는 `totalScore`를 사용하고 별도 metadata에 `scoreBatchId`와 `policyVersion`을 저장한다.
- Redis 장애 시 MySQL의 `APPLIED` 지원으로 지원 Set을 복구한다. 최신 `READY` 점수 묶음과 그 묶음의 `READY` 스냅샷을 조회하고, 현재 상태가 `APPLIED`인 지원만 Sorted Set에 복구한다.
- 완료된 점수 묶음이 없으면 해당 공고를 `UNSCORED`로 취급하고 자동 매칭을 시작하지 않는다. `PENDING` 또는 `FAILED` 스냅샷도 Redis 후보에 넣지 않는다.
- 선택 입력이 누락됐지만 정책에 따라 `READY`가 된 스냅샷은 복구 대상이다. 필수 입력 누락이나 계산 실패로 `FAILED`가 된 지원은 재계산 성공 전까지 후보에서 제외한다.
- 취소, 거절, 선정, 공고 마감 시 대기열에서 제거한다.
- Redis 점수는 후보 추출을 위한 복제 값이다. 동점자의 최종 순서는 MySQL에서 `applied_at`, `application_id`로 결정하고, 매칭 직전에 지원 상태와 점수 묶음·스냅샷 상태를 다시 확인한다.

현재 구현 단계에서는 지원 트랜잭션 커밋 후 애플리케이션 이벤트 리스너가 지원 Set을 갱신하고, 활성 지원 구성이 최신 `READY` 점수 배치와 다를 때 `application-time-v1` 점수를 재계산한다. 점수 배치 트랜잭션을 먼저 커밋한 뒤 최신 배치의 `READY` 스냅샷을 배치별 Sorted Set으로 교체하고 `scoreBatchId`와 `policyVersion` metadata를 함께 저장한다. 주기적인 복구 작업은 MySQL 상태로 지원 Set을 다시 구성한 뒤, 현재 정책과 활성 지원 구성이 일치하는 최신 배치를 복구하거나 불일치할 때 새 배치를 계산한다. 복구·이벤트 갱신은 공고별 Redis 잠금으로 직렬화하고 Set과 Sorted Set 교체는 각각 원자적으로 실행한다. 잠금마다 증가하는 공고별 fencing token을 metadata에 보존해 잠금 TTL이 지난 작업의 늦은 교체·삭제를 거부한다. Redis 반영 실패는 기록하되 이미 커밋된 지원과 점수 배치를 실패로 바꾸지 않는다. 로컬 projection과 별개로 Outbox relay가 서비스 간 이벤트를 Redis Stream에 발행하며, Stream 성공 후에만 Outbox 이벤트를 `PUBLISHED`로 변경한다.

## 조회와 권한

- 알바생은 자신의 지원만 조회하고 취소할 수 있다.
- `OWNER`는 `GET /api/jobs/{jobPostId}/applications`와 `GET /api/jobs/{jobPostId}/applications/{applicationId}`로 지원자 목록과 상세를 조회한다.
- 점주 조회는 지원 접수 승인에서 보존한 `owner_member_id`로 제한한다. 해당 공고의 지원 원장이 있으면 점수 묶음을 읽기 전에 소유자 일치 여부부터 검증해 다른 점주에게 배치 메타데이터도 노출하지 않는다. 다른 점주의 상세 조회도 권한 오류로 처리한다.
- 지원자 목록은 최신 `READY` 점수 묶음을 기본으로 사용하고 점수 묶음 ID, 계산 완료 시각, 정책·모델 버전을 함께 반환한다.
- 다음 페이지에서 응답받은 `scoreBatchId`를 요청하면 같은 불변 점수 묶음으로 정렬을 고정한다. 요청한 묶음이 해당 공고의 `READY` 묶음이 아니면 조회를 거부한다.
- 같은 묶음의 `READY` 스냅샷은 `total_score DESC, applied_at ASC, application_id ASC`로 먼저 반환한다. `FAILED` 또는 스냅샷이 없는 현재 지원은 목록에서 누락하지 않고 뒤에 배치하며 순위와 점수는 `NULL`로 반환한다.
- 지원자 이름은 member-service 최소 정보 계약으로 가져온다. 아직 없는 평점·경력·노쇼·활동·도착 정보는 요소 점수와 입력값을 `NULL`로 유지하고 `missingInputs`로 구분한다.
- 지원 이력이 없는 공고는 요청에 점수 묶음 ID가 있더라도 묶음을 조회하지 않고 배치 메타데이터가 없는 빈 목록으로 응답한다. 공고 존재 여부와 현재 소유권까지 구분하는 내부 계약이 생기면 이 경우도 `job-service` 원본으로 검증한다.
- 정확한 현재 순위와 매칭 확률의 공개 범위는 별도 제품 정책으로 결정한다.
- 지원자 목록의 변동이 잦으므로 점수 묶음을 고정하거나 커서 기반 페이지네이션을 사용한다.

## 필수 검증 시나리오

- 같은 알바생의 동시 중복 지원
- 내부 secret 누락·불일치 시 지원 저장 차단
- 지원 접수 승인과 공고 마감의 경쟁, 멱등 재요청과 승인 만료
- 지원 취소와 매칭 확정의 경쟁
- 여러 명 모집 시 확정 인원 초과 방지
- Redis 장애 중 지원 성공과 지원 Set·점수 묶음·정책 버전 재구성
- 같은 점수의 지원 시각·지원 ID 순서
- `PENDING`·`FAILED` 스냅샷의 점수 순위 및 자동 매칭 제외
- 필수·선택 점수 입력 누락과 계산 실패
- 점수 정책 버전 변경 후 재계산
- 지원 이벤트의 중복·역순 `revision` 처리
- 다른 점주나 알바생의 지원 조회·취소 차단
- 지원 목록 페이지 이동 중 점수 변경

## 구현 단위

브랜치와 PR은 필요에 따라 여러 이슈를 포함할 수 있지만, 리뷰 가능한 크기를 유지한다.

1. `matching-service` 기반 구조와 데이터베이스 설정
2. 지원 엔티티, 상태 이력, 중복 제약과 저장소
3. 회원·공고 검증 계약, 지원 생성·조회·취소 API
4. Outbox와 Redis 대기열, 장애 복구
5. 점수 스냅샷과 지원자 정렬
6. 수동 매칭 후보 선택과 상태 이력
7. 알바생 매칭 제안 조회와 거절
8. 모집 자리 예약과 매칭 확정 Saga
9. 모집 완료와 미선정 지원 종료
10. 자동 매칭과 폴백 매칭
11. 실시간 상태 갱신
