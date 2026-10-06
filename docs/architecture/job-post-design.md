# 공고 등록·조회와 지원 접수 승인

## 범위

`job-service`는 공고와 업종 카테고리의 원본을 소유한다. 현재는 점주의 공고 비공개(`PAYMENT_PENDING`) 등록과 payment-service 결제 주문 생성 연동, 점주의 결제 주문 조회, 공고 상세·목록 조회, 점주 본인 공고 목록, `matching-service`가 지원 저장 전에 호출하는 지원 접수 승인 내부 API, 매칭 확정 Saga가 호출하는 모집 자리 예약·확정·반환 내부 API와 만료 예약 회수, 1인 예정 급여 계산, 확정 인원 기준의 모집 완료 전이와 matching-service 모집 완료 알림, payment-service 예치 상태(`funding-status`) 수신에 따른 `PAYMENT_PENDING → OPEN` 공개와 예치 차단, 결제 대기 공고의 결제 조건 변경과 실패 주문 재결제(주문 교체), 환불 검토 대상 기록을 제공한다. 공개 이후의 공고 수정·재모집, 수동 마감, 지원 기한 만료에 따른 자동 마감, 재오픈, 실제 환불·정산, 확정된 매칭·근무의 자동 취소는 후속 작업이다.

## 공개 API

응답은 공통 `ApiResponse`를 따른다. 인증이 필요한 요청은 auth-service가 발급한 Bearer JWT를 사용한다.

