# AWS 백엔드 배포

## 범위와 준비 상태

졸업 프로젝트 백엔드 8개만 배포한다. 프론트엔드는 별도 팀원이 Vercel에 배포한다. 이 구성은 AWS 리소스를 자동 생성하거나 프론트 코드를 변경하지 않는다.

시작안은 Lightsail 서울 리전, Linux/Ubuntu, **IPv4 포함 8GB / 2 vCPU / 160GB** 서버 한 대다. 2026-10-28 확인한 공개 요금은 월 $44다. 환율 1,500원과 부가세 10%를 가정하면 두 달 서버 비용은 약 145,200원이다. 도메인, 백업, 전송량 초과와 외부 인증/SMS 요금은 별도다. 11~12월 총 30만 원 한도 안에서 18~21만 원을 목표로 두고 여유를 남긴다. 무료 크레딧은 예산에 포함하지 않는다.

8GB의 충분성은 아직 보장하지 않는다. 앱마다 최대 힙 256MB/컨테이너 640MB, DB 연결 풀 5개로 시작하고 전체 시나리오의 메모리, OOM, 지연을 측정한다. 서버에서 Gradle 빌드를 하지 않는다. CPU는 버스트형이므로 시연 전 장시간 부하도 확인한다. 실제 사용자 운영을 결정하면 가용성·DB 분리를 재검토한다.

로컬 ARM64 Docker에서 8개 앱, MySQL, Redis 2개, Caddy와 네트워크 namespace 컨테이너의 전체 기동 및 공고 조회/JWT 보호/DB 권한 분리/Redis 설정/MySQL dump 검사를 통과했다. 기동 후 대기 상태의 컨테이너 메모리 합계는 약 3.4GiB였다. AWS x86_64 서버의 부하 시험 결과가 아니며, 재기동 안정성과 실제 제공자 연동은 실서버에서 추가 확인해야 한다.

## 배치와 공개 범위

- `app-network`가 호스트 80/443만 공개하고, 같은 네트워크 공간의 Caddy가 그 포트에서 HTTPS 인증서를 자동 관리한다.
- API 호스트 이름은 `API_DOMAIN`에 넣는다. Vercel 주소는 프론트 주소이며 백엔드 주소가 아니다. 사용자는 무료 서브도메인을 선택했다. 첫 배포는 고정 IP를 이름에 포함하는 sslip.io 주소를 사용한다. 공인 IP에 HTTP로만 배포하면 HTTPS 프론트에서 호출할 수 없다.
- 애플리케이션, MySQL, Redis는 Docker 네트워크에서만 통신한다. AWS 방화벽에 3306/6379/8080~8088을 열지 않는다.
- 기존 job/matching/payment 클라이언트는 HTTPS 또는 루프백 HTTP만 허용한다. 이를 유지하기 위해 8개 앱과 Caddy는 `app-network`의 네트워크 namespace를 공유하며, 앱들은 각각 `127.0.0.1:8081~8088`에만 바인딩한다. MySQL/Redis는 별도 컨테이너의 Docker 내부 주소로 접근한다. 앱별 프로세스·메모리·파일시스템은 분리된다. namespace 컨테이너 자체를 교체/재시작하면 기존 앱이 옛 namespace에 남을 수 있으므로 `deploy.sh up`은 인프라 확인 후 앱과 proxy를 모두 재생성해 현재 namespace에 연결한다. 이 방식은 단일 서버 시연용이며 여러 서버로 확장할 때는 내부 TLS를 먼저 구성한다.
- MySQL 인스턴스 하나에 서비스별 DB 8개와 권한이 분리된 계정 8개를 만든다. 서비스 간 테이블 조회나 물리 FK를 허용하지 않는다.
- auth Redis와 이벤트 Redis를 분리하고 AOF `appendfsync always` 및 `noeviction`을 사용한다. 이벤트 Stream은 임의로 trim하지 않는다. 메모리 한도 도달 시 쓰기가 실패하므로 메모리와 relay/consumer 실패 로그를 관찰한다.
- `/api/*/internal` 경로는 외부에서 404 처리하고 외부의 `X-Internal-Secret` 헤더를 제거한다. 기존 서비스의 내부 인증과 JWT 검증도 유지한다.
- `/api/jobs/{id}/applications` 및 하위 매칭 경로는 matching-service에 전달한다. 일반 공고 API는 job-service에 전달한다.
- Actuator와 루트 경로의 Swagger(`/swagger-ui/**`, `/v3/api-docs`)는 공개 라우팅하지 않는다. 팀원용 Swagger는 `/docs/{서비스}/` 아래에서만 Basic Auth로 공개한다. 서비스 이름은 `auth`, `member`, `job`, `matching`, `payment`, `work`, `chat`, `notification`이다. 이 경로에서는 Swagger UI와 해당 서비스의 OpenAPI 문서만 전달하고 API·Actuator는 404 처리한다. UI 요청에만 `X-Forwarded-Prefix`를 붙이고 문서 요청에는 붙이지 않아 OpenAPI 서버 주소가 공개 API 루트로 유지된다. 따라서 Swagger의 Try it out은 위의 `/api/...` 공개 경로와 Bearer JWT로 호출된다. 문서에는 내부 API 명세도 보이지만 외부 호출은 계속 404다.
- 컨테이너 healthcheck는 시작 완료 후 공개 인증이 필요 없는 내부 `/v3/api-docs`를 확인한다. 이는 프로세스 시작 검사이며 지속적인 DB/Redis 가용성 검사를 대신하지 않는다.

