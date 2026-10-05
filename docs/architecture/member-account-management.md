# 회원 정보 수정과 계정 관리

## 구현된 수정 범위

`PATCH /api/members/me`는 JWT의 memberId에 해당하는 ACTIVE 회원만 수정한다.
생략하거나 null인 필드는 유지하고, 목록을 전달하면 그 목록으로 교체한다.

- 공통: name(1~50자, 공백만 입력 불가).
- WORKER: workerProfile.desiredHourlyWage, activityRadiusKm, immediatelyAvailable, baseLocation, preferredBusinessTypes, availableTimes.
- OWNER: ownerProfile.storeName, businessType, storeLocation.
- location은 address/detailAddress/latitude/longitude를 함께 전달한다. detailAddress는 null로 지울 수 있다.
- 역할이 다른 프로필은 거절한다. 이메일·전화번호·역할·사업자등록번호·사업자 검증 상태·가입일은 이 API로 변경하지 않는다.
- 사업자등록번호 변경은 별도 사업자 재검증 계약이 필요하므로 일반 수정 범위에서 제외한다.
- worker 목록은 비어 있을 수 없으며 가입과 같은 개수·문자열 제한을 적용한다. 중복 업종과 중복/역전 시간은 거절한다.
- 기존 공고·지원·매칭·근무의 스냅샷은 이 변경으로 소급 수정하지 않는다.

예시:

```json
{
  "name": "김알바",
  "workerProfile": {
    "desiredHourlyWage": 13000,
    "immediatelyAvailable": false,
    "preferredBusinessTypes": ["CAFE", "RESTAURANT"]
  }
}
```

## 연락처 변경

일반 가입 인증과 별도의 CONTACT_CHANGE 목적을 사용한다. 아래 API 모두 JWT가 필요하다.

| API | 요청 / 응답 |
| --- | --- |
| POST /api/auth/account/email-verifications/send | {"email":"new@example.com"} |
| POST /api/auth/account/sms-verifications/send | {"phoneNumber":"01012345678"} |
| POST /api/auth/account/contact-changes | UUID Idempotency-Key 헤더 + {"channel":"EMAIL 또는 PHONE","target":"새 연락처","verificationCode":"6자리 코드"} |
| GET /api/auth/account/contact-changes/{commandId} | 본인 요청의 PENDING / SUCCEEDED / REJECTED 상태 |

인증번호는 변경할 연락처에 발송한다. 이메일 5분, SMS 3분 유효하며 실패 5회 제한,
동일 목적·연락처 발송 1분 제한을 적용한다. Redis Lua로 코드 소비와 실패 횟수 처리를 원자적으로 수행한다.
가입 verified flag나 PASSWORD_RESET 코드는 연락처 변경을 승인하지 않는다.

auth-service는 계정 행 잠금 아래 코드 소비, 명령 저장, UPDATING 상태 전환,
모든 기기의 refresh token 폐기를 처리한다. 이메일 변경은 새 이메일을 auth DB의 unique 제약으로 먼저 확보한다.
그 후 DB 트랜잭션 밖에서 member-service 내부 변경 API를 호출한다.
member-service는 회원 행 잠금 아래 연락처와 명령 처리 결과를 함께 저장한다.
동일 명령의 성공·거절 결과는 고정되고, 이전 명령 재전송으로 이후 연락처를 덮어쓰지 않는다.

응답의 commandId/memberId/channel/target/accepted와 ApiResponse.success를 확인한 뒤
성공 시 ACTIVE, 확정 거절 시 이전 이메일과 ACTIVE를 복원한다. 완료된 auth 명령에서 이전/새 연락처 원문을 지운다.
타임아웃·서버 오류·잘못된 응답은 PENDING을 유지한다. DB에 다음 시도 시각을 저장하고
15초부터 최대 300초까지 재시도 간격을 늘린다. 프로세스 재시작 후에도 같은 명령을 재전송한다.
처리 중에는 LOCAL/OAuth 로그인과 토큰 발급·재발급을 막는다. 완료 후 다시 로그인해야 한다.
기존 stateless access token은 만료까지 유효하며 처리 상태 조회에도 사용할 수 있다.

member-service의 `PATCH /api/members/internal/{memberId}/contact`는 X-Internal-Secret과 UUID Idempotency-Key를 요구한다.
직접 호출하는 프런트 API가 아니며 인증번호 확인을 마친 auth-service만 사용한다.
회원 기본 정보 조회·프로필 수정은 계속 member-service가 담당한다.

## 확정된 탈퇴 정책과 API

2026-10-06 사용자 결정: 진행 중 지원·매칭·근무·결제가 있으면 탈퇴를 차단한다.
지원·근무를 자동 취소하거나 임의로 환불하지 않는다.

- `POST /api/auth/account/withdrawals`: 본인 JWT + UUID `Idempotency-Key`, 202 응답.
- `GET /api/auth/account/withdrawals/{commandId}`: 본인 요청의 상태와 차단 서비스를 조회한다.
- CHECKING → FINALIZING → SUCCEEDED 또는 CHECKING → RELEASING → REJECTED.
- SUCCEEDED 이후에는 기존 JWT로도 상태 조회를 포함한 보호 API를 사용할 수 없다.
- REJECTED이면 계정을 ACTIVE로 돌리고 새 키로 재요청할 수 있다. 폐기한 refresh token은 복원하지 않는다.
- 불명확한 원격 결과는 진행 상태로 남고 재시도한다. 서버 재시작 시에도 명령을 복구한다.

