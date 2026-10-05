# 인증번호 발송 운영 설정

auth-service는 이메일을 Resend `POST /emails`, SMS를 SOLAPI `POST /messages/v4/send-many/detail`로 접수합니다. 두 요청 모두 HTTPS로 전송하며 연결 제한은 3초, 읽기 제한은 5초입니다. 업체가 요청을 거절하거나 접수 응답이 올바르지 않으면 API는 안전한 `GLOBAL-502-001`을 반환합니다. 확정된 거절은 해당 인증번호를 삭제하고, 시간 초과 등 접수 여부가 불확실한 경우에는 인증번호를 TTL까지 유지합니다. 1분 재발송 제한은 실패 후에도 유지합니다. 접수 성공은 최종 배달 완료를 뜻하지 않습니다.

운영 환경에는 다음 값을 주입합니다. 값은 Git에 저장하지 않습니다.

| 환경 변수 | 설정 |
| --- | --- |
| `AUTH_RESEND_API_KEY` | Resend API 키 |
| `AUTH_VERIFICATION_EMAIL_FROM` | Resend에서 검증된 도메인의 발신 이메일 |
| `AUTH_SOLAPI_API_KEY` | SOLAPI API 키 |
| `AUTH_SOLAPI_API_SECRET` | SOLAPI API Secret |
| `AUTH_VERIFICATION_SMS_FROM` | SOLAPI에 사전 등록된 발신번호 |

배포 전에 실제 수신 가능한 이메일 주소와 휴대폰 번호로 각각 `/api/auth/email-verifications/send`와 `/api/auth/sms-verifications/send`를 호출하고, 수신한 번호로 대응하는 `/verify` API를 호출해 확인합니다. 이어서 업체 콘솔의 발송 상태를 확인합니다. 인증번호 본문은 애플리케이션 로그에 남기지 않으며, 설정이 빠진 경우에도 가짜 성공 응답을 반환하지 않습니다.

API 계약과 발신 자격 요건: [Resend 이메일 API](https://resend.com/vercel), [SOLAPI 메시지 발송](https://solapi.com/developers/api/messages), [SOLAPI API 키 인증](https://solapi.com/developers/api/authentication-api-key).
