# fida Fable 검토 진행 상황

기준: `/Users/phs/workspace/kista/kista-api/docs/superpowers/fable-review/CHARTER.md`
세션 이름: `fable-fida` (2026-11-12). 규모가 작아 1회 검토로 끝냈다.

## 완료

- [x] 측정: `findings/01`~`04` (파이프라인, KISTA 계약, 문서·배포, 서버 실측)
- [x] 지침서: `guides/01`~`04`
- [x] fable-api에 권고·cross-repo 4건 전달, fable-infra에 자원·배포 전달
- [x] 사용자 결정 (2026-11-12)
  1. 통합 여부: **별도 레포 유지(D안)**. 호스트는 kista-server로 이전(별도 확정)
  2. `POST /orders/from-image`: **삭제**
  3. GH Actions 수동 재실행(`fida-schedule.yml` + `FandingRunner`): **삭제**
  4. Google Sheets: **일단 유지**, 향후 삭제 고려

## 결정 대기

- Gemini 유료 키 전환·모델 고정 해제 (`03` 태스크 5·6 전제)

`04` 삭제 후보는 2026-11-12에 승인됐다. 조건부 항목인 `AGENTS.md`는 유지한다.

## 실행 순서 (새 세션에서 지침서 단위로)

1. ~~`02` 태스크 1~5~~ — 완료 (2026-11-15). GitHub Secrets 중 `fida-schedule.yml` 전용 9개는 사용자가 직접 삭제
2. ~~`03` 태스크 2·3~~ — 코드 완료 (2026-11-15, 커밋 cd286bb·8373741). ocr 테스트 이전 전후 43개, DisplayName 집합 동일
   - 태스크 3 **서버 배포: 2026-11-15** (커밋 3562d4a, Server Deploy 성공, 컨테이너 healthy, `/state`를 appuser 100:101이 쓸 수 있음 확인). `OCR_ARCHIVE_PATH`는 서버 `.env`에 없어 기본값 `/state/ocr-archive`를 쓴다
   - 첫 보관은 다음 정규 실행 2026-11-17(화) 07:00 KST. 그 뒤 `ssh fida-server ls /opt/fida/.fida-state/ocr-archive/`로 날짜 폴더 생성을 확인한다
   - 20거래일 뒤가 `03` 태스크 4 시작 시점이다. 화~토 실행 기준 2026-12-12(토)쯤이고, 미국 휴장일만큼 늦어진다. 날짜 폴더가 20개 이상인지로 판정한다
   - infra 이전 시 `/opt/fida/.fida-state/ocr-archive/`를 옮긴다(uid 100:101 소유)
3. `04` 태스크 7 (pre-apply 훅) — 완료 (2026-11-15, 커밋 d57e133, `--self-test`는 macOS·ubuntu 양쪽 통과) → infra 이전 → `04` 태스크 8
   - 태스크 8 중 bundle 계약 부분을 이전 전으로 당김 (2026-11-15, 브랜치 `infra-bundle`, main 미병합). `roles`·`images`·`caddy/fida.caddy` 추가, compose에서 caddy 삭제(`shared_net` + 전용 `browser_net`, mem_limit 1024m/2048m), `Caddyfile` 삭제, `server-deploy.yml` SSH 배포를 infra `repository_dispatch`로 교체, README 축소
   - **main 병합 = 컷오버.** 병합하면 구 SSH 배포가 사라지고 infra로 dispatch한다. 이전 당일 사용자 승인 뒤 병합한다. 그 전에 main에 커밋하면 구 SSH 배포가 fida-server에 그대로 나간다(paths 필터 없음)
   - 병합 전 준비: fida 레포 `vars.INFRA_APP_CLIENT_ID`·`secrets.INFRA_APP_PRIVATE_KEY` 등록 완료(2026-11-15). kista-infra bbeed69는 main에 push됨(rebase 후 5947ac7). 남은 것은 infra 세션 몫이다: `state/fida.yml`(roles 2개), `secrets/fida.env`(`FANDING_*` 포함), service-account.json 이동, ocr-archive(아직 없음, kista-server에 100:101 빈 디렉토리 생성), DNS 전환, 구 서버 fida 정지. 병합 시점은 infra 세션이 알린다
   - 첫 dispatch에서 확인: fida 커밋에 `deploy/production` status가 붙는지(kista-infra-dispatch App의 fida 설치·statuses 권한)
   - 태스크 8에 남은 것: `KISTA_URL`을 `http://caddy:8080`으로(infra 내부 리스너 배포 뒤, infra 소유 env), playwright `read_only`·`cap_drop: ALL` 검토
4. ~~`04` 태스크 1~4·6~~ — 완료 (2026-11-15). 루트 `CLAUDE.md` 58줄, 환경변수 기준은 `.env.example`, 승인된 삭제 후보 삭제(`AGENTS.md` 유지), 메모리 3건 삭제
   - `04` 태스크 5: infra 이전이 1~2주 안이라 워크플로 수정은 건너뜀(reconcile.sh가 맡음). 2026-11-15 서버 수동 정리로 실행 중(67b8840)·직전(3562d4a) 이미지만 남기고 fida 태그 42개 삭제. `docker images | grep -c fida` = 4, 디스크 31G에서 8.2G로. 이전이 늦어지면 같은 정리를 다시 한다
5. ~~`03` 태스크 1~~ — 완료 (2026-11-15). 최근 게시글 5건 모두 본문 이미지 3장, `table` 0개, 텍스트는 전날 체결 요약뿐이라 DOM 대체 불가(`findings/05-post-format.md`)
6. `03` 태스크 4 이후는 회귀 자료 20건 이후(약 4주)
   - 2026-11-15 전제 확인: 구 서버 `/opt/fida/.fida-state/ocr-archive/` 없음, 날짜 폴더 0개. infra 이전 뒤에는 kista-server 쪽 보관 경로에서 센다
