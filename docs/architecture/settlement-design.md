# 근로자 정산 설계

## 범위

#105의 첫 단위로 정산 정책, 상태 전이, 금액 불변식과 payment-service의 정산 기반 모델을 정의하고 근무 완료 사실로 잠금 금액을 정산 예약으로 옮기는 처리 서비스를 구현했다. 두 번째 단위는 `matching:domain-events`의 `MatchConfirmed`와 `work:domain-events`의 `WorkCompleted`를 소비해 정산 대상을 영속적으로 만든다([정산 이벤트 소비](#정산-이벤트-소비)). 2026-10-28 MVP 단위는 [정산 조회와 지급 실행](settlement-payout-mvp.md)을 추가했다. 지급 명령·결과 검증·잔액 이관은 테스트 대역으로 검증하며, 실제 지급은 기본 비활성화한다. 아래 항목은 실제 연동 후속 범위다.

- 실제 외부 지급(지급대행) 어댑터·수취인 등록·운영 복구
- 실제 PG 환불 환경 검증(#107, 요청 실행·결과 복구 구현은 [환불 설계](refund-design.md))
- 사용자 취소·노쇼(#104)

`WorkCompleted`는 근무 완료 사실이며 지급 명령이 아니다. 이벤트에는 금액·결제 ID·지급 계좌가 없다. payment-service는 matching-service가 `MatchConfirmed`로 확정한 결제 ID를 정산 대상으로 기록해 두고, 그 잠금의 금액을 정산한다.

## 확정한 정책 (2026-10-18)

| 항목 | 결정 |
| --- | --- |
| 정산 금액 | 해당 매칭 잠금 금액 전액. 실제 근무시간으로 조정하지 않는다. |
| 근로자 시급 고정 시점 | 매칭 확정(잠금) 시점. 잠금마다 금액이 다를 수 있다. |
| 추가 시급 할증 | 근무일이 가까워질수록 오르는 할증은 최대 할증 시급 기준으로 미리 예치한다. 남은 예치는 환불(#107) 대상이다. 할증 자체는 job-service 후속 구현이다. |
| 수수료·공제 | 미정. 이번 단위는 잠금 금액 전액을 정산 예약만 한다. 지급 실행 전에 확정해야 한다. |
| 원 단위 | 정수 KRW만 정산한다. 원 미만 잠금은 반올림하지 않고 거절한다(`UNSUPPORTED_AMOUNT`). |
| 지급 시점 | 근무 완료 후 대기 기간이 지나면 자동 지급한다. 기본 24시간, `PAYMENT_SETTLEMENT_PAYOUT_DELAY`로 조정한다. |
| 지급 제공자 | 토스페이먼츠 지급대행. 기존 Toss 결제 승인은 근로자 지급을 제공하지 않는다. 연동은 후속 단위다. |
| 수취 계좌 | member-service 소유. payment-service는 지급 시점에 조회하는 계약을 후속으로 정의한다. |
| 테스트 범위 | MySQL Testcontainers 내부 모델 검증만 한다. 외부 HTTP 호출·지급 대역은 만들지 않는다. |

수수료 부담 주체(점주 추가 부담/근로자 공제)와 원천징수 적용 여부·의무자는 세무 확인이 필요하다. 이 결정 없이 지급 실행을 연결하지 않는다.

## 금액 불변식

예치(`payment_deposits`)는 공고당 하나이며 아래 항목으로 나눈다.

```text
예치 총액 = 가용 + 잠금 + 정산 예약 + 지급 완료 + 환불 예약 + 환불 완료
각 항목 >= 0, 가용 >= 0
```

| 항목 | 컬럼 | 증가 | 감소 |
| --- | --- | --- | --- |
| 예치 총액 | `deposited_amount` | 검증된 PG 예치 | 없음 |
| 잠금 | `locked_amount` | 매칭 잠금 | Saga 해제, 정산 이관 |
| 정산 예약 | `settlement_reserved_amount` | 정산 이관 | 지급 완료 |
| 지급 완료 | `settlement_paid_amount` | 지급 완료 | 없음 |
| 환불 예약·환불 완료 | `refund_reserved_amount`, `refunded_amount` | [환불 설계](refund-design.md) | |
| 가용 | 계산값 | Saga 해제, 정산 차액, 환불 확정 실패 | 매칭 잠금, 환불 요청 |

- 새 잠금은 `가용`만 쓸 수 있다. 정산 예약·지급 완료 금액은 가용에 다시 들어가지 않는다.
- 정산 이관은 잠금 금액 전체를 잠금에서 빼고 정산 금액을 정산 예약에 더한다. 정산 금액은 잠금 금액 이하이고, 적으면 차액이 가용으로 돌아간다. 현재 정책에서는 둘이 같다.
- DB CHECK `ck_deposit_balance`가 `잠금 + 정산 예약 + 지급 완료 + 환불 예약 + 환불 완료 <= 예치 총액`을 강제한다(V8).
- #107 첫 단위(2026-10-22)가 환불 예약·환불 완료 항목을 같은 행에 추가하고 `PaymentDeposit.availableAmount()`와 CHECK에 반영했다. 예치 총액을 줄이는 방식으로 환불을 표현하지 않는다. 같은 금액이 정산과 환불에 동시에 쓰이지 않도록 환불 가능액은 `가용`에서만 예약한다.

## 잠금 상태와 해제 규칙

| 잠금 상태 | 의미 | Saga 해제(새 키) | 매칭 활성 잠금 |
| --- | --- | --- | --- |
| `LOCKED` | 매칭에 배정된 금액 | 해제, 가용 복귀 | 예 |
| `RELEASED` | Saga 보상으로 반환됨 | 변경 없이 성공 | 아니오 |
| `TRANSFERRED` | 금액이 정산으로 넘어감 | `PAYMENT-409-007` 거절, 영속화 | 예 |

- `TRANSFERRED` 잠금의 해제는 금액 변경 전에 거절하고, 같은 키의 재요청도 같은 거절을 받는다. 지급할 금액이 Saga 해제로 가용 잔액에 다시 들어가지 않는다.
- 예전에 성공한 잠금·해제 명령의 같은 키 재요청은 원래 결과를 그대로 반환한다. 명령 fingerprint는 바뀌지 않았다.
- `TRANSFERRED`는 매칭의 활성 잠금으로 남아 같은 매칭의 새 잠금을 `PAYMENT-409-003`으로 막는다.
- 확정된 근무는 Saga 보상이 거절되므로 정상 경로에서는 정산 이관과 Saga 해제가 겹치지 않는다. 위 규칙은 지연·중복 요청에 대한 방어다.
- 잠금의 정산 이관 이력은 명령 키 대신 `settlement_id`로 원인을 남긴다.

## 정산 상태

| 상태 | 의미 | 허용 전이 |
| --- | --- | --- |
| `RESERVED` | 금액 예약, 지급 대기 | `ON_HOLD`, `PAYOUT_REQUESTED`(대기 기간 경과 후) |
| `ON_HOLD` | 지급 보류 | `RESERVED` |
| `PAYOUT_REQUESTED` | 외부 지급 결과 대기 | `PAID`, `PAYOUT_FAILED` |
| `PAYOUT_FAILED` | 확정된 지급 실패 | `PAYOUT_REQUESTED`, `ON_HOLD` |
| `PAID` | 지급 완료 | 없음 |

- `PAYOUT_REQUESTED`는 결과를 확인하기 전에는 보류하거나 다시 요청할 수 없다. 결과가 불명확하면 같은 지급 참조를 조회한다. MVP 실행기는 재송금하지 않으며, 조회 부재·불일치는 검토 대상으로 남긴다.
- `PAID`로 갈 때 예치의 정산 예약을 지급 완료로 옮긴다(`PaymentDeposit.completeSettlementPayout`).
- 보류 사유는 `ON_HOLD`일 때만 존재한다. 현재 사유는 `FUNDING_BLOCKED`(예치 후 PG 취소·부분취소 확인)와 `WORK_TERMINATED`(취소·노쇼 후 이미 생성된 지급 전 정산 보류)다. 예치 차단은 예약 시 같은 트랜잭션에서 반영한다. 근무 종료는 RESERVED/PAYOUT_FAILED 정산을 보류하고 TRANSFERRED 잠금을 해제하지 않는다. 보류 해제 조건은 후속 계약이며, PAYOUT_REQUESTED/PAID를 임의로 되돌리지 않는다.
- 모든 전이는 `payment_settlement_histories`에 이전·다음 상태, 사유, 시각으로 남긴다.

## 업무 식별자와 중복 방지

- 정산의 업무 식별자는 근무(`work_id`)와 잠금(`payment_lock_id`)이며 각각 DB 유일 제약이 있다. eventId 중복 제거(소비자의 수신 기록)와 별개로 한 근무·한 지급 대상에 정산은 하나다.
- 같은 근무의 재요청은 매칭·공고·점주·근로자·완료 시각이 같으면 `ALREADY_RESERVED`와 원래 정산 ID를 반환한다. eventId가 달라도 처음 정산을 유지한다. 내용이 다르면 `WORK_CONFLICT`다.
- 정산 대상과 다른 근무(같은 매칭의 다른 근무 포함)는 `WORK_CONFLICT`다.

## 정산 예약 처리 계약

`SettlementReservationService.reserve(WorkCompletion)`은 WorkCompleted v1의 `eventId`, `workId`, `matchingId`, `jobPostId`, `ownerMemberId`, `workerMemberId`, `occurredAt`을 받는다. 호출자의 트랜잭션에 참여한다. 정산할 잠금은 매칭 slot의 현재 잠금이 아니라 그 매칭의 정산 대상(`payment_settlement_targets`)이 가리키는 잠금이다.

| 결과 | 조건 | 상태 변경 |
| --- | --- | --- |
| `RESERVED` | 정산 대상과 근무·참여자가 일치하고 대상 잠금이 활성 `LOCKED` | 잠금 `TRANSFERRED`, 정산 생성, 금액 이관, 이력 |
| `ALREADY_RESERVED` | 같은 근무의 같은 사실 | 없음 |
| `INVALID_COMPLETION` | 필수 값 누락·비양수 ID·64자 초과 eventId | 없음 |
| `LOCK_NOT_FOUND` | 대상 잠금이 더 이상 매칭의 활성 잠금이 아님(확정 뒤 해제 등) | 없음 |
| `LOCK_MISMATCH` | 정산 대상·잠금과 공고·점주·근로자 불일치 | 없음 |
| `WORK_CONFLICT` | 같은 근무의 다른 사실, 정산 대상이 아닌 근무 | 없음 |
| `UNSUPPORTED_AMOUNT` | 정수 KRW가 아닌 잠금 | 없음 |
| `CONFIRMATION_REJECTED` | 같은 매칭의 `MatchConfirmed`가 정산 대상 기록에서 거절됨 | 없음 |

- 거절은 같은 사실로 재시도해도 바뀌지 않는 확정 결과다. 소비자는 결과를 수신 기록과 같은 트랜잭션에 저장하고 커밋 후 ACK한다.
- 정산 대상이 아직 없고 거절된 확정 이벤트도 없으면 결과가 아니라 `SettlementTargetNotReadyException`이다. 나중에 확정 이벤트가 오면 정상화될 수 있으므로 기록하지 않고 재시도한다.
- DB·잠금 실패는 결과가 아니라 예외로 전파해 전체를 롤백한다. 소비자는 ACK하지 않고 재시도한다.
- 완료 시각은 마이크로초로 자르고 이벤트와 같은 근무 시간대(현재 Asia/Seoul) 로컬 시각으로 저장한다. `payable_at = 완료 시각 + 대기 기간`이며 자동 지급 작업은 같은 시간대의 현재 시각과 비교해야 한다.
- 정산 예약은 매칭 slot 행을 새로 만들지 않는다.

## 정산 이벤트 소비

### 정산 대상 식별 계약

WorkCompleted v1에는 결제 ID와 금액이 없다(2026-10-04 결정으로 work 이벤트에서 결제 ID를 제외). 매칭 slot의 현재 활성 잠금을 그대로 고르면, 확정 뒤 예상하지 못한 해제·교체가 있을 때 다른 Saga 시도의 잠금을 정산할 수 있다. 확정한 결제 ID의 소유자는 확정 Saga를 조정하는 matching-service이며, 이미 발행 중인 `MatchConfirmed` v1 payload에 `workId`, `paymentId`, `lockedAmount`, `currency`가 들어 있다. 그래서 새 API나 이벤트 스키마 변경, 다른 서비스 DB 조회 없이 이 계약을 정산 대상 식별에 쓴다.

- `MatchConfirmed`를 받으면 매칭 slot을 잠그고 `paymentId`가 그 매칭의 활성 잠금인지 확인한다. 잠금의 매칭·공고·점주·근로자·금액·통화가 이벤트와 모두 같을 때만 `payment_settlement_targets`(매칭당 하나, 근무·잠금 유일)에 기록한다. 금액은 옮기지 않는다.
- 이전 Saga 시도의 `RELEASED` 잠금은 활성 잠금이 아니므로 대상이 될 수 없고(`LOCK_NOT_FOUND`), 근무 완료 처리도 대상 잠금 대신 slot의 다른 잠금을 고르지 않는다.
- 같은 매칭의 확정 재전달은 eventId·revision·시각이 달라도 확정 사실(근무·잠금·참여자·금액)이 같으면 `ALREADY_RECORDED`이고, 다르면 `TARGET_CONFLICT`다. 기록된 대상은 갱신하지 않는다.
- 근무 완료는 정산 대상과 근무·참여자를 비교하고, 대상 잠금이 여전히 활성인지 확인한 뒤 기존 정산 예약을 수행한다. 정산 금액은 payment-service가 소유한 잠금 금액이며, 대상 기록 때 matching-service의 `lockedAmount`와 일치함을 확인했다.

| 대상 기록 결과 | 조건 |
| --- | --- |
| `RECORDED` | 활성 잠금과 확정 사실 일치, 새 대상 기록 |
| `ALREADY_RECORDED` | 같은 매칭의 같은 확정 사실 |
| `INVALID_CONFIRMATION` | 근무·결제 ID가 양의 정수 문자열이 아님, 필수 값 누락, 비양수 금액 |
| `LOCK_NOT_FOUND` | 결제 ID가 매칭의 활성 잠금이 아님(없음·해제됨·다른 시도) |
| `LOCK_MISMATCH` | 잠금과 매칭·공고·점주·근로자·금액·통화 불일치 |
| `TARGET_CONFLICT` | 같은 매칭의 다른 확정 사실, 다른 매칭이 이미 대상으로 쓴 근무 |

### Stream·그룹

| 이벤트 | Stream(설정) | 그룹 / 공유 논리 consumer |
| --- | --- | --- |
| `MatchConfirmed` v1 (`aggregateType=MATCHING`) | `matching:domain-events` (`MATCHING_OUTBOX_STREAM_KEY`) | `payment-settlement-matching-v1` / `payment-settlement-matching` |
| `WorkCompleted` v1 (`aggregateType=WORK`) | `work:domain-events` (`WORK_OUTBOX_STREAM_KEY`) | `payment-settlement-work-v1` / `payment-settlement-work` |

- 두 Stream 모두 발행 서비스와 같은 `MATCHING_REDIS_HOST/PORT`를 쓴다. 새 그룹은 `0-0`부터 읽어 소비자 연결 전의 확정·완료 이벤트도 처리한다. 이것은 실제 발행된 완료 사실을 받는 것이며 V4의 소급 생성 금지와 충돌하지 않는다. Stream은 trim하지 않는다.
- `PAYMENT_SETTLEMENT_EVENTS_ENABLED=false`는 두 소비자를 함께 끈다. 한쪽만 켜면 근무 완료가 대상 없이 계속 대기하므로 분리하지 않았다.
- 매 poll에서 pending을 회전 커서로 batch-size(기본 100)만큼 다시 읽은 뒤 새 레코드를 읽는다. 처리되지 않는 레코드가 뒤 레코드의 복구와 새 레코드 처리를 막지 않는다.

### 해석·검증

- envelope는 각 Outbox relay가 문자열로 넣은 값이다. eventId(1~128자 출력 가능 ASCII), aggregateId·revision(양의 정수), version(`1`), occurredAt을 payload와 비교한다. matching payload는 Outbox `DATETIME(6)` 저장 전 나노초 시각이라 마이크로초 절삭·반올림 차이만 같은 시각으로 인정한다. matching payload의 날짜는 숫자 배열, work payload의 날짜는 ISO 문자열이며 둘 다 해석한다.
- `MatchConfirmed`: `aggregateId = payload.aggregateId = payload.matchingId`. 확정 시각은 envelope occurredAt이다.
- `WorkCompleted`: `aggregateId = payload.aggregateId = payload.workId`, revision 3, 상태 `COMPLETED`. 완료 시각은 envelope occurredAt이다. 처리자·일정 필드는 정산에 쓰지 않는다.
- 출근·시작 등 다른 이벤트 종류와 aggregateType은 대상이 아니므로 바로 ACK한다.

### 실패 분류와 ACK

| 분류 | 예 | 처리 |
| --- | --- | --- |
| 정상 결과 | `RECORDED`, `ALREADY_RECORDED`, `RESERVED`, `ALREADY_RESERVED` | 수신 기록과 함께 커밋 후 ACK |
| 불일치(영구 거절) | 잠금·참여자·금액 불일치, 다른 근무, 해제된 대상 잠금, 거절된 확정, 업무 값 누락 | 결과를 수신 기록과 함께 커밋 후 ACK, 오류 로그 |
| 잘못된 이벤트 | envelope·payload 불일치, 지원하지 않는 version, 완료 이벤트의 revision·상태 위반, 같은 eventId의 다른 내용 | 기록하지 않고 오류 로그, pending 유지(운영자 확인) |
| 선행 데이터 미도착 | `MatchConfirmed`보다 먼저 도착한 `WorkCompleted` | 기록하지 않고 경고 로그, pending 유지 후 재처리 |
| 일시 오류 | DB·잠금 실패, 커밋 후 ACK 실패, 처리 전 프로세스 중단 | 롤백 후 pending 유지, 재시작 후에도 pending 재수신으로 복구 |

- 업무 값 누락(근무·결제 ID가 숫자가 아님, 매칭·참여자 ID 없음 등)은 job-service `ApplicationSubmitted` 소비자와 같이 거절 결과로 기록한다. envelope를 해석할 수 없는 경우만 pending으로 남긴다.
- 확정이 거절된 매칭의 근무 완료는 대기하지 않고 `CONFIRMATION_REJECTED`로 기록한다. 거절된 확정은 같은 사실로 다시 와도 바뀌지 않기 때문이다.
- 선행 확정이 오지 않는 동안 근무 완료는 매 poll 경고 로그와 함께 대기한다. 재시도 한도나 자동 ACK는 없다.

### 원자성과 중복

- `MatchConfirmedReceiptService`: 정산 대상과 `payment_matching_event_receipts`(eventId 유일, fingerprint, 결과)를 한 트랜잭션에 저장한다.
- `WorkCompletedReceiptService`: 정산 생성·잠금 이관·예치 금액 이동·이력과 `payment_work_event_receipts`(eventId 유일, fingerprint, 결과, 정산 ID)를 한 트랜잭션에 저장한다. 수신 기록 저장이 실패하면 정산과 금액 이동도 롤백된다.
- 같은 eventId 재전달은 fingerprint가 같으면 저장된 결과를 돌려주고 상태를 바꾸지 않는다. 동시 재전달은 매칭 slot 잠금으로 직렬화되고, 늦은 쪽은 수신 기록 유일 제약으로 롤백된 뒤 다음 재수신에서 중복으로 ACK된다.
- 두 수신 서비스는 READ COMMITTED로 실행한다. 수신 기록 조회가 slot 잠금보다 먼저 일어나므로 REPEATABLE READ면 잠금 전 스냅샷이 고정되어, 동시에 커밋된 같은 근무의 정산을 보지 못하고 `WORK_CONFLICT`로 잘못 기록한다. 상호 배제는 slot·예치·잠금 행 잠금과 유일 제약이 맡는다.
- 다른 eventId의 같은 근무 완료는 정산 근무 유일 제약과 재요청 비교로 `ALREADY_RESERVED`가 되고 자기 수신 기록만 남긴다.
- 수신 기록이 있는 eventId와 이미 생성된 정산은 다시 처리해도 상태를 바꾸지 않는다. 늦게 온 출근·시작 이벤트나 재전달된 완료가 보류·지급 등 진행된 정산을 `RESERVED`로 되돌리지 않는다.
- fingerprint는 각 값의 길이와 콜론을 붙인 정규 문자열의 SHA-256이다. `MatchConfirmed:v1` 접두어와 eventId·매칭·revision·근무·잠금·공고·참여자·금액(뒤 0 제거)·통화·확정 시각, `WorkCompleted:v1` 접두어와 eventId·근무·revision·매칭·공고·참여자·완료 시각을 쓴다.

### 지급과의 경계

- 정산 예약은 `RESERVED`, 예치가 차단됐으면 같은 트랜잭션에서 `ON_HOLD(FUNDING_BLOCKED)`로 끝난다. 이벤트 소비는 `PAYOUT_REQUESTED`·`PAID`로 전이하지 않고, 지급 완료 금액이나 외부 PG·지급대행 호출을 만들지 않는다.

## 동시성

잠금 순서는 기존 `명령 → 매칭 slot → 예치 → 개별 잠금`을 유지하고 정산 행은 그 뒤에 만든다. 정산 대상 기록은 매칭 slot을 잠근 뒤 잠금 행을 읽으며 예치는 잠그지 않는다.

- 정산 예약과 Saga 해제는 같은 매칭 slot에서 직렬화된다. 먼저 커밋된 쪽만 적용된다(정산 후 해제는 거절, 해제 후 정산은 `LOCK_NOT_FOUND`).
- 정산 예약과 다른 매칭의 신규 잠금은 같은 예치 행에서 직렬화된다. 이관은 가용을 늘리지 않으므로 초과 사용이 생기지 않는다.
- 같은 근무의 동시 요청은 slot 잠금으로 직렬화되어 하나만 `RESERVED`다. 매칭 ID가 다른 동시 요청은 유일 제약이 최종 방어이며 예외 후 재시도에서 `WORK_CONFLICT`가 된다.

## 기존 데이터

V4 마이그레이션은 기존 잠금을 정산으로 소급 생성하지 않는다. V5~V7(정산 대상, 확정·완료 수신 기록)도 기존 매칭을 추정해 채우지 않고, 새 소비자 그룹이 Stream 처음부터 실제 발행된 `MatchConfirmed`·`WorkCompleted`를 재생해 만든다. 근무 완료 사실을 받은 잠금만 정산 대상이 된다. 기존 잠금·해제 명령과 이력은 그대로 보존하고, 기존 이력의 명령 키도 유지한다.

## 회원 탈퇴

잠금이 정산으로 이관되면 `LOCKED`가 아니므로, 지급 완료(`PAID`) 전 정산을 점주·근로자 모두의 탈퇴 차단 사유로 추가했다.

2026-10-26부터 예치가 지급 완료 정산과 환불 완료로 모두 끝나면(`deposited = settlement_paid + refunded`, 예치 차단 없음) 그 예치와 공고의 DEPOSITED 주문은 점주 탈퇴를 차단하지 않는다. 지급 완료 전 정산은 계속 점주·근로자 모두를 차단한다. 지급 실행은 기본 비활성화이므로 운영 종료는 아직 환불 경로만 연결돼 있다. 테스트 지급 성공도 동일한 종료 계산을 만족한다. 판단 기준은 [회원 계정 관리](member-account-management.md)를 따른다.

## #104·#107과 공유하는 규칙

- 같은 금액은 정산과 환불 중 한 곳에만 쓴다. 정산 예약·지급 완료는 환불 가능액이 아니다.
- 금액 변경은 모두 예치 행 잠금 아래에서 하고 위 잠금 순서를 따른다.
- 확정 근무의 취소·노쇼(#104)가 잠금을 다룰 때는 `LOCKED` 잠금만 해제·환불 대상으로 보고, `TRANSFERRED` 잠금은 정산 상태 전이(보류 등)로 처리한다.
- `RELEASED` 잠금이나 `CLOSED` 공고만으로 환불·정산 종료를 판단하지 않는다.

## 후속 작업

- 실제 지급 연결: 수수료·공제 확정, 수취인 등록·조회 계약, PG 어댑터, 운영 확인·복구 경로. MVP 명령·임대·기존 참조 조회·대기 기간 검사는 구현했으며, 실행은 기본 비활성화다.
- 보류 해제 조건(예치 차단 해제 등)과 #104 보류 사유.
- job-service 할증 시급과 최대 할증 선예치.
- 실제 work·matching·payment 프로세스를 연결한 정산 E2E. 현재는 발행 형식을 재현한 Redis/MySQL 통합 테스트로 검증했다.
- 해석할 수 없는 정산 이벤트와 장기 대기 근무 완료의 운영 확인 도구.
