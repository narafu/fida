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
   - 태스크 3 **서버 배포: 미배포** (push 대기). main push 시 `server-deploy.yml`이 배포한다. 화~토 06:50~07:45 KST는 피한다
   - 배포 뒤 첫 실행에서 서버 `/opt/fida/.fida-state/ocr-archive/<날짜>/` 생성을 확인하고 여기에 배포일을 적는다: `배포일: ____`
   - 배포일부터 20거래일 뒤가 `03` 태스크 4 시작 시점이다
   - infra 이전 시 `/opt/fida/.fida-state/ocr-archive/`를 옮긴다(uid 100:101 소유)
3. `04` 태스크 7 (pre-apply 훅) — 완료 (2026-11-15, 커밋 d57e133, `--self-test`는 macOS·ubuntu 양쪽 통과) → infra 이전 → `04` 태스크 8
4. `04` 태스크 1~4·6 (삭제 후보 승인 뒤, CLAUDE.md는 코드 쪽 결정이 끝난 뒤 한 번에)
5. `03` 태스크 4 이후는 회귀 자료 20건 이후(약 4주)