## 1. AWS 서버 준비

신규 간편 가입(Sign up for AWS new) 계정에서는 한국 연락처 기준 프로젝트 리전이 시드니로 배정되고 리전 선택 메뉴가 없을 수 있다. Lightsail은 이 방식의 Free Tier에도 포함된다. 결제수단을 등록하는 Paid Plan 업그레이드만으로 여러 리전이 열리지는 않는다. 서울을 사용하려면 고급 기능 활성화와 RegionFloor 정책 조정이 필요할 수 있다. 고급 기능 활성화는 되돌릴 수 없고 기존 지출 상한 기능을 제거하므로 계정 유형과 영향을 확인한 뒤 사용자가 선택해야 한다. 현재 계정이 이 방식인지는 AWS Settings에서 추가 확인해야 한다.

2026-10-29에 사용자가 Lightsail 사용을 확정했다. 처음에는 서울 리전 조회가 조직 SCP의 명시적 거부로 차단됐지만, 아래 정책과 콘솔 표시 설정을 조정한 후 서울 생성 화면이 정상적으로 열렸다. 이후 사용자가 서울 영역 A에 Ubuntu / 8GB / 2 vCPU / 160GB / 듀얼 스택의 `Ubuntu-1`을 생성했다. 실행 중이며 시스템·인스턴스 상태 검사 모두 통과했다. 고정 IP `StaticIp-1`(`15.165.106.227`)도 연결됐다. 예정 API 호스트명은 `api.15-165-106-227.sslip.io`이며 DNS·HTTPS 검증은 아직 하지 않았다. HTTP 80(IPv4/IPv6), HTTPS 443(IPv4)을 허용했고 SSH 22는 Lightsail 브라우저 SSH 전용으로 제한한 뒤 재접속을 확인했다. Ubuntu 24.04.4 LTS / x86_64 서버에 공식 apt 저장소의 Docker Engine 29.9.2, Compose 5.6.1을 설치했으며 `hello-world` 실행에 성공했다. Git 2.43.0, Python 3.12.3도 준비됐다. 이미지 발행과 실제 백엔드 배포는 남아 있다.

