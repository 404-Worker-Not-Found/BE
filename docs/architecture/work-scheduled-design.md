# 예정 근무와 Saga 보상 계약

## 범위

매칭 확정 Saga가 결제 잠금 다음 단계에서 예정 근무를 생성한다. 이후 채팅방 생성 또는 자리 확정이 실패하면 같은 근무를 취소한다. 근무 생성 시점에는 matching이 아직 `PENDING`일 수 있으므로 출근 가능한 확정 근무로 간주하지 않는다. `MatchConfirmed` 소비로 확정 여부를 반영하고 확정된 본인 근무를 조회한다. 확정 이후에는 알바생 GPS 출근과 점주의 근무 시작·완료 확인을 지원한다.

## 내부 API

모든 요청에 `X-Internal-Secret`과 1~128자의 공백 없는 ASCII `Idempotency-Key`가 필요하다. 응답은 기존 `ApiResponse`를 따른다.

| 명령 | 경로 | 요청/응답 |
| --- | --- | --- |
| 생성 | `POST /api/works/internal/scheduled` | 기존 Saga의 matchingId, jobPostId, ownerMemberId, workerMemberId, paymentId, workDate, startTime, endTime, endTimeNextDay, 선택적 latitude/longitude 쌍 / `data.workId` 문자열 |
| 보상 | `POST /api/works/internal/{workId}/cancel` | 본문 없음 / 성공 envelope |

생성과 취소는 서로 다른 명령 키를 사용한다. 같은 키와 같은 요청은 원래 결과를 반환한다. 키를 다른 요청이나 다른 작업에 재사용하면 409다. 다른 키로 동일 matching의 활성 근무를 생성하면 409다. 없는 근무 취소는 404, 확정된 근무 또는 예정·취소 외 상태의 보상은 409다. 인증 실패는 401, 잘못된 입력은 400이다.

## 영속성과 잠금

- `works`: 공고·회원·결제 외부 ID와 근무 일정 스냅샷 및 상태. 서비스 간 물리 FK는 없다.
- `work_commands`: 명령 키와 SHA-256 요청 fingerprint, 결과 work ID. 상태 변경과 같은 트랜잭션에서 기록한다.
- `work_matching_slots`: matching별 활성 work ID와 직렬화 잠금. 취소된 이전 work의 지연 명령은 활성 slot을 조건부 해제하므로 새 근무에 영향을 주지 않는다.
- `work_status_histories`: 전이와 발생 시각. Saga 생성·취소에는 내부 명령 키, 참여자 출근·시작·완료에는 actor_member_id를 기록한다. 둘 중 하나만 존재해야 한다.
- 잠금 순서는 명령 행 → matching slot → work 행이다. 생성 경쟁은 slot과 active matching 유일 제약으로 차단한다.
- 예상하지 못한 DB 장애는 롤백 후 5xx로 반환한다. 호출자는 같은 명령 키로 재시도한다.

취소된 시도는 삭제하지 않는다. 같은 생성 키의 재전송은 취소된 원래 work ID를 반환하며 새 근무를 만들지 않는다. 모든 Saga 보상 종료 후 새 생성 키로 재시도할 때만 대체 근무를 만든다. 성공한 명령 보관 기간은 아직 정하지 않았으므로 자동 삭제하지 않는다.

## 상태와 후속 작업

상태 이름은 `SCHEDULED`, `CHECKED_IN`, `IN_PROGRESS`, `COMPLETED`, `CANCELED`, `FAILED`, `NO_SHOW`다. 근무 상태 전이는 `SCHEDULED -> CHECKED_IN -> IN_PROGRESS -> COMPLETED`와 미확정 근무의 `SCHEDULED -> CANCELED` 보상을 처리한다. 매칭 확정은 상태 전이와 별개로 `confirmed_at`과 `confirmation_revision`에 기록한다. 노쇼 처리, 사용자 취소, 근무 알림 이벤트와 정산은 후속 작업이다. 현재 완료 API는 결제 해제나 정산을 호출하지 않는다.

자정을 넘는 근무도 일정 스냅샷으로 보관한다. 생성 요청의 `endTimeNextDay`는 선택이다. 없거나 null이면 요청 역직렬화 시점에 시각으로 채운다(종료 시각이 시작 시각 이하이면 true). 이 필드가 없던 이전 형식의 재시도가 저장된 명령을 조회하기 전에 400으로 거절되면, matching Saga가 확정적 실패로 보고 보상하면서 이미 만든 근무를 취소하지 못하기 때문이다. 명시적으로 전달한 값은 같은 규칙과 일치해야 하며 어긋나면 400이다. 시작·종료 시각이 없으면 채우지 않고 Bean Validation이 400으로 거절한다. `works.end_time_next_day`(V3)에 저장하고 본인 근무 조회 응답에 포함한다. V3 이전 행은 `end_time <= start_time`으로 채웠다. 익일 여부가 시각으로 결정되므로 생성 명령 지문은 필드 추가 전 형식을 그대로 유지한다. 따라서 이전 형식 요청, 같은 의미의 명시 요청, null 요청은 같은 키에서 같은 `workId`를 반환하고, 이전 버전이 저장한 명령의 재시도도 충돌하지 않는다. `MatchConfirmed` v1 이벤트는 바꾸지 않았으며, 일정 의미가 필요한 소비자는 같은 규칙으로 익일 여부를 판단한다. 과거 날짜를 일괄 거부하면 복구 명령이 실패할 수 있으므로 현재 시각에 따른 만료 조건을 추가하지 않는다.

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
- 이벤트 반영 전에는 매칭 확정 직후라도 조회에 잠시 나타나지 않을 수 있다. 프런트는 재조회한다. 가게 이름·주소 등의 표시 정보는 별도 계약이 필요하다.
- `WORK_EVENTS_ENABLED=false`로 소비를 끌 수 있다. 기본값은 true다. 기존 근무는 migration 시 확정 여부를 추측하지 않고 미확정으로 유지하며, 보관된 확정 이벤트를 재생해 반영한다.


