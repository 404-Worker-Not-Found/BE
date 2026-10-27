# 상호 리뷰 설계

#126의 첫 단위로, 근무가 완료되면 점주와 알바생이 서로 리뷰(평점·코멘트)를 남기고 받은 리뷰와 평점을 조회하는 기능을 `member-service`에 구현했다. [MVP 도메인 흐름](mvp-domain-flow.md)의 소유권에 따라 리뷰와 평점 집계는 `member-service`가 소유하고, 근무 완료 사실은 work-service의 `WorkCompleted` 이벤트로 받는다.

아래는 구현하지 않는다.

- 신뢰 점수 계산, `TrustScoreUpdated` 발행, matching-service 점수 입력(평점) 연결(다음 단위)
- 노쇼·취소에 따른 패널티(#104)
- 리뷰 수정·삭제, 신고·숨김(신고·분쟁은 MVP 제외 범위)
- 리뷰 작성 알림

## 임시 정책

리뷰 점수 범위·작성 기간·수정 정책은 아직 확정되지 않은 정책 값이다([MVP 도메인 흐름](mvp-domain-flow.md)). 시연과 개발을 위해 아래 임시 값을 쓰고, 바뀔 수 있는 값은 설정으로 분리했다.

| 항목 | 임시 정책 | 설정 |
| --- | --- | --- |
| 작성 자격 | 완료 근무의 점주·알바생만 상대방에게 쓴다. 근무당·작성자당 한 번 | - |
| 평점 | 1~5 정수. `4.5`, `"5"`처럼 정수가 아닌 값은 400 | DB CHECK와 함께 고정 |
| 코멘트 | 선택, 500자 이하. 앞뒤 공백을 빼고 저장하며 빈 값은 저장하지 않는다 | DB 컬럼과 함께 고정 |
| 작성 기간 | 근무 완료 시각부터 14일 직전까지. 그 시각과 같거나 지나면 409 | `MEMBER_REVIEW_WRITE_PERIOD`(기본 `14d`) |
| 수정·삭제 | 지원하지 않는다 | - |
| 공개 범위 | 로그인한 회원은 누구의 받은 리뷰든 조회할 수 있다. 작성자 회원 ID는 보여 주지 않고 작성자가 점주인지 알바생인지만 보여 준다 | - |
| 평균 평점 | 받은 평점의 산술 평균, 소수 첫째 자리 반올림. 받은 리뷰가 없으면 `null` | - |

## API

모든 API는 회원 JWT가 필요하다. 응답은 공통 `ApiResponse`다.

| API | 설명 |
| --- | --- |
| `POST /api/members/me/reviews` | 리뷰 작성. 본문 `{"workId":10,"rating":5,"comment":"친절했어요"}` |
| `GET /api/members/me/reviews/pending?page=0&size=20` | 내가 참여해 완료됐고 작성 기간 안이며 아직 리뷰하지 않은 근무. 최근 완료 순 |
| `GET /api/members/{memberId}/reviews?page=0&size=20` | 회원이 받은 리뷰 수·평균 평점과 리뷰 목록. 최근 순 |

- 작성 응답 `data`: `reviewId`, `workId`, `revieweeMemberId`, `reviewerRole`(`OWNER`/`WORKER`), `rating`, `comment`, `createdAt`
- 작성할 리뷰 목록 항목: `workId`, `jobPostId`, `workDate`, `myRole`, `revieweeMemberId`, `completedAt`, `writableUntil`
- `page`는 0 이상, `size`는 1~100(기본 20)이다.

| 결과 | 코드 |
| --- | --- |
| 성공, 같은 내용의 재요청 | 200 (재요청은 처음 리뷰) |
| 근무 참여자가 아님 | 403 `REVIEW-403-001` |
| 작성자나 상대방이 탈퇴 진행 중 | 403 `GLOBAL-403-001` |
| 리뷰할 수 있는 완료 근무가 아님(근무 없음, 완료 이벤트 미수신) | 404 `REVIEW-404-001` |
| 이미 다른 내용으로 작성함 | 409 `REVIEW-409-001` |
| 작성 기간 지남 | 409 `REVIEW-409-002` |
| 받은 리뷰 조회 대상 회원 없음 | 404 `MEMBER-404-001` |

같은 근무에 같은 작성자가 다시 요청하면 평점과 정리한 코멘트가 같을 때 처음 리뷰를 돌려준다. 작성 기간이 지난 뒤의 같은 요청도 처음 리뷰를 돌려준다. 검사 순서는 근무 존재 → 참여자 → 계정 장벽 → 기존 리뷰 → 작성 기간이다.

## 리뷰할 수 있는 근무: WorkCompleted 소비

- `work:domain-events`(matching Redis)의 `WorkCompleted` v1을 전용 그룹 `member-review-work-v1`, 공유 논리 소비자 `member-review-work`로 읽는다. 그룹은 `0-0`부터 만들어 소비자 연결 전에 완료된 근무도 리뷰 대상이 된다. Stream은 trim하지 않는다.
- envelope와 payload(eventId, aggregateId=workId, revision 3, version 1, 발생 시각, 상태 `COMPLETED`)가 맞아야 한다. 공고·점주·알바생 ID가 양수이고 점주와 알바생이 달라야 한다.
- `reviewable_works`에 근무 ID, 공고 ID, 점주·알바생, 근무일, 완료 시각(이벤트 발생 시각을 마이크로초로 절삭)을 기록한다. 기록은 커밋된 뒤 ACK한다.
- 같은 근무의 재전달은 처음 기록을 유지하고 ACK한다. 같은 근무 ID인데 공고·참여자가 다르면 계약 위반이므로 기록하지 않는다.
- 해석할 수 없는 레코드와 같은 근무 ID의 다른 내용은 오류 로그와 함께 pending으로 남긴다. 자동 ACK나 재시도 한도는 두지 않는다. DB 장애 같은 일시 오류도 pending으로 남아 다음 poll에서 재처리한다. 다른 근무 이벤트(출근·시작)는 바로 ACK한다.
- 매 poll마다 회전 커서로 pending을 batch-size만큼 다시 읽은 뒤 새 레코드를 읽는다. 다른 서비스의 근무·매칭 이벤트 소비자와 같은 규칙이다.

## 저장과 동시성

| 테이블(V5) | 내용 |
| --- | --- |
| `reviewable_works` | 리뷰할 수 있는 근무. 근무 ID 기본 키, eventId 유일, 점주≠알바생 CHECK |
| `member_reviews` | 리뷰. `(work_id, reviewer_member_id)` 유일, 평점 1~5·역할·작성자≠상대 CHECK, `reviewable_works` 외래 키 |
| `member_review_stats` | 회원이 받은 리뷰 수와 평점 합. 다음 단위의 신뢰 점수 입력 |

- 리뷰 저장과 평점 집계 갱신은 한 트랜잭션이다. 집계는 `INSERT ... ON DUPLICATE KEY UPDATE` 한 문장으로 더해 동시에 쓴 다른 근무의 리뷰도 잃지 않는다.
- 잠금 순서는 작성자·상대방 계정 장벽(회원 ID 오름차순) → 근무 행이다. 같은 근무의 리뷰는 근무 행 잠금으로 직렬화된다.
- 기존 리뷰 확인은 잠금 읽기다. MySQL 반복 읽기 격리에서 일반 읽기는 트랜잭션의 첫 읽기 시점 스냅샷을 보므로, 근무 행 잠금을 기다리는 동안 다른 요청이 커밋한 같은 리뷰를 놓치고 유일 제약 오류가 난다.
- 탈퇴 준비·완료된 회원은 계정 장벽 때문에 리뷰를 쓰거나 새로 받을 수 없다. 이미 쓴 리뷰와 집계는 거래 증거로 남기며 탈퇴 시 지우지 않는다. 리뷰는 탈퇴 차단 사유가 아니다.

## 시각 기준

근무 완료 시각은 work-service가 근무 일정 시간대로 만든 로컬 시각이다. member-service도 현재 시각을 같은 시간대(`member.schedule.time-zone`, work-service·job-service·matching-service와 같은 `WORK_ATTENDANCE_TIME_ZONE`, 기본 `Asia/Seoul`)로 고정한 `Clock`에서 읽어 작성 기간을 판단하고 리뷰 작성 시각을 남긴다.

## 운영 설정

| 환경 변수 | 기본값 | 의미 |
| --- | --- | --- |
| `MEMBER_REVIEW_WRITE_PERIOD` | `14d` | 근무 완료 후 리뷰 작성 기간 |
| `MEMBER_REVIEW_EVENTS_ENABLED` | `true` | `WorkCompleted` 소비자 사용 |
| `MEMBER_REVIEW_EVENTS_POLL_INTERVAL` / `..._INITIAL_DELAY` / `..._BATCH_SIZE` | `1s` / `0s` / `100` | 주기·첫 실행·배치 크기 |
| `MATCHING_REDIS_HOST` / `MATCHING_REDIS_PORT` | `localhost` / `6381` | 근무 이벤트 Stream이 있는 Redis |
| `WORK_OUTBOX_STREAM_KEY` | `work:domain-events` | 근무 이벤트 Stream |

## 후속 작업

1. 신뢰 점수: 평점 집계와 노쇼·취소 이력으로 신뢰 점수를 계산하고, 같은 트랜잭션에서 저장한 뒤 `TrustScoreUpdated`를 발행해 matching-service 점수 입력(평점)에 연결한다.
2. 리뷰 작성 요청 알림(notification-service)과 화면 연결.
3. 리뷰 수정·삭제와 작성 기간 등 정책 확정.

## 검증

Redis·MySQL Testcontainers로 확인했다.

- 근무 이벤트 소비: 완료 근무 기록과 다른 근무 이벤트 ACK, 같은 완료의 재전달, 참여자가 같은 이벤트·같은 근무 ID의 다른 내용·envelope 불일치의 pending 유지와 뒤 레코드 처리
- 리뷰: 점주·알바생 상호 작성과 평균, 같은 요청 재반환과 다른 내용 409, 참여자가 아닌 회원·없는 근무, 탈퇴 진행 중 회원, 작성 기간 경계(직전 허용, 같은 시각 거절), 기간 뒤 같은 요청, 작성할 리뷰 목록, 같은 리뷰 동시 요청(한 건·집계 1회), 같은 회원에게 쓰는 동시 리뷰(집계 누락 없음)
- HTTP: 인증, 평점·코멘트·페이지 검증, 오류 코드, Swagger 경로. JVM 시간대를 UTC로 둬도 통과한다.
