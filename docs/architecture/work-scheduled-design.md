# 예정 근무와 Saga 보상 계약

## 범위

매칭 확정 Saga가 결제 잠금 다음 단계에서 예정 근무를 생성한다. 이후 채팅방 생성 또는 자리 확정이 실패하면 같은 근무를 취소한다. 근무 생성 시점에는 matching이 아직 `PENDING`일 수 있으므로 출근 가능한 확정 근무로 간주하지 않는다. 확정 확인·출근·진행·완료 API는 후속 작업이다.

## API

모든 요청에 `X-Internal-Secret`과 1~128자의 공백 없는 ASCII `Idempotency-Key`가 필요하다. 응답은 기존 `ApiResponse`를 따른다.

| 명령 | 경로 | 요청/응답 |
| --- | --- | --- |
| 생성 | `POST /api/works/internal/scheduled` | 기존 Saga의 matchingId, jobPostId, ownerMemberId, workerMemberId, paymentId, workDate, startTime, endTime / `data.workId` 문자열 |
| 보상 | `POST /api/works/internal/{workId}/cancel` | 본문 없음 / 성공 envelope |

생성과 취소는 서로 다른 명령 키를 사용한다. 같은 키와 같은 요청은 원래 결과를 반환한다. 키를 다른 요청이나 다른 작업에 재사용하면 409다. 다른 키로 동일 matching의 활성 근무를 생성하면 409다. 없는 근무 취소는 404, 예정·취소 외 상태의 보상은 409다. 인증 실패는 401, 잘못된 입력은 400이다.

## 영속성과 잠금

- `works`: 공고·회원·결제 외부 ID와 근무 일정 스냅샷 및 상태. 서비스 간 물리 FK는 없다.
- `work_commands`: 명령 키와 SHA-256 요청 fingerprint, 결과 work ID. 상태 변경과 같은 트랜잭션에서 기록한다.
- `work_matching_slots`: matching별 활성 work ID와 직렬화 잠금. 취소된 이전 work의 지연 명령은 활성 slot을 조건부 해제하므로 새 근무에 영향을 주지 않는다.
- `work_status_histories`: 생성·취소 전이, 내부 명령 키, 발생 시각. 이 API의 actor는 matching Saga다.
- 잠금 순서는 명령 행 → matching slot → work 행이다. 생성 경쟁은 slot과 active matching 유일 제약으로 차단한다.
- 예상하지 못한 DB 장애는 롤백 후 5xx로 반환한다. 호출자는 같은 명령 키로 재시도한다.

취소된 시도는 삭제하지 않는다. 같은 생성 키의 재전송은 취소된 원래 work ID를 반환하며 새 근무를 만들지 않는다. 모든 Saga 보상 종료 후 새 생성 키로 재시도할 때만 대체 근무를 만든다. 성공한 명령 보관 기간은 아직 정하지 않았으므로 자동 삭제하지 않는다.

## 상태와 후속 작업

상태 이름은 `SCHEDULED`, `CHECKED_IN`, `IN_PROGRESS`, `COMPLETED`, `CANCELED`, `FAILED`, `NO_SHOW`다. 이번 API는 생성과 `SCHEDULED -> CANCELED`만 처리한다. 출근·노쇼 정책, 사용자 취소, 알림 이벤트, 조회 권한은 후속 작업이다.

자정을 넘는 근무도 일정 스냅샷으로 보관한다. 과거 날짜를 일괄 거부하면 복구 명령이 실패할 수 있으므로 현재 시각에 따른 만료 조건을 추가하지 않는다.

## 로컬 실행

`.env.example`의 `WORK_*` 값을 개인 `.env`에 설정하고 공통 내부 secret을 맞춘다. `./scripts/local-run.sh infra`와 `./scripts/local-run.sh work`로 실행한다. HTTP 포트는 8086, 로컬 MySQL 포트는 3311이다. 실제 `.env`는 저장소에 추가하지 않는다.
