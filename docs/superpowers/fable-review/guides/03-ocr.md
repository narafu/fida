# 03. OCR: 가장 큰 덩어리

대상: `adapter/out/ocr` 5파일 733줄(main의 35%)과 테스트 815줄. 근거는 `findings/01` §6~7, §9.

## 현재 구조와 문제

| 파일 | 줄 | 역할 |
|---|---|---|
| `GeminiClient` | 220 | 프롬프트 47줄, HTTP 1회, 오류 분류, quota 기록, MIME 감지 |
| `OcrResultReconciler` | 213 | 같은 값을 두 경로로 받아 고르고 경고 수집 |
| `GeminiResponseParser` | 122 | 텍스트를 DTO로 |
| `GeminiVisionAdapter` | 93 | 3회 재시도 루프 |
| `GeminiQuotaTracker` | 85 | 일일 호출 수를 파일에 기록 |

이 코드가 큰 이유는 과설계가 아니라 **무료 티어 `gemini-2.5-flash-lite`가 자주 틀리기 때문**이다. 보정 규칙마다 운영 사고 날짜가 주석에 박혀 있다(`OcrResultReconciler.java:61, 86, 99, 102, 123`). 그러니 줄이는 길은 리팩토링이 아니라 전제를 바꾸는 것이다.

구조에서 보이는 문제:

1. **같은 값을 두 번 묻는다.** 프롬프트가 `sell`과 `sell_rows`, `current_cycle_start`와 `capital_rows`, `current_cycle_realized_pnl`과 `performance_rows`, `holding_qty`와 `cumulative_qty`를 함께 요구한다(`GeminiClient.java:36-47`). Reconciler의 절반은 둘 중 무엇을 믿을지 정하는 코드다.
2. **quota 추적 120줄은 무료 티어 일 20회 한도 때문에만 존재한다.** 상태 파일, 볼륨 마운트, uid 소유권 사고(2026-08, 3주간 카운트 리셋), 알림 1종이 딸려 있다.
3. **테스트 위치가 어긋났다.** `GeminiVisionAdapterTest` 726줄 중 약 300줄이 Reconciler 시나리오인데 어댑터 입구로 넣어 검증한다. 같은 JSON 문자열을 테스트마다 복제한다. 전용 `OcrResultReconcilerTest`는 36줄이다.
4. **회귀 자료가 없다.** 과거 매매표 이미지와 Gemini 원시 응답을 보관하지 않는다. 프롬프트나 모델을 바꿨을 때 예전 사고가 재발하는지 확인할 방법이 없어서, 규칙을 지우지 못하고 쌓기만 한다.
5. `OcrResultReconciler`는 정적 `reconcile(raw)`가 인스턴스를 만들어 `reconcile()`을 부르는 이중 구조다(`:33-41`). 경고 목록을 필드로 들고 다니려는 것이다.

## 목표 구조

**모델은 보이는 대로 옮겨 적고, 무엇을 쓸지는 코드가 정한다.** 값마다 출처를 하나로 한다.

- 프롬프트는 라벨 행 목록만 요구한다: `buy_rows`, `sell_rows`(물리적 3행), `capital_rows`, `performance_rows`, `quantity_rows`(보유개수·누적개수·매수개수). 직접 필드(`sell`, `current_cycle_start`, `current_cycle_realized_pnl`, `holding_qty`, `cumulative_qty`)는 없앤다.
- Java가 라벨로 값을 고른다. 지금도 `findRowValue`·`labelContains`로 하는 일이다(`OcrResultReconciler.java:189-203`).
- 응답 형식은 Gemini `responseSchema`로 강제한다. 지금은 `responseMimeType`만 쓴다(`GeminiClient.java:151`). 스키마를 걸면 `GeminiResponseParser`의 코드펜스·한글 후처리와 JSON 파싱 실패 재시도가 필요 없어진다.
- 모델은 고정하지 않는다. 회귀 자료로 `flash-lite`와 상위 모델을 비교해 고른다. 유료 키로 바꾸면 quota 추적을 통째로 지운다. 하루 1~3회 호출이라 비용은 월 몇 센트 수준으로 예상되나 실제 단가는 실행 시점에 확인한다.

남기는 것: 이상치 경고(현사이클 시작 배율, 시즌 시작원금 혼동, 수량 필드의 소수값). 이건 모델이 좋아져도 실주문 입력을 지키는 마지막 확인이다.

### 그렇게 정한 이유

- 사고 이력의 대부분이 "직접 필드를 엉뚱한 행에서 채움"이다(누적실현수익을 누적개수로, 시즌 실현수익을 현사이클 실현수익으로). 라벨과 값을 함께 받으면 코드가 라벨로 걸러낸다. 이미 `performance_rows`를 신뢰 소스로 쓰는 쪽으로 움직였다(`:174-186`).
- 두 출처 교차검증이 주는 안전은 "둘이 다르면 경고"인데, 실제로는 한쪽을 일방적으로 믿고 있다. 교차검증이 아니라 폴백이다.

### 기각한 대안

- **지금 구조 그대로 두고 테스트만 정리.** 다음 오독마다 규칙이 또 붙는다.
- **회귀 자료 없이 프롬프트부터 변경.** 실주문 입력이다. 과거 사고 재발 여부를 확인할 수 없는 상태에서 바꾸지 않는다.
- **Gemini를 다른 OCR(Tesseract 등)로 교체.** 표 레이아웃이 날마다 달라 라벨 기반 추출이 필요하다. 기각.
- **스크래퍼가 DOM에서 숫자를 직접 읽기.** 매매표가 이미지로 올라온다는 전제를 이번 검토에서 확인하지 않았다. 텍스트로 올라오는 날이 있다면 OCR 자체가 필요 없으니 태스크 1에서 확인한다.