## 출근·근무 실행 API와 기본 정책

아래 API에는 참여자 JWT가 필요하다. 내부 명령 키 대신 근무별 상태와 최초 처리 시각으로 재요청을 판별한다.

| API | 주체 | 기본 조건 |
| --- | --- | --- |
| `GET /api/works/me/attendance-policy` | 로그인 사용자 | 적용 중인 설정 조회 |
| `POST /api/works/me/{workId}/check-in` | 해당 근무 알바생 | 확정된 SCHEDULED, 본문 latitude/longitude, 기준 위치 100m 이내, 시작 30분 전~30분 후(경계 포함) |
| `POST /api/works/me/{workId}/start` | 해당 근무 점주 | CHECKED_IN, 예정 시작 시각 이후 |
| `POST /api/works/me/{workId}/complete` | 기본값 해당 근무 점주 | IN_PROGRESS, 예정 종료 시각 이후 |

- 확정되지 않은 근무, 다른 회원 근무, 허용되지 않은 역할은 404다. 좌표 입력 오류는 400, 위치 미등록·허용 시간/거리 초과·잘못된 상태는 409다.
- 성공 응답은 WorkResponse이며 기존 조회에도 장소 좌표와 `checkedInAt`, `startedAt`, `completedAt`이 포함된다. 같은 단계 재요청은 현재 상태와 최초 시각을 반환하고 이력을 추가하지 않는다. 이후 단계까지 진행되었어도 앞 단계 재요청은 되돌리지 않는다.
- work 행 잠금 아래 상태와 actor 이력을 같은 트랜잭션으로 기록한다. 동시 출근은 한 번만 반영하고 이력 기록 실패 시 상태 변경도 롤백한다. 참여자 명령은 work 행만 잠그므로 기존 matching-slot → work 잠금과 역전하지 않는다.
- 기준 좌표는 내부 예정 근무 생성 당시의 공고 스냅샷이다. 알바생이 보낸 GPS 좌표는 거리 판정에만 사용하고 DB에는 판정 거리와 정책 ID만 남긴다. 클라이언트 GPS의 진위 검증이나 위치 위조 탐지는 이 범위에 포함하지 않는다.
- 기존 좌표 없는 근무를 임의 위치로 보정하지 않는다. 조회는 유지하되 GPS 출근은 `WORK-409-005`로 거부한다. 기존 생성 명령 fingerprint는 명시적 v1 형식으로 유지하고 좌표가 있는 요청만 v2로 계산해 재시도 호환성을 보장한다.
- 예정 종료 시간이 시작 이하이면 다음 날 종료로 해석한다. 날짜·시각은 정책 시간대의 로컬 일정이며 기본 `Asia/Seoul`이다. 저장된 일정에 맞는 시간대를 운영 중 임의 변경하지 않는다.

### 설정 및 정책 교체

| 환경 변수 | 기본값 | 의미 |
| --- | --- | --- |
| `WORK_CHECK_IN_RADIUS_METERS` | `100` | 유한한 양수 미터 반경 |
| `WORK_CHECK_IN_EARLY_WINDOW` | `30m` | 시작 전 출근 허용 시간 |
| `WORK_CHECK_IN_LATE_WINDOW` | `30m` | 시작 후 출근 허용 시간 |
| `WORK_ATTENDANCE_TIME_ZONE` | `Asia/Seoul` | 일정과 서버 판정 시간대 |
| `WORK_COMPLETION_ROLE` | `OWNER` | 완료 확인 역할, `OWNER` 또는 `WORKER` |
| `WORK_ALLOW_EARLY_COMPLETION` | `false` | 예정 종료 전 완료 허용 여부 |

설정은 애플리케이션 시작 때 적용된다. 반경·허용 시간·완료 주체 변경에는 코드 수정이 필요 없다. 판정 방식 자체가 바뀌면 `AttendancePolicy`를 구현한 Bean으로 기본 GPS 정책을 교체한다. 역할별 본인 근무 검증, 상태 전이, 잠금, 이력 원자성은 서비스에 남기고 정책에는 거리·시간 판정과 완료 역할·공개 설정만 둔다. 클라이언트는 정책 조회 응답을 사용해 화면의 안내를 맞춘다.
