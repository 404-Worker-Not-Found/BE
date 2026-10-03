# 채팅방 생성과 Saga 보상

## 범위

매칭 확정 Saga가 예정 근무 다음 단계에서 채팅방을 생성하고, 이후 자리 확정이 거절되면 해당 방을 종료한다. OPEN은 내부 자원이 생성됐다는 뜻이다. 아직 매칭이 PENDING일 수 있으므로 메시지를 보낼 수 있는 확정 상태로 간주하지 않는다. `MatchConfirmed` 소비로 별도의 확정 시각·revision을 기록한 뒤 참여자 조회를 허용한다. 메시지 전송과 실시간 연결은 후속 작업이다.

## 내부 API

모든 요청은 X-Internal-Secret과 1~128자의 공백 없는 ASCII Idempotency-Key를 사용한다. 응답은 ApiResponse다.

| 명령 | 경로 | 요청/응답 |
| --- | --- | --- |
| 생성 | `POST /api/chat-rooms/internal` | matchingId, jobPostId, ownerMemberId, workerMemberId, workId / `data.chatRoomId` 문자열 |
| 보상 종료 | `POST /api/chat-rooms/internal/{chatRoomId}/close` | 본문 없음 / 성공 envelope |

회원·공고·매칭 ID는 양수 Long, 외부 workId는 공백만으로 이루어지지 않은 최대 255자 문자열이다. 생성과 종료는 별도 명령 키를 사용한다. 같은 키와 같은 요청은 원래 결과를 반환하고 다른 요청이나 작업에 재사용하면 CHAT-409-001이다. 다른 키로 같은 matching의 OPEN 방을 만들면 CHAT-409-002, 없는 방을 종료하면 CHAT-404-001이다. CLOSED 방의 반복 종료는 성공한다.

## 영속성과 복구

- chat_rooms: 매칭·공고·회원·근무 외부 참조, OPEN/CLOSED 상태, 생성·종료 시각. 서비스 간 물리 FK는 없다.
- chat_commands: 명령 키, 요청 fingerprint, 원래 결과 ID. 방 상태·이력과 같은 트랜잭션에 저장하며 자동 삭제하지 않는다.
- chat_matching_slots: matching별 직렬화 잠금과 활성 방 ID. DB 유일 제약도 중복 OPEN 방 생성을 차단한다.
- chat_status_histories: 이전·다음 상태, 내부 명령 키, 발생 시각.
- 잠금 순서는 명령 행 → matching slot → 방 행이다.

종료한 생성 명령을 다시 호출해도 종료된 원래 방 ID를 돌려준다. 모든 보상이 끝난 뒤 새 생성 명령으로 새 방을 만들 수 있다. 이전 방의 지연 종료는 새 방이나 활성 slot을 해제하지 않는다. 확정된 방은 Saga 보상 종료를 409로 거절한다. 내부 보상 API이므로 일반 사용자 퇴장·근무 완료 후 보관 정책과 분리한다.

fingerprint는 CREATE + 요청 record의 필드 표현 또는 CLOSE + 방 ID를 SHA-256으로 계산한다. 필드 순서·표현을 바꾸면 기존 명령 재시도에 영향을 주므로 스키마를 변경할 때 기존 fingerprint 호환성도 유지해야 한다.

4xx는 부수 효과 없는 거절이다. 예상하지 못한 DB·내부 오류는 롤백하고 원문을 응답에 노출하지 않는 500으로 반환한다. 호출자는 결과 미확정으로 취급해 같은 명령 키로 재시도한다. 일반 IllegalArgumentException을 일괄 400으로 바꾸지 않으며 업무 오류는 BusinessException과 ChatRoomErrorCode를 사용한다.

## 실행과 검증

.env.example의 CHAT_* 설정을 개인 .env에 채우고 공통 내부 secret을 맞춘다. `./scripts/local-run.sh infra`, `./scripts/local-run.sh chat`로 실행한다. HTTP 8087, 로컬 MySQL 3312를 사용한다. 통합 테스트는 MySQL Testcontainers로 명령 재시도·충돌·동시 생성과 종료·롤백·인증·입력 검증·Swagger를 확인한다.

## 매칭 확정 소비와 내 채팅방 조회

- matching-service의 Redis Stream `matching:domain-events`에서 `MatchConfirmed` v1을 소비한다. `MATCHING_OUTBOX_STREAM_KEY`와 `MATCHING_REDIS_HOST/PORT`를 발행 서비스와 동일하게 설정한다. JWT 검증에는 공통 `AUTH_JWT_SECRET`을 사용한다.
- 소비 그룹은 `chat-confirmation-v1`, 논리 consumer는 `chat-confirmation`이다. 새 그룹은 `0-0`부터 기존 이벤트를 읽는다. pending 이벤트를 먼저 복구하고 신규 이벤트를 읽으며, 이벤트 처리와 수신 이력을 MySQL에서 원자적으로 커밋한 뒤 Redis에 ACK한다. 별도 consumer 이름으로 바꿀 때는 기존 pending 회수 전략도 변경해야 한다.
- 이벤트 envelope의 ID·타입·revision·버전과 payload를 비교하고, 채팅방 ID·매칭·공고·참여자·근무 ID가 저장된 방과 일치하는지 검사한다. 맞지 않거나 지원하지 않는 이벤트는 ACK하지 않고 pending에 남긴다. 다른 이벤트 유형은 ACK한다. 실패 로그에는 Stream record ID와 예외 타입만 남긴다.
- 잠금 순서는 matching slot → chat room → event receipt다. 같은 eventId와 같은 내용은 중복 처리해도 한 번만 반영하며, 같은 eventId의 다른 내용은 거부한다. 낮거나 같은 revision은 확정 상태를 되돌리지 않는다. 이미 CLOSED인 이전 시도는 receipt만 기록하고, 대체 방을 확정하지 않는다.
- `GET /api/chat-rooms/me?page=0&size=20`: JWT의 역할과 회원 ID에 따라 본인이 점주 또는 알바생인 확정된 OPEN 방만 조회한다. size는 1~100이고 생성 시각·ID 내림차순이다.
- `GET /api/chat-rooms/me/{chatRoomId}`: 같은 권한으로 상세를 조회한다. 타인·미확정·종료된 방은 모두 404다. 응답에는 채팅방·매칭·공고·참여자·근무 ID와 확정 시각을 포함한다.
- 이벤트 반영 전에는 매칭 확정 직후라도 방이 잠시 조회되지 않을 수 있다. 프런트는 재조회한다. 기존 방은 확정 여부를 추측하지 않고 보관된 확정 이벤트 재생으로 반영한다. `CHAT_EVENTS_ENABLED=false`로 소비를 끌 수 있다.
