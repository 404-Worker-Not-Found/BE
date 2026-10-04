# 공고 등록·조회와 지원 접수 승인

## 범위

`job-service`는 공고와 업종 카테고리의 원본을 소유한다. 현재는 점주의 공고 등록, 공고 상세·목록 조회, `matching-service`가 지원 저장 전에 호출하는 지원 접수 승인 내부 API, 매칭 확정 Saga가 호출하는 모집 자리 예약·확정·반환 내부 API와 만료 예약 회수, 1인 예정 급여 계산, 확정 인원 기준의 모집 완료 전이와 matching-service 모집 완료 알림을 제공한다. 수동 마감, 지원 기한 만료에 따른 자동 마감, 재오픈, 결제 예치 후 공개는 후속 작업이다.

## 공개 API

응답은 공통 `ApiResponse`를 따른다. 인증이 필요한 요청은 auth-service가 발급한 Bearer JWT를 사용한다.

| 명령 | 경로 | 인증 | 요청/응답 |
| --- | --- | --- | --- |
| 공고 등록 | `POST /api/jobs` | `OWNER` 역할 | 사업장·카테고리·근무 일시·급여·모집 인원·위경도·긴급도·지원 마감 / `data` 공고 ID |
| 상세 조회 | `GET /api/jobs/{id}` | 없음 | 가게·카테고리 이름·주소·급여·근무 일시와 시간·설명·모집 인원 |
| 목록 조회 | `GET /api/jobs/search` | 없음 | 위치·거리·급여·시작 시각·카테고리·유형·페이지 조건 / 공고 카드 목록과 페이지 정보 |

공고 등록 규칙:

