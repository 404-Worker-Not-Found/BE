# 매칭 결과 인앱 알림

## 범위와 소유권

notification-service는 회원별 인앱 알림과 읽음 상태를 전용 MySQL에 보관한다. matching-service의 `matching:domain-events` Redis Stream에서 `MatchConfirmed` v1과 `ApplicationRejected` v1을 소비한다. 다른 서비스 DB에 접근하거나 서비스 간 FK를 만들지 않는다.

- `MatchConfirmed`: 점주 OWNER와 알바생 WORKER 각각에게 알림을 생성한다. 화면 기본 문구는 “매칭이 확정되었습니다.”로 표시할 수 있다.
- `ApplicationRejected`: 해당 WORKER에게만 알림을 생성한다. 기본 문구는 “지원한 공고의 모집이 완료되었습니다.”다.
- API는 type과 공고·지원·매칭 ID를 반환한다. 프런트에서 문구와 이동 화면을 결정한다. 토큰, 결제 ID, 다른 참여자 ID, 내부 이벤트 ID는 노출하지 않는다.
- 현재 ApplicationSubmitted 이벤트에는 점주 ID가 없으므로 점주 지원 알림을 임의로 생성하지 않는다. 공고·출근·노쇼·결제 알림, 실시간 push/SSE/WebSocket, 외부 앱 푸시·SMS·이메일은 후속 범위다. 인앱 목록과 미읽음 개수는 REST 재조회로 갱신한다.

## 소비와 멱등성

- 소비 그룹은 `notification-results-v1`, 논리 consumer는 `notification-results`다. 새 그룹은 `0-0`부터 기존 이벤트를 읽는다. 여러 인스턴스가 같은 논리 consumer를 사용하며 DB에서 중복 처리를 직렬화한다.
- 매 poll에서 pending 100개를 회전 커서로 읽고 신규 이벤트도 최대 100개 읽는다. 잘못된 이벤트가 있어도 뒤의 정상 이벤트가 처리된다. 소비 이름을 바꾸려면 기존 pending 회수 전략도 변경해야 한다.
- envelope의 eventId/eventType/aggregateType/aggregateId/revision/version을 payload와 대조한다. 지원하는 이벤트에 누락·불일치·잘못된 상태·버전이 있으면 ACK하지 않고 pending에 남긴다. 다른 이벤트 타입은 이 소비자의 범위 밖이므로 ACK한다. Stream trim이나 자동 폐기는 하지 않는다.
- `notification_events`는 eventId별 fingerprint를 저장한다. receipt 행을 잠근 후 기존 알림을 확인하고 회원별 알림을 생성한다. receipt와 알림을 같은 트랜잭션에서 커밋한 뒤 ACK한다. 같은 eventId의 다른 내용은 거부한다.
- 알림의 `(event_id, member_id, member_role)` DB 유일 제약이 추가 중복을 차단한다. ACK 전에 프로세스가 중단돼도 재전달은 같은 알림을 유지하며 읽음 상태를 초기화하지 않는다.
- fingerprint v1은 공통 필드 eventId, eventType, ISO_LOCAL_DATE_TIME occurredAt, aggregateId, revision, version, applicationId, jobPostId, workerMemberId를 순서대로 사용한다. MatchConfirmed는 matchingId와 ownerMemberId, ApplicationRejected는 status를 뒤에 붙인다. 각 값의 문자열 길이와 콜론을 앞에 붙인 후 SHA-256을 계산한다. 새로운 record 필드나 사용하지 않는 추가 JSON 필드가 기존 지문을 바꾸면 안 된다.
- 알림은 상태를 덮어쓰는 projection이 아니라 발생 이력이므로 과거 이벤트도 보관한다. 재전달의 기준은 source의 안정된 eventId다. 발생 시각은 occurredAt, 목록 정렬은 서비스에 저장된 ID를 사용한다.

## API

모든 API는 기존 JWT의 memberId와 role로 본인 알림만 처리하고 공통 ApiResponse를 사용한다.

| API | 설명 |
| --- | --- |
| `GET /api/notifications/me?beforeId={id}&size=20` | ID 내림차순. beforeId는 선택적인 양수 상한, size는 1~100. content/nextBeforeId/hasNext 반환 |
| `GET /api/notifications/me/unread-count` | 본인 역할의 미읽음 unreadCount 반환 |
| `PATCH /api/notifications/me/{notificationId}/read` | 최초 읽음 시각을 기록. 재시도해도 같은 시각 유지 |

알림 응답은 notificationId, type, jobPostId, matchingId, applicationId, occurredAt, createdAt, readAt이다. 미선정 알림의 matchingId는 null이다. 타인·다른 역할·없는 알림의 읽음 요청은 `NOTIFICATION-404-001`이다. 인증되지 않은 요청은 401이다. 삭제와 보관 만료 정책은 아직 구현하지 않는다.

## 실행과 검증

- HTTP 8088, 로컬 MySQL 3314. `.env.example`의 NOTIFICATION_* 변수를 설정하고 `./scripts/local-run.sh notification`으로 실행한다.
- 이벤트 Redis는 MATCHING_REDIS_HOST/PORT와 MATCHING_OUTBOX_STREAM_KEY를 공유한다. `NOTIFICATION_EVENTS_ENABLED=false`로 소비를 끌 수 있다.
- JWT secret은 공통 AUTH_JWT_SECRET이며 누락·공백·32 UTF-8 바이트 미만은 시작 시 거부한다.
- 저장소 전체 검증에 새 서비스를 포함한다. MySQL/Redis Testcontainers로 중복·동시 수신·pending 복구·롤백·권한·읽음 멱등성·페이지 조회를 검증한다.