관리 계정에서 확인한 `AdvancedModeRegionRestrictionSecurityControlPolicy`의 `RegionFloor` 목록에 서울이 없었다. 2026-10-29 사용자 승인 후 `ap-northeast-2` 한 항목만 추가해 저장했고, 저장된 정책을 다시 읽어 승인안과 일치함을 확인했다. 기존 `builderid:*` 항목의 편집기 미인식 오류가 표시됐지만 최종 저장은 성공했으며 그 항목은 변경하지 않았다. 정책은 조직 전체 멤버 계정에 적용된다. 원본·수정안·적용 결과는 저장소 밖에 별도 백업했다. 이어 프로젝트 계정의 별도 표시 리전 목록에도 서울을 추가했다. Lightsail 생성 화면에서 실제 위치가 서울, 영역 A(`ap-northeast-2a`)로 표시되고 리전 제한 안내가 사라진 것을 확인했다.

1. AWS 계정에 MFA를 설정하고 관리용 계정으로 Lightsail 콘솔에 로그인한다.
2. 서울 리전에서 Ubuntu LTS, x86_64, IPv4 포함 8GB 서버를 생성한다. 도커 이미지는 `linux/amd64`다. AWS에서 실제 선택 가능한 OS/플랜과 가격을 생성 직전에 확인한다.
3. 고정 IP를 생성해 인스턴스에 연결한다. `api.<고정-IP의-점을-하이픈으로-바꾼-값>.sslip.io`를 API 주소로 사용한다. 예를 들어 문서용 IP `203.0.113.10`이라면 `api.203-0-113-10.sslip.io` 형식이며, 실제 배포에는 반드시 실제 고정 IP를 넣는다. 이 서비스는 IP를 포함한 이름을 해당 IP로 해석하므로 계정 생성이나 DNS 레코드 등록은 필요 없다. DNS 조회 결과가 실제 IP와 같은지 확인한다. 무료 외부 DNS와 인증기관의 가용성/발급 제한에 의존하므로 시연 직전 인증서 발급을 반복하지 않는다. 발급 제한이면 제한 해소 또는 다른 무료 DNS 이름 사용을 검토한다. 고정 IP를 반납하거나 바꾸면 API 주소도 다시 설정해야 한다.
4. 네트워크 방화벽은 TCP 80/443을 공개하고 SSH 22는 Lightsail 브라우저 SSH 전용 또는 관리자의 공인 IP로 제한한다. 현재 서버는 브라우저 SSH 전용이며 로컬 SSH를 쓰려면 관리자 IP를 별도로 허용해야 한다. Caddy 인증서 발급에도 80/443 도달성이 필요하다.
5. AWS Budgets에서 월 비용 예산과 실제 사용액 알림을 설정한다. 알림은 강제 결제 상한이 아니고 갱신이 지연될 수 있다.
6. 서버에 Git, Python 3, Docker Engine와 Compose plugin을 설치한다. Docker 공식 Ubuntu 설치 문서를 따른다. 현재 서버는 docker 그룹에 사용자를 추가하지 않았으므로 Docker와 배포 스크립트를 `sudo`로 실행한다. docker 그룹은 root에 준하는 권한이다.

```bash
sudo docker version
sudo docker compose version
python3 --version
```

저장소를 서버에 clone하고 배포할 커밋을 checkout한다. 비공개 저장소에는 서버 전용 읽기 전용 deploy key를 쓰고 개인 SSH 키를 복사하지 않는다. 서버에서 소스 빌드를 실행하지 않는다.

## 2. 검증된 이미지 발행

`.github/workflows/backend-images.yml`은 수동 실행 워크플로다. 먼저 이 워크플로를 기본 브랜치에 반영해야 Actions의 수동 실행 버튼을 사용할 수 있다. 배포 대상 브랜치를 선택해 실행하면 전체 저장소 검증이 성공한 뒤 8개 이미지를 GHCR에 발행한다. 별도 AWS 키나 SSH 키를 GitHub에 등록할 필요가 없다. 이 단계는 서버에 자동 반영하지 않는다.

태그는 선택한 커밋의 40자리 SHA다. **8개 이미지가 모두 발행된 워크플로 실행만 배포한다.** 부분 발행에 실패한 태그는 사용하지 않는다. 서버와 이미지의 커밋을 맞춘다.

