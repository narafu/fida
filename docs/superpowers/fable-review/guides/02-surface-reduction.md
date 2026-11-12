# 02. 진입 경로와 표면 줄이기

상태: **2026-11-12 사용자 승인 — `from-image` 삭제(태스크 1), GH Actions 수동 재실행 삭제(태스크 4).** 아래 "사용자 승인 후" 조건은 충족됐다.

대상: 실행 경로 4개 중 3개, KISTA 응답 타입 3벌, 소비처 없는 의존. 근거는 `findings/01` §3~5, §8~9.

## 현재 구조와 문제

파이프라인은 하나인데 들어오는 길이 넷이다.

| 경로 | 용도 | 고유 코드 |
|---|---|---|
| `FandingScheduler` | 정식 경로(화~토 07:00) | 52줄 |
| `POST /api/fida/orders` | 서버 안에서 수동 재실행 | 약 5줄 |
| `FandingRunner` + `job` 프로필 + `fida-schedule.yml` | GitHub Actions 수동 재실행 | Java 33줄 + YAML 69줄 + 테스트 55줄 |
| `POST /api/fida/orders/from-image` | 이미지 업로드로 OCR 후 KISTA 전송 | main 약 96줄 + 테스트 약 70줄 |

- 수동 재실행 수단이 둘이다(`POST /orders`, GH Actions). GH Actions 쪽은 시크릿 9개를 GitHub에 한 벌 더 두고, `SPRING_PROFILES_ACTIVE=job` + `SPRING_MAIN_WEB_APPLICATION_TYPE=none` 조합 제약과 quota 캐시 복원 로직을 끌고 다닌다. 메모리의 "일원화 선호"와 어긋난다.
- `from-image`는 2026-07-10 이후 손대지 않았고 호출 흔적이 없다. 이 경로 하나 때문에 다음이 존재한다.
  - `ProcessImagesUseCase`와 `TradingRecordService.process(byte[], LocalDate)` (`TradingRecordService.java:49-58`)
  - `FromImageResponse` 27줄, `KistaResult` 8필드
  - `GeminiClient.detectMimeType` (`GeminiClient.java:168-183`)
  - 시트·알림을 건너뛰고 KISTA 실패를 예외로 던지는, 정식 경로와 반대인 분기
- `KistaOrderResponse`, `KistaResult`, `FromImageResponse`가 같은 8필드다. 정식 경로가 쓰는 것은 `id`와 `tradeDate` 로그 한 줄뿐이다(`TradingRecordService.java:93`).
- `KistaAdapter.validate()`(`KistaAdapter.java:51-61`)는 요청과 응답을 대조하는데, kista는 저장값이 아니라 요청값을 되돌려준다. 항상 통과하는 검증이고, 2026-07-18에는 이 검증이 리네임된 필드를 읽다가 유일한 계약 사고를 냈다.
- `micrometer-registry-prometheus`와 `prometheus` 엔드포인트 노출은 수집하는 쪽이 없다(`build.gradle.kts:39`, `application.yml:13`).
- `PortContractTest` 118줄은 인터페이스 메서드 시그니처를 리플렉션으로 단언한다. 컴파일러가 이미 보장한다.

## 목표 구조

- 진입 경로 2개: `FandingScheduler`(자동), `POST /api/fida/orders`(수동). 둘 다 `ProcessTradingRecordUseCase.process()` 하나를 부른다.
- `KistaPort.sendOrders()`는 `UUID`를 반환한다. `KistaOrderResponse`는 `record KistaOrderResponse(UUID id)` 한 줄. `KistaResult` 삭제.
- 응답 검증은 `id == null`이면 예외, 그것 하나.
- 프로필 분기 없음. `FandingScheduler`의 `@Profile("!job")` 제거, `@ConditionalOnProperty`는 유지(로컬에서 끄는 용도).

### 그렇게 정한 이유

- 수동 재실행은 서버에서 `docker exec fida wget ... /api/fida/orders` 한 줄로 된다(`deploy/server/README.md` "수동 트리거"). 서버가 죽었을 때는 GH Actions로 돌려도 Gemini·시트·KISTA 쪽 결과가 같으니 쓸모가 있어 보이지만, 100일 무중단이고 그 상황이면 서버 복구가 먼저다.
- 스크래핑이 깨졌을 때의 우회로는 kista admin의 기준표 수동 등록이다(같은 엔드포인트를 admin이 호출한다, `findings/02` §1). `from-image`가 없어도 복구 수단이 남는다.

### 기각한 대안

- **헥사고날 포트 전부 제거.** 재심했고 유지한다. out 포트 6개는 합쳐 64줄이고 서비스 테스트가 이 경계로 목을 건다. 지워서 얻는 줄 수보다 ArchUnit 규칙 재작성과 테스트 수정이 크다. in 포트는 `ProcessImagesUseCase`만 `from-image`와 함께 사라진다.
- **REST 수동 트리거까지 제거하고 스케줄러만.** 재실행 수단이 0이 된다. 기각.
- **`GlobalExceptionHandler` 삭제.** 엔드포인트가 1개로 줄어도 500 메시지 고정(2026-10-30 보안 수정)은 필요하다. 삭제 대신 `from-image` 전용 핸들러만 걷어낸다.

