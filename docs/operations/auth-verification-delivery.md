# 인증번호 발송 운영 설정

auth-service는 이메일을 Gmail SMTP(`smtp.gmail.com:587`, STARTTLS), SMS를 SOLAPI `POST /messages/v4/send-many/detail`로 접수합니다. 두 경로 모두 연결 제한은 3초, 읽기 제한은 5초입니다. Gmail 인증 실패 또는 SMS 업체의 확정된 거절은 해당 인증번호를 삭제합니다. 전송 중 연결이 끊겨 접수 여부가 불확실한 경우에는 인증번호를 TTL까지 유지합니다. 실패 응답은 안전한 `GLOBAL-502-001`이고, 1분 재발송 제한은 실패 후에도 유지합니다. SMTP 발송 완료 또는 업체 접수 성공은 최종 배달 완료를 뜻하지 않습니다.

운영 환경에는 다음 값을 주입합니다. 값은 Git에 저장하지 않습니다.

| 환경 변수 | 설정 |
| --- | --- |
| `AUTH_MAIL_USERNAME` | Gmail 발신 계정 주소 (`@gmail.com`) |
| `AUTH_MAIL_PASSWORD` | 위 계정에서 발급한 Google 앱 비밀번호 |
| `AUTH_SOLAPI_API_KEY` | SOLAPI API 키 |
| `AUTH_SOLAPI_API_SECRET` | SOLAPI API Secret |
| `AUTH_VERIFICATION_SMS_FROM` | SOLAPI에 사전 등록된 발신번호 |

Gmail 계정에 2단계 인증을 설정하고 [앱 비밀번호](https://support.google.com/accounts/answer/185833)를 발급합니다. 계정 주소와 앱 비밀번호를 서버의 두 환경 변수에만 주입합니다. Gmail의 일반 로그인 비밀번호는 사용하지 않습니다. 배포 전에 실제 수신 가능한 이메일 주소와 휴대폰 번호로 각각 `/api/auth/email-verifications/send`와 `/api/auth/sms-verifications/send`를 호출하고, 수신한 번호로 대응하는 `/verify` API를 호출해 확인합니다. 인증번호 본문은 애플리케이션 로그에 남기지 않으며, 설정이 빠진 경우에도 가짜 성공 응답을 반환하지 않습니다.

발송 설정과 API 계약: [Gmail SMTP](https://developers.google.com/workspace/gmail/imap/imap-smtp), [SOLAPI 메시지 발송](https://solapi.com/developers/api/messages), [SOLAPI API 키 인증](https://solapi.com/developers/api/authentication-api-key).