| 명령 | 경로 | 인증 | 요청/응답 |
| --- | --- | --- | --- |
| 공고 등록 | `POST /api/jobs` | `OWNER` 역할 | 사업장·카테고리·근무 일시·급여·모집 인원·위경도·긴급도·지원 마감 / `data` 공고 ID |
| 상세 조회 | `GET /api/jobs/{id}` | 없음(결제 대기 공고는 점주 본인 토큰 필요) | 가게·카테고리 이름·주소·급여·근무 일시와 시간·설명·모집 인원 |
| 결제 주문 조회 | `GET /api/jobs/{id}/payment-order` | `OWNER` 역할, 점주 본인 | 공고 상태·주문 생성 상태·`paymentOrderId`·결제용 버전·금액·통화·최근 결제 변경 요청(`latestChange`) ([결제 주문 생성](#결제-주문-생성)) |
| 결제 조건 변경 | `PUT /api/jobs/{id}/payment-terms` | `OWNER` 역할, 점주 본인, `Idempotency-Key` | 변경 후 전체 결제 조건 / 변경 요청 처리 상태 ([결제 조건 변경과 재결제](#결제-조건-변경과-재결제)) |
| 재결제 | `POST /api/jobs/{id}/payment-order/retries` | `OWNER` 역할, 점주 본인, `Idempotency-Key` | 본문 없음 / 변경 요청 처리 상태 |
| 목록 조회 | `GET /api/jobs/search` | 없음 | 위치·거리·급여·시작 시각·카테고리·유형·페이지 조건 / 공고 카드 목록과 페이지 정보 |
| 점주 본인 공고 목록 | `GET /api/jobs/me` | `OWNER` 역할, 점주 본인(JWT `memberId`) | 상태·페이지 조건 / 점주 공고 카드 목록(확정 인원 포함)과 페이지 정보 ([점주 본인 공고 목록](#점주-본인-공고-목록)) |

공고 등록 규칙:

- 점주 회원 ID는 요청 본문이 아니라 JWT의 `memberId`를 사용한다.
- 근무 시간은 분 단위로 1분 이상 24시간 이하여야 한다. 자정을 넘으면 `isEndTimeNextDay=true`로 보내며 이때 종료 시각은 시작 시각 이하다. 초 단위가 있는 시각, 익일 플래그 없이 종료가 시작 이전인 구간, 익일 플래그로 24시간을 넘는 구간은 `JOB-400-001`이다.
- 1인 예정 급여와 전체 예치 예정액(아래 [예정 급여 계산](#예정-급여-계산))을 계산할 수 없거나 허용 범위를 넘으면 `JOB-400-004`다. 전체 예치 예정액이 100원 미만이면 올려 맞추지 않고 같은 코드로 거절한다.
- 지원 마감은 현재 이후이고 근무 시작 이전이어야 한다.
- 시급은 10,320원 이상, 모집 인원은 1명 이상이다. 카테고리는 저장된 ID여야 한다.
- 사업장 소유권 검증은 `BusinessValidator` 포트 뒤에 있다. 현재 구현체 `StubBusinessValidator`는 경고 로그만 남기고 통과시키므로 실제 소유권 검증으로 간주하지 않는다.
- 등록하면 `PAYMENT_PENDING`(비공개)으로 저장하고, 같은 트랜잭션에서 전체 예치 예정액의 결제 주문 생성 명령을 저장한다. 응답은 기존과 같이 공고 ID이며 주문 생성 결과를 기다리지 않는다. 점주는 [결제 주문 조회](#점주-결제-주문-조회)로 주문 ID를 확인한다.
- 공고는 payment-service가 검증한 예치 확인(`funded=true`)을 [예치 상태 수신](#예치-상태-수신)으로 받은 뒤에만 `OPEN`으로 공개된다. 임시 공개나 가짜 예치 성공 경로는 두지 않는다.

목록 조회 규칙:

- `OPEN`이고 지원 마감 전이며 예치 차단(`funding_blocked`)이 아닌 공고만 반환한다. `PAYMENT_PENDING` 공고는 점주 본인 요청이어도 검색에 포함하지 않는다. 예치 차단 공고는 신규 지원을 받지 않으므로 제외한다([예치 차단 공고의 모집과 노출](#예치-차단-공고의-모집과-노출)).
- `type=URGENT`이면 긴급도 `HIGH` 공고만 마감 임박 순으로 정렬한다. 그 외에는 거리 오름차순이며 거리를 계산할 수 없는 공고는 뒤에 둔다.
- `maxDistanceKm`을 사용하려면 `workerLat`, `workerLng`가 필요하다. `minWage`는 `maxWage`보다 클 수 없다.
- 기본 페이지는 0, 크기는 20, 최대 크기는 100이다.
- 후보 공고를 모두 조회한 뒤 애플리케이션 메모리에서 필터·정렬·페이지 처리한다. 공고 수가 늘어나면 DB 조건 조회와 공간 인덱스로 바꿔야 한다.

상세 조회의 `applicantCount`는 원본이 `matching-service`에 있고 조회 계약이 없으므로 현재 `null`이다.

상세 조회 접근 규칙:

- `PAYMENT_PENDING` 공고는 Bearer 토큰의 `memberId`가 공고의 점주 ID와 같을 때만 반환한다. 토큰이 없거나 유효하지 않은 요청, 다른 회원의 요청은 존재하지 않는 공고와 같은 404 `JOB-404-001`이다. 역할이 아니라 인증된 회원 ID로 본인을 확인한다.
- `OPEN`·`MATCHING`·`CLOSED` 공고는 기존과 같이 인증 없이 반환한다. 예치 차단 여부는 상세 노출에 영향을 주지 않는다. 기존 지원자와 매칭 참여자가 공고를 계속 확인해야 하기 때문이다.
- `GET /api/jobs/me`는 고정 경로라 `/{id}` 상세 조회로 연결되지 않고, 숫자 ID 상세 조회의 접근 규칙도 바꾸지 않는다.
- 이 제한은 사용자 상세 조회(`JobFindService.findJobDetail`)에만 적용한다. 내부 처리(지원 접수 승인, 자리 예약·확정·반환, 모집 완료, 예치 상태 수신)는 공고 저장소를 직접 잠가 조회하고 각자 상태를 검사하며, `JobFindService.findJobPost`는 상태와 관계없이 조회한다.

### 점주 본인 공고 목록

`GET /api/jobs/me`(`OWNER` Bearer JWT). 점주가 등록한 공고를 관리 화면용 카드로 돌려준다.

- 권한: 점주 ID는 토큰의 `memberId`로만 정하고 요청 값으로 받지 않는다. 다른 점주의 공고는 결과에 없다. 토큰이 없거나 유효하지 않으면 401 `GLOBAL-401-001`, `OWNER`가 아니면 403 `GLOBAL-403-001`, 토큰 처리 엔진 오류는 500 `GLOBAL-500-001`로 다른 인증 API와 같은 경계를 따른다. `GET /api/jobs/**` 공개 허용보다 먼저 검사한다.
- 포함 범위: `PAYMENT_PENDING`·`OPEN`·`MATCHING`·`CLOSED` 전부와 예치 차단 공고를 포함한다. 지원 마감·근무 시작이 지난 공고도 빼지 않는다. 선택 조건 `status`로 한 상태만 거를 수 있고, 정의되지 않은 값은 400 `GLOBAL-400-002`다.
- 정렬과 페이지: 등록 시각(`created_at`) 내림차순, 같으면 ID 내림차순. 페이지 기본 0, 크기 기본 20·최대 100(공고 검색과 같은 검증, 위반 시 400). 범위 밖 페이지는 빈 `jobs`와 전체 건수를 돌려준다.
- 조회 방식: 점주·상태 조건과 정렬·페이지를 DB 쿼리로 처리한다(`idx_job_posts_owner_created`). 공고 검색처럼 전체를 읽고 메모리에서 거르지 않는다.
- 응답: `page`, `size`, `totalCount`, `totalPages`, `jobs`. 카드는 `id`, `storeName`, `title`, `status`, `isFundingBlocked`, `workDate`, `startTime`, `endTime`, `isEndTimeNextDay`, `applicationDeadline`, `recruitCount`, `confirmedCount`, `applicantCount`다.
- `confirmedCount`는 확정(`CONSUMED`)된 모집 자리 예약 수다. `RESERVED`·`RELEASED`·`EXPIRED`는 세지 않는다. 페이지에 담긴 공고 ID로 묶어 한 번의 집계 쿼리로 센다(공고별 쿼리 없음).
- `applicantCount`는 상세 조회와 같이 지원자 수 조회 계약이 없어 `null`이다.
- 결제 진행 상태는 넣지 않는다. 점주는 공고별 [점주 결제 주문 조회](#점주-결제-주문-조회)를 사용한다.

## 내부 API

`/api/jobs/internal/**`는 `X-Internal-Secret`이 일치해야 하며, 불일치하면 401 `GLOBAL-401-001`이다. 보안 설정의 `permitAll`은 이 필터가 먼저 검증한 뒤 적용된다.

같은 `job.internal.secret`(`INTERNAL_API_SECRET`)을 matching-service로 보내는 요청 헤더에도 쓴다. 비어 있거나 공백·제어 문자·비ASCII 문자가 있는 값, 즉 출력 가능한 ASCII(0x21~0x7E)가 아닌 문자가 들어간 값은 시작 시 거절한다. 줄바꿈 같은 값은 HTTP 헤더로 보낼 수 없고, 앞뒤 공백이나 비ASCII 문자는 받는 쪽 비교와 어긋날 수 있다. 값을 잘라 내거나 보정하지 않으며, 시작 실패 보고와 오류 메시지에 값을 포함하지 않는다.

| 명령 | 경로 | 요청/응답 |
| --- | --- | --- |
| 지원 접수 승인 | `POST /api/jobs/internal/{jobPostId}/application-admissions` | `workerMemberId` / `admissionId`, `jobPostId`, `jobVersion`, `ownerMemberId`, `categoryId`, `workDate`, `startTime`, `endTime`, `latitude`, `longitude`, `admittedAt`, `expiresAt` |
| 모집 자리 예약 | `POST /api/jobs/internal/{jobPostId}/matching-seat-reservations` | `matchingId`, `applicationId`, `workerMemberId` / `reservationId`(문자열), `jobPostId`, `jobVersion`, `ownerMemberId`, `workDate`, `startTime`, `endTime`, `endTimeNextDay`, `lockedAmount`, `currency`, `reservedAt`, `expiresAt` |
| 모집 자리 확정 | `POST /api/jobs/internal/{jobPostId}/matching-seat-reservations/{reservationId}/confirm` | 본문 없음 / `reservationId`, `status` |
| 모집 자리 반환 | `POST /api/jobs/internal/{jobPostId}/matching-seat-reservations/{reservationId}/release` | 본문 없음 / `reservationId`, `status` |
| 예치 상태 수신 | `POST /api/jobs/internal/{jobPostId}/funding-status` | `orderId`, `jobVersion`, `ownerMemberId`, `amount`, `currency`, `fundingRevision`, `funded` / `jobPostId`, `orderId`, `fundingRevision`, `result`, `skipReason`, `jobStatus`, `fundingBlocked` ([예치 상태 수신](#예치-상태-수신)) |

`Idempotency-Key`는 1~100자의 공백 없는 ASCII다. 공고 행을 비관적 잠금으로 잡은 뒤 같은 키의 승인을 조회한다. 따라서 같은 공고에 같은 키로 온 동시 요청은 앞선 요청의 결과를 본다. 공고가 서로 다르면 잠그는 행도 달라 조회가 직렬화되지 않으므로, `uk_job_application_admissions_idempotency_key` 유일 제약이 마지막 방어선이다.

- 같은 키와 같은 공고·회원 요청이면 기존 승인을 반환한다. 만료됐거나 `RESERVED`가 아니면 `JOB-409-003`이다.
- 같은 키를 다른 공고나 회원 요청에 재사용하면 `JOB-409-004`다. 동시 요청이 유일 제약에서 걸린 경우에도 같은 코드로 응답한다.
- 제약 위반 변환은 저장 트랜잭션이 롤백된 뒤 전역 HTTP 예외 처리 경계인 `GlobalExceptionHandler`에서 한다. 서비스는 저장 예외를 변환하지 않고 전파한다. cause 사슬 전체에서 Hibernate `ConstraintViolationException`을 찾아 제약 이름이 정확히 일치할 때만 `JOB-409-004` 응답으로 변환한다. 중간에 다른 제약 위반을 만나도 탐색을 계속하고, 순환 참조에서는 종료한다. 대상 제약이 없는 무결성 위반은 서버 오류로 남긴다. MySQL이 반환하는 `테이블명.제약명`에서는 테이블명 접두사를 제거하고 비교한다.
- 새 승인은 공고가 `OPEN`이고 예치 차단이 아니며 지원 마감 전일 때만 발급한다. `PAYMENT_PENDING` 공고와 예치 차단 공고의 새 승인은 `JOB-409-001`이다. 같은 키의 기존 승인 재요청은 상태 검사보다 먼저 처리하므로 기존 계약을 유지한다. 승인 생성 시각이 지원 접수 시점이다.
- 승인은 모집 자리를 차감하지 않는다. 모집 인원 동시성은 매칭 확정 단계의 자리 예약에서 다룬다.
- 만료 시간은 `APPLICATION_ADMISSION_TTL`이며 기본 5분이다.
- 응답의 점주 회원 ID, 업종 ID, 근무 일시, 위경도는 발급 당시 잠근 공고에서 승인 레코드에 함께 저장한 스냅샷이다. 응답을 만들 때 공고를 다시 읽지 않으므로 같은 멱등 키의 재요청은 공고가 바뀌어도 같은 응답을 받는다.

계약의 세부 배경은 [지원 도메인 설계](./matching-application-design.md)의 공고 담당 범위 계약을 따른다.

## 모집 자리 예약

매칭 확정 Saga의 첫 단계에서 남은 모집 인원 한 자리를 임시로 확보하고, 마지막 단계에서 확정 인원으로 반영한다. 후속 단계가 명확히 실패하면 Saga 보상으로 반환된다. 상태는 `RESERVED`, `CONSUMED`, `RELEASED`, `EXPIRED`다. 모든 명령은 `X-Internal-Secret`과 명령별 `Idempotency-Key`(1~100자의 공백 없는 ASCII)를 사용한다.

### 잠금과 불변식

예약·확정·반환·만료 회수는 모두 공고 행 → 예약 행 순서로 비관적 잠금을 잡는다. 같은 공고의 명령은 공고 행 잠금으로 직렬화되어 `CONSUMED 수 + 유효한 RESERVED 수 <= recruitCount`가 항상 유지된다. 현재 시각은 잠금을 얻은 뒤 `Clock` 빈에서 읽어 `expiresAt`과 비교하며, `expiresAt <= now`인 `RESERVED`는 유효하지 않다.

만료 예약 회수는 범위 조건 `UPDATE`를 쓰지 않는다. 보조 인덱스 범위 갱신은 gap 잠금을 잡아 다른 공고의 예약 `INSERT`와 교착될 수 있으므로, 공고 행 잠금 아래에서 대상 ID를 비잠금 조회한 뒤 기본 키로만 `EXPIRED`로 바꾼다.

### 예약

- 같은 키의 기존 예약이 있으면 `jobPostId + matchingId + applicationId + workerMemberId`가 모두 같을 때 저장된 스냅샷으로 원래 응답을 돌려준다. 공고를 다시 읽지 않고, `RELEASED`·`EXPIRED`여도 되살리거나 새로 발급하지 않는다. 하나라도 다르면 `JOB-409-004`다. 동시 최초 요청은 같은 공고 잠금 아래에서 키를 다시 조회하므로 하나만 생성된다. 서로 다른 공고에 같은 키를 쓴 동시 요청은 유일 제약에서 거절되며 `JOB-409-004`로 응답한다.
- 공고 상태는 `OPEN` 또는 `MATCHING`이어야 한다([MVP 도메인 흐름](./mvp-domain-flow.md)의 `MATCHING`은 지원 접수를 멈추고 매칭을 진행하는 상태다). 그 외 상태(`PAYMENT_PENDING`, `CLOSED`)와 예치 차단 공고는 `JOB-409-005`다. 같은 키의 재요청은 상태 검사보다 먼저 저장된 스냅샷을 돌려준다.
- 자리 예약 기한은 지원 마감이 아니라 근무 시작 시각(`workDate + startTime`)이다. 지원 마감 이후에도 기존 지원자의 매칭 확정은 가능하며, 근무 시작 이후에는 `JOB-409-006`이다.
- 같은 잠금 안에서 이 공고의 만료된 `RESERVED`를 먼저 `EXPIRED`로 회수한다. 스케줄러가 늦어도 만료 자리가 새 예약을 막지 않는다.
- 같은 공고에서 같은 `matchingId` 또는 `applicationId`가 `RESERVED`·`CONSUMED`로 자리를 점유하고 있으면 다른 키라도 `JOB-409-008`이다. 종료된(`RELEASED`·`EXPIRED`) 시도 이후에는 새 키로 다시 예약할 수 있다. `matchingId`에 영구 유일 제약을 두지 않는다.
- 점유 수가 `recruitCount` 이상이면 `JOB-409-007`이다.
- 응답 스냅샷은 발급 당시 공고의 버전, 점주 ID, 근무 일시, `endTimeNextDay`, 공고 좌표 `latitude`/`longitude`, 1인 예정 급여(`lockedAmount`, 정수 KRW), 통화 `KRW`다. 스냅샷과 요청 식별 필드는 `updatable = false`다.
- 좌표는 공고 행 잠금 안에서 예약 행의 `DECIMAL(10,7)` 스냅샷에 저장하고 응답도 예약 행에서만 읽는다. 요청에는 기준 좌표를 받지 않는다. V15 이전 예약은 두 좌표가 null로 유지되며 현재 공고 좌표로 소급 보정하지 않는다. 같은 예약 키는 공고 변경·예약 만료·반환 후에도 최초 좌표를 반환한다.
- `expiresAt = reservedAt + MATCHING_SEAT_RESERVATION_TTL`(기본 10분, 지원 접수 승인 TTL과 별도 설정)이며 재요청으로 연장하지 않는다. 시각 정밀도는 아래 [시각 저장 정밀도](#시각-저장-정밀도)를 따른다.

### 확정

- 예약이 경로의 공고에 속하지 않거나 없으면 `JOB-404-003`이다.
- 같은 확정 키가 다른 예약의 확정에 이미 쓰였으면 `JOB-409-004`다.
- 유효한 `RESERVED`는 `CONSUMED`로 바꾸고 확정 키와 시각을 기록한다. 공고가 예치 차단이면 최초 확정을 `JOB-409-005`로 거절하고 예약은 `RESERVED`로 남는다([예치 차단 공고의 모집과 노출](#예치-차단-공고의-모집과-노출)).
- 이미 `CONSUMED`인 예약에 처음 확정한 키로 다시 요청하면 성공한다. 확정 응답이 유실된 경우의 복구이므로 원래 `expiresAt`이 지났어도 거절하지 않는다. 다른 키는 저장된 키를 덮어쓰지 않고 `JOB-409-010`이다.
- 기한이 지난 `RESERVED`와 `EXPIRED`는 `JOB-409-009`, `RELEASED`는 `JOB-409-010`이다.
- 확정으로 `CONSUMED` 수가 `recruitCount`에 도달하면 같은 트랜잭션에서 공고를 모집 완료로 마감한다([모집 완료](#모집-완료)). 공고가 이미 `CLOSED`여도 원래 키의 확정 재요청은 성공하며 예약 스냅샷과 `jobVersion`은 바뀌지 않는다.

### 반환

- 예약 존재와 키 재사용 검증은 확정과 같다.
- `RESERVED`는 `RELEASED`로 바꾼다. 이미 기한이 지났다면 `EXPIRED`로 회수한다.
- `EXPIRED`는 자리가 이미 회수됐으므로 성공한다. 처음 들어온 반환 키만 기록한다.
- 이미 `RELEASED`·반환 키가 기록된 `EXPIRED`는 처음 반환한 키일 때만 성공하고, 다른 키는 `JOB-409-010`이다.
- `CONSUMED`는 반환하지 않고 `JOB-409-010`이다.
- 반환은 경로의 예약 ID만 바꾸므로 오래된 시도의 늦은 반환이 같은 매칭의 새 예약에 영향을 주지 않는다.

### 만료 회수

`MATCHING_SEAT_RESERVATION_EXPIRY_SWEEP_INTERVAL`(기본 1분)마다 만료된 `RESERVED`가 있는 공고를 최대 `MATCHING_SEAT_RESERVATION_EXPIRY_SWEEP_BATCH_SIZE`(기본 100)개 고른다. 공고마다 별도 트랜잭션에서 공고 행을 잠그고 회수하므로 한 트랜잭션이 여러 공고를 오래 잠그지 않는다. 상태 조건이 있어 반복 실행이나 여러 인스턴스 실행에서도 중복 회수하지 않는다. 한 공고의 실패는 로그만 남기고 다음 공고를 계속 처리한다. `MATCHING_SEAT_RESERVATION_EXPIRY_SWEEP_ENABLED=false`로 끌 수 있으며, 꺼져 있어도 새 예약 요청이 해당 공고의 만료 예약을 회수한다.

### 시각 저장 정밀도

예약 시각 컬럼은 `DATETIME(6)`이고 MySQL은 더 정밀한 값을 반올림해 저장한다. `Clock`은 나노초를 줄 수 있으므로, 최초 응답의 메모리 값과 재요청이 DB에서 읽은 값이 달라지지 않도록 저장 전에 직접 정규화한다.

- `reservedAt` = 발급 시각을 마이크로초로 절삭한 값.
- `expiresAt` = `reservedAt + TTL`을 마이크로초로 절삭한 값. TTL의 마이크로초 미만은 결과적으로 버려진다.
- 같은 엔티티 값을 저장과 최초 응답에 함께 쓰며, 재요청은 저장된 값을 그대로 돌려준다. 현재 시각으로 다시 계산하지 않는다.
- `consumedAt`, `releasedAt`, `expiredAt`도 같은 방식으로 절삭해 저장한다.
- 절삭 후 1마이크로초 이상 남지 않는 TTL(예: 999ns)은 기동 시 설정 오류로 거절한다.
- 만료 판정은 `expiresAt <= 현재 시각`이며, Java 비교에는 절삭하지 않은 현재 시각을 쓴다. 저장된 `expiresAt`은 항상 마이크로초 단위이므로 이 판정은 절삭한 현재 시각과의 비교와 같다. 만료 회수 쿼리에는 절삭한 현재 시각을 인자로 넘겨, MySQL이 나노초 인자를 반올림해 만료 경계가 앞당겨지지 않게 한다. 결과적으로 `expiresAt` 직전(1ns 전)에는 확정할 수 있고, `expiresAt`과 그 이후에는 확정할 수 없다.
- 이미 저장된 예약의 시각과 TTL은 일괄 수정하지 않는다. MySQL이 반올림해 저장했으므로 기존 값도 마이크로초 단위다.

### 멱등 키 제약 변환

예약·확정·반환 키는 각각 `uk_job_matching_seat_reservations_idempotency_key`, `uk_job_matching_seat_reservations_confirm_key`, `uk_job_matching_seat_reservations_release_key` 유일 제약을 갖는다. 지원 접수 승인과 같은 방식으로 `GlobalExceptionHandler`가 롤백 이후 이 제약 이름과 정확히 일치할 때만 `JOB-409-004`로 변환한다.

## 모집 완료

확정 인원이 모집 인원에 도달하면 job-service가 공고를 마감하고 matching-service에 남은 지원·제안의 종료를 요청한다. job-service의 모집 완료(공고 `CLOSED`)와 matching-service의 후속 정리 완료(알림 명령 `SUCCEEDED`)는 별개의 상태다. 알림 실패는 이미 커밋된 자리 확정과 공고 마감을 되돌리지 않는다.

### 판단 기준과 트랜잭션

- 모집 완료 기준은 `CONSUMED 수 >= recruitCount`다. 예약 불변식상 `CONSUMED`는 `recruitCount`를 넘지 않으므로 실제로는 같을 때 완료된다. `CONSUMED + 유효한 RESERVED == recruitCount`는 추가 예약을 막는 조건일 뿐 완료 조건이 아니다.
- `JobRecruitmentCompletionService`가 자리 확정과 같은 로컬 트랜잭션(`Propagation.MANDATORY`)에서 `RESERVED → CONSUMED`, 공고 `OPEN`/`MATCHING → CLOSED`, `job_status_histories` 저장, 알림 명령 저장을 함께 처리한다. 하나라도 실패하면 자리 확정까지 모두 롤백된다.
- 잠금 순서는 기존과 같이 공고 행 → 예약 행이다. 같은 공고의 동시 확정은 공고 행 잠금으로 직렬화되고, 이미 `CLOSED`인 공고에는 이력·명령을 다시 만들지 않는다. `uk_job_recruitment_completion_commands_job_version (job_post_id, job_version)`이 같은 전이의 중복 명령을 DB에서도 막는다.
- 상태 이력은 기존 `job_status_histories` 컬럼을 사용한다. `from_status`(이전 상태), `to_status`(`CLOSED`), `reason`(`주체:사유` 형식의 `SYSTEM:RECRUITMENT_FILLED`), `created_at`(전이 시각)을 기록한다. 확정 재요청과 알림 재시도는 이력을 추가하지 않는다.

### 완료 버전

- 알림의 `X-Job-Version`은 모집 완료 전이가 반영된 공고 버전이다. 예약 발급 당시 버전(`job_matching_seat_reservations.job_version`)과 다르다.
- JPA `@Version`은 flush 때 증가하므로, 상태를 바꾼 뒤 같은 트랜잭션에서 flush해 증가한 버전을 얻고 그 값으로 명령을 저장한다. 버전 확인을 위해 공고를 따로 커밋하지 않는다.
- 명령의 `command_id`, `job_post_id`, `job_version`은 `updatable = false`이며 재시도할 때 공고를 다시 읽지 않는다. matching-service는 이 버전 이하의 늦은 지원을 차단하고 더 높은 버전(재오픈)의 지원은 허용하므로, 과거 알림이 재오픈된 공고를 종료시키지 않는다.

### 알림 명령과 전송

`POST {MATCHING_SERVICE_BASE_URL}/api/applications/internal/jobs/{jobPostId}/recruitment-completion`, 본문 없음. 헤더는 `X-Internal-Secret`(`INTERNAL_API_SECRET`), `X-Job-Version`(완료 버전), `Idempotency-Key`(DB에 먼저 저장한 UUID 명령 ID, 36자)다. 성공은 2xx이면서 공통 응답의 `success=true`인 경우뿐이다.

명령 생명주기는 `PENDING → SUCCEEDED`다. 한 번의 시도는 다음 세 단계로 나뉘며 HTTP 대기 중에는 DB 트랜잭션이나 공고 행 잠금을 잡지 않는다.

1. 짧은 트랜잭션에서 실행권 획득: `PENDING`이고 `next_attempt_at <= now`이며 실행권이 없거나 만료된 행만 `lease_token`, `lease_expires_at`을 설정하고 `attempt_count`를 1 올린다. 기본 키 조건의 단일 `UPDATE`라 여러 인스턴스가 동시에 실행해도 한 실행자만 얻는다.
2. 트랜잭션 밖에서 HTTP 호출. 연결 시작부터 응답 헤더와 본문 수신 완료까지 `MATCHING_SERVICE_CALL_TIMEOUT`(기본 8초)을 넘지 않는다.
3. 별도의 짧은 트랜잭션에서 `lease_token`이 일치할 때만 성공 또는 실패를 기록하고 실행권을 비운다. 실행권이 만료되어 다른 실행자가 넘겨받았다면 이전 실행자의 늦은 결과는 0행 갱신으로 무시된다.

#### 호출 제한시간

이 호출은 JDK `java.net.http.HttpClient`(HTTP/1.1)의 비동기 교환 하나로 보낸다. 교환은 연결, 요청 전송, 응답 헤더, 본문 수신(최대 8KB)을 모두 포함하며, `MATCHING_SERVICE_CALL_TIMEOUT`이 지나면 교환을 취소한다. JDK 17에서 취소는 HTTP/1.1 연결을 닫으므로 상대가 본문을 조금씩 계속 보내도 호출이 연장되지 않고, 백그라운드에 요청이나 본문 읽기가 남지 않는다. 비동기 작업은 전용 고정 스레드 2개에서 실행하고 종료 시 정리한다.

| 설정 | 기본값 | 제한하는 구간 |
| --- | --- | --- |
| `MATCHING_SERVICE_CONNECT_TIMEOUT` | `3s` | TCP 연결 수립 |
| `MATCHING_SERVICE_READ_TIMEOUT` | `5s` | 요청 전송 후 응답 헤더 수신까지. 본문 수신은 포함하지 않는다. |
| `MATCHING_SERVICE_CALL_TIMEOUT` | `8s` | 연결 시작부터 본문 수신 완료까지 전체. 실제로 강제되는 상한이다. |

- 세 값은 1ms 이상 1h 이하여야 하고, 연결·헤더 제한은 전체 제한을 넘을 수 없다. 밀리초 미만 값은 무제한 대기나 즉시 만료로 해석될 수 있어 거절한다. 위반하면 기동 시 실패한다.
- 이전 구현의 `HttpURLConnection` 읽기 타임아웃은 읽기 사이 간격만 제한했다. 연결 100ms·읽기 300ms(계산상 400ms) 설정에서 본문을 50ms 간격으로 받는 호출이 10초 넘게 이어졌고, 본문 수신 중에는 다른 스레드의 `disconnect()`로도 소켓이 닫히지 않았다. 그래서 이 호출만 JDK `HttpClient`로 바꿨다.
- 전체 제한시간 초과는 일시 오류 `TIMEOUT`이다. 상대가 처리했는지 알 수 없으므로 성공으로 기록하지 않고, 같은 명령 ID·버전으로 backoff 재시도한다.
- DNS 이름 해석 시간은 JDK가 취소할 수 없어 이 제한에 포함되지 않는다. 운영 주소는 해석 지연이 없는 서비스 이름이나 고정 주소를 쓴다.

#### 실행권 여유

실행권 만료 시간은 `MATCHING_SERVICE_CALL_TIMEOUT + 1초` 이상이어야 하며 그보다 짧으면(전체 제한시간 이하 포함) 기동 시 거절한다. 1초는 제한시간 초과 후 교환 취소와 결과 기록 트랜잭션을 위한 최소 여유다. 기본값은 실행권 30초, 전체 제한 8초로 22초의 여유가 있다. HTTP 호출은 전체 제한시간 안에 반드시 끝나므로 호출 도중 실행권이 만료되어 다른 실행자가 같은 명령을 동시에 보내지 않는다. 그래도 실행권 토큰 조건부 갱신은 유지하므로 늦게 끝난 이전 실행자는 결과를 덮어쓰지 못한다.

| 결과 | 분류 | 다음 시도 |
| --- | --- | --- |
| 2xx + 끝까지 받은 본문이 JSON이고 `success`가 불리언 `true` | 성공 | 없음(`SUCCEEDED`) |
| 409(예: Saga 미완료 `APPLICATION-409-006`) | `CONFLICT` | backoff |
| 연결·응답 헤더·전체 호출 제한시간 초과(본문 수신 중 포함), 408 | `TIMEOUT` | backoff |
| 연결 실패, 본문 수신 중 연결 끊김 등 전송 오류 | `NETWORK` | backoff |
| 429 | `THROTTLED` | backoff |
| 5xx | `SERVER_ERROR` | backoff |
| 401·403 | `AUTHENTICATION` | 최대 지연, `ERROR` 로그 |
| 그 밖의 4xx, 끝까지 받았지만 계약과 다른 2xx(빈 본문, 잘못된 JSON, `success`가 `true`가 아님, 8KB 초과) | `CONTRACT` | 최대 지연, `ERROR` 로그 |

본문 수신 실패와 본문 형식 오류는 구분한다.

- 본문 수신은 HTTP 교환의 일부다. 본문을 받다가 시간이 초과되면 `TIMEOUT`, 연결이 끊기거나 선언한 길이보다 적게 받으면 `NETWORK`이며, 이미 받은 HTTP 상태와 관계없이 이 분류가 우선한다. 받은 상태는 `last_failure_http_status`에 진단 정보로 남긴다. 예: 200 헤더 뒤 본문이 멈추면 `TIMEOUT`, 상태 200. 읽다가 중단된 본문은 성공으로 인정하지 않는다.
- 본문을 끝까지 받은 응답은 HTTP 상태로 분류한다. JSON 형식이 잘못됐거나 뒤에 다른 내용이 붙었으면 오류 코드 없이 상태만 사용한다. 예: 503 + 잘못된 JSON은 `SERVER_ERROR`, 코드 없음.
- 상대 오류 코드는 본문이 정상 JSON이고 안전한 형식(`[A-Z0-9_-]{1,50}`)일 때만 보존한다.
- 전송 오류(I/O)도 JSON 형식 오류도 아닌 예외는 내부 오류로 보고 통신·계약 실패로 바꾸지 않는다. 실행기 경계(스케줄러 전송과 커밋 후 즉시 전송)에서 명령 ID와 예외 타입 사슬만 `ERROR`로 기록하고, 실행권이 만료되면 같은 명령이 다시 전송된다. 예외 메시지·cause·suppressed 예외와 스택 트레이스에는 요청 헤더 값(내부 secret 포함) 같은 검증되지 않은 문자열이 담길 수 있어 기록하지 않는다. 한 명령의 내부 오류는 같은 배치의 다음 명령 처리를 막지 않는다.

- backoff는 `RETRY_BASE_DELAY × 2^(시도-1)`이며 `RETRY_MAX_DELAY`를 넘지 않는다. 인증·계약 오류는 원인 해결 전까지 최대 지연 간격으로만 다시 확인하며 `[운영 확인 필요]` 오류 로그와 `last_failure_type`으로 일시 오류와 구분한다.
- 재시도 횟수로 명령을 종료·삭제하지 않고 모집 완료도 취소하지 않는다. 원인을 고치면 같은 명령 ID로 자동 재처리된다. 즉시 재처리가 필요하면 해당 명령의 `next_attempt_at`을 현재 시각으로 당긴다. 식별 컬럼과 `job_version`은 바꾸지 않는다.
- 실패 기록에는 분류, HTTP 상태, 형식이 맞는 응답 오류 코드만 저장한다. 응답 원문, 요청 헤더, secret은 저장하거나 로그로 남기지 않는다.
- 자리 확정 직후에는 matching-service의 확정 Saga가 아직 로컬 확정을 끝내지 않았을 수 있으므로 첫 시도의 409는 정상적인 재시도 대상이다.

### 실행 경로와 복구

- 커밋 후 즉시 전송: `RecruitmentCompletionCommandCreatedEvent`를 `AFTER_COMMIT`에서 받아 별도 단일 스레드에서 한 번 시도한다. 자리 확정 요청 스레드는 응답을 기다리지 않는다. 실행되지 않거나 실패해도 아래 스케줄러가 복구한다.
- 전송 스케줄러: `RECRUITMENT_COMPLETION_DISPATCH_INTERVAL`(기본 5초)마다 전송 대상을 최대 `RECRUITMENT_COMPLETION_DISPATCH_BATCH_SIZE`(기본 50)개 처리한다.
- 중단 지점별 복구: 마감 커밋 직후 HTTP 전에 멈추면 `PENDING` 명령을 스케줄러가 찾는다. 실행권 획득 후 멈추면 실행권 만료 뒤 다른 실행자가 넘겨받는다. 상대가 처리한 뒤 응답이 유실되거나 HTTP 성공 후 로컬 기록 전에 멈추면 같은 명령 ID·버전으로 다시 보내며, matching-service는 같은 버전의 완료를 이력·이벤트 추가 없이 성공 처리하므로 수렴한다.

### 이전 구현의 정원 충족 공고 복구

모집 완료 구현 이전에 마지막 자리가 확정되어 `OPEN`/`MATCHING`으로 남은 공고는 `RecruitmentCompletionReconciler`가 마감한다. `RECRUITMENT_COMPLETION_RECONCILE_INTERVAL`(기본 10분, 첫 실행 `RECRUITMENT_COMPLETION_RECONCILE_INITIAL_DELAY` 30초)마다 `CONSUMED 수 >= recruitCount`인 모집 중 공고 ID를 최대 `RECRUITMENT_COMPLETION_RECONCILE_BATCH_SIZE`(기본 100)개 조회하고, 공고마다 별도 트랜잭션에서 공고 행을 잠근 뒤 같은 기준으로 다시 판단한다. 정원이 차지 않은 공고와 이미 마감된 공고는 바꾸지 않는다. 마감된 공고는 일반 경로와 같은 이력과 알림 명령을 남긴다. 배포 후 첫 실행에서 기존 대상이 처리되며, 대상이 많으면 배치 크기 단위로 다음 실행에 이어서 처리된다. 실행마다 직전 배치의 마지막 ID 다음부터 조회하고, 배치가 덜 차면 끝에 닿은 것으로 보고 다음 실행은 처음부터 다시 찾는다. 처리에 실패한 공고도 조회 위치를 넘기므로, 계속 실패하는 낮은 ID 공고가 매 배치를 차지해 뒤 공고의 복구를 막지 않는다. 조회 위치는 인스턴스 메모리에만 두며, 재시작하면 처음부터 다시 찾는다.

### 운영 설정

| 환경 변수 | 기본값 | 의미 |
| --- | --- | --- |
| `MATCHING_SERVICE_BASE_URL` | `http://localhost:8084` | matching-service 주소. HTTPS 또는 루프백 HTTP만 허용 |
| `MATCHING_SERVICE_CONNECT_TIMEOUT` / `MATCHING_SERVICE_READ_TIMEOUT` | `3s` / `5s` | 연결 수립·응답 헤더 수신 제한 |
| `MATCHING_SERVICE_CALL_TIMEOUT` | `8s` | 본문 수신까지 포함한 한 번의 호출 전체 제한 |
| `RECRUITMENT_COMPLETION_DISPATCH_ENABLED` | `true` | 전송 스케줄러 사용 |
| `RECRUITMENT_COMPLETION_DISPATCH_AFTER_COMMIT` | `true` | 커밋 후 즉시 전송 사용 |
| `RECRUITMENT_COMPLETION_DISPATCH_INTERVAL` / `..._BATCH_SIZE` | `5s` / `50` | 스케줄러 주기·배치 |
| `RECRUITMENT_COMPLETION_LEASE_DURATION` | `30s` | 실행권 만료 시간(`MATCHING_SERVICE_CALL_TIMEOUT + 1초` 이상) |
| `RECRUITMENT_COMPLETION_RETRY_BASE_DELAY` / `..._MAX_DELAY` | `2s` / `5m` | backoff 기본·최대 지연 |
| `RECRUITMENT_COMPLETION_RECONCILE_ENABLED` | `true` | 정원 충족 공고 복구 사용 |
| `RECRUITMENT_COMPLETION_RECONCILE_INTERVAL` / `..._INITIAL_DELAY` / `..._BATCH_SIZE` | `10m` / `30s` / `100` | 복구 주기·첫 실행·배치 |

## 결제 주문 생성

신규 공고는 `PAYMENT_PENDING`으로 비공개 저장되고, 모집 인원 전체 예정 급여의 결제 주문을 payment-service에 만든다. 계약은 [토스 예치 설계](./toss-deposit-design.md)의 공고 담당자 구현 계약을 따른다. 주문 생성은 공고 공개가 아니다. 검증된 예치 후 `OPEN` 전환은 [예치 상태 수신](#예치-상태-수신)이 맡는다.

### 명령 저장

- 공고 저장과 `job_payment_order_commands` 명령 저장은 공고 등록의 한 로컬 트랜잭션이다. 명령 저장이 실패하면 공고도 저장되지 않는다.
- 명령은 발급 당시 스냅샷을 `updatable = false`로 보존한다: 공고 ID, 결제용 공고 버전(`job_version`, 저장 직후의 공고 버전), 점주 회원 ID, 금액(정수 KRW), 통화 `KRW`, `Idempotency-Key`(UUID).
- 결제용 버전은 발급 당시 값으로 고정한다. 주문 ID 연결 등으로 공고의 `@Version`이 증가해도 재시도는 저장된 값만 보내며 공고를 다시 읽지 않는다.
- 같은 생성 시도의 재시도는 같은 명령 행을 다시 보내는 것이며 같은 키와 같은 본문이다. 재결제·조건 수정처럼 새 주문이 필요하면 공고 행을 잠근 뒤 다음 `issue_sequence`로 새 키의 명령을 발급한다(`JobPaymentOrderCommandIssuer`). 공고 등록과 [결제 조건 변경과 재결제](#결제-조건-변경과-재결제)가 명령을 발급한다.
- DB 제약: `uk_job_payment_order_commands_idempotency_key`(키), `uk_job_payment_order_commands_job_sequence`(`job_post_id, issue_sequence`, 같은 순번의 중복 발급 방지), `uk_job_payment_order_commands_order_id`(한 주문은 한 명령에만 연결), 금액 `>= 100`, 통화 `KRW` 검사.

### 전송

`POST {PAYMENT_SERVICE_BASE_URL}/api/payments/internal/orders`, 헤더 `X-Internal-Secret`(`INTERNAL_API_SECRET`), `Idempotency-Key`, 본문 `{"jobPostId","jobVersion","ownerMemberId","amount","currency"}`(명령 스냅샷 그대로).

모집 완료 알림과 같은 실행 방식이다(별도 범용 프레임워크 없이 같은 구조의 클래스를 둔다).

1. 짧은 트랜잭션에서 실행권 획득: `PENDING`이고 `next_attempt_at <= now`이며 실행권이 없거나 만료된 행만 기본 키 조건 단일 `UPDATE`로 `lease_token`, `lease_expires_at`을 설정하고 `attempt_count`를 1 올린다. 여러 실행자가 동시에 실행해도 한 실행자만 얻는다.
2. 트랜잭션과 잠금 없이 HTTP 호출. 연결 시작부터 본문 수신 완료까지 `PAYMENT_SERVICE_CALL_TIMEOUT`(기본 8초)을 넘지 않으며, 넘으면 JDK `HttpClient` 교환을 취소해 연결을 닫는다. 응답 본문은 최대 8KB까지 받는다.
3. 결과 기록. 성공은 아래 [주문 ID 연결](#주문-id-연결), 실패는 `lease_token`이 일치할 때만 분류·HTTP 상태·형식이 맞는 응답 코드와 다음 시도 시각을 기록하고 실행권을 비운다.

실행권 만료 시간(`PAYMENT_ORDER_LEASE_DURATION`, 기본 30초)은 `PAYMENT_SERVICE_CALL_TIMEOUT + 1초` 이상이어야 하며 아니면 기동 시 거절한다. 호출 중 실행권이 만료되어 다른 실행자가 같은 명령을 동시에 보내지 않게 하는 여유다.

실행 경로: 공고 등록 커밋 후 `PaymentOrderCommandCreatedEvent`를 `AFTER_COMMIT`에서 받아 별도 단일 스레드에서 한 번 시도한다(등록 요청은 응답을 기다리지 않는다). 실행되지 않거나 실패해도 `PAYMENT_ORDER_DISPATCH_INTERVAL`(기본 5초)마다 스케줄러가 `PAYMENT_ORDER_DISPATCH_BATCH_SIZE`(기본 50)개씩 미완료 명령을 전송한다. 실행권 획득 후 멈춘 실행자의 명령은 실행권 만료 뒤 다른 실행자가 넘겨받는다.

### 응답 검증

HTTP 2xx만으로 성공 처리하지 않는다. 끝까지 받은 본문이 JSON이고 다음을 모두 만족해야 주문 생성 성공이다.

- 공통 응답의 `success`가 불리언 `true`이고 `data`가 객체다.
- 필수 필드 `orderId`(`[A-Za-z0-9-]{1,64}`), `jobPostId`, `jobVersion`(정수), `amount`(숫자), `currency`, `status`(문자열)가 있다.
- `jobPostId`, `jobVersion`, `amount`(소수 표기 `100000.00`도 값으로 비교), `currency`가 요청 스냅샷과 같다. 응답 JSON의 소수는 이 클라이언트의 reader에서 `USE_BIG_DECIMAL_FOR_FLOATS`로 처음부터 `BigDecimal`로 읽는다. double을 거치면 `9999999999999999.00` 같은 허용 범위의 큰 금액이 다른 값이 되어 정상 주문도 불일치로 거절되기 때문이다. 전역 `ObjectMapper` 설정은 바꾸지 않는다. 응답에 점주 ID는 없으므로 점주는 요청 지문으로 payment-service가 대조한다.
- `status`가 연결 가능한 주문 상태다. payment-service 주문 상태는 `READY → CONFIRMING → DEPOSITED/FAILED/REVIEW_REQUIRED`, 대체 시 `SUPERSEDED`다. 같은 키의 재요청은 처음 만든 주문의 현재 상태를 돌려주므로, 응답이 유실된 사이 결제가 진행됐을 수 있다. 주문이 존재한다는 사실은 같으므로 `READY`, `CONFIRMING`, `DEPOSITED`, `FAILED`, `REVIEW_REQUIRED`는 모두 같은 주문 ID를 연결한다. 주문 상태로 공고를 공개하거나 결제 성공을 판단하지 않는다. `SUPERSEDED`는 이미 다른 주문으로 대체된 주문이므로 연결하지 않는다.

### 실패 분류와 재시도

| 결과 | 분류 | 다음 시도 |
| --- | --- | --- |
| 전체 호출 제한시간 초과(본문 수신 중 포함), 408 | `TIMEOUT` | backoff |
| 연결 실패, 본문 수신 중 연결 끊김 | `NETWORK` | backoff |
| 429 | `THROTTLED` | backoff |
| 5xx | `SERVER_ERROR` | backoff |
| 401·403 | `AUTHENTICATION` | 최대 지연, `[운영 확인 필요]` 오류 로그 |
| 409(`ORDER-409-001`: 같은 키의 다른 요청 내용, 같은 공고의 다른 활성 주문과 충돌) | `CONFLICT` | 최초 주문 생성: 최대 지연, 운영 확인. 결제 변경 명령: 확정적 교체 거절로 종료(`REJECTED`, [교체 결과 반영](#교체-결과-반영)) |
| 그 밖의 4xx, 계약과 다른 2xx(빈 본문, 잘못된 JSON, `success`가 `true`가 아님, 필수 필드 누락·형식 오류, 알 수 없는 주문 상태, 8KB 초과) | `CONTRACT` | 최대 지연, 운영 확인 |
| 응답의 공고 ID·버전·금액·통화가 요청과 다름 | `SNAPSHOT_MISMATCH` | 최대 지연, 운영 확인 |
| 응답 주문이 `SUPERSEDED` | `ORDER_SUPERSEDED` | 최대 지연, 운영 확인 |

- backoff는 `PAYMENT_ORDER_RETRY_BASE_DELAY × 2^(시도-1)`이며 `PAYMENT_ORDER_RETRY_MAX_DELAY`를 넘지 않는다.
- 타임아웃·네트워크 오류는 payment-service가 주문을 만들었는지 알 수 없다. 같은 키·같은 본문으로 다시 보내면 payment-service가 처음 만든 주문을 돌려주므로 원래 주문 ID가 복구된다. 새 키로 우회하지 않는다.
- 어떤 실패도 주문 생성 성공으로 기록하거나 공고에 연결하지 않고, 재시도 횟수로 명령을 종료·삭제하지 않는다. 원인을 고치면 같은 명령이 자동 재처리된다. 즉시 재처리가 필요하면 해당 명령의 `next_attempt_at`만 현재 시각으로 당기고 스냅샷 컬럼은 바꾸지 않는다.
- 운영 확인 대상의 원인 예: 내부 secret 불일치(인증), 다른 환경의 같은 공고 ID에 이미 활성 주문이 있음(충돌), payment-service 계약 변경(계약), 공고·결제 데이터 불일치(스냅샷). `SNAPSHOT_MISMATCH`·`ORDER_SUPERSEDED`는 재시도만으로 바뀌지 않을 가능성이 높으므로 원인을 확인한 뒤 재결제 후속 기능으로 새 명령을 발급해야 한다.
- 전송 오류(I/O)도 응답 검증 실패도 아닌 예외는 내부 오류다. 실행기 경계에서 명령 ID와 예외 타입 사슬만 `ERROR`로 기록하고, 실행권이 만료되면 같은 명령을 다시 보낸다. 한 명령의 내부 오류는 같은 배치의 다음 명령을 막지 않는다.
- 로그와 실패 기록에는 명령 ID, 공고 ID, 결제용 버전, 시도 횟수, 분류, HTTP 상태, 형식이 맞는 응답 코드, 연결된 주문 ID만 남긴다. 요청 헤더(내부 secret·멱등 키), 요청·응답 원문, 예외 메시지는 저장하거나 로그로 남기지 않는다.
- DNS 이름 해석 시간은 JDK가 취소할 수 없어 전체 제한시간에 포함되지 않는다. 운영 주소는 해석 지연이 없는 서비스 이름이나 고정 주소를 쓴다.

### 주문 ID 연결

검증된 응답은 한 로컬 트랜잭션에서 공고 행 → 명령 행 순서로 잠근 뒤 기록한다.

- 명령의 `lease_token`이 이 실행자의 것이고 `PENDING`일 때만 기록한다. 실행권이 만료되어 다른 실행자가 넘겨받았거나 이미 처리된 명령이면 아무것도 바꾸지 않는다(늦은 응답 무시).
- 이 명령이 공고의 가장 큰 `issue_sequence`가 아니면(더 늦게 발급된 명령이 있으면) 주문을 연결하지 않고 명령을 `SUPERSEDED`로 종료한다. 과거 명령이 최신 연결을 덮어쓰지 않는다.
- 최대 순번은 공고 행 잠금을 잡은 뒤 잠금 조회(`FOR SHARE`)로 읽는다. MySQL REPEATABLE READ의 일반 조회는 트랜잭션의 첫 일반 조회(공고 ID 조회) 시점 스냅샷을 보므로, 공고 잠금을 기다리는 사이 커밋된 새 명령을 놓쳐 오래된 명령을 최신으로 판정할 수 있다. 잠금 조회는 최신 커밋 값을 읽고, 새 명령 발급도 공고 행 잠금을 먼저 잡으므로 잠금을 가진 동안 이 값은 바뀌지 않는다. 서비스 전체 격리 수준은 바꾸지 않는다.
- 최신 명령이면 명령을 `SUCCEEDED`로 바꾸고 `order_id`를 기록하며, 공고에 `payment_order_id`와 원래 결제 스냅샷(`payment_job_version`, `payment_amount`, `payment_currency`)을 연결한다. 공고 상태는 바꾸지 않는다. 연결로 공고의 `@Version`은 증가하지만 `payment_job_version`은 명령의 발급 당시 버전이다.
- [예치 상태 수신](#예치-상태-수신)은 공고에 연결된 이 주문 ID·금액·통화·점주·결제용 버전과 대조한다.

### 점주 결제 주문 조회

`GET /api/jobs/{id}/payment-order`(`OWNER` Bearer JWT). 공고의 점주 ID와 토큰의 `memberId`가 다르면 404 `JOB-404-001`, 토큰이 없으면 401, `OWNER`가 아니면 403이다.

| 필드 | 의미 |
| --- | --- |
| `jobPostId`, `jobStatus` | 공고 ID와 현재 상태. 공개 여부는 `jobStatus`로 판단한다. |
| `orderCreationStatus` | `PENDING`: 명령은 저장됐지만 주문 생성이 아직 확인·연결되지 않음. `CREATED`: 검증된 주문 ID가 연결됨. `NOT_REQUESTED`: 이 기능 이전에 만들어져 명령이 없는 공고 |
| `latestChange` | 가장 최근의 결제 조건 변경·재결제 요청(없으면 `null`). `PENDING`인 동안 위 주문 필드는 이전 주문이며, `APPLIED`가 되면 새 주문으로 바뀐다. 필드는 아래 [변경 요청 응답](#변경-요청-응답)과 같다 |
| `paymentOrderId` | `CREATED`일 때만 값이 있고 그 밖에는 `null` |
| `paymentJobVersion`, `amount`, `currency` | `CREATED`면 연결된 결제 스냅샷, `PENDING`이면 최신 명령의 스냅샷, `NOT_REQUESTED`면 `null` |

프론트엔드는 공고 등록 응답의 공고 ID로 이 API를 조회해 `CREATED`가 될 때까지 기다린 뒤 `paymentOrderId`로 payment-service 주문 조회·결제를 진행한다. `PENDING`이 오래 지속되면 운영 확인 대상 실패일 수 있다. 결제 주문 상태(`READY`, `DEPOSITED` 등)는 payment-service의 `GET /api/payments/orders/{orderId}`로 확인하며, 예치 완료가 곧 공고 공개는 아니다.

### 결제 주문 운영 설정

| 환경 변수 | 기본값 | 의미 |
| --- | --- | --- |
| `PAYMENT_SERVICE_BASE_URL` | `http://localhost:8085` | payment-service 주소(matching-service와 공유). HTTPS 또는 루프백 HTTP만 허용 |
| `PAYMENT_SERVICE_CONNECT_TIMEOUT` / `PAYMENT_SERVICE_READ_TIMEOUT` | `3s` / `5s` | 연결 수립·응답 헤더 수신 제한. 전체 제한을 넘을 수 없다 |
| `PAYMENT_SERVICE_CALL_TIMEOUT` | `8s` | 본문 수신까지 포함한 한 번의 호출 전체 제한(1ms~1h) |
| `PAYMENT_ORDER_DISPATCH_ENABLED` | `true` | 전송 스케줄러 사용 |
| `PAYMENT_ORDER_DISPATCH_AFTER_COMMIT` | `true` | 공고 등록 커밋 후 즉시 전송 사용 |
| `PAYMENT_ORDER_DISPATCH_INTERVAL` / `..._BATCH_SIZE` | `5s` / `50` | 스케줄러 주기·배치 |
| `PAYMENT_ORDER_LEASE_DURATION` | `30s` | 실행권 만료 시간(`PAYMENT_SERVICE_CALL_TIMEOUT + 1초` 이상) |
| `PAYMENT_ORDER_RETRY_BASE_DELAY` / `..._MAX_DELAY` | `2s` / `5m` | backoff 기본·최대 지연 |

### 기존 공고 처리 정책

- V10 이전에 생성된 `OPEN`·`MATCHING`·`CLOSED` 공고를 `PAYMENT_PENDING`으로 일괄 변경하지 않는다. 결제 주문과 명령을 소급 생성하지도 않는다. 이미 공개된 공고를 비공개로 되돌리면 진행 중인 지원·매칭이 갑자기 막히기 때문이다.
- 이런 공고는 기존과 같이 검색·상세 조회·지원·자리 예약 대상이며, 결제 주문 조회는 `NOT_REQUESTED`다.
- 적용 한계: 이 공고들은 예치로 뒷받침되지 않는다. 매칭 확정 Saga의 payment-service 결제 잠금은 예치가 있어야 하므로 거절되고 Saga가 보상한다. 즉 수락이 확정되지 않는다. 운영에서 기존 공고를 계속 쓰려면 공고를 다시 등록해야 한다. [결제 조건 변경과 재결제](#결제-조건-변경과-재결제)는 결제 대기 공고만 받으므로 주문이 없는 공개 공고의 소급 예치 경로는 아직 없다. 로컬·테스트 데이터는 공고를 다시 등록하는 것을 권장한다.

## 결제 조건 변경과 재결제

점주는 결제 대기(`PAYMENT_PENDING`) 공고의 결제 조건을 바꾸거나, 결제가 실패한 주문을 같은 조건으로 다시 결제할 새 주문을 요청한다. 둘 다 새 결제 시도이며 다음 순번의 주문 생성 명령과 새 멱등 키를 발급한다. 공고의 현재 조건과 주문 연결은 새 주문이 payment-service에서 만들어지고 job-service가 그 응답을 검증·연결할 때까지 그대로다.

### 요청과 권한

- `PUT /api/jobs/{id}/payment-terms`: 변경 후의 전체 결제 조건(부분 수정 없음). 본문 `workDate`, `startTime`, `endTime`, `isEndTimeNextDay`, `baseHourlyWage`, `extraWage`(시간당 추가 시급, null 허용), `recruitCount`, `applicationDeadline`.
- `POST /api/jobs/{id}/payment-order/retries`: 본문 없음. 연결된 주문과 같은 조건·같은 결제용 버전·같은 금액으로 새 주문을 요청한다.
- `OWNER` JWT가 필요하다(없으면 401, 다른 역할은 403). 점주는 JWT의 `memberId`로만 확인하며 본문의 `ownerMemberId` 같은 값은 무시한다. 다른 점주의 공고와 없는 공고는 존재 여부를 드러내지 않도록 같은 404 `JOB-404-001`이다.
- 수정 대상은 위 결제 조건 필드뿐이다. 근무 일시·익일 여부·기본 시급·추가 시급·모집 인원은 예치 금액을 정하고, 지원 마감은 근무 시작 이전이어야 하므로 함께 바꾼다. 가게·제목·설명·위치·긴급도는 이 경로로 바꾸지 않는다.
- 검증은 공고 등록과 같다. 시각은 분 단위, 근무 1분~24시간(`JOB-400-001`), 시급 10,320원 이상, 추가 시급 양수, 모집 인원 1명 이상(`GLOBAL-400-002`), 지원 마감은 현재 이후이고 근무 시작 이전(`JOB-400-002`), 금액은 `JobWageCalculator`의 정수 KRW·전체 100원 이상·payment-service 금액 상한·`long` 오버플로 검사(`JOB-400-004`)를 그대로 쓴다. 현재와 같은 조건은 변경이 아니며 `JOB-400-005`다(같은 조건의 새 결제는 재결제를 쓴다).

### 변경 허용 범위

공고 행을 비관적 잠금으로 잡은 뒤 다음을 확인한다. job-service가 아는 사실만으로 거절할 뿐, 통과했다고 교체가 확정되지는 않는다. 이전 주문이 `READY`·`FAILED`·`CONFIRMING` 중 무엇인지는 job-service가 알 수 없으므로 payment-service가 자신의 잠금 안에서 최종 판단한다.

| 공고 상태 | 처리 |
| --- | --- |
| `PAYMENT_PENDING`, 주문 연결됨, 진행 중인 주문 생성 없음, 현재 주문에 예치 상태가 반영된 적 없음, 지원 승인·자리 예약 이력 없음 | 변경 요청 접수(재결제는 지원 마감 전일 때만) |
| `OPEN`·`MATCHING`·`CLOSED`(공개·마감) | 409 `JOB-409-014`. 별도 재모집 정책 없이 공개 뒤 진행된 지원·매칭의 조건을 바꾸지 않는다 |
| 최신 주문 생성 명령이 아직 `PENDING`(최초 생성 포함, 결과 불명 포함) | 409 `JOB-409-015`. 한 공고에서 여러 주문 교체가 동시에 진행되지 않는다 |
| 현재 주문에 `funded=false`(REVIEW_REQUIRED)나 `funded=true`(공개 기한이 지나 `PUBLICATION_SKIPPED`)가 반영됨, `funding_blocked` | 409 `JOB-409-014`. 결제 검토·환불이 필요한 주문을 새 주문 발급으로 우회하지 않는다 |
| 지원 승인·자리 예약 이력이 있음 | 409 `JOB-409-014`. 결제 대기 공고는 지원·예약을 받지 않아 정상 흐름에서는 없지만 방어적으로 확인한다 |
| 재결제인데 지원 마감이 지남 | 409 `JOB-409-014`. 예치돼도 공개할 수 없으므로 결제 조건 변경으로 일정을 바꾼다 |

### 요청 멱등성

- `Idempotency-Key`는 1~100자의 공백 없는 ASCII이며 필수다. 업무 상태 확인보다 먼저, 공고 행 잠금 아래에서 같은 키의 요청을 찾는다.
- 같은 키·같은 요청(같은 공고·점주·요청 종류, 조건 변경이면 같은 조건)은 새 명령을 만들지 않고 그 요청의 현재 처리 상태(`PENDING`·`APPLIED`·`REJECTED`)를 돌려준다. 공고가 바뀐 뒤에도 같다.
- 현재 시각에 따라 달라지는 검증(지원 마감이 현재 이후, 지나지 않은 근무일)은 요청 DTO가 아니라 서비스에서 같은 키의 기존 요청을 확인한 뒤 새 요청에만 적용한다. 그래서 마감이나 근무일이 지난 뒤의 같은 키·같은 본문 재요청도 400이 아니라 처음 처리 상태를 받는다. 형식 검증(필수 값, 최저 시급, 양수 인원 등)은 시각과 무관하므로 요청 경계에 둔다.
- 같은 키를 다른 조건·다른 요청 종류·다른 공고에 쓰면 409 `JOB-409-004`다. 서로 다른 공고에 같은 키를 쓴 동시 요청은 `uk_job_payment_change_requests_idempotency_key`가 막고 롤백 뒤 같은 코드로 변환한다.

### 결제용 버전과 새 시도

- 결제용 버전(`jobVersion`, 공고의 `payment_job_version`)은 결제 조건 스냅샷의 버전이며 JPA `@Version`이 아니다. 새 결제 시도는 버전이 아니라 새 `issue_sequence`, 새 `Idempotency-Key`, payment-service의 새 `orderId`로 구분한다.
- 조건 변경은 이 공고 명령들의 가장 큰 결제용 버전 + 1을 쓴다. 거절된 명령의 버전도 다시 쓰지 않는다. payment-service는 같은 버전의 금액 변경을 거절하고, `READY` 주문은 더 높은 버전으로만 교체하기 때문이다.
- 같은 조건 재결제는 연결된 주문의 결제용 버전과 금액을 그대로 쓴다. payment-service는 같은 버전의 교체를 같은 금액의 `FAILED` 주문에만 허용하므로, 결제창을 닫은 `READY` 주문의 재결제는 거절되고 점주는 기존 주문으로 다시 결제한다. 버전을 올리면 같은 조건에 다른 버전이 생기고 `READY` 주문도 교체되어 이 구분이 사라진다.
- 연결 후 공고의 `@Version`은 증가하지만 `payment_job_version`은 명령 발급 당시 값을 유지한다.

### 저장과 전송

요청 트랜잭션은 공고 행 잠금 아래에서 `job_payment_change_requests` 행(요청 키, 종류, 적용할 조건 스냅샷, 명령 ID)과 다음 순번의 `job_payment_order_commands` 행(결제용 버전, 점주, 금액, `KRW`, 새 키)을 함께 저장한다. 공고 행은 바꾸지 않는다. 둘 중 하나라도 저장에 실패하면 함께 롤백된다. 커밋 후 전송은 [결제 주문 생성](#전송)의 실행권·재시도 경로를 그대로 쓰며, HTTP 호출은 DB 트랜잭션 밖에서 실행한다.

### 교체 결과 반영

결과는 실행권 토큰이 일치할 때만 공고 행 → 명령 행 → 변경 요청 행 순서로 잠근 한 로컬 트랜잭션에서 반영한다.

| 상황 | 처리 |
| --- | --- |
| 요청은 저장됐지만 주문 생성 호출 전 | 변경 요청 `PENDING`, 공고는 이전 조건·이전 주문. 스케줄러가 같은 명령을 보낸다 |
| 검증된 새 주문 응답 | 명령 `SUCCEEDED`, 공고에 새 주문·결제 스냅샷 연결, 조건 변경이면 같은 트랜잭션에서 조건 적용, 요청 `APPLIED`. 공고 상태와 `funding_blocked`는 바꾸지 않는다 |
| payment-service 409(교체 거절) | 확정적 거절. 명령 `REJECTED`(분류·코드 보존), 요청 `REJECTED`(`resolutionCode=ORDER-409-001`), 공고는 그대로. payment-service는 거절한 요청을 롤백해 주문도 키도 남기지 않으며, 이 키는 다시 보내지 않는다. 다음 시도는 새 요청·새 키다 |
| 타임아웃·네트워크 오류·5xx·429·잘못된 응답·스냅샷 불일치 | 결과 불명. 명령·요청 `PENDING`, 공고는 이전 조건·이전 주문. 새 키로 다른 주문을 만들거나 기존 조건으로 종료하지 않고, 같은 키·같은 본문으로 다시 보내 payment-service가 처음 만든 주문(또는 거절)을 확인해 수렴한다 |
| payment-service는 새 주문을 만들었지만 응답 유실 | 위와 같다. 같은 키 재전송이 원래 주문 ID를 돌려준다 |
| 응답은 받았지만 결과 저장 트랜잭션 실패 | 전부 롤백되어 명령은 `PENDING`과 실행권만 남는다. 실행권 만료 뒤 같은 키로 다시 보내 원래 주문을 연결한다. 거절 기록 저장이 실패한 경우도 거절이 확정되지 않았으므로 같은 키로 다시 판단한다 |
| 실행권이 만료된 이전 실행자의 늦은 응답 | 토큰 불일치로 0건 반영(`LEASE_LOST`). 새 실행자의 결과를 덮어쓰지 않는다 |
| 새 주문 응답을 받았는데 공고가 더 이상 교체할 수 없음(이전 주문으로 공개됨, 이전 주문에 예치 상태 반영) | 정상 교체 정책에서는 생기지 않는다(payment-service가 DEPOSITED·REVIEW_REQUIRED를 교체하지 않음). 공개된 조건을 바꾸지 않고 연결하지 않으며, 명령은 주문 ID만 남긴 `SUPERSEDED`, 요청은 `REJECTED`(`JOB_STATE_CHANGED`), `[운영 확인 필요]` 오류 로그. 그 주문의 늦은 예치 알림은 이 공고의 이전 주문(`STALE_ORDER`)으로 처리되어 환불 검토 대상이 된다 |

연결 트랜잭션은 공고 행 잠금 전에 명령의 공고 ID를 일반 조회로 읽어 MySQL 읽기 스냅샷을 만든다. 그래서 현재 주문의 예치 상태는 잠금 조회(`FOR SHARE`)로 읽어, 잠금을 기다리는 동안 커밋된 예치 반영을 놓치지 않는다.

### 예치 알림과 funding_blocked

`funding_blocked`는 공고 단위 값이며 연결된 주문의 예치 판단만 반영한다. 주문별 예치 상태와 revision은 `job_payment_fundings`에 따로 있다.

- 새 주문 연결은 차단을 해제하거나 공고를 공개하지 않는다. 새 주문의 검증된 `funded=true`가 [예치 상태 수신](#예치-상태-수신)으로 반영될 때만 그 주문 기준으로 차단을 해제하고 공개 가능 여부를 판단한다. 결제 대기 공고는 그 전까지 신규 지원·자리 예약을 받지 않는다.
- 이전 주문의 `funded=false`는 `STALE_ORDER`로 기록만 하고 새 주문을 차단하지 않는다. 이전 주문의 `funded=true`도 `STALE_ORDER`이며 새 주문을 공개하지 않고 환불 검토 대상이 된다.
- 현재 주문이 차단(`funded=false`)된 공고는 교체를 받지 않는다. 그래서 차단 상태에서 새 주문이 연결되는 경우가 없고, 차단은 그 주문의 더 높은 `funded=true` revision만 해제한다.
- 연결 전에 도착한 새 주문의 예치 알림은 이전 주문으로 분류하지 않는다. 이전 주문 판별은 명령에 검증된 주문 ID가 기록된 주문만 대상으로 하고, 최신 명령이 `PENDING`이며 스냅샷이 같으면 기록 없이 409 `JOB-409-013`으로 재전송을 요청한다(같은 조건 재결제처럼 이전 주문과 스냅샷이 같아도 주문 ID로 구분한다). payment-service는 같은 명령을 무기한 재전송하고 job-service는 같은 키로 주문을 연결하므로 연결 뒤 같은 알림이 정상 처리된다.
- 마감 등 공개 불가능한 업무 상태는 예치 알림으로 되돌리지 않는다(기존 규칙).

### 변경 요청 응답

`changeId`, `jobPostId`, `changeType`(`TERMS_CHANGE`/`REPAYMENT`), `status`(`PENDING`/`APPLIED`/`REJECTED`), 새 주문의 `paymentJobVersion`·`amount`·`currency`, `paymentOrderId`(`APPLIED`일 때만, 연결되지 않은 주문 ID는 노출하지 않는다), `resolutionCode`(거절 사유), `requestedAt`, `resolvedAt`. 프런트엔드는 같은 키로 다시 요청하거나 결제 주문 조회의 `latestChange`로 진행을 확인하고, `APPLIED`가 되면 새 `paymentOrderId`로 결제한다. `REJECTED`(`ORDER-409-001`)는 이전 주문이 결제 확인 중·예치 완료·검토 필요이거나 같은 조건의 `READY`라는 뜻이며, 결과가 확인된 뒤 새 키로 다시 요청한다.

## 예치 상태 수신

payment-service가 검증한 예치 상태를 받아 결제 대기 공고를 공개하거나 신규 모집을 차단한다. 송신 계약은 [토스 예치 설계](./toss-deposit-design.md#2-예치-상태-수신)를 따른다. 실제 환불·정산, 이미 확정된 매칭·근무의 자동 취소는 이 범위가 아니다. 주문 교체 중의 알림 처리는 [예치 알림과 funding_blocked](#예치-알림과-funding_blocked)를 따른다.

`POST /api/jobs/internal/{jobPostId}/funding-status`, 헤더 `X-Internal-Secret`, `Idempotency-Key`(1~100자 공백 없는 ASCII, payment-service는 `funding-{orderId}-{fundingRevision}`), 본문 `{"orderId","jobVersion","ownerMemberId","amount","currency","fundingRevision","funded"}`(payment-service `FundingJobClient`가 보내는 주문 스냅샷과 알림 revision). 성공 응답 `data`는 `jobPostId`, `orderId`, `fundingRevision`, `result`, `skipReason`, `jobStatus`(처리 직후 공고 상태), `fundingBlocked`다.

### 입력 검증

- 모든 필드가 필수이며 누락·null은 400(`GLOBAL-400-*`)이다. `funded`는 JSON 불리언만 받고 누락을 false로 해석하지 않는다.
- `orderId`는 `[A-Za-z0-9-]{1,64}`(주문 연결 응답 검증과 같은 형식)다. `jobVersion`·`ownerMemberId`·`fundingRevision`은 1 이상의 정수 JSON 숫자다. 전역 Jackson 설정은 정수 필드에 온 `1.5`를 1로 잘라 받으므로, 이 필드들과 `funded`에만 형 변환 없는 역직렬화를 적용한다. `1.5`, `1.0`, `"1"`, `"true"`, `funded: 1`은 400이다. 전역 설정은 바꾸지 않는다.
- `amount`는 100 이상 99,999,999,999,999,999 이하(payment-service DECIMAL(19,2)의 정수부)인 정수 KRW다. payment-service는 `100000.00`처럼 소수 둘째 자리로 보내므로 scale은 비교하지 않고 값이 정수인지만 본다(`100000.50`은 400). 비교·저장은 정수 KRW로 정규화한 값이다.
- `currency`는 대문자 세 글자다. 저장 스냅샷(`KRW`)과 다르면 주문 불일치(409)다.
- 인증 실패는 내부 인증 필터의 401 `GLOBAL-401-001`이다. 내부 secret, 멱등 키, 요청 원문은 로그에 남기지 않는다. 경고 로그에는 공고 ID, 주문 ID, revision만 남긴다.

### 처리 순서와 트랜잭션

한 로컬 트랜잭션에서 다음 순서로 처리한다.

1. 공고 행 비관적 잠금(없으면 404 `JOB-404-001`). 지원 승인, 자리 예약·확정·반환, 만료 회수, 모집 완료와 같은 잠금이며 잠금 순서는 공고 행 → 수신 기록·주문별 예치 상태다. 잠금이 트랜잭션의 첫 조회라서, 뒤의 일반 조회는 잠금을 기다리는 동안 커밋된 수신 기록과 주문 연결을 본다.
2. 멱등 재요청 확인. 공고 버전·업무 상태보다 먼저 확인한다. 같은 키의 수신 기록이 있으면 경로의 공고 ID와 본문 필드 전체가 같을 때 저장된 응답을 그대로 반환하고, 다르면 409 `JOB-409-004`다. 다른 키라도 같은 주문·revision의 수신 기록이 있으면, 같은 내용일 때 그 응답을 반환하고(효과·새 기록 없음) 다르면 409 `JOB-409-012`다. 공개로 `@Version`이 증가했거나 이후 공고가 마감됐어도 이미 처리한 명령은 처음 응답을 받는다.
3. 주문 판별([주문 판별](#주문-판별)).
4. revision 비교와 반영, 공개 전이와 상태 이력, 수신 기록 저장.

수신 기록, 주문별 revision, 예치 차단, 공고 상태, 상태 이력은 함께 커밋되거나 함께 롤백된다. 중간 실패는 500이고 아무것도 남지 않으므로 같은 명령의 재시도가 처음부터 다시 처리된다.

검증을 통과한 알림만 기록한다. 연결 대기·주문 불일치·멱등 충돌로 거절한 알림은 키와 revision을 선점하지 않으므로 이후의 정상 알림을 막지 않는다.

같은 공고의 동시 최초 요청은 공고 잠금으로 직렬화되어 뒤 요청이 앞의 수신 기록을 본다. 서로 다른 공고에 같은 키를 쓴 동시 요청은 `uk_job_funding_status_receipts_idempotency_key`가, 같은 주문·revision은 `uk_job_funding_status_receipts_order_revision`이 마지막으로 막는다. 롤백 뒤 `GlobalExceptionHandler`가 제약 이름이 정확히 일치할 때만 각각 `JOB-409-004`, `JOB-409-012`로 변환한다. 롤백 전용 트랜잭션 안에서 재조회하지 않는다.

### 주문 판별

| 알림의 주문 | 판별 기준 | 처리 |
| --- | --- | --- |
| 공고에 연결된 주문(`payment_order_id`) | 결제용 버전(`payment_job_version`, 현재 `@Version`이 아님)·점주·금액·통화가 공고의 결제 스냅샷과 같다. 다르면 아래 불일치 | [revision과 상태 반영](#revision과-상태-반영) |
| 이 공고의 이전 주문 | 현재 연결과 다르지만 이 공고의 명령에 검증된 주문으로 기록돼 있고(`job_payment_order_commands.order_id`) 그 명령 스냅샷과 같다 | 200 `STALE_ORDER`. 최신 주문의 예치 상태와 공고를 바꾸지 않는다 |
| 연결 대기 주문 | 공고의 최신 명령이 아직 `PENDING`(주문 ID 미연결)이고 그 명령 스냅샷과 같다 | 409 `JOB-409-013`, 기록 없음 |
| 그 밖의 주문 | 관련 없는 주문, 스냅샷 불일치, 주문이 없는 V10 이전 공고 | 409 `JOB-409-011`, 기록 없음, `[운영 확인 필요]` 경고 로그 |

알림 금액을 현재 공고 조건으로 다시 계산하지 않는다. 비교 기준은 주문 생성 명령 발급 당시 고정한 스냅샷이다.

#### 연결 대기 알림

주문 생성 응답이 유실되거나 주문 연결이 늦으면, payment-service의 예치 알림이 job-service의 주문 연결보다 먼저 올 수 있다. 수신 내용을 저장했다가 연결 후 재처리하는 방식(A) 대신, payment-service가 재시도하는 실패 응답을 주고 연결 후 같은 명령을 정상 처리하는 방식(B)을 쓴다.

- payment-service는 성공 envelope가 아닌 모든 응답을 같은 키·같은 본문으로 30~240초 backoff로 횟수 제한 없이 재전송한다(`PaymentRecovery`, `FundingNotificationRepository`). 주문당 최신 revision 한 건만 보관하므로 재전송은 항상 그 주문의 최신 상태다.
- job-service의 주문 생성 명령도 같은 키로 무기한 재시도해 원래 주문 ID를 복구하고 연결한다. 따라서 연결 대기는 시간이 지나면 해소되고 다음 재전송이 정상 처리된다.
- A는 대기 기록, 연결 시 재처리 트리거, 재시작 후 복구 스케줄러가 추가로 필요하고 이미 있는 두 재시도 경로와 겹친다.

연결 대기 응답은 수신 기록과 revision을 남기지 않으므로 최종 멱등 응답으로 고정되지 않는다. 알림 내용만으로 공고에 주문을 연결하지 않는다. 한계: 주문 연결이 운영 확인 대상 실패(`SNAPSHOT_MISMATCH`, `ORDER_SUPERSEDED` 등)로 계속 이뤄지지 않으면 알림도 계속 409를 받는다. 이때 두 서비스의 로그에서 같은 공고·주문을 확인한다.

### revision과 상태 반영

연결된 주문의 알림만 예치 상태와 공고에 반영한다.

- `job_payment_fundings`에 주문별 마지막 적용 revision과 예치 여부를 저장한다. 알림 revision이 저장값보다 낮거나 같으면 200 `STALE_REVISION`으로 수신 사실만 기록하고 예치 상태·공고 상태를 바꾸지 않는다. 처음 오는 주문은 revision 값과 관계없이 적용한다. payment-service는 revision을 1부터 올리지만 앞 revision이 재전송 전에 덮어써질 수 있기 때문이다.
- `funded=false`: `job_posts.funding_blocked=true`로 신규 지원 승인과 신규 자리 예약을 막는다. 공고 상태는 바꾸지 않는다. 상태를 무조건 `PAYMENT_PENDING`으로 되돌리면 마감·취소 공고가 나중의 예치 확인으로 다시 공개될 수 있기 때문이다. 200 `FUNDING_BLOCKED`.
- `funded=true`: 먼저 `funding_blocked=false`로 예치 차단을 해제하고, 공고 상태 전이는 따로 판단한다.
  - `PAYMENT_PENDING`이고 지원 마감과 근무 시작(`workDate + startTime`) 전이면 `OPEN`으로 공개하고, 같은 트랜잭션에서 `job_status_histories`에 `PAYMENT_PENDING → OPEN`, `SYSTEM:FUNDING_CONFIRMED`를 기록한다. 200 `PUBLISHED`.
  - `PAYMENT_PENDING`이지만 근무 시작(`WORK_STARTED`) 또는 지원 마감(`APPLICATION_DEADLINE_PASSED`)이 지났으면 공개하지 않고 이력도 남기지 않는다. 200 `PUBLICATION_SKIPPED`, 환불 검토 대상.
  - `OPEN`·`MATCHING`은 이미 공개된 공고라 전이와 이력이 없다. 200 `FUNDING_CONFIRMED`.
  - `CLOSED`는 다시 열지 않는다. 200 `PUBLICATION_SKIPPED`(`JOB_CLOSED`), 환불 검토 대상.
- 경계 시각과 현재 시각이 같으면 지난 것으로 본다. 현재 시각은 공고 잠금을 얻은 뒤 `Clock`에서 읽는다.
- 더 높은 `funded=true` revision의 차단 해제는 공고 상태 전이와 별개다. 예치가 회복돼도 마감 공고는 재개하지 않는다. 현재 payment-service는 `REVIEW_REQUIRED`가 된 주문에 다시 예치 확인을 보내지 않으므로, 이 경로는 향후 검토 해소 기능을 위한 수신 계약이다.

### 응답 요약

| 상황 | HTTP | 코드·결과 | 기록 |
| --- | --- | --- | --- |
| 처음 처리한 검증된 최신 주문 알림 | 200 | `PUBLISHED`, `FUNDING_CONFIRMED`, `FUNDING_BLOCKED`, `PUBLICATION_SKIPPED`(마감·공개 기한 경과, 환불 검토) | 수신 기록, revision, 상태 |
| 같은 키·같은 요청의 재전송 | 200 | 처음 응답 그대로 | 추가 없음 |
| 다른 키·같은 주문 revision·같은 내용 | 200 | 처음 응답 그대로 | 추가 없음 |
| 낮거나 같은 revision | 200 | `STALE_REVISION` | 수신 기록만 |
| 이전 주문의 알림 | 200 | `STALE_ORDER` | 수신 기록만(`funded=true`면 환불 검토) |
| 주문 연결 대기 | 409 | `JOB-409-013` | 없음. payment-service 재전송으로 연결 후 처리 |
| 관련 없는 주문·스냅샷 불일치 | 409 | `JOB-409-011` | 없음 |
| 같은 키의 다른 요청·다른 공고 | 409 | `JOB-409-004` | 없음 |
| 같은 주문·revision의 다른 내용 | 409 | `JOB-409-012` | 없음(처음 기록 보존) |
| 내부 인증 실패 / 입력 오류 / 공고 없음 | 401 / 400 / 404 | `GLOBAL-401-001` / `GLOBAL-400-*` / `JOB-404-001` | 없음 |
| 처리 중 서버 오류 | 500 | `GLOBAL-500-001` | 전부 롤백 |

payment-service는 200 success만 전송 완료로 보고 그 밖의 응답은 모두 재전송한다. `JOB-409-011`·`JOB-409-012`·404는 재전송으로 해소되지 않을 가능성이 높으므로 경고 로그를 근거로 운영 확인한다.

### 예치 차단 공고의 모집과 노출

- 신규 지원 승인: `OPEN`이어도 예치 차단이면 409 `JOB-409-001`(메시지로 예치 확인 필요를 구분)이다. matching-service가 이미 `APPLICATION-409-003`(지원을 받지 않는 공고)으로 대응하는 코드라 그대로 쓴다. 새 코드는 matching-service에서 503으로 바뀌어 사용자에게 일시 장애처럼 보인다.
- 신규 자리 예약: `OPEN`·`MATCHING`이어도 예치 차단이면 409 `JOB-409-005`다.
- 자리 확정: 공고 잠금 아래에서 예치 차단을 확인하고, 차단 뒤의 `RESERVED → CONSUMED` 최초 확정을 409 `JOB-409-005`로 거절한다. matching-service Saga는 4xx 확정 거절을 확정적 거절로 보고(`MatchingConfirmationClientException.isOutcomeUnknown()`은 5xx·통신 오류만 결과 불명) 채팅·근무·결제·자리 순으로 보상한 뒤 `START_NEW_ATTEMPT`로 남긴다. 자리 반환은 보상 경로이므로 예치 차단과 관계없이 허용한다.
- 이미 성공한 명령: 같은 키의 지원 승인(만료 전)과 자리 예약 재요청은 저장된 결과를, 이미 `CONSUMED`인 예약의 같은 키 확정 재요청은 성공을 그대로 돌려준다. 멱등 재요청 확인은 차단 검사보다 먼저다. 예치 차단은 `CONSUMED` 예약을 반환하거나 취소하지 않는다.
- 확정이 차단보다 먼저 커밋되면 확정 결과를 보존한다. 이미 확정된 매칭·근무와 결제 잠금의 사후 처리(취소·환불·정산)는 별도 복구 정책 대상이다. 차단 전에 발급된 지원 승인은 TTL(기본 5분) 안에 matching-service 지원 저장에 쓰일 수 있지만, 그 지원의 매칭 확정은 자리 예약 단계에서 거절된다.
- 노출: 공개 검색은 신규 지원을 받는 공고만 보여 주므로 예치 차단 공고를 제외한다. 상세 조회는 기존 지원자·매칭 참여자가 계속 볼 수 있도록 공고 상태 기준 정책을 유지한다(`OPEN`·`MATCHING`·`CLOSED`는 공개, `PAYMENT_PENDING`은 점주 본인만). 상세 응답에 예치 차단 여부 필드는 추가하지 않았다.

### 환불 검토 추적

`job_funding_status_receipts.refund_review_required=true`는 공개에 쓰이지 못한 예치 확인(`PUBLICATION_SKIPPED`)과 이전 주문의 예치 확인(`STALE_ORDER`이면서 `funded=true`)이다. 같은 수신 트랜잭션에서 `job_payment_refund_reviews`(V12)에 주문당 한 건을 남기고, 처음 검토 대상이 된 수신 기록(`receipt_id`)과 사유(`STALE_ORDER`, `JOB_CLOSED`, `APPLICATION_DEADLINE_PASSED`, `WORK_STARTED`)를 연결한다. 주문 ID와 수신 기록 ID에 각각 유일 제약이 있어 같은 주문의 이후 알림이 검토 기록을 다시 만들지 않는다. 검토 기록 저장이 실패하면 수신 기록·revision·공고 상태도 함께 롤백된다. V12는 이미 표시된 수신 기록을 주문별 첫 기록 기준으로 옮긴다. 최신 공고 상태는 바꾸지 않고 이전 주문의 금전적 사실만 보존한다.

- 이전 주문의 예치가 교체보다 먼저 기록되는 순서: 공개에 쓰였으면(`PUBLISHED`) 공고가 `OPEN`이라 교체가 거절되고, 공개되지 못했으면(`PUBLICATION_SKIPPED`) 그 시점에 검토 기록이 남고 교체도 거절된다(payment-service의 DEPOSITED 거절, job-service의 예치 반영 확인). 따라서 교체 뒤에 검토 대상이 사라지는 순서는 없다.
- 교체 뒤 늦게 도착한 이전 주문의 예치 확인과 연결하지 않은 주문의 예치 확인은 `STALE_ORDER` 검토 대상이다. 정상 교체 정책에서는 대체된 주문이 예치되지 않으므로 방어적 처리다.

실제 환불·정산은 실행하지 않으며, 후속 기능이 이 기록과 payment-service 주문 상태를 대조해 처리한다. 마감 공고(`JOB_CLOSED`)는 이미 공개·확정된 매칭에 쓰인 예치일 수 있으므로 검토에서 판정한다.

## 예정 급여 계산

`JobWageCalculator`가 공고 도메인의 단일 계산 기준이다. payment-service에 같은 공식을 복제하지 않는다.

초기 계산 기준:

- 근무 분 = 종료 분 - 시작 분 + (`endTimeNextDay`이면 1,440). 1분 이상 1,440분 이하이며 시작·종료 시각은 분 단위여야 한다.
- `extraWage`는 시간당 추가 시급(원/시간)이다. `null`이면 0으로 본다.
- 1인 예정 급여 = ⌊근무 분 × (`baseHourlyWage` + `extraWage`) ÷ 60⌋. 시급을 먼저 합산하고 정수 연산으로 한 번만 원 미만을 버린다. 부동소수점을 쓰지 않는다.
- 전체 예치 예정액 = 1인 예정 급여 × `recruitCount`. 내림한 1인 금액에 곱하므로 자리 예약의 `lockedAmount` 합계와 항상 같다.
- 1인 금액은 1원 이상이어야 하고, 전체 금액은 payment-service 주문 최소 금액인 100원 이상, payment-service 금액 컬럼(DECIMAL(19,2))의 정수부 한도 이하이며 `long` 곱셈 오버플로를 허용하지 않는다. 100원 미만은 올려 맞추지 않고 `JOB-400-004`로 거절한다. 현재 요청 검증의 최저 시급(10,320원)에서는 1분 근무도 1인 172원이므로 등록 API로 100원 미만이 되지 않지만, 계산 기준이 바뀌어도 이 하한은 유지한다.
- 휴게시간은 공고 입력값이 없어 차감하지 않는다.

미결정 정책: 휴게시간 입력과 차감, 시간대(야간·휴일) 가산 기준과 공식, 긴급도 가산, 통화별 최소 단위. 결정되면 새 계산 기준으로 바꾸고 기존 예약 스냅샷 금액은 유지한다.

## 상태와 버전

공고 상태는 `PAYMENT_PENDING`, `OPEN`, `MATCHING`, `CLOSED`가 정의되어 있다. 생성 시 `PAYMENT_PENDING`(비공개)이며, 현재 구현된 전이는 검증된 예치 확인에 따른 `PAYMENT_PENDING → OPEN`(`SYSTEM:FUNDING_CONFIRMED`)과 확정 인원 충족에 따른 `OPEN`/`MATCHING → CLOSED`(`SYSTEM:RECRUITMENT_FILLED`)다. 주문 생성 완료, 결제 조건 변경·재결제의 주문 교체, 예치 차단은 상태를 바꾸지 않는다. 두 전이를 `job_status_histories`에 기록한다. 예치 차단은 상태가 아니라 `funding_blocked`로 저장한다. 수동 마감, 지원 기한 만료 마감, 재오픈 API는 없다. 전체 전이 규칙은 [MVP 도메인 흐름](./mvp-domain-flow.md)을 따른다.

`job_posts.version`은 JPA 낙관적 잠금 값이며 1부터 시작한다. 결제 주문 생성 명령은 공고 저장 직후의 버전(신규 공고는 1)을 결제용 버전으로 고정하고, 주문 연결로 버전이 올라도 공고의 `payment_job_version`은 그 값을 유지한다. 이후 결제 조건 변경은 `@Version`이 아니라 이 공고 명령들의 최대 결제용 버전 + 1을 쓰고, 같은 조건 재결제는 연결된 주문의 버전을 그대로 쓴다. 지원 승인에는 발급 당시 버전을 `jobVersion`으로 저장한다. 모집 완료 전이로 증가한 버전은 알림 명령의 완료 버전이 되어 늦은 지원 차단과 재오픈 구분의 기준이 된다.

지원 승인 상태는 `RESERVED`, `CONSUMED`, `EXPIRED`다. `ApplicationSubmitted` 수신에 따른 `CONSUMED` 처리와 만료 상태 정리는 아직 구현되지 않았다.

자리 예약에는 발급 당시 공고 버전을 `jobVersion`으로 저장한다. 자리 예약과 마지막이 아닌 자리 확정은 공고 상태를 바꾸지 않는다. 마지막 자리 확정은 [모집 완료](#모집-완료)로 공고를 마감한다.

## 영속성

- `industry_categories`: 업종 카테고리. 대분류와 하위 분류를 V4 마이그레이션에서 초기 데이터로 넣는다.
- `job_posts`: 사업장·점주 외부 ID, 카테고리 FK, 근무 일시, 급여, 모집 인원, 위경도, 긴급도, 지원 마감, 상태, 버전, 연결된 결제 주문 ID와 원래 결제 스냅샷(`payment_order_id`, `payment_job_version`, `payment_amount`, `payment_currency`, V10). 네 결제 컬럼은 모두 NULL이거나 모두 값이 있어야 한다(`ck_job_posts_payment_order_snapshot`). 예치 차단 여부 `funding_blocked`(V11, 기본 false). 점주 본인 공고 목록용 `(owner_id, created_at, id)` 인덱스 `idx_job_posts_owner_created`(V15).
- `job_status_histories`: 공고 상태 전이 이력. 예치 확인 공개와 모집 완료 전이를 기록한다. 처리 주체 컬럼이 없어 `reason`에 `주체:사유`로 기록한다.
- `job_application_admissions`: 공고 FK, 알바생 회원 외부 ID, 멱등 키(유일), 공고 버전, 승인 상태, 승인·만료·사용 시각, 발급 당시 공고 스냅샷(점주 회원 ID, 업종 ID, 근무 일시, 위도·경도). 스냅샷 컬럼은 `updatable = false`로 두어 발급 후 바뀌지 않는다.
- `job_matching_seat_reservations`(V8): 공고 FK, 매칭·지원·알바생 외부 ID, 예약·확정·반환 멱등 키(각각 유일), 공고 버전, 상태, 예약·확정·반환·만료 처리 시각과 발급 시 확정한 `expires_at`, 발급 당시 스냅샷(점주 ID, 근무 일시, `end_time_next_day`, `locked_amount` 정수 KRW, `currency`). 인덱스는 공고별 점유 집계·중복 점유 확인용 `(job_post_id, status, expires_at)`과 만료 대상 공고 조회용 `(status, expires_at)`이다.
- `job_recruitment_completion_commands`(V9): 공고 FK, 명령 ID(UUID, 유일), 완료 버전, 전송 상태(`PENDING`/`SUCCEEDED`), 시도 횟수, 다음 시도 시각, 실행권 토큰·만료 시각, 마지막 시도 시각, 마지막 실패 분류·HTTP 상태·응답 코드, 생성·성공 시각. `(job_post_id, job_version)` 유일 제약과 전송 대상 조회용 `(status, next_attempt_at)` 인덱스를 둔다.

- `job_payment_order_commands`(V10): 공고 FK, 발급 순번, 멱등 키(UUID), 결제용 공고 버전, 점주 ID, 금액(정수 KRW), 통화, 처리 상태(`PENDING`/`SUCCEEDED`/`SUPERSEDED`), 검증된 주문 ID, 시도 횟수, 다음 시도 시각, 실행권 토큰·만료 시각, 마지막 시도 시각, 마지막 실패 분류·HTTP 상태·응답 코드, 종료 시각. 유일 제약은 [명령 저장](#명령-저장)을 따르고, 전송 대상 조회용 `(status, next_attempt_at)` 인덱스를 둔다.
- `job_payment_fundings`(V11): 공고 FK, 주문 ID(유일), 마지막 적용 `funding_revision`, 예치 여부, 적용 시각. 연결된 주문의 알림만 생성·갱신한다.
- `job_payment_refund_reviews`(V12): 공고 FK, 주문 ID(유일), 처음 검토 대상이 된 수신 기록 FK(유일), 사유. 생성 후 바꾸지 않는다.
- `job_payment_change_requests`(V13): 공고 FK, 점주 ID, 점주 요청 `Idempotency-Key`(유일), 요청 종류, 발급한 명령 FK(유일), 처리 상태(`PENDING`/`APPLIED`/`REJECTED`), 적용할 결제 조건 스냅샷(근무일·시작/종료 시각·익일 여부·기본 시급·추가 시급·모집 인원·지원 마감), 종료 사유·시각. 요청 식별 필드와 조건 스냅샷은 생성 후 바꾸지 않는다. `job_payment_order_commands.status`에는 교체 거절 종료 `REJECTED`가 추가됐다.
- `job_funding_status_receipts`(V11): 검증을 통과한 예치 상태 알림의 영구 수신 기록. 멱등 키(유일), 공고 FK, 요청 필드 전체(주문 ID, 결제용 버전, 점주, 정수 금액, 통화, revision, 예치 여부), 처리 결과·공개 생략 사유, 처리 직후 공고 상태·차단 여부, 환불 검토 필요 여부, 수신 시각. `(order_id, funding_revision)` 유일 제약과 환불 검토 조회용 `(refund_review_required, received_at)` 인덱스를 둔다. 모든 컬럼은 생성 후 바꾸지 않는다.

사업장·점주·알바생 ID와 결제 주문 ID는 다른 서비스의 원본이므로 물리 FK 없이 외부 ID로만 저장한다.

## 오류 코드

| HTTP | 코드 | 의미 |
| --- | --- | --- |
| 400 | `JOB-400-001` | 근무 종료 시각이 올바르지 않음 |
| 400 | `JOB-400-002` | 지원 마감 시각이 올바르지 않음 |
| 400 | `JOB-400-003` | 검색 조건이 올바르지 않음 |
| 400 | `JOB-400-004` | 급여 계산 금액이 허용 범위를 벗어남 |
| 400 | `JOB-400-005` | 변경된 결제 조건이 없음(같은 조건은 재결제를 사용) |
| 404 | `JOB-404-001` | 존재하지 않는 공고 |
| 404 | `JOB-404-002` | 존재하지 않는 카테고리 |
| 404 | `JOB-404-003` | 공고에 속한 모집 자리 예약이 없음 |
| 409 | `JOB-409-001` | 지원을 받지 않는 공고 |
| 409 | `JOB-409-002` | 지원 마감 시간이 지남 |
| 409 | `JOB-409-003` | 지원 접수 승인 만료 |
| 409 | `JOB-409-004` | 멱등 키를 다른 요청에 재사용 |
| 409 | `JOB-409-005` | 매칭할 수 없는 공고 상태 |
| 409 | `JOB-409-006` | 근무 시작 시각이 지나 자리 예약 불가 |
| 409 | `JOB-409-007` | 남은 모집 자리가 없음 |
| 409 | `JOB-409-008` | 같은 매칭 또는 지원이 이미 자리를 점유 |
| 409 | `JOB-409-009` | 모집 자리 예약 만료 |
| 409 | `JOB-409-010` | 자리 예약 상태와 맞지 않는 요청(반환된 예약 확정, 확정된 예약 반환, 다른 키로 이미 처리됨) |
| 409 | `JOB-409-011` | 공고의 결제 주문과 일치하지 않는 예치 상태 알림(관련 없는 주문, 스냅샷 불일치) |
| 409 | `JOB-409-012` | 같은 주문·revision의 예치 상태 알림 내용이 다름 |
| 409 | `JOB-409-013` | 결제 주문 연결 전이라 예치 상태를 아직 반영할 수 없음(같은 명령으로 재시도) |
| 409 | `JOB-409-014` | 결제 조건을 변경하거나 재결제할 수 없는 공고(공개·마감, 예치 확인·검토가 반영된 주문, 지원·예약 이력, 지원 마감 후 재결제) |
| 409 | `JOB-409-015` | 진행 중인 결제 주문 생성·교체가 있음(결과 확인 후 다시 요청) |

요청 형식·검증 오류와 인증·권한 오류는 공통 `GLOBAL-*` 코드를 사용한다. 예상하지 못한 오류는 원문을 노출하지 않는 500으로 응답한다. 공통 기준은 [오류 처리 검토](./error-handling-review.md)를 따른다.

## 후속 작업

- 환불 검토 대상(`job_payment_refund_reviews`)의 조회·조치 도구와 실제 환불·정산
- 예치 차단 전에 확정된 매칭·근무·결제 잠금의 사후 복구 정책(취소·환불·정산)
- 예치 차단 여부를 공고 상세 응답에 표시할지 여부(현재 필드 없음)
- 실제 토스 테스트 결제창을 거친 공고 공개 E2E(현재 서비스 간 실제 HTTP E2E는 로컬 PG 대역으로만 검증)
- 공개 이후의 공고 수정·재모집 정책, V10 이전 공개 공고의 소급 예치(재결제 경로는 결제 대기 공고만 허용)
- 결과 불명·운영 확인 상태로 남은 결제 변경 요청(`PENDING`)의 조회·조치 도구
- 운영 확인 대상으로 남은 결제 주문 생성 명령의 조회·조치 도구
- 수동 마감, 지원 기한 만료 자동 마감, 재오픈 등 나머지 공고 상태 전이와 그 이력 기록. 재오픈은 버전을 올려 이전 완료 알림과 구분해야 한다.
- 확정된 자리의 취소·환불 정책
- 지원 승인 `CONSUMED` 처리
- member-service 연동을 통한 사업장 소유권 검증
- DB 기반 검색 조건과 페이지 처리

## 실행과 검증

`.env.example`의 `JOB_*` 값을 개인 `.env`에 설정하고 공통 `AUTH_JWT_SECRET`, `INTERNAL_API_SECRET`을 맞춘다. `./scripts/local-run.sh infra`, `./scripts/local-run.sh job`으로 실행한다. HTTP 8083, 로컬 MySQL 3309를 사용한다. Swagger UI는 `/swagger-ui.html`이다. 통합 테스트는 MySQL Testcontainers로 지원 승인 발급·멱등·오류 코드, 모집 자리 예약·확정·반환의 멱등·동시성·만료 경계·제약 변환, 만료 회수, 모집 완료 전이(정원 미충족 시 미마감, 동시 확정 시 1회 기록, 이력·명령 저장 실패 시 함께 롤백, 이전 공고 복구), 알림 전송(실제 소켓의 matching-service 대역으로 경로·헤더·타임아웃·실패 분류, 헤더 전 정지·헤더 지연·본문 일부 후 정지·본문 조금씩 전송에서 전체 제한시간 안의 종료와 실제 연결 닫힘, 반복 초과 시 연결·작업·스레드 미누적, HTTP 대기 중 트랜잭션·잠금 미보유, backoff, 실행권 인계와 늦은 결과 무시, 응답 유실 수렴), 급여 계산(전체 예치액 100원 경계와 오버플로), 결제 대기 비공개 생성(공고·명령 원자적 저장과 명령 저장 실패 시 롤백, 스냅샷 불변), 공개 전 공고의 점주 본인 상세 조회 허용과 타인·익명 404·검색 제외·신규 지원 승인과 자리 예약 거절, 결제 주문 생성 전송(실제 소켓의 payment-service 대역으로 경로·헤더·본문, 응답 envelope·필수 필드·스냅샷·주문 상태 검증, 타임아웃·5xx·잘못된 응답 분류, HTTP 대기 중 트랜잭션·잠금 미보유, 응답 유실 뒤 같은 키·스냅샷으로 원래 주문 복구, 진행된 주문 상태 연결, `@Version` 증가 후 원래 결제용 버전 재시도, 동시 실행, 실행권 만료 인계와 늦은 결과 무시, 과거 명령의 최신 연결 덮어쓰기 방지, 커밋 후 즉시 전송, 안전한 로그), 점주 결제 주문 조회, 결제 조건 변경·재결제(점주 본인 권한과 타인 404, 본문 소유자 무시, 시각·시급·인원·지원 마감·금액 상한 검증, 같은 키 재요청의 진행·처리 상태 반환과 다른 요청 409, 새 결제용 버전·새 키 명령과 대기 조건 스냅샷, FAILED 같은 금액 재결제의 같은 버전·새 키, 진행 중인 주문 생성 중 거절, 공개·차단·예치 반영 공고 거절, 교체 거절 후 기존 조건·주문 보존, 타임아웃·5xx·잘못된 응답 뒤 같은 키 수렴, 결과 저장·요청 저장 실패 롤백, 기록되지 않은 거절의 재판단, 만료된 실행자의 늦은 결과 무시, 연결 전 새 주문 예치 알림 409와 연결 후 새 조건 공개, 이전 주문 알림의 차단·공개 무효와 revision 역전·중복, 변경 대기 중 차단·공개된 공고의 교체 무효, 동시 같은 키·다른 요청, 변경·연결과 예치 수신의 양쪽 잠금 순서 경쟁), 환불 검토 기록(주문당 1건, 첫 수신 기록 연결, 저장 실패 롤백), 예치 상태 수신(실제 등록·주문 생성·연결 경로 뒤 수신 API 호출로 내부 인증·입력 검증·공개와 이력 1건·같은 키 재전송과 공개 후 `@Version` 증가·키 재사용 409·다른 키의 같은 주문 revision 중복 효과 방지·같은 revision 내용 충돌·낮은 revision 뒤 차단 유지와 더 높은 true revision 처리·이전 주문 알림·주문 연결 전 409와 연결 후 같은 명령 처리·스냅샷 불일치·지원 마감과 근무 시작 경계·마감 공고 미공개·동시 수신·중간 실패 원자적 롤백·다른 공고 같은 키 동시 요청의 제약 변환·안전한 로그), 예치 차단과 신규 지원 승인·자리 예약·최초 확정의 양쪽 실행 순서 경쟁, 이미 성공한 지원 승인·예약·확정 명령의 멱등 결과 유지, 차단 공고의 검색 제외와 상세 노출, 점주 본인 공고 목록(다른 점주 공고 제외와 요청 값의 점주 ID 무시, 결제 대기·예치 차단·마감 공고 포함, 상태 필터와 잘못된 상태 값, 같은 등록 시각의 ID 내림차순 정렬, 기본 페이지·크기 상한·범위 밖 페이지, `RESERVED`·`RELEASED`·`EXPIRED`가 섞인 확정 인원 집계와 공고 수와 무관한 쿼리 수, 비로그인 401·알바생 403·잘못된 토큰 401·토큰 엔진 오류 500, `/api/jobs/me`와 숫자 ID 상세 조회의 독립), 요청 바인딩 형 변환 실패의 안전한 오류 메시지, 내부 인증, JWT 오류 경계, Swagger 접근을 확인한다. 시간 경계 테스트는 테스트용 `MutableClock` 빈으로 현재 시각을 고정한다. 설정이 다른 테스트 클래스마다 캐시된 Spring 컨텍스트가 연결 풀을 유지하므로 테스트 MySQL 컨테이너는 `max_connections=500`으로 띄운다. 테스트에서는 전송·복구 스케줄러와 커밋 후 즉시 전송(모집 완료 알림·결제 주문 생성 모두)을 끄고 직접 호출한다. payment-service 대역은 같은 키·본문에 처음 만든 주문을 돌려주는 멱등 응답을 흉내 내며 실제 PG 결제는 하지 않는다. payment-service의 주문 생성 수신 처리는 payment-service 테스트가, matching-service 수신 처리는 matching-service의 `RecruitmentCompletionContractTests`가 검증한다.

### 서비스 간 실제 프로세스 검증(2026-10-06)

job-service와 payment-service를 별도 JVM 프로세스로 실행하고 각자의 일회용 MySQL 컨테이너, 실행마다 새로 만든 테스트 전용 secret, 로컬 토스 PG 대역 서버(`TOSS_API_BASE_URL`, loopback HTTP)를 써서 실제 HTTP로 검증했다. 결제 성공은 payment-service 승인 API → PG 대역 → 예치 반영 → 예치 상태 전송 경로로만 만들었고 DB는 확인용으로만 읽었다. 확인한 흐름: 비공개 생성과 주문 생성·점주 조회의 `paymentOrderId` 연결, 공개 전 익명·타인·알바생 상세 404와 검색 제외·지원 승인 409·자리 예약 409, 테스트 예치(DEPOSITED)와 `funding-status` 전송 후 `OPEN`·이력 1건, 공개 후 상세·검색·지원 승인·자리 예약 허용과 공개 공고의 조건 변경 409, `READY` 주문의 조건 변경(새 버전·새 금액·새 주문, 이전 주문 `SUPERSEDED`와 승인 409, 새 주문 예치 후 새 조건으로 공개), `FAILED` 주문의 같은 조건 재결제(같은 버전·같은 금액·새 키·새 주문, 예치 후 공개), `CONFIRMING` 주문의 조건 변경 거절(`REJECTED`, 기존 조건·주문 유지). 실제 토스 테스트 결제창과 실제 토스 API 승인은 이 실행에 포함되지 않았다. 연결 전 예치 알림, 응답 유실·저장 실패, 잠금 순서 경쟁은 통합 테스트로만 검증했다.
