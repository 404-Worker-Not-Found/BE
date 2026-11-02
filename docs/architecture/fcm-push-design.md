# FCM 푸시 발송 (#156)

## 범위

notification-service가 새로 저장하는 매칭·지원 결과, 근무 상태, 예치 이상 알림을 Android FCM으로 보낸다. 기존 JWT와 회원·역할별 인앱 알림 계약은 유지한다. 과거 알림을 소급 발송하거나 기기 등록 시 보관함 전체를 보내지 않는다. 채팅 메시지는 이 서비스에 알림 이벤트를 생산하지 않으므로 이번 발송 대상에 포함되지 않는다.

## 앱 연동

모든 요청은 `Authorization: Bearer <accessToken>`을 사용한다.

| API | 요청 | 응답 |
| --- | --- | --- |
| `PUT /api/notifications/me/device-tokens` | `{"token":"FCM에서 받은 토큰","platform":"ANDROID"}` | 공통 응답의 `data.deviceTokenId` |
| `DELETE /api/notifications/me/device-tokens/{deviceTokenId}` | 본인이 등록한 ID | 공통 성공 응답, `data=null` |

- 로그인 직후 현재 FCM 토큰을 등록한다. 토큰 갱신 시 새 토큰을 등록하고 이전 `deviceTokenId`를 삭제한다. 로그아웃 전에는 해당 기기의 ID만 삭제한다. JWT가 없는 동안 발급된 토큰은 앱이 보관했다가 로그인 후 등록한다.
- 등록 토큰은 최대 4096자의 ASCII 가시 문자이며 공백·제어 문자를 거부한다. 토큰의 접두사나 내부 형식을 추정하지 않는다. Android만 지원한다. 원문은 요청 본문과 서비스 전용 DB에서만 다루고 응답·URL·로그에 넣지 않는다.
- 같은 토큰·같은 회원·같은 역할의 반복 등록은 같은 ID와 세대를 유지한다. 한 회원의 여러 토큰을 지원한다. 같은 휴대폰에서 계정이나 역할을 전환하면 같은 토큰의 소유자를 현재 JWT 주체로 바꾸고 세대를 증가시킨다. 마지막으로 등록한 회원·역할이 해당 토큰을 사용한다.
- 다른 회원·다른 역할·없는 ID 삭제는 변경 없이 성공한다. 다른 사람의 기기 존재 여부를 응답하지 않는다. 삭제·탈퇴·FCM UNREGISTERED 시 토큰을 비활성화하고 DB 원문을 비운다. 해시와 ID는 발송 이력 연결에 남긴다.
- Android 앱은 알림 권한과 FCM SDK를 별도로 설정해야 한다. 앱이 전면에 있으면 `onMessageReceived`에서 표시·동기화를 처리한다. 백그라운드에서 표시되는 제목은 `Worker Not Found`, 본문은 민감정보 없는 공통 문구다.
- 데이터의 `notificationId`와 `type`으로 알림 목록을 재조회하고 이동 화면을 결정한다. 중복 수신은 `notificationId`로 제거한다. Android 알림 tag도 같은 ID를 사용한다. 푸시는 상태의 최종 근거가 아니며 실제 내용과 접근 권한은 REST에서 확인한다.

## 저장과 전송

`NotificationWriter`는 기존 이벤트 수신 트랜잭션 안에서 인앱 알림과 `notification_push_deliveries`를 함께 저장한다. 발송 대상은 그 시점의 활성 토큰과 세대다. 이후 새로 등록한 기기나 새 계정에 과거 작업을 넘기지 않는다. 이벤트 중복 수신은 기존 receipt/알림 멱등성으로 막고, `(notification_id,device_token_id,token_generation)` 유일 제약도 둔다. 대기열 저장 실패는 receipt와 인앱 알림까지 함께 롤백하므로 Redis 재수신으로 복구한다.

