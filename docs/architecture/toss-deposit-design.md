# 토스 테스트 예치와 공고 공개 계약

## 결정과 범위

2026-09-22에 토스페이먼츠 직접 연동, 테스트 환경, 원화 카드 결제, 예치 완료 후 공고 공개를 결정했다. 실제 출금·PG 에스크로·알바생 계좌 지급·정산·환불 요청은 이번 범위가 아니다. payment_deposits는 PG에서 검증한 결제를 서비스 내부의 예치 잔액으로 표현한다. payment_locks는 매칭별 잔액 배정이며 PG 에스크로가 아니다.

공고 서비스가 계산한 모집 인원 전체의 예정 급여를 예치한다. 금액은 100원 이상 정수 KRW이고, 클라이언트가 보낸 금액을 근거로 주문을 만들지 않는다. 근무 시간·시급·가산·휴게시간 계산은 공고 도메인의 책임이며 별도 공식을 결제 서비스에 복제하지 않는다.

공고는 PAYMENT_PENDING으로 비공개 저장하고, 검증된 예치가 반영된 이후에만 OPEN으로 공개한다. 결제 중단·실패 중에는 점주만 공고를 볼 수 있다. 2026-10-05 기준 job-service는 PAYMENT_PENDING 비공개 생성, 결제 주문 생성 명령 저장·재시도, 주문 ID 연결과 점주 조회, 공개 전 공고의 접근·검색·신규 지원·자리 예약 차단, 아래 2절 예치 상태 수신(funding-status)에 따른 OPEN 공개와 예치 차단을 구현했다. 2026-10-06에 결제 대기 공고의 결제 조건 변경·실패 주문 재결제(주문 교체)를 추가하고, 두 서비스를 별도 프로세스로 실행해 로컬 PG 대역 서버로 결제 후 공개까지 실제 HTTP로 확인했다([서비스 간 실제 프로세스 검증](#2026-10-06-서비스-간-실제-프로세스-검증)). 실제 토스 테스트 결제창을 거친 공개는 아직 확인하지 않았으므로 payment-service만 배포해서 이 정책이 시행됐다고 간주하면 안 된다. job-service 구현 세부는 [공고 설계](./job-post-design.md#결제-주문-생성), [결제 조건 변경과 재결제](./job-post-design.md#결제-조건-변경과-재결제), [예치 상태 수신](./job-post-design.md#예치-상태-수신)을 따른다.

## 공고 담당자 구현 계약

### 1. 결제 주문 생성

공고와 주문 생성 명령을 먼저 공고 DB에 기록하고, 외부 HTTP 호출은 DB 트랜잭션 밖에서 재시도한다. 동일 공고·버전의 주문 생성 재시도에는 같은 Idempotency-Key를 사용한다.

POST /api/payments/internal/orders

헤더: X-Internal-Secret, Idempotency-Key (1~128자 공백 없는 ASCII)

```json
{"jobPostId":10,"jobVersion":1,"ownerMemberId":20,"amount":100000,"currency":"KRW"}
```

jobVersion은 비공개 공고의 금액·모집 조건 스냅샷 버전이다. 응답은 공통 ApiResponse이며 data에는 orderId, jobPostId, jobVersion, amount, currency, status가 포함된다. 클라이언트가 공개 주문 API에 임의의 금액을 전달하는 경로는 없다.

점주의 공고 조회 응답에서 paymentOrderId를 제공한다(구현: `GET /api/jobs/{id}/payment-order`, 주문 생성 완료 전에는 `orderCreationStatus=PENDING`, `paymentOrderId=null`). 공고 공개 전에는 다른 회원의 조회·검색·지원 경로에 노출하지 않는다. 네트워크 오류로 주문 ID를 받지 못하면 동일 키로 재시도해 원래 ID를 복구한다.

금액 관련 정보를 수정하면 새 결제용 버전과 새 주문 생성 키가 필요하다. 결제 키가 아직 연결되지 않은 READY 주문과 확인된 FAILED 주문만 대체할 수 있다(payment-service의 READY는 결제 키 연결과 동시에 CONFIRMING이 되므로 READY에는 결제 키가 없다). CONFIRMING은 결과 확인 전까지 변경할 수 없고, DEPOSITED/REVIEW_REQUIRED는 재결제할 수 없다. 이전 주문은 SUPERSEDED로 남기고 승인을 차단한다. FAILED 주문의 같은 금액 재결제도 새 주문·새 키를 사용한다.

payment-service는 주문 생성 트랜잭션에서 명령 키 → 공고 → 활성 주문 행 순서로 잠그고 교체 가능 여부를 최종 판단한다. 승인 준비도 같은 주문 행을 잠그므로 교체와 승인은 직렬화된다. 승인 준비가 먼저 잡으면 교체는 CONFIRMING을 보고 거절되고, 교체가 먼저 잡으면 승인은 SUPERSEDED를 보고 거절되며 PG를 호출하지 않는다.

| 요청(활성 주문 대비) | READY | FAILED | CONFIRMING·DEPOSITED·REVIEW_REQUIRED |
| --- | --- | --- | --- |
| 더 높은 결제용 버전(조건 변경) | 교체 | 교체 | 409 |
| 같은 버전·같은 금액(재결제) | 409(기존 주문으로 다시 결제) | 교체 | 409 |
| 같은 버전·다른 금액, 낮은 버전, 다른 점주 | 409 | 409 | 409 |

409(`ORDER-409-001`)는 요청 전체를 롤백하므로 주문도 명령 키도 남지 않는다. job-service는 이미 주문이 연결된 공고의 교체 명령이 받은 409를 확정적 거절로 보고 그 명령을 종료하며, 공고의 현재 조건과 주문 연결을 유지한다. 다음 시도는 새 명령·새 키다. 같은 조건 재결제는 결제용 버전을 유지하고 조건 변경은 이전 명령보다 큰 버전을 쓴다. 타임아웃·5xx·잘못된 응답은 결과 불명으로 보고 같은 키로 재시도해 수렴한다.

### 2. 예치 상태 수신

payment-service는 예치 반영과 같은 트랜잭션에서 전송할 상태를 저장하고 아래 API를 호출한다.

POST /api/jobs/internal/{jobPostId}/funding-status

헤더: X-Internal-Secret, Idempotency-Key (`funding-{orderId}-{fundingRevision}`)

```json
{"orderId":"UUID","jobVersion":1,"ownerMemberId":20,"amount":100000,"currency":"KRW","fundingRevision":1,"funded":true}
```

- 반드시 공고에 저장한 최신 주문 ID·금액·통화·소유자·원래 결제용 jobVersion과 비교한다. 결제용 jobVersion을 현재 @Version과 비교하지 않고, 금액을 현재 공고 조건으로 다시 계산하지 않는다. 금액은 정수 KRW 값으로 비교하며 `100000.00`과 `100000`의 scale 차이는 불일치가 아니다.
- 명령 처리 기록을 먼저 확인해 재전송은 원래 결과로 응답한다. 공고 공개 후 @Version이 증가하거나 공고가 마감돼도 동일 명령은 처음 응답을 받는다. 같은 키를 다른 공고·다른 내용에 쓰면 409 `JOB-409-004`다. 다른 키의 같은 주문·revision은 같은 내용이면 처음 응답(효과 없음), 내용이 다르면 409 `JOB-409-012`다.
- orderId별 마지막 fundingRevision을 영구 저장한다. 낮거나 같은 revision은 200 `STALE_REVISION`으로 기록만 하고 상태를 되돌리지 않는다. 다른 주문의 이벤트는 공고에 연결된 최신 주문 여부도 검사해, 이 공고의 이전 주문이면 200 `STALE_ORDER`로 기록만 하고 최신 주문의 예치 상태를 바꾸지 않는다.
- 공고에 아직 주문이 연결되지 않았지만 최신 주문 생성 명령의 스냅샷과 같으면 연결 대기로 보고 409 `JOB-409-013`을 기록 없이 응답한다. payment-service의 같은 명령 재전송이 job-service의 주문 연결 뒤 정상 처리된다. 연결 대기 알림만으로 주문을 연결하지 않는다. 그 밖의 주문·스냅샷 불일치는 409 `JOB-409-011`이다.
- funded=true이고 아직 PAYMENT_PENDING이며 지원 마감·근무 시작 전인 공고만 OPEN으로 바꾸고 상태 이력(`SYSTEM:FUNDING_CONFIRMED`)을 남긴다. 공개 상태 변경과 수신·revision 기록을 같은 공고 DB 트랜잭션에서 처리한다. 이미 OPEN·MATCHING이면 이력 없이 200 `FUNDING_CONFIRMED`다.
- 이미 마감된 공고(CLOSED)나 지원 마감·근무 시작이 지난 결제 대기 공고에는 예치가 늦게 완료돼도 공개하지 않는다. 결제 수신 사실과 사유(`skipReason`)를 보존하고 환불 검토 대상(`refund_review_required`)으로 남긴다. 적용할 수 없는 업무 상태도 수신·기록했다면 200 success(`PUBLICATION_SKIPPED`)로 응답해 불필요한 재전송을 종료한다. 실제 환불은 실행하지 않는다.
- funded=false는 토스에서 확인한 취소·부분취소 또는 결제 검토 필요 상태다. 공고 상태는 바꾸지 않고 `funding_blocked`로 신규 지원 승인·신규 자리 예약·차단 뒤의 최초 자리 확정을 막는다(200 `FUNDING_BLOCKED`). 이미 확정된 자리와 진행 중인 근무·매칭은 별도 복구 정책으로 처리한다. 더 높은 funded=true revision은 차단을 해제하지만 마감 공고를 재개하지 않는다.
- 성공 응답은 ApiResponse success=true다. 타임아웃·5xx·실패 envelope와 모든 4xx는 같은 명령으로 재시도한다(현재 payment-service 구현). 인증 실패, 잘못된 요청, 주문·스냅샷 불일치, 멱등 키 충돌은 200으로 묶지 않으며 재전송으로 해소되지 않으면 운영 확인 대상이다.

예치 완료와 공고 공개는 서로 다른 서비스의 상태다. 프론트엔드는 DEPOSITED만 보고 ‘공고 공개 완료’로 표시하면 안 되며 공고 상태를 조회해야 한다. 새 revision이 전송 중 생겨도 이전 revision의 응답이 최신 알림을 완료 처리하지 못하도록 token/revision 조건으로 보호한다.

## 점주·프론트엔드 계약

1. 공고 등록 후 `GET /api/jobs/{id}/payment-order`를 점주 JWT로 조회해 `orderCreationStatus=CREATED`가 되면 paymentOrderId를 받는다. `PENDING`이면 주문 생성이 아직 완료되지 않은 것이다.
2. GET /api/payments/orders/{orderId}를 점주의 Bearer JWT로 호출한다. OWNER만 접근하고 다른 점주의 주문은 404다.
3. 토스 V2 주문서형 결제를 테스트 클라이언트 키로 초기화한다. 카드 결제만 노출하며 서버 응답의 orderId와 amount를 사용한다. orderName은 공고 급여 예치임을 명확히 표시한다.
4. 토스 인증 성공 후 POST /api/payments/orders/{orderId}/confirm에 동일 점주의 JWT와 아래 본문을 보낸다.

```json
{"paymentKey":"토스가 발급한 키","amount":100000}
```

5. amount는 저장 주문과 비교하고, 실제 승인 요청에는 서버 저장 금액을 사용한다. 성공 redirect 자체는 결제 완료가 아니다.
6. CONFIRMING은 승인 결과 확인 중이다. 같은 paymentKey로 재확인하고 새 결제를 시작하지 않는다. 응답 상태가 DEPOSITED인지 확인한 뒤 공고 공개 여부를 조회한다.
7. READY에서 결제창을 닫았으면 같은 주문으로 다시 시작할 수 있다. FAILED는 공고 서비스에 새 주문 생성을 요청한다. REVIEW_REQUIRED는 재결제하지 말고 확인이 필요한 상태로 표시한다.

시크릿 키는 브라우저·응답·Git에 넣지 않는다. 클라이언트 키는 같은 테스트 상점의 짝이 맞는 키를 프론트엔드 환경에 설정한다. 사용자 JWT와 내부 service secret은 서로 대체할 수 없다.

## 결제 상태, 복구와 웹훅

주문 상태는 READY → CONFIRMING → DEPOSITED/FAILED/REVIEW_REQUIRED다. READY/FAILED 주문 대체 시 SUPERSEDED가 된다. 주문의 paymentKey는 최초 승인 준비 때 고정하고 다른 키로의 재시도는 거절한다.

승인 요청은 Basic 인증과 `confirm-{orderId}` 멱등 키로 /v1/payments/confirm에 보낸다. DB 트랜잭션과 2분 lease를 먼저 커밋하고 네트워크 호출은 밖에서 수행한다. 연결 3초·응답 10초 타임아웃을 사용한다. 외부 결과 저장 때 token과 만료 시각을 확인해 오래된 작업자를 차단한다.

승인 실패 응답·타임아웃은 예치 실패로 단정하지 않는다. 결과가 불명확하면 CONFIRMING을 유지하고 같은 paymentKey로 조회한다. 조회 결과가 IN_PROGRESS면 같은 멱등 키로 승인을 재개한다. 확인된 ABORTED/EXPIRED 또는 예치 전 전액 CANCELED만 FAILED로 확정한다. 조회할 수 없는 잘못된 paymentKey도 임의 해제하지 않고 복구 대기 상태를 유지한다. 관리자의 원인 확인 전 새 결제 주문으로 우회하지 않는다.

잔액 반영은 토스에서 조회·승인한 paymentKey, orderId, totalAmount, currency를 저장 주문과 대조하고, 카드·DONE·approvedAt·전액 balanceAmount를 확인했을 때만 한다. 예치 반영·주문 상태·전이 이력·공고 알림은 한 DB 트랜잭션이다. approved_at은 UTC로 저장한다. 같은 주문의 반복 결과는 예치를 추가하지 않는다.

웹훅 URL: POST /api/payments/webhooks/toss
등록 이벤트: PAYMENT_STATUS_CHANGED

일반 결제 웹훅의 본문은 상태 변경의 힌트로만 사용한다. 이미 주문에 연결된 orderId/paymentKey와 일치하는 알림만 재조회 대기열에 반영하고 200으로 응답한다. 웹훅이 새로운 결제 키를 연결하거나 승인 요청을 최초로 시작할 수 없다. 조회는 서버의 시크릿 키로 수행한다. 중복·위조 알림 본문으로 잔액이 증가하지 않는다. 조회 도중 들어온 웹훅은 reconcile_revision 비교로 유실하지 않는다.

30초 간격 작업자가 조회 대기와 공고 알림을 각각 최대 20개 처리한다. 실패는 30~240초 backoff로 재시도하고, 만료된 lease는 다른 인스턴스가 복구한다. 공고 알림에는 최대 시도 횟수 제한이 없다. 예치 완료 이후 확인된 취소·부분취소는 REVIEW_REQUIRED로 기록하고 funding_blocked=true로 새 잠금을 차단한다. 이미 잠긴 잔액은 임의 차감하지 않으며 환불·정산 후속 기능이 처리해야 한다.

## 실행과 검증

기본 TOSS_ENABLED=false이며 키 없이 가짜 승인 성공을 반환하지 않는다. 토스 API 주소(`TOSS_API_BASE_URL`)는 기본값이 `https://api.tosspayments.com`이며, 로컬 PG 대역 서버로 서비스 간 연동을 확인할 때만 loopback HTTP 주소로 바꿀 수 있다. 시크릿 키를 Basic 인증으로 보내므로 그 밖의 주소는 시작 단계에서 거절한다. 실제 토스 테스트를 하려면 개인 .env에 TOSS_ENABLED=true와 TOSS_SECRET_KEY를 설정하고 ./scripts/local-run.sh payment로 실행한다. test_sk_ 또는 test_gsk_만 받으며 라이브 키는 시작 단계에서 거부한다. 공통 AUTH_JWT_SECRET도 필요하다.

자동 검증은 MySQL Testcontainers와 HTTP 계약 mock을 사용한다. 공고 공개까지 포함한 전체 서비스 E2E는 별도로 검증해야 한다.

### 2026-09-22 로컬 토스 테스트 결과

- 개인 테스트 키와 임시 로컬 결제 화면으로 1,000원 카드 결제를 수행했다. 토스 인증 → payment-service 승인 → DB 예치 반영까지 확인했다.
- 주문 조회 결과는 DEPOSITED, DB deposited_amount는 1000.00, locked_amount는 0.00, funding_blocked는 false였다. 상태 이력은 READY, CONFIRMING, DEPOSITED가 각각 1건이었다.
- 공고 알림은 funded=true, delivered=false로 남았다. 공고 수신 API가 연결되지 않았으므로 공고 공개 성공으로 간주하지 않는다.
- 별도 테스트 점주·공고 ID와 로컬 테스트용 JWT를 사용했다. 실제 로그인·공고 생성·제품 프런트 연동까지 검증한 것은 아니다.
- 임시 화면은 저장소 밖의 로컬 도구이며 제품 프런트가 아니다. 실제 출금·에스크로·근로자 지급을 검증한 것이 아니다.
- 실제 토스 웹훅 수신, 토스 장애 복구, 결제 취소 흐름은 이번 수동 결제에서 확인하지 않았다. 기존 자동 테스트 결과와 구분한다.

### 2026-10-06 서비스 간 실제 프로세스 검증

- job-service와 payment-service를 별도 JVM 프로세스로 실행했다. 각자의 일회용 MySQL 컨테이너를 쓰고, 실행마다 새로 만든 테스트 전용 JWT·내부 secret과 형식만 맞춘 가짜 토스 테스트 키를 썼다. 개인 `.env`와 실제 토스 키는 쓰지 않았고, 로그에 secret 값이 남지 않은 것을 확인했다.
- PG는 로컬 토스 대역 서버(loopback HTTP)였다. 결제 성공은 payment-service 승인 API → PG 대역 → 예치 반영 → 예치 상태 전송 경로로만 만들었으며 DB는 확인용으로만 읽었다.
- 서비스 간 실제 HTTP로 확인한 것: job-service 주문 생성 요청과 점주 조회의 `paymentOrderId` 연결, payment-service 승인(DEPOSITED)과 스케줄러의 funding-status 전송, job-service의 `OPEN` 전환과 이력 1건, 공개 전 타인 조회·지원 승인·자리 예약 차단과 공개 후 허용, READY 주문의 조건 변경(이전 주문 SUPERSEDED·승인 409, 새 주문 예치 후 새 조건으로 공개), FAILED 주문의 같은 조건 재결제, CONFIRMING 주문의 교체 거절과 기존 조건·주문 유지. 40개 확인 항목이 모두 통과했다.
- 확인하지 않은 것: 실제 토스 테스트 결제창(브라우저 인증과 paymentKey 발급)과 실제 토스 승인·조회 API, 실제 웹훅, 제품 프런트엔드·실제 로그인. 연결 전에 도착한 예치 알림, 응답 유실·결과 저장 실패, 잠금 순서 경쟁은 각 서비스의 MySQL 통합 테스트로만 검증했다.
- 실제 토스 테스트 환경까지 확인하려면 같은 상점의 테스트 시크릿·클라이언트 키, 결제창을 띄울 테스트 프런트(또는 임시 화면), 실제 로그인으로 받은 점주 JWT가 필요하다.

### 지금 진행 가능한 작업과 팀원 의존 작업

| 작업 | 진행 조건 |
| --- | --- |
| 프런트·공고 담당자에게 주문/승인/알림 계약 전달 | 지금 진행 가능. 이 문서의 요청·응답 계약 사용 |
| 실제 프런트 결제 화면 연결 및 실패·취소 안내 | 공고 API 완성 전에도 계약과 테스트 주문으로 준비 가능 |
| 공고 비공개 생성 및 결제 주문 생성 연결 | 2026-10-05 job-service 구현 완료: PAYMENT_PENDING 생성, 주문 생성 명령 저장·재시도, 주문 ID 연결, 점주 결제 주문 조회, 공개 전 접근 차단. 2026-10-06 두 서비스 실제 프로세스 간 HTTP로 주문 생성·`paymentOrderId` 연결과 공개 전 조회·지원 승인·자리 예약 차단을 로컬 PG 대역 환경에서 확인. 실제 토스 테스트 결제창·프런트 연결 검증은 남음 |
| 예치 완료 알림 수신 후 OPEN 전환 | 2026-10-05 job-service 구현 완료: funding-status API, 수신 기록 멱등 처리, 주문·스냅샷 대조, 주문별 revision 저장, 연결 대기 409 재시도, OPEN 공개와 이력, funded=false 신규 지원·자리 예약·최초 확정 차단, 공개 불가 예치의 환불 검토 표시. 2026-10-06 두 서비스 실제 프로세스 간 HTTP로 funded=true 전송 → OPEN 전환·이력을 로컬 PG 대역 예치로 확인. funded=false 차단·환불 검토 표시는 통합 테스트로만 검증. 실제 토스 테스트 결제·웹훅 검증은 남음 |
| 공개 불가·이전 주문 예치의 환불 검토와 환불 실행 | 검토 대상 기록만 구현(2026-10-06): job-service `job_payment_refund_reviews`에 주문당 1건, 첫 수신 기록 연결. 조회·조치 도구와 실제 환불은 미구현 |
| 예치 차단 전에 확정된 매칭·근무·결제 잠금의 사후 처리 | 미구현. 별도 취소·환불·정산 복구 정책 필요 |
| 공고 조건 수정 시 새 버전·새 주문 생성 키, 주문 교체·재결제 | 2026-10-06 구현: 결제 대기 공고의 결제 조건 변경·재결제 API, 요청 멱등성, 대기 조건 스냅샷, 교체 거절·결과 불명 처리. 2026-10-06 두 서비스 실제 프로세스 간 HTTP로 READY 조건 변경, FAILED 재결제, CONFIRMING 교체 거절을 로컬 PG 대역 환경에서 확인. 응답 유실·저장 실패·잠금 경쟁은 통합 테스트로만 검증. 실제 토스 테스트 결제창·프런트 연결 검증은 남음. 공개 이후 수정·재모집은 미정 |
| 기존(주문 없는) 공개 공고 처리 | 일괄 비공개 전환·소급 주문 생성 안 함. 예치가 없어 매칭 확정의 결제 잠금 단계에서 거절됨. 재결제 경로는 결제 대기 공고만 받으므로 재등록 필요 |
| 결제 전 비노출 → 결제 후 공개 전체 검증 | 2026-10-06 두 서비스 실제 프로세스 간 HTTP로 비공개 생성부터 예치·OPEN 공개, 공개 후 지원 승인·자리 예약 허용까지 로컬 PG 대역 환경에서 확인. 실제 토스 테스트 결제창·프런트 연결 검증은 남음 |

결제 서비스의 기본 설정과 테스트 예치 확인, job-service의 비공개 생성·주문 생성 연동과 funding-status 수신·공개·주문 교체는 각 서비스의 자동 테스트로 확인했다(job-service는 payment-service 주문 생성 대역과 수신 API 호출로, payment-service는 실제 주문 상태로 교체 허용 상태와 승인 경쟁을 검증). 두 서비스 실제 프로세스 간 결제 후 공개는 로컬 PG 대역으로 확인했다. 실제 토스 테스트 환경을 거친 공개 확인은 남아 있다.

## 공식 문서

- [토스 결제 API](https://docs.tosspayments.com/reference)
- [인증과 멱등 요청 헤더](https://docs.tosspayments.com/reference/using-api/authorization)
- [웹훅 이벤트](https://docs.tosspayments.com/reference/using-api/webhook-events)
- [테스트 환경](https://docs.tosspayments.com/guides/v2/get-started/environment)
