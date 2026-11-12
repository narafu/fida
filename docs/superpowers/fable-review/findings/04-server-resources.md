# 04. fida 서버 실측 (읽기 전용 ssh 조회, 2026-11-12 11:35 KST)

접속: `ssh fida-server` (`~/.ssh/config`에 호스트 등록, 키 `~/.ssh/id_ed25519_oci_fida`). 조회 명령은 `free`, `nproc`, `df`, `docker stats --no-stream`, `docker images`, `docker system df`, `docker logs --since 96h | grep`, `docker inspect`, `uptime`뿐이다.

## 호스트

| 항목 | 값 |
|---|---|
| CPU | 1 OCPU (`nproc` = 1) |
| RAM | 5,903MB 총량, 사용 909MB, available 4,994MB, swap 없음 |
| 디스크 | 48GB 중 29GB 사용(60%), 20GB 여유 |
| 가동 | 100일 무재부팅, load average 0.19 / 0.05 / 0.01 |

fable-api 전달 메시지에는 "2번 인스턴스 RAM 12GB·50GB"라고 적혀 있었으나 실측은 **RAM 6GB·1 OCPU**다(`deploy/server/README.md`의 `VM.Standard.A1.Flex 1 OCPU 6GB`와 일치).

## 컨테이너 (평시, 스케줄 실행 4시간 뒤)

| 컨테이너 | 메모리 사용 / 한도 | CPU |
|---|---|---|
| fida | 208.9MiB / 1.5GiB | 0.05% |
| fida-playwright-server | 233.4MiB / 3GiB | 0.00% |
| fida-caddy | 28.6MiB / 64MiB | 0.04% |

평시 합계 약 470MiB. 스크래핑 중 Chromium 피크는 약 1.2GiB(2026-10-31 실측, compose 주석)라 실행 중 합계는 약 1.5GiB로 추정된다(피크는 이번에 재측정하지 않았다). playwright 컨테이너는 2026-10-31 기동 이후 OOM kill 없음.

## 실행 시간대와 소요

화~토 07:00 KST에 1회. `TradingRecordService`의 "파이프라인 완료" 로그 기준:

| 날짜 | 소요 |
|---|---|
| 2026-11-10 | 48.7초 |
| 2026-11-11 | 47.0초 |
| 2026-11-12 | 109.7초 |

11-12가 두 배인 이유는 로그에서 확인하지 않았다(Gemini 503 재시도는 1분 대기라 그 가능성이 있다). 실패 시 07:20에 1회 재시도한다. 하루 중 나머지 23시간 58분은 유휴다.

## 디스크: 이미지 누적

| 항목 | 값 |
|---|---|
| 이미지 | 43개, 25.61GB, 그중 **22.94GB(89%) 회수 가능** |
| fida 이미지 | 359MB / 개 |
| playwright-server 이미지 | 1.38GB / 개 |

배포마다 SHA 태그 이미지 2개(합 1.74GB)가 쌓인다. `server-deploy.yml`의 `docker image prune -f`는 dangling 이미지만 지우므로 태그가 붙은 이전 버전은 남는다. 디스크 29GB 중 약 23GB가 이 누적분이다. 현재 속도면 배포 약 11회 뒤 디스크가 찬다. 대응은 `guides/04-docs-files-ops.md` 태스크 5.

## 인스턴스 통합 판단용 요약 (fable-infra 전달분)

- 상시 점유: 약 0.5GB RAM, CPU 거의 0
- 피크: 화~토 07:00~07:02 KST에 약 1.5GB RAM, 1 vCPU를 1~2분 사용. 실패 시 07:20 재시도
- 한도 예약: 1.5G + 3G + 64M = 약 4.6GB(실사용의 3배)
- 디스크: 이미지 정리 후 실사용 약 6GB
- 외부 공개: 443 `/actuator/health`만(UptimeRobot용). 나머지 경로는 Caddy가 404
- 아웃바운드: fanding.kr, Gemini API, Google Sheets API, Telegram API, healthchecks.io, `api.kista-app.com`
- 서버 수동 배치물: `/opt/fida/.env`, `/opt/fida/secrets/service-account.json`(uid 100:101), `/opt/fida/.fida-state/`
- 배포: fida 레포 `server-deploy.yml`이 SSH·scp로 직접 배포(kista-infra `reconcile.sh`와 별개 방식)
