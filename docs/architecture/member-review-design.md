# 상호 리뷰 설계

#126의 첫 단위로, 근무가 완료되면 점주와 알바생이 서로 리뷰(평점·코멘트)를 남기고 받은 리뷰와 평점을 조회하는 기능을 `member-service`에 구현했다. [MVP 도메인 흐름](mvp-domain-flow.md)의 소유권에 따라 리뷰와 평점 집계는 `member-service`가 소유하고, 근무 완료 사실은 work-service의 `WorkCompleted` 이벤트로 받는다.

아래는 구현하지 않는다.

- 확정 후 취소에 따른 패널티와 신뢰 점수 감점(#104, 사용자 결정으로 보류). 노쇼 감점은 #164에서 추가했다([노쇼 감점](#노쇼-감점164-임시-정책)).
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

## 신뢰 점수(#126 두 번째 단위, 임시 정책)

2026-11-02 사용자 선택: 받은 리뷰 평점만으로 계산하고 노쇼·취소 감점은 넣지 않는다. 매칭 점수에는 지원 시각과 50:50으로 반영한다([지원 도메인 설계](matching-application-design.md#아직-없는-점수-데이터의-후속-연동)).

| 항목 | 임시 정책(`review-rating-v1`) | 설정 |
| --- | --- | --- |
| 보정 평점 | (기본 평점 × 보정 리뷰 수 + 받은 평점 합) / (보정 리뷰 수 + 받은 리뷰 수). 리뷰가 적을 때 한두 개로 극단값이 되지 않게 한다 | `MEMBER_TRUST_SCORE_PRIOR_RATING`(기본 `3.0`), `MEMBER_TRUST_SCORE_PRIOR_REVIEW_COUNT`(기본 `3`) |
| 신뢰 점수 | (보정 평점 - 1) / 4 × 100, 소수 둘째 자리 반올림. 0~100 | - |
| 리뷰 없음 | 점수를 만들지 않는다. matching-service는 평점 입력 누락으로 처리한다 | - |
| 대상 | 리뷰를 받은 모든 회원(점주 포함). 현재 매칭 점수는 알바생 점수만 쓴다 | - |

예: 5점 1개 62.50, 5점 3개 75.00, 4점·5점 65.00. 설정값을 바꾸면 같은 정책 버전이라도 이후 계산 값이 달라지며 기존 점수는 다음 리뷰 때 다시 계산된다.

### 노쇼 감점(#164, 임시 정책)

2026-11-09 사용자 선택: 노쇼 1회당 고정 점수를 빼고(`review-rating-no-show-v1`), 리뷰 없는 근로자는 중립 점수에서 감점한다. 회복·시간 감쇠는 넣지 않는다. 위 평점 계산은 그대로이며 이 정책 버전이 `review-rating-v1`을 대체한다.

| 항목 | 임시 정책(`review-rating-no-show-v1`) | 설정 |
| --- | --- | --- |
| 평점 점수 | 위 신뢰 점수 계산과 같다. 받은 리뷰가 없으면 기본 평점의 점수(기본 3.0 → 50.00) | 위와 같음 |
| 신뢰 점수 | max(0, 평점 점수 - 노쇼 감점 × 노쇼 횟수). 0~100 | `MEMBER_TRUST_SCORE_NO_SHOW_PENALTY`(기본 `10`, 0~100, 소수 둘째 자리까지) |
| 점수 없음 | 받은 리뷰와 노쇼가 모두 없을 때만. matching-service는 평점 입력 누락으로 처리한다 | - |
| 노쇼 대상 | work-service `WorkNoShow` v1의 근로자(`workerMemberId`). 노쇼는 시스템 자동 판정만 있다(`actorRole=SYSTEM`) | - |

예: 5점 1개 + 노쇼 1회 52.50, 리뷰 없이 노쇼 1회 40.00, 리뷰 없이 노쇼 5회 이상 0.00.

- `work:domain-events`의 `WorkNoShow`를 전용 그룹 `member-trust-no-show-v1`로 소비한다. 리뷰 대상 근무 그룹은 이미 지난 노쇼를 ACK했으므로 별도 그룹을 처음 만들 때 Stream 처음부터 읽어 배포 전 노쇼도 반영한다(work-service는 Stream을 trim하지 않는다). envelope·payload 검증과 ACK·pending 규칙은 `WorkCompleted` 소비자와 같고, revision 4·상태 `NO_SHOW`·처리자 `SYSTEM`(처리 회원 ID 없음)을 요구한다.
- 노쇼 근무는 `member_no_show_works`(V7, 근무 ID 기본 키)에 한 번만 기록하고 같은 근무의 재전달은 다시 감점하지 않는다. 같은 근무 ID의 다른 공고·참여자는 기록하지 않고 pending으로 남긴다.
- 노쇼 횟수는 리뷰 수·평점 합과 같은 `member_review_stats` 행(`no_show_count`)에 더한다. 노쇼만 있는 회원은 리뷰 수 0인 행이 생긴다. 리뷰와 노쇼가 같은 행 잠금으로 직렬화되므로 revision과 이벤트 순서가 입력 갱신 순서와 같다. 노쇼 쪽 잠금 순서는 노쇼 근무 행 → 집계 행 → 신뢰 점수 행이다.
- `member_trust_scores`에 `no_show_count`를 저장하고 리뷰 수 0을 허용한다(리뷰나 노쇼가 1 이상). `TrustScoreUpdated` v1 payload에 `noShowCount`를 더했고 `reviewCount`는 0일 수 있다. matching-service는 V16과 parser에서 리뷰 수 0을 허용한다. 이전 matching-service는 리뷰 수 0 이벤트를 pending으로 남기므로(유실 없음) matching-service를 먼저 배포하는 것이 좋다.
- 탈퇴한 회원의 노쇼도 기존 리뷰 대상 근무 기록처럼 계정 상태와 무관하게 기록한다.
- V7은 기존 점수를 다시 계산하지 않는다. 기존 회원은 노쇼가 0회라 점수가 같으며 `policy_version`은 다음 리뷰·노쇼 때 새 버전으로 바뀐다.

### 저장과 발행

- 리뷰 저장 트랜잭션에서 평점 집계를 더한 뒤 집계 행을 잠금 읽기로 다시 읽어 점수를 계산하고 `member_trust_scores`(V6)에 저장한다. 같은 회원이 받는 리뷰는 집계 행 잠금으로 직렬화되므로 `revision`(회원별 1부터 증가)과 이벤트 순서가 집계 순서와 같다. 잠금 순서는 계정 장벽 → 근무 행 → 상대방 집계 행 → 신뢰 점수 행이다.
- 같은 트랜잭션에서 `member_outbox`에 `TrustScoreUpdated` v1을 저장한다. 같은 내용의 리뷰 재요청은 새 점수나 이벤트를 만들지 않는다.
- relay가 짧은 DB 임대로 이벤트를 점유해 `member:domain-events`(matching Redis)에 다른 서비스와 같은 envelope(`eventId`, `aggregateType=MEMBER`, `aggregateId`=회원 ID, `eventType`, `revision`, `version`, `occurredAt`, `payload`)로 발행한다. 실패는 상한이 있는 지수 backoff로 재시도하고 전달은 at-least-once다.
- payload: `eventId`, `eventType`, `memberId`, `revision`, `version`(1), `trustScore`(소수 둘째 자리), `reviewCount`(노쇼만 있는 회원은 0), `noShowCount`(`review-rating-no-show-v1`부터), `policyVersion`. 발생 시각은 envelope `occurredAt`만 쓴다.

### 기존 데이터

V5(상호 리뷰)가 이미 배포돼 있으므로 V6는 테이블을 만든 뒤 기존 `member_review_stats`(받은 리뷰 1개 이상)로 신뢰 점수를 한 번 채운다. 이렇게 하지 않으면 V6 이전에 리뷰를 받은 회원은 다음 리뷰 전까지 matching-service에서 리뷰 없는 지원자로 처리된다.

- 값은 `review-rating-v1`의 기본 설정(기본 평점 3.0, 보정 리뷰 수 3)으로 계산하고 `revision=1`로 저장한다. 배포 환경에서 설정을 바꿨더라도 이 값은 기본 설정 기준이며, 그 회원이 다음 리뷰를 받을 때 현재 설정으로 다시 계산된다.
- 같은 마이그레이션에서 회원별 고정 이벤트 ID(`trust-score-backfill-{memberId}`)로 `TrustScoreUpdated` v1을 Outbox에 넣고, 기존 relay가 발행해 matching-service 사본까지 전달한다.
- SQL 계산은 반올림 경계에서도 Java 정책과 같은 값이 되도록 충분한 소수 자릿수로 나눈다. 가능한 집계 조합 전체를 Java 정책과 비교하는 마이그레이션 테스트로 확인한다.

## 운영 설정

| 환경 변수 | 기본값 | 의미 |
| --- | --- | --- |
| `MEMBER_REVIEW_WRITE_PERIOD` | `14d` | 근무 완료 후 리뷰 작성 기간 |
| `MEMBER_REVIEW_EVENTS_ENABLED` | `true` | `WorkCompleted` 소비자 사용 |
| `MEMBER_REVIEW_EVENTS_POLL_INTERVAL` / `..._INITIAL_DELAY` / `..._BATCH_SIZE` | `1s` / `0s` / `100` | 주기·첫 실행·배치 크기 |
| `MATCHING_REDIS_HOST` / `MATCHING_REDIS_PORT` | `localhost` / `6381` | 근무 이벤트 Stream이 있는 Redis |
| `WORK_OUTBOX_STREAM_KEY` | `work:domain-events` | 근무 이벤트 Stream |
| `MEMBER_TRUST_SCORE_PRIOR_RATING` / `MEMBER_TRUST_SCORE_PRIOR_REVIEW_COUNT` | `3.0` / `3` | 신뢰 점수 보정 기준(임시 정책) |
| `MEMBER_TRUST_SCORE_NO_SHOW_PENALTY` | `10` | 노쇼 1회당 신뢰 점수 감점(임시 정책) |
| `MEMBER_NO_SHOW_EVENTS_ENABLED` | `true` | `WorkNoShow` 소비자 사용 |
| `MEMBER_NO_SHOW_EVENTS_POLL_INTERVAL` / `..._INITIAL_DELAY` / `..._BATCH_SIZE` | `1s` / `0s` / `100` | 주기·첫 실행·배치 크기 |
| `MEMBER_OUTBOX_ENABLED` / `MEMBER_OUTBOX_STREAM_KEY` | `true` / `member:domain-events` | 회원 이벤트 relay와 발행 Stream |

## 후속 작업

1. 확정 후 취소 이력의 감점, 노쇼 감점의 회복·시간 감쇠: 정책 확정 후 새 정책 버전으로 추가한다.
2. 리뷰 작성 요청 알림(notification-service)과 화면 연결.
3. 리뷰 수정·삭제와 작성 기간 등 정책 확정.

## 검증

Redis·MySQL Testcontainers로 확인했다.

- 근무 이벤트 소비: 완료 근무 기록과 다른 근무 이벤트 ACK, 같은 완료의 재전달, 참여자가 같은 이벤트·같은 근무 ID의 다른 내용·envelope 불일치의 pending 유지와 뒤 레코드 처리
- 리뷰: 점주·알바생 상호 작성과 평균, 같은 요청 재반환과 다른 내용 409, 참여자가 아닌 회원·없는 근무, 탈퇴 진행 중 회원, 작성 기간 경계(직전 허용, 같은 시각 거절), 기간 뒤 같은 요청, 작성할 리뷰 목록, 같은 리뷰 동시 요청(한 건·집계 1회), 같은 회원에게 쓰는 동시 리뷰(집계 누락 없음)
- HTTP: 인증, 평점·코멘트·페이지 검증, 오류 코드, Swagger 경로. JVM 시간대를 UTC로 둬도 통과한다.
- 노쇼 감점: 리뷰 없는 노쇼의 중립 점수 감점과 0점 하한, 리뷰 점수에서의 감점과 이후 리뷰의 감점 유지, 같은 근무 재전달(같은·새 eventId) 1회 감점, 처리자·revision 위반과 같은 근무 ID의 다른 참여자 pending 유지와 뒤 레코드 처리, 같은 근로자에게 동시에 들어온 리뷰·노쇼의 revision 연속성과 최종 점수, V6 → V7 업그레이드의 기존 점수 유지와 입력 CHECK, matching-service의 리뷰 수 0 사본 반영
- 신뢰 점수: V5 이전 스키마에 집계를 넣고 V6를 적용한 백필(점수·이벤트·리뷰 없는 집계 제외, SQL과 Java 계산 일치), 보정 공식 경계값과 잘못된 집계·설정 거절, 리뷰마다 점수·revision·Outbox 갱신, 같은 리뷰 재요청 무변경, 같은 회원에게 쓰는 동시 리뷰의 revision 연속성과 최종 점수, relay의 공통 envelope 발행