## 예상 감량과 위험

| 항목 | main | test | 기타 |
|---|---|---|---|
| Runner·job 프로필·GH 워크플로 | −33 | −55 | YAML −69, GitHub Secrets 9개 삭제 가능 |
| `from-image` 경로 | 약 −96 | 약 −70 | |
| KISTA 응답 3벌 정리 | 약 −45 | `KistaAdapterTest` 273줄 중 응답 대조 시나리오 | |
| prometheus | −2 | | 의존 1개 |
| `PortContractTest` | | −118 | |
| 합계 | 약 −175 (8%) | 약 −300 (11%) | |

위험:
- `from-image` 삭제는 사용자가 실제로 쓰는지에 달렸다. **삭제 전 사용자 확인 필수.**
- `fida-schedule.yml` 삭제 뒤에는 서버가 내려가 있으면 수동 실행 수단이 없다.
- `detectMimeType`은 `from-image` 때문에 생겼지만 스크래퍼 스크린샷이 항상 PNG인지 확인한 뒤 지운다(`playwright-server/scrape-fanding.js`의 `screenshot` 호출 옵션 확인). PNG가 아니면 남긴다.

## 동작 보존용 선행 테스트

새로 쓸 것은 없다. 다음이 삭제 전후로 통과해야 한다.

- `TradingRecordServiceTest` 중 `process()` 경로 테스트(시트·알림 실패 격리, KISTA 실패 무시)
- `FandingSchedulerTest` 전체(재시도, heartbeat)
- `KistaAdapterTest` 중 요청 직렬화·헤더·전량 주문(null 수량) 테스트

## 태스크 분해

순서대로 한다. 각 태스크 끝에 해당 테스트 클래스만 돌리고, 전체 스위트는 5번 뒤에 1회.

| # | 내용 | 대상 파일 | 모델 | 완료 판정 |
|---|---|---|---|---|
| 1 | `from-image` 경로 제거(사용자 승인 후) | `FidaOrderController`, `FromImageResponse`, `ProcessImagesUseCase`, `TradingRecordService`, `GlobalExceptionHandler`(전용 핸들러만), 해당 테스트, `adapter/CLAUDE.md` 웹 레이어 절 | Sonnet | `bash gradlew test --tests "*FidaOrderControllerTest" --tests "*TradingRecordServiceTest" 2>&1 \| grep -E "FAILED\|BUILD"` |
| 2 | `detectMimeType` 제거 여부 판단 후 처리 | `GeminiClient`, `scrape-fanding.js`(읽기만) | Sonnet | `--tests "*GeminiVisionAdapterTest"` |
| 3 | KISTA 응답을 `id`만 읽게 축소, `KistaResult` 삭제, `KistaPort` 반환형 `UUID`, `validate()`를 null 검사로 교체 | `KistaAdapter`, `KistaOrderResponse`, `KistaResult`, `KistaPort`, `TradingRecordService`, `KistaAdapterTest`, `TradingRecordServiceTest` | Sonnet | `--tests "*KistaAdapterTest" --tests "*TradingRecordServiceTest"` |
| 4 | Runner·`job` 프로필·`fida-schedule.yml` 제거(사용자 승인 후) | `FandingRunner`, `FandingRunnerTest`, `FandingScheduler`(`@Profile` 제거), `.github/workflows/fida-schedule.yml`, 루트 `CLAUDE.md`의 job 관련 제약·Commands, `docker-compose.yml`에 one-shot 전용 설정이 있으면 함께 | Sonnet | `--tests "*FandingSchedulerTest" --tests "*HexagonalArchitectureTest"` |
| 5 | prometheus 의존·노출 제거, `PortContractTest` 삭제 | `build.gradle.kts`, `gradle/libs.versions.toml`, `application.yml`, `PortContractTest` | Haiku | `bash gradlew build 2>&1 \| grep -E "FAILED\|BUILD"` |

4번 뒤에 GitHub Secrets 중 `fida-schedule.yml` 전용 9개는 사용자가 직접 지운다(`findings/03` §5 목록).

## 하지 말아야 할 것

- `FandingScheduler`의 cron, 20분 재시도, heartbeat 위치를 건드리지 않는다.
- `requireSubmittable()`을 지우지 않는다(`01` cross-repo 4번).
- `FidaOrderRequest`의 JSON 필드명을 바꾸지 않는다. kista가 `tradeDate` 별칭으로 받는다.
- 3번에서 응답 `orders` null 검사를 남기지 않는다. 읽지 않는 필드다.

## 의존·순서

- 3번은 `01`의 cross-repo 1번(kista 쪽 `id` 고정 단언)과 짝이다. 순서는 무관하다.
- `03-ocr.md`보다 먼저 한다. 1·2번이 `GeminiClient`와 서비스 테스트를 줄여 둔다.
- 4번은 `04-docs-files-ops.md` 태스크 1(CLAUDE.md 정리)과 같은 파일을 건드린다. 한 디스패치로 묶거나 4번을 먼저 한다.