로컬에서 프록시 경로·내부 API 차단·CORS를 별도로 검증하려면 `python3 deploy/test-proxy.py`를 실행한다. Docker가 필요하며 임시 네트워크/컨테이너를 만들고 종료 시 정리한다. AWS나 실제 DB는 사용하지 않는다. `python3 deploy/test-config.py`는 임시 설정과 실제 Compose CLI로 셸의 이전 이미지 태그 등이 배포 파일을 덮어쓰지 않는지, 잘못된 태그를 거부하는지 검사한다. 두 검사 모두 이미지 발행 전 워크플로에 포함된다.

기본 이미지 이름은 `ghcr.io/404-worker-not-found/be/auth-service:<SHA>` 형식이다. 저장소를 옮기면 `IMAGE_PREFIX`도 변경한다. 조직의 Actions/package 정책에 따라 GHCR 쓰기 권한 허용이 필요할 수 있다.

비공개 이미지라면 서버에서 GHCR에 로그인한다. 토큰은 package 읽기 권한만 부여하고 비밀번호 입력창에 입력한다. 명령줄 인자로 토큰을 넣지 않는다. 조직 SSO가 있으면 해당 토큰도 승인해야 한다.

```bash
sudo docker login ghcr.io -u YOUR_GITHUB_USERNAME
```

## 3. 서버 환경변수

저장소 루트에서 실행한다.

```bash
umask 077
cp deploy/env.example .env.deploy
chmod 600 .env.deploy
openssl rand -hex 32
```

마지막 명령을 각 비밀번호와 secret마다 따로 실행해 파일을 채운다. Swagger 계정은 `SWAGGER_USERNAME`에 아이디를, `SWAGGER_PASSWORD_HASH`에 아래 명령이 출력한 bcrypt 해시를 넣는다. 비밀번호는 명령 인자가 아니라 입력창에 입력하고 원문은 `.env.deploy`에 저장하지 않는다. 해시의 `$`가 Compose 변수로 해석되지 않도록 값 전체를 작은따옴표로 감싼다(`SWAGGER_PASSWORD_HASH='$2a$14$...'`). 따옴표가 없으면 `check`가 거부한다. 비밀번호는 팀원에게 별도 채널로 전달하고, 바꿀 때는 해시를 교체한 뒤 `up`으로 proxy를 재생성한다.

```bash
sudo docker run --rm -it caddy:2-alpine caddy hash-password
```

 `.env.deploy`는 Git에서 제외된다. 로컬 `.env`를 복사하거나 `source`하지 않는다. `IMAGE_TAG`에는 전체 SHA, `API_DOMAIN`에는 스킴/포트 없는 호스트명, `FRONTEND_ORIGIN`에는 팀원이 확정한 `https://프로젝트.vercel.app`처럼 정확한 Origin을 넣는다. 끝에 `/`를 붙이지 않고 `*.vercel.app` 전체를 허용하지 않는다. 프론트 주소가 아직 없으면 예제의 예약된 Origin을 유지해도 서버 API의 비브라우저 확인은 가능하다.

배포 스크립트는 Compose 파일의 보간 변수에 해당하는 셸 환경변수를 제거한 후 `.env.deploy`를 읽는다. 셸에 남아 있는 이전 `IMAGE_TAG`나 인증정보가 배포 파일을 덮어쓰지 않는다. Docker 연결·로그인용 `DOCKER_HOST`와 `DOCKER_CONFIG` 등은 유지한다.

회원가입 실제 동작에는 Gmail 앱 비밀번호, SOLAPI 키/등록 발신번호, 점주 가입에는 국세청 API 키가 필요하다. OAuth도 제공자 설정과 프론트 redirect URI 등록이 필요하다. 비어 있으면 해당 기능이 성공했다고 간주하지 않는다. Toss는 테스트 키만 지원하며 `TOSS_ENABLED=false`일 때 결제로 자금을 확보해야 하는 공고 게시/매칭의 전체 흐름은 검증할 수 없다. 실결제 키를 넣지 않는다.