| 서비스 | 차단 내역 |
| --- | --- |
| job | CLOSED가 아닌 점주 공고, 유효 RESERVED 지원 승인, RESERVED 모집 자리, 미완료 주문/모집 완료 전달 |
| matching | APPLIED 지원, PENDING 제안, PROCESSING/COMPENSATING 확정 Saga |
| work | COMPLETED/CANCELED/FAILED/NO_SHOW가 아닌 근무 |
| payment | FAILED/SUPERSEDED가 아닌 주문, LOCKED 결제, 잔액·잠금·확인 필요 상태의 예치금 |

확정된 매칭과 선택된 지원은 work/payment의 종료 상태로 함께 검사한다.
현재 최종 환불·정산 계약이 없으므로 DEPOSITED 주문은 탈퇴를 계속 차단한다.
RELEASED 잠금은 환불이나 정산 종료의 증거로 취급하지 않는다.
필요한 서비스가 응답하지 않아도 탈퇴를 완료하지 않는다.

각 서비스는 본인 DB의 account_gates 행과 명령별 receipt를 사용한다.
준비 검사와 신규 생성이 같은 회원 잠금을 사용하므로 검사 직후 새 거래가 생기는 경쟁을 막는다.
두 회원이 참여하는 생성은 회원 ID 오름차순으로 잠근다. 기존 도메인 행 잠금 순서는 유지한다.
준비 상태는 회원의 HTTP/JWT 접근과 기존 WebSocket의 수신·구독도 막는다.
거절 시 모든 서비스에 release를 보내고, 이미 시작한 확정 단계는 release하지 않고 commit을 재전송한다.
예전 release/prepare 요청은 나중 탈퇴 명령의 장벽을 해제하거나 회원을 다시 활성화하지 못한다.
성공한 내부 생성 명령의 멱등 재조회는 유지하되 새 리소스 생성은 장벽 뒤에서 거절한다.

내부 경로는 `/api/{domain}/internal/account-withdrawals/{memberId}/{prepare|release|commit}`이고
본문은 UUID commandId다. matching의 domain은 applications다.
모든 경로는 공유 X-Internal-Secret을 검사한다. notification도 동일한 INTERNAL_API_SECRET 설정을 사용한다.
응답의 memberId/commandId/state와 성공 여부가 요청에 일치해야 다음 단계로 넘어간다.
외부 호출은 DB 트랜잭션 밖에서 수행하며 DB 임대 180초로 coordinator 중복 실행을 막는다.
호출당 연결 2초·읽기 5초, 영속 재시도는 15초부터 최대 300초다.
각 소유 서비스 URL은 기존 *_SERVICE_BASE_URL 및 NOTIFICATION_SERVICE_BASE_URL로 설정한다.

## 데이터 삭제·보존 기준

탈퇴 완료는 auth/member 개인정보 삭제와 서비스별 장벽 확정을 모두 확인한 상태다.

| 분류 | 처리 기준 |
| --- | --- |
| 이름·이메일·전화번호 | 즉시 제거. 이름은 탈퇴회원 표시로 대체, unique 이메일·전화번호는 NULL로 해제 |
| 점주·알바생 프로필·위치·선호 업종·가능 시간 | 즉시 삭제. orphanRemoval로 자식 데이터와 위치까지 제거 |
| LOCAL 자격 증명·OAuth 연결·모든 refresh token | auth에서 즉시 삭제 |
| 연락처 변경 명령·처리 영수증의 연락처 fingerprint | auth/member에서 삭제 |
| 회원 ID·역할·가입/탈퇴·상태와 기술적 명령 영수증 | 거래 참조 및 재시도 판별용 tombstone/감사 메타데이터로 보존. 회원 ID 재사용 금지 |
| 이메일 인증 캐시 | auth에서 삭제. 전화번호 검증 캐시는 기존 TTL(코드 3분, 가입 verified flag 최대 30분)로 소멸 |
| 공고·지원·매칭·근무·결제 상태/금액/시각/참조 ID 및 계약 스냅샷 | 계정 탈퇴로 연쇄 삭제하지 않고 각 소유 서비스의 거래 증빙으로 보존 |
| 채팅·알림 | 상대방 대화/이벤트 기록을 탈퇴로 삭제하지 않는다. 탈퇴 회원의 접근은 장벽으로 차단 |

계약 및 결제·공급 증빙은 종료 후 5년, 별도 불만·분쟁 기록은 종결 후 3년의 보존 기준을 사용한다.
기간·범위를 정할 때 참고한 원문은 [전자상거래법 시행령 제6조](https://www.law.go.kr/lsLinkCommonInfo.do?lsJoLnkSeq=1018638379)다.
플랫폼에 적용되는 구체적 법적 지위·추가 의무는 운영 시 별도 검토한다.
단순 프로필·선호 정보는 증빙 보존 대상에 포함하지 않는다.
일반 채팅·알림과 무기한 tombstone 보존을 법정 의무로 간주하지 않는다.
기록의 기간 만료 정리와 별도 증빙 보관소/분쟁 보존 예외는 소유 도메인의 운영 작업이며 이 계정 API에 삭제 권한을 주지 않는다.
현재 구현은 계정 개인정보 제거와 거래 이력 보존을 수행하고, 거래 이력의 기간 만료 자동 파기는 아직 구현하지 않는다.
