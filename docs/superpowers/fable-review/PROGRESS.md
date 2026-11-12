# fida Fable 검토 진행 상황

기준: `/Users/phs/workspace/kista/kista-api/docs/superpowers/fable-review/CHARTER.md`
세션 이름: `fable-fida` (2026-11-12). 규모가 작아 1회 검토로 끝낸다.

## 완료

- [x] 측정 (2026-11-12)
  - `findings/01-pipeline-measure.md` — 규모, 수직 추적, 단일 구현 추상화, 타입 복제, 테스트
  - `findings/02-kista-contract.md` — fida↔kista-api 계약 대조, 어긋난 이력, 스택 비교
  - `findings/03-docs-files-deploy.md` — 문서, 불필요 파일, 배포 구성
  - `findings/04-server-resources.md` — 서버 실측(ssh 읽기 전용)
- [x] 지침서 (2026-11-12)
  - `guides/01-merge-or-separate.md` — 통합 여부 권고: 별도 레포 유지 + 접점 축소, 운영만 통합
  - `guides/02-surface-reduction.md` — 진입 경로 4개를 2개로, KISTA 응답 3벌 정리
  - `guides/03-ocr.md` — OCR 733줄: 회귀 자료 확보 뒤 출처 단일화
  - `guides/04-docs-files-ops.md` — 문서 86% 감량, 삭제 후보, 서버 이미지 누적
- [x] fable-api에 권고 전달, fable-infra에 자원·배포 조사 전달

## 사용자 결정 대기

1. 통합 여부: D(별도 유지, 권고) / C(레포 통합·별도 이미지) / A·B(role 흡수)
2. `POST /orders/from-image` 삭제 여부
3. GH Actions 수동 재실행(`fida-schedule.yml` + `FandingRunner`) 삭제 여부
4. Gemini 유료 키 전환·모델 고정 해제 여부
5. Google Sheets 기록 유지 여부
6. `04`의 삭제 후보 승인

## 바로 다음 할 일

결정이 나면 실행 순서는 `04` 태스크 5(디스크, 독립) → `02` 태스크 1~5 → `03` 태스크 2·3 → `04` 태스크 1~4·6. `03` 태스크 4 이후는 회귀 자료가 20건 모인 뒤(약 4주).

실행은 이 검토 세션이 아니라 새 세션에서 지침서 단위로 한다.