```bash
sudo ./scripts/deploy.sh check
sudo ./scripts/deploy.sh pull
sudo ./scripts/deploy.sh up
sudo ./scripts/deploy.sh status
```

MySQL 계정 초기화는 **빈 볼륨에서 최초 한 번만** 실행된다. 나중에 `.env.deploy`의 DB 비밀번호만 바꿔도 기존 계정은 변경되지 않는다. 비밀번호 변경은 해당 MySQL 계정 변경과 앱 재기동을 함께 계획한다. 초기화가 중간 실패했다면 로그를 확인하고 계정을 복구하며, 실제 데이터가 있는 볼륨을 지우지 않는다.

MySQL healthcheck는 계정 초기화가 모두 끝난 후 기록한 `.service-databases-ready` 파일과 TCP 연결을 함께 확인한다. 부분 초기화 상태에서 자동 재시작되더라도 준비 완료로 오판하지 않게 한다. 수동 복구 시 모든 DB·계정·접속을 확인한 뒤에만 이 표식을 복구한다.

스크립트의 `up` 성공은 컨테이너 시작과 내부 HTTP 검사 성공을 의미한다. DNS/공개 HTTPS/외부 제공자/업무 흐름의 성공은 다음 단계에서 따로 확인한다.

## 4. 배포 후 확인과 팀원 전달

```bash
curl -i https://API_HOST/api/jobs/search
curl -i https://API_HOST/api/jobs/internal/example
curl -i -X OPTIONS https://API_HOST/api/auth/login \
  -H 'Origin: https://YOUR_FRONTEND.vercel.app' \
  -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: authorization,content-type'
sudo ./scripts/deploy.sh logs auth-service
sudo docker stats --no-stream
free -h
df -h
```

```bash
curl -i https://API_HOST/docs/job/swagger-ui/index.html
curl -i -u SWAGGER_USERNAME https://API_HOST/docs/job/v3/api-docs
```

공고 조회는 200, 내부 API는 404, 계정 없는 Swagger는 401, 계정을 입력한 OpenAPI 문서는 200과 `servers`의 `https://API_HOST`가 나와야 한다. 등록한 Origin의 preflight는 204와 정확한 `Access-Control-Allow-Origin`이 나와야 한다. 미등록 Origin의 preflight는 403이어야 한다. 지원자 조회는 OWNER 권한, WebSocket은 `wss://API_HOST/api/chat-rooms/ws` 연결과 STOMP 인증까지 확인한다. 실제 가입/로그인, 테스트 결제, 공고 게시, 지원, 매칭 확정, 채팅과 알림까지 팀원과 연결해서 확인한다. CORS는 프록시에서 처리하며 서비스 보안을 해제하지 않는다. 인증은 기존 Bearer JWT 방식이고 CORS credentials/cookie 옵션은 켜지 않는다.

팀원에게 전달할 값:

- REST base URL: `https://API_HOST` (각 API 경로가 이미 `/api/...`를 포함한다)
- WebSocket URL: `wss://API_HOST/api/chat-rooms/ws`
- Swagger: `https://API_HOST/docs/{서비스}/swagger-ui/index.html`과 Swagger 계정
- 허용된 Vercel Origin, 토큰/요청/오류 계약, Toss 테스트용 공개 client key
- 내부 API secret, JWT 서명키, DB/PG secret은 전달하지 않는다.

Vercel의 preview 주소는 배포마다 달라질 수 있다. 먼저 고정된 production 주소 하나를 허용하고, 주소 변경 시 `.env.deploy`를 수정한 뒤 `sudo ./scripts/deploy.sh up`으로 앱과 proxy를 재생성한다. 이 명령은 짧은 서비스 중단을 동반한다.

## 5. 업데이트, 백업과 복구