외부 FCM 호출은 DB 트랜잭션 밖에서 수행한다. 5초 간격으로 최대 20개를 처리하고, 각 작업은 `FOR UPDATE SKIP LOCKED`로 짧게 잠근 뒤 임대한다. 10분 임대와 매 실행의 새 UUID로 완료 처리를 제한한다. 이전 작업자의 늦은 결과는 새 임대나 토큰 세대를 변경하지 못한다. 푸시 스케줄러는 이벤트 소비 스케줄러와 별도 스레드를 사용한다.

작업을 임대할 때 토큰 활성 상태·세대·현재 회원·역할과 계정 장벽을 다시 확인한다. 로그아웃, 계정 전환, 탈퇴 등으로 달라진 대상은 취소한다. 탈퇴 확정은 장벽과 같은 트랜잭션에서 모든 토큰을 비활성화한다. 대상 검사 이후 로그아웃·계정 전환과 외부 전송이 경합하면 전송 취소를 보장할 수 없어 공통 본문만 사용하며, 알림 조회는 기존 JWT 권한을 다시 확인한다.

## 실패와 중복

- 최대 8번 시도하고 첫 작업 생성 후 24시간이 지나면 보내지 않는다. 이는 MVP 전송 정책이며 법적 알림 전달 보장은 아니다.
- 일시 오류·인증 설정 오류는 30초부터 최대 1시간 지수 백오프로 재시도한다. FCM quota 오류는 최소 60초, 제공자의 `Retry-After`가 더 길면 그 시간을 따른다. 24시간을 넘는 대기는 작업 만료로 끝난다.
- `UNREGISTERED`만 해당 세대 토큰을 비활성화한다. `INVALID_ARGUMENT`나 `SENDER_ID_MISMATCH`는 작업 실패로 남기며 토큰을 임의 삭제하지 않는다. 나머지 오류의 상세 메시지·응답 본문·토큰은 로그에 넣지 않는다.
- `SENT`는 FCM이 요청을 수락했다는 뜻이며 휴대폰 표시나 사용자 읽음을 보장하지 않는다. 수락 후 결과 저장 전에 프로세스가 종료되면 재전송될 수 있다. 외부 FCM과 DB 사이의 정확히 한 번 전달은 보장하지 않는다.
- `FAILED`/`CANCELED` 작업은 자동으로 되살리지 않는다. 운영자는 오류 코드와 시도 수를 확인해야 한다. 재처리 API와 발송 이력 보존·삭제 정책은 후속 범위다.

## 실행과 배포

기본값은 `NOTIFICATION_PUSH_ENABLED=false`다. 이 상태에서는 Firebase SDK를 초기화하거나 FCM을 호출하지 않지만, 등록된 기기에는 새 알림의 발송 작업을 보관한다. 재활성화 시 유효기간 안의 대기 작업을 처리한다.

실제 발송을 켜려면 선행 배포 설정 PR #155(#153)를 반영하고, 서버의 서비스 계정 파일을 알림 컨테이너에 읽기 전용으로 연결한다. `GOOGLE_APPLICATION_CREDENTIALS`는 JSON 원문이 아닌 컨테이너 내부 파일 경로다. `.env.deploy`에 `NOTIFICATION_PUSH_ENABLED=true`를 설정하고 이 기능이 포함된 새 알림 이미지를 배포해야 한다. V5 마이그레이션은 기기 토큰·대기열 테이블만 추가하며 기존 V1~V4는 수정하지 않는다. 설정만으로 기존 서버 이미지에 기능이 생기지 않는다.

활성화 상태에서 인증 파일이 없거나 잘못된 경우 시작을 실패시킨다. 토큰과 서비스 계정은 같은 Firebase 프로젝트를 사용해야 한다. 테스트는 MySQL·Redis Testcontainers와 가짜 전송 경계로 수행하며 실제 서비스 계정이나 휴대폰 토큰을 사용하지 않는다. 실제 기기의 수신과 제공자 인증 검증은 앱 연동 후 별도로 수행한다.

공식 자료: [Admin SDK 설정](https://firebase.google.com/docs/admin/setup), [토큰 관리](https://firebase.google.com/docs/cloud-messaging/manage-tokens), [FCM 오류 코드](https://firebase.google.com/docs/cloud-messaging/error-codes).