- 점주 회원 ID는 요청 본문이 아니라 JWT의 `memberId`를 사용한다.
- 근무 시간은 분 단위로 1분 이상 24시간 이하여야 한다. 자정을 넘으면 `isEndTimeNextDay=true`로 보내며 이때 종료 시각은 시작 시각 이하다. 초 단위가 있는 시각, 익일 플래그 없이 종료가 시작 이전인 구간, 익일 플래그로 24시간을 넘는 구간은 `JOB-400-001`이다.
- 1인 예정 급여와 전체 예치 예정액(아래 [예정 급여 계산](#예정-급여-계산))을 계산할 수 없거나 허용 범위를 넘으면 `JOB-400-004`다.
- 지원 마감은 현재 이후이고 근무 시작 이전이어야 한다.
- 시급은 10,320원 이상, 모집 인원은 1명 이상이다. 카테고리는 저장된 ID여야 한다.
- 사업장 소유권 검증은 `BusinessValidator` 포트 뒤에 있다. 현재 구현체 `StubBusinessValidator`는 경고 로그만 남기고 통과시키므로 실제 소유권 검증으로 간주하지 않는다.
- 등록 즉시 `OPEN`으로 저장한다. 결제 예치 후 공개 정책은 아직 반영되지 않았다.

목록 조회 규칙:

- `OPEN`이고 지원 마감 전인 공고만 반환한다.
- `type=URGENT`이면 긴급도 `HIGH` 공고만 마감 임박 순으로 정렬한다. 그 외에는 거리 오름차순이며 거리를 계산할 수 없는 공고는 뒤에 둔다.
- `maxDistanceKm`을 사용하려면 `workerLat`, `workerLng`가 필요하다. `minWage`는 `maxWage`보다 클 수 없다.
- 기본 페이지는 0, 크기는 20, 최대 크기는 100이다.
- 후보 공고를 모두 조회한 뒤 애플리케이션 메모리에서 필터·정렬·페이지 처리한다. 공고 수가 늘어나면 DB 조건 조회와 공간 인덱스로 바꿔야 한다.

상세 조회의 `applicantCount`는 원본이 `matching-service`에 있고 조회 계약이 없으므로 현재 `null`이다. 상세 조회는 공고 상태와 관계없이 반환한다.

## 내부 API

`/api/jobs/internal/**`는 `X-Internal-Secret`이 일치해야 하며, 불일치하면 401 `GLOBAL-401-001`이다. 보안 설정의 `permitAll`은 이 필터가 먼저 검증한 뒤 적용된다.

같은 `job.internal.secret`(`INTERNAL_API_SECRET`)을 matching-service로 보내는 요청 헤더에도 쓴다. 비어 있거나 공백·제어 문자·비ASCII 문자가 있는 값, 즉 출력 가능한 ASCII(0x21~0x7E)가 아닌 문자가 들어간 값은 시작 시 거절한다. 줄바꿈 같은 값은 HTTP 헤더로 보낼 수 없고, 앞뒤 공백이나 비ASCII 문자는 받는 쪽 비교와 어긋날 수 있다. 값을 잘라 내거나 보정하지 않으며, 시작 실패 보고와 오류 메시지에 값을 포함하지 않는다.

| 명령 | 경로 | 요청/응답 |
| --- | --- | --- |
| 지원 접수 승인 | `POST /api/jobs/internal/{jobPostId}/application-admissions` | `workerMemberId` / `admissionId`, `jobPostId`, `jobVersion`, `ownerMemberId`, `categoryId`, `workDate`, `startTime`, `endTime`, `latitude`, `longitude`, `admittedAt`, `expiresAt` |
| 모집 자리 예약 | `POST /api/jobs/internal/{jobPostId}/matching-seat-reservations` | `matchingId`, `applicationId`, `workerMemberId` / `reservationId`(문자열), `jobPostId`, `jobVersion`, `ownerMemberId`, `workDate`, `startTime`, `endTime`, `endTimeNextDay`, `lockedAmount`, `currency`, `reservedAt`, `expiresAt` |
| 모집 자리 확정 | `POST /api/jobs/internal/{jobPostId}/matching-seat-reservations/{reservationId}/confirm` | 본문 없음 / `reservationId`, `status` |
| 모집 자리 반환 | `POST /api/jobs/internal/{jobPostId}/matching-seat-reservations/{reservationId}/release` | 본문 없음 / `reservationId`, `status` |

`Idempotency-Key`는 1~100자의 공백 없는 ASCII다. 공고 행을 비관적 잠금으로 잡은 뒤 같은 키의 승인을 조회한다. 따라서 같은 공고에 같은 키로 온 동시 요청은 앞선 요청의 결과를 본다. 공고가 서로 다르면 잠그는 행도 달라 조회가 직렬화되지 않으므로, `uk_job_application_admissions_idempotency_key` 유일 제약이 마지막 방어선이다.

- 같은 키와 같은 공고·회원 요청이면 기존 승인을 반환한다. 만료됐거나 `RESERVED`가 아니면 `JOB-409-003`이다.
- 같은 키를 다른 공고나 회원 요청에 재사용하면 `JOB-409-004`다. 동시 요청이 유일 제약에서 걸린 경우에도 같은 코드로 응답한다.
- 제약 위반 변환은 저장 트랜잭션이 롤백된 뒤 전역 HTTP 예외 처리 경계인 `GlobalExceptionHandler`에서 한다. 서비스는 저장 예외를 변환하지 않고 전파한다. cause 사슬 전체에서 Hibernate `ConstraintViolationException`을 찾아 제약 이름이 정확히 일치할 때만 `JOB-409-004` 응답으로 변환한다. 중간에 다른 제약 위반을 만나도 탐색을 계속하고, 순환 참조에서는 종료한다. 대상 제약이 없는 무결성 위반은 서버 오류로 남긴다. MySQL이 반환하는 `테이블명.제약명`에서는 테이블명 접두사를 제거하고 비교한다.
- 새 승인은 공고가 `OPEN`이고 지원 마감 전일 때만 발급한다. 승인 생성 시각이 지원 접수 시점이다.
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
- 공고 상태는 `OPEN` 또는 `MATCHING`이어야 한다([MVP 도메인 흐름](./mvp-domain-flow.md)의 `MATCHING`은 지원 접수를 멈추고 매칭을 진행하는 상태다). 그 외 상태는 `JOB-409-005`다.
- 자리 예약 기한은 지원 마감이 아니라 근무 시작 시각(`workDate + startTime`)이다. 지원 마감 이후에도 기존 지원자의 매칭 확정은 가능하며, 근무 시작 이후에는 `JOB-409-006`이다.
- 같은 잠금 안에서 이 공고의 만료된 `RESERVED`를 먼저 `EXPIRED`로 회수한다. 스케줄러가 늦어도 만료 자리가 새 예약을 막지 않는다.
- 같은 공고에서 같은 `matchingId` 또는 `applicationId`가 `RESERVED`·`CONSUMED`로 자리를 점유하고 있으면 다른 키라도 `JOB-409-008`이다. 종료된(`RELEASED`·`EXPIRED`) 시도 이후에는 새 키로 다시 예약할 수 있다. `matchingId`에 영구 유일 제약을 두지 않는다.
- 점유 수가 `recruitCount` 이상이면 `JOB-409-007`이다.
- 응답 스냅샷은 발급 당시 공고의 버전, 점주 ID, 근무 일시, `endTimeNextDay`, 1인 예정 급여(`lockedAmount`, 정수 KRW), 통화 `KRW`다. 스냅샷과 요청 식별 필드는 `updatable = false`다.
- `expiresAt = reservedAt + MATCHING_SEAT_RESERVATION_TTL`(기본 10분, 지원 접수 승인 TTL과 별도 설정)이며 재요청으로 연장하지 않는다. 시각 정밀도는 아래 [시각 저장 정밀도](#시각-저장-정밀도)를 따른다.

### 확정

- 예약이 경로의 공고에 속하지 않거나 없으면 `JOB-404-003`이다.
- 같은 확정 키가 다른 예약의 확정에 이미 쓰였으면 `JOB-409-004`다.
- 유효한 `RESERVED`는 `CONSUMED`로 바꾸고 확정 키와 시각을 기록한다.
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

## 예정 급여 계산

`JobWageCalculator`가 공고 도메인의 단일 계산 기준이다. payment-service에 같은 공식을 복제하지 않는다.

초기 계산 기준:

- 근무 분 = 종료 분 - 시작 분 + (`endTimeNextDay`이면 1,440). 1분 이상 1,440분 이하이며 시작·종료 시각은 분 단위여야 한다.
- `extraWage`는 시간당 추가 시급(원/시간)이다. `null`이면 0으로 본다.
- 1인 예정 급여 = ⌊근무 분 × (`baseHourlyWage` + `extraWage`) ÷ 60⌋. 시급을 먼저 합산하고 정수 연산으로 한 번만 원 미만을 버린다. 부동소수점을 쓰지 않는다.
- 전체 예치 예정액 = 1인 예정 급여 × `recruitCount`. 내림한 1인 금액에 곱하므로 자리 예약의 `lockedAmount` 합계와 항상 같다.
- 1인 금액은 1원 이상이어야 하고, 전체 금액은 payment-service 금액 컬럼(DECIMAL(19,2))의 정수부 한도 이하이며 `long` 곱셈 오버플로를 허용하지 않는다.
- 휴게시간은 공고 입력값이 없어 차감하지 않는다.

미결정 정책: 휴게시간 입력과 차감, 시간대(야간·휴일) 가산 기준과 공식, 긴급도 가산, 통화별 최소 단위. 결정되면 새 계산 기준으로 바꾸고 기존 예약 스냅샷 금액은 유지한다.

## 상태와 버전

공고 상태는 `OPEN`, `MATCHING`, `CLOSED`가 정의되어 있다. 생성 시 `OPEN`이며, 현재 구현된 전이는 확정 인원 충족에 따른 `OPEN`/`MATCHING → CLOSED`뿐이다. 이 전이만 `job_status_histories`에 기록한다. 수동 마감, 지원 기한 만료 마감, 재오픈 API는 없다. 전체 전이 규칙은 [MVP 도메인 흐름](./mvp-domain-flow.md)을 따른다.

`job_posts.version`은 JPA 낙관적 잠금 값이며 1부터 시작한다. 지원 승인에는 발급 당시 버전을 `jobVersion`으로 저장한다. 모집 완료 전이로 증가한 버전은 알림 명령의 완료 버전이 되어 늦은 지원 차단과 재오픈 구분의 기준이 된다.

지원 승인 상태는 `RESERVED`, `CONSUMED`, `EXPIRED`다. `ApplicationSubmitted` 수신에 따른 `CONSUMED` 처리와 만료 상태 정리는 아직 구현되지 않았다.

자리 예약에는 발급 당시 공고 버전을 `jobVersion`으로 저장한다. 자리 예약과 마지막이 아닌 자리 확정은 공고 상태를 바꾸지 않는다. 마지막 자리 확정은 [모집 완료](#모집-완료)로 공고를 마감한다.

## 영속성

- `industry_categories`: 업종 카테고리. 대분류와 하위 분류를 V4 마이그레이션에서 초기 데이터로 넣는다.
- `job_posts`: 사업장·점주 외부 ID, 카테고리 FK, 근무 일시, 급여, 모집 인원, 위경도, 긴급도, 지원 마감, 상태, 버전.
- `job_status_histories`: 공고 상태 전이 이력. 현재 모집 완료 전이만 기록한다. 처리 주체 컬럼이 없어 `reason`에 `주체:사유`로 기록한다.
- `job_application_admissions`: 공고 FK, 알바생 회원 외부 ID, 멱등 키(유일), 공고 버전, 승인 상태, 승인·만료·사용 시각, 발급 당시 공고 스냅샷(점주 회원 ID, 업종 ID, 근무 일시, 위도·경도). 스냅샷 컬럼은 `updatable = false`로 두어 발급 후 바뀌지 않는다.
- `job_matching_seat_reservations`(V8): 공고 FK, 매칭·지원·알바생 외부 ID, 예약·확정·반환 멱등 키(각각 유일), 공고 버전, 상태, 예약·확정·반환·만료 처리 시각과 발급 시 확정한 `expires_at`, 발급 당시 스냅샷(점주 ID, 근무 일시, `end_time_next_day`, `locked_amount` 정수 KRW, `currency`). 인덱스는 공고별 점유 집계·중복 점유 확인용 `(job_post_id, status, expires_at)`과 만료 대상 공고 조회용 `(status, expires_at)`이다.
- `job_recruitment_completion_commands`(V9): 공고 FK, 명령 ID(UUID, 유일), 완료 버전, 전송 상태(`PENDING`/`SUCCEEDED`), 시도 횟수, 다음 시도 시각, 실행권 토큰·만료 시각, 마지막 시도 시각, 마지막 실패 분류·HTTP 상태·응답 코드, 생성·성공 시각. `(job_post_id, job_version)` 유일 제약과 전송 대상 조회용 `(status, next_attempt_at)` 인덱스를 둔다.

사업장·점주·알바생 ID는 다른 서비스의 원본이므로 물리 FK 없이 외부 ID로만 저장한다.

## 오류 코드

| HTTP | 코드 | 의미 |
| --- | --- | --- |
| 400 | `JOB-400-001` | 근무 종료 시각이 올바르지 않음 |
| 400 | `JOB-400-002` | 지원 마감 시각이 올바르지 않음 |
| 400 | `JOB-400-003` | 검색 조건이 올바르지 않음 |
| 400 | `JOB-400-004` | 급여 계산 금액이 허용 범위를 벗어남 |
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

요청 형식·검증 오류와 인증·권한 오류는 공통 `GLOBAL-*` 코드를 사용한다. 예상하지 못한 오류는 원문을 노출하지 않는 500으로 응답한다. 공통 기준은 [오류 처리 검토](./error-handling-review.md)를 따른다.

## 후속 작업

- `PAYMENT_PENDING` 비공개 생성, 결제 주문 생성(전체 예치 예정액은 `JobWageCalculator` 사용), 예치 상태 수신 후 공개 ([토스 예치 설계](./toss-deposit-design.md))
- 수동 마감, 지원 기한 만료 자동 마감, 재오픈 등 나머지 공고 상태 전이와 그 이력 기록. 재오픈은 버전을 올려 이전 완료 알림과 구분해야 한다.
- 확정된 자리의 취소·환불 정책
- 지원 승인 `CONSUMED` 처리
- member-service 연동을 통한 사업장 소유권 검증
- DB 기반 검색 조건과 페이지 처리

## 실행과 검증

`.env.example`의 `JOB_*` 값을 개인 `.env`에 설정하고 공통 `AUTH_JWT_SECRET`, `INTERNAL_API_SECRET`을 맞춘다. `./scripts/local-run.sh infra`, `./scripts/local-run.sh job`으로 실행한다. HTTP 8083, 로컬 MySQL 3309를 사용한다. Swagger UI는 `/swagger-ui.html`이다. 통합 테스트는 MySQL Testcontainers로 지원 승인 발급·멱등·오류 코드, 모집 자리 예약·확정·반환의 멱등·동시성·만료 경계·제약 변환, 만료 회수, 모집 완료 전이(정원 미충족 시 미마감, 동시 확정 시 1회 기록, 이력·명령 저장 실패 시 함께 롤백, 이전 공고 복구), 알림 전송(실제 소켓의 matching-service 대역으로 경로·헤더·타임아웃·실패 분류, 헤더 전 정지·헤더 지연·본문 일부 후 정지·본문 조금씩 전송에서 전체 제한시간 안의 종료와 실제 연결 닫힘, 반복 초과 시 연결·작업·스레드 미누적, HTTP 대기 중 트랜잭션·잠금 미보유, backoff, 실행권 인계와 늦은 결과 무시, 응답 유실 수렴), 급여 계산, 내부 인증, JWT 오류 경계, Swagger 접근을 확인한다. 시간 경계 테스트는 테스트용 `MutableClock` 빈으로 현재 시각을 고정한다. 테스트에서는 전송·복구 스케줄러와 커밋 후 즉시 전송을 끄고 직접 호출한다. matching-service 수신 처리는 matching-service의 `RecruitmentCompletionContractTests`가 검증한다.