이미지를 먼저 전부 받아둔다. 시연 중 배포하지 않고 유지보수 시간을 정한다. 앱과 proxy를 멈춘 뒤 DB를 백업한다. 실행 중 `backup`도 가능하지만 여러 서비스의 업무 상태와 Redis를 같은 시점으로 맞추는 백업은 아니다.

```bash
# .env.deploy의 IMAGE_TAG를 새 SHA로 변경하고 해당 소스 커밋도 checkout한다.
sudo ./scripts/deploy.sh check
sudo ./scripts/deploy.sh pull
sudo ./scripts/deploy.sh pause
sudo ./scripts/deploy.sh backup
# Lightsail 스냅샷도 완료된 것을 확인한 후 진행한다.
sudo ./scripts/deploy.sh up
```

`backups/*.sql.gz`에는 개인정보가 포함될 수 있다. 파일 권한을 유지하고 접근 제한된 외부 저장소에도 복사한다. 서버 디스크 한 곳의 사본만으로 복구를 보장하지 않는다. 정기적인 별도 보관과 실제 복원 연습을 진행한다. 이벤트 Redis의 consumer group/Stream도 복구에 필요하므로 DB dump만으로 전체 시스템을 복원했다고 간주하지 않는다. 앱이 멈춘 상태에서 Redis AOF가 반영된 후 전체 서버 스냅샷을 함께 남기고 완료를 확인한다.

애플리케이션 코드만 되돌릴 수 있는 경우 이전 SHA로 바꾸고 `pull`/`up`한다. Flyway migration이 실행된 DB는 이미지 rollback으로 되돌아가지 않는다. **이전 코드와 새 스키마가 호환되는지 확인한 경우에만** 이전 이미지를 실행한다. 자동 DB rollback은 하지 않는다.

전체 복구는 앱과 proxy를 멈춘 상태에서 검증된 서버 스냅샷으로 새 인스턴스를 복원하고 `.env.deploy`, DB/Redis 상태, 이미지 태그, 고정 IP/DNS를 확인하는 절차로 진행한다. MySQL dump만 복원할 때는 새 DB에 먼저 테스트하고 동일 시점 Redis와의 정합성 및 재처리 가능성을 확인한다. 서비스가 스스로 재발행하지 않는 PUBLISHED 이벤트도 있으므로 Redis를 비우지 않는다.

디스크 용량, DB 백업 복원, 인증서 갱신, 메모리/OOM, DB 연결, Redis noeviction 오류와 CPU 크레딧을 시연 전에 확인한다. `docker compose down -v`나 무차별 volume prune을 운영 서버에서 실행하지 않는다.

Lightsail은 서버를 stop해도 요금이 계속 발생한다. 프로젝트 종료 시 필요한 백업을 외부에 보관한 뒤 인스턴스·불필요한 스냅샷·연결 해제된 고정 IP 등 남은 리소스를 정리하고 Billing에서 비용을 확인한다.

## 공식 문서

- [Lightsail 요금](https://aws.amazon.com/lightsail/pricing/)
- [Lightsail 청구와 중지 상태 과금](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-frequently-asked-questions-faq-billing-and-account-management.html)
- [Docker Ubuntu 설치](https://docs.docker.com/engine/install/ubuntu/)
- [GHCR 인증](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)
- [Caddy 자동 HTTPS](https://caddyserver.com/docs/automatic-https)
- [sslip.io / nip.io 무료 DNS](https://sslip.io/)
- [AWS Budgets](https://docs.aws.amazon.com/cost-management/latest/userguide/budgets-managing-costs.html)
- [신규 간편 가입의 프로젝트 리전](https://docs.aws.amazon.com/accounts/latest/reference/project-regions.html)
- [AWS Settings 계정 업그레이드](https://docs.aws.amazon.com/accounts/latest/reference/upgrade-account.html)
- [고급 기능 활성화와 변경 사항](https://docs.aws.amazon.com/accounts/latest/reference/activate-advanced-features.html)