## 예상 감량과 위험

| 단계 | main | test | 조건 |
|---|---|---|---|
| 테스트 이전·픽스처 파일화 | 0 | 약 −150 | 없음 |
| Reconciler 이중 구조 정리 | 약 −10 | 0 | 없음 |
| `responseSchema` 도입 | 약 −60 (Parser 후처리, JSON 재시도) | 약 −80 | 회귀 통과 |
| 직접 필드 제거, 출처 단일화 | 약 −70 (Reconciler 선택 로직, 프롬프트 직접 필드 설명) | 약 −150 | 회귀 통과 |
| quota 추적 제거 | 약 −120 | −53 | 유료 키 결정 |
| 합계(전부 실행 시) | 약 −260 (733에서 약 470) | 약 −430 | |

위험:
- 프롬프트·모델 변경은 실주문에 영향을 준다. 회귀 자료가 20건 이상 모이기 전에는 태스크 4 이후를 시작하지 않는다.
- 회귀 자료에는 계좌 금액이 찍힌 이미지가 들어간다. git에 올리지 않는다.

## 동작 보존용 선행 테스트

- 태스크 2에서 Reconciler 시나리오를 `OcrResultReconcilerTest`로 옮길 때, 옮기기 전 `GeminiVisionAdapterTest`의 해당 테스트가 전부 통과하는 상태에서 시작하고, 옮긴 뒤 시나리오 수가 같은지 센다(운영 사고 날짜가 붙은 시나리오 15개 안팎).
- 태스크 3의 회귀 자료가 태스크 4~5의 선행 테스트다.

## 태스크 분해

| # | 내용 | 대상 | 모델 | 완료 판정 |
|---|---|---|---|---|
| 1 | 매매표가 항상 이미지인지 확인. 최근 게시글 5건의 본문 구조를 본다 | `playwright-server/scrape-fanding.js`, 서버 로그 | Sonnet | 결과를 `findings/05-post-format.md`에 기록 |
| 2 | Reconciler 시나리오를 `OcrResultReconcilerTest`로 이전. Gemini 응답 JSON은 `src/test/resources/ocr/*.json` 픽스처로. Reconciler를 정적 메서드 + 지역 경고 목록으로 정리 | `GeminiVisionAdapterTest`, `OcrResultReconcilerTest`, `OcrResultReconciler` | Sonnet | `bash gradlew test --tests "com.fida.adapter.out.ocr.*" 2>&1 \| grep -E "FAILED\|BUILD"`, 시나리오 수 동일 |
| 3 | 실행마다 이미지와 Gemini 원시 응답을 `/state/ocr-archive/<date>/`에 저장(최근 60일 보관). 이미 `./.fida-state:/state` 마운트가 있다 | `GeminiVisionAdapter` 또는 `GeminiClient`, `application.yml` | Sonnet | 단위 테스트 1개 + 배포 뒤 서버에서 파일 생성 확인 |
| 4 | 보관 이미지를 대상으로 프롬프트·모델 조합을 돌려 최종 `ParsedOrder`를 비교하는 수동 실행 스크립트(테스트 태그 또는 `main` 1개). 비교 대상: 현행, `responseSchema`, 행 전용 프롬프트, 상위 모델 | 새 파일 1개 | **Opus** | 20건 이상에서 조합별 일치율 표를 `findings/06-ocr-replay.md`에 기록 |
| 5 | 4번 결과로 채택한 조합 적용: 프롬프트 직접 필드 제거, Reconciler 선택 로직 축소, Parser 후처리 제거 | `GeminiClient`, `GeminiResponseParser`, `OcrResultReconciler`, 테스트, `adapter/CLAUDE.md` OCR 절 | **Opus** | 회귀 전건 일치 + ocr 패키지 테스트 |
| 6 | 유료 키 결정 시 quota 추적 제거 | `GeminiQuotaTracker`, `GeminiClient.recordQuota`, `NotifyPort.notifyGeminiQuota`, `TelegramAdapter`, `application.yml`, `.env.example`, 루트 `CLAUDE.md` 환경변수 표 | Sonnet | `bash gradlew build 2>&1 \| grep -E "FAILED\|BUILD"` |

2·3번은 바로 할 수 있다. 4번은 3번 배포 뒤 약 4주(20거래일) 기다린다. 5·6번은 사용자 결정 뒤.

## 하지 말아야 할 것

- 회귀 자료 없이 프롬프트 문구, 보정 규칙, 모델을 바꾸지 않는다.
- 이상치 경고 3종과 `requireSubmittable()`을 지우지 않는다.
- 재시도 횟수·간격(503, 60초 3회)은 이 지침서 범위가 아니다. `responseSchema` 도입으로 JSON 재시도가 빠질 때만 함께 고친다.
- 보관 이미지를 git에 올리거나 테스트 픽스처로 복사하지 않는다.

## 사용자가 결정할 것

- Gemini 유료 키 전환 여부(전환하면 quota 코드 120줄과 상태 파일이 사라진다)
- 모델 고정 해제 여부(루트 `CLAUDE.md`의 "`gemini-2.5-flash-lite` 고정" 제약)

## 의존·순서

- `02`의 태스크 1·2가 먼저다(`from-image`와 MIME 감지가 빠진 상태에서 시작).
- 6번은 `04` 태스크 1(CLAUDE.md 환경변수 표)과 같은 파일을 건드린다.
