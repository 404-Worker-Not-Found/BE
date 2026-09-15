# 채팅방 생성과 Saga 보상

## 범위

매칭 확정 Saga가 예정 근무 다음 단계에서 채팅방을 생성하고, 이후 자리 확정이 거절되면 해당 방을 종료한다. OPEN은 내부 자원이 생성됐다는 뜻이다. 아직 매칭이 PENDING일 수 있으므로 메시지를 보낼 수 있는 확정 상태로 간주하지 않는다. 사용자 조회·메시지 전송·실시간 연결 및 MatchConfirmed 소비는 후속 작업이며, 제공 전에 매칭 확정과 참가자 권한을 검증해야 한다.

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

종료한 생성 명령을 다시 호출해도 종료된 원래 방 ID를 돌려준다. 모든 보상이 끝난 뒤 새 생성 명령으로 새 방을 만들 수 있다. 이전 방의 지연 종료는 새 방이나 활성 slot을 해제하지 않는다. 내부 보상 API이므로 일반 사용자 퇴장·근무 완료 후 보관 정책과 분리한다.

fingerprint는 CREATE + 요청 record의 필드 표현 또는 CLOSE + 방 ID를 SHA-256으로 계산한다. 필드 순서·표현을 바꾸면 기존 명령 재시도에 영향을 주므로 스키마를 변경할 때 기존 fingerprint 호환성도 유지해야 한다.

4xx는 부수 효과 없는 거절이다. 예상하지 못한 DB·내부 오류는 롤백하고 원문을 응답에 노출하지 않는 500으로 반환한다. 호출자는 결과 미확정으로 취급해 같은 명령 키로 재시도한다. 일반 IllegalArgumentException을 일괄 400으로 바꾸지 않으며 업무 오류는 BusinessException과 ChatRoomErrorCode를 사용한다.

## 실행과 검증

.env.example의 CHAT_* 설정을 개인 .env에 채우고 공통 내부 secret을 맞춘다. `./scripts/local-run.sh infra`, `./scripts/local-run.sh chat`로 실행한다. HTTP 8087, 로컬 MySQL 3312를 사용한다. 통합 테스트는 MySQL Testcontainers로 명령 재시도·충돌·동시 생성과 종료·롤백·인증·입력 검증·Swagger를 확인한다.
