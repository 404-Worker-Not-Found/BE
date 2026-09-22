# 예정 근무와 Saga 보상 계약

## 범위

매칭 확정 Saga가 결제 잠금 다음 단계에서 예정 근무를 생성한다. 이후 채팅방 생성 또는 자리 확정이 실패하면 같은 근무를 취소한다. 근무 생성 시점에는 matching이 아직 `PENDING`일 수 있으므로 출근 가능한 확정 근무로 간주하지 않는다. `MatchConfirmed` 소비로 확정 여부를 반영하고 확정된 본인 근무를 조회한다. 출근·진행·완료 API는 후속 작업이다.

## API

모든 요청에 `X-Internal-Secret`과 1~128자의 공백 없는 ASCII `Idempotency-Key`가 필요하다. 응답은 기존 `ApiResponse`를 따른다.

| 명령 | 경로 | 요청/응답 |
| --- | --- | --- |
| 생성 | `POST /api/works/internal/scheduled` | 기존 Saga의 matchingId, jobPostId, ownerMemberId, workerMemberId, paymentId, workDate, startTime, endTime / `data.workId` 문자열 |
| 보상 | `POST /api/works/internal/{workId}/cancel` | 본문 없음 / 성공 envelope |

생성과 취소는 서로 다른 명령 키를 사용한다. 같은 키와 같은 요청은 원래 결과를 반환한다. 키를 다른 요청이나 다른 작업에 재사용하면 409다. 다른 키로 동일 matching의 활성 근무를 생성하면 409다. 없는 근무 취소는 404, 확정된 근무 또는 예정·취소 외 상태의 보상은 409다. 인증 실패는 401, 잘못된 입력은 400이다.

## 영속성과 잠금

- `works`: 공고·회원·결제 외부 ID와 근무 일정 스냅샷 및 상태. 서비스 간 물리 FK는 없다.
- `work_commands`: 명령 키와 SHA-256 요청 fingerprint, 결과 work ID. 상태 변경과 같은 트랜잭션에서 기록한다.
- `work_matching_slots`: matching별 활성 work ID와 직렬화 잠금. 취소된 이전 work의 지연 명령은 활성 slot을 조건부 해제하므로 새 근무에 영향을 주지 않는다.
- `work_status_histories`: 생성·취소 전이, 내부 명령 키, 발생 시각. 이 API의 actor는 matching Saga다.
- 잠금 순서는 명령 행 → matching slot → work 행이다. 생성 경쟁은 slot과 active matching 유일 제약으로 차단한다.
- 예상하지 못한 DB 장애는 롤백 후 5xx로 반환한다. 호출자는 같은 명령 키로 재시도한다.

취소된 시도는 삭제하지 않는다. 같은 생성 키의 재전송은 취소된 원래 work ID를 반환하며 새 근무를 만들지 않는다. 모든 Saga 보상 종료 후 새 생성 키로 재시도할 때만 대체 근무를 만든다. 성공한 명령 보관 기간은 아직 정하지 않았으므로 자동 삭제하지 않는다.

## 상태와 후속 작업

상태 이름은 `SCHEDULED`, `CHECKED_IN`, `IN_PROGRESS`, `COMPLETED`, `CANCELED`, `FAILED`, `NO_SHOW`다. 근무 상태 전이는 생성과 미확정 근무의 `SCHEDULED -> CANCELED`만 처리한다. 매칭 확정은 상태 전이와 별개로 `confirmed_at`과 `confirmation_revision`에 기록한다. 출근·노쇼 정책, 사용자 취소, 근무 알림 이벤트는 후속 작업이다.

자정을 넘는 근무도 일정 스냅샷으로 보관한다. 과거 날짜를 일괄 거부하면 복구 명령이 실패할 수 있으므로 현재 시각에 따른 만료 조건을 추가하지 않는다.

## 로컬 실행

`.env.example`의 `WORK_*` 값을 개인 `.env`에 설정하고 공통 내부 secret을 맞춘다. `./scripts/local-run.sh infra`와 `./scripts/local-run.sh work`로 실행한다. HTTP 포트는 8086, 로컬 MySQL 포트는 3311이다. 실제 `.env`는 저장소에 추가하지 않는다.


## 매칭 확정 소비와 내 근무 조회

- matching-service의 Redis Stream `matching:domain-events`에서 `MatchConfirmed` v1을 소비한다. `MATCHING_OUTBOX_STREAM_KEY`와 `MATCHING_REDIS_HOST/PORT`를 발행 서비스와 동일하게 설정한다. JWT 검증에는 공통 `AUTH_JWT_SECRET`을 사용한다.
- 소비 그룹은 `work-confirmation-v1`, 논리 consumer는 `work-confirmation`이다. 새 그룹은 `0-0`부터 기존 이벤트를 읽는다. 여러 인스턴스가 같은 consumer를 사용하며, pending 중복 처리는 DB 잠금과 receipt로 직렬화한다. 별도 consumer 이름으로 바꾸면 기존 pending 회수 전략도 함께 바꿔야 한다.
- 매 poll에서 pending을 최대 100개 순회하고 신규 이벤트를 최대 100개 처리한다. pending 커서를 진행시켜 잘못된 이벤트가 뒤의 복구 대상이나 신규 이벤트를 막지 않게 한다. 재시작 시 pending 순회를 처음부터 재개한다.
- envelope와 payload의 ID·타입·버전을 비교하고, 근무 ID·매칭·공고·참여자·결제·일정 스냅샷이 저장된 근무와 일치하는지 확인한다. 알 수 없는 확정 스키마나 잘못된 이벤트는 ACK하지 않는다. 관련 없는 이벤트 타입은 ACK한다.
- matching slot → work → event receipt 순으로 잠근다. 확정 기록과 `work_confirmation_events`의 eventId/fingerprint를 동일 DB 트랜잭션으로 커밋한 뒤 ACK한다. 커밋 후 ACK 유실은 중복 소비로 복구한다. 이벤트 ID를 다른 내용에 재사용하면 거부한다.
- 낮거나 같은 revision은 근무를 되돌리지 않는다. 취소된 이전 시도는 확정하지 않고 receipt만 남기며 대체 근무에 영향을 주지 않는다. 확정된 근무에는 Saga 취소를 허용하지 않는다.
- 처리 실패는 pending에 남기며 로그에는 Stream record ID와 예외 타입만 남긴다. 운영자는 지속 실패 원인을 해결한 뒤 재처리해야 한다. 자동 폐기·Stream trimming은 하지 않는다.
- `GET /api/works/me?page=0&size=20`: JWT 역할에 따라 본인이 점주 또는 알바생인 확정 근무만 조회한다. size는 1~100이며 근무 날짜·시작 시각·ID 내림차순이다. 응답은 `content`, `page`, `size`, `totalElements`, `totalPages`를 포함한다.
- `GET /api/works/me/{workId}`: 같은 권한으로 상세를 조회한다. 다른 회원의 근무·미확정 근무·없는 근무는 모두 404다. 결제 식별자는 노출하지 않는다.
- 이벤트 반영 전에는 매칭 확정 직후라도 조회에 잠시 나타나지 않을 수 있다. 프런트는 재조회한다. 이는 출근 API를 추가한 것이 아니며, 가게 이름·주소 등의 표시 정보는 별도 계약이 필요하다.
- `WORK_EVENTS_ENABLED=false`로 소비를 끌 수 있다. 기본값은 true다. 기존 근무는 migration 시 확정 여부를 추측하지 않고 미확정으로 유지하며, 보관된 확정 이벤트를 재생해 반영한다.
