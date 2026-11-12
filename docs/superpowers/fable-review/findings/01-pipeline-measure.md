# 01 파이프라인 측정 (코드 수정 없음, 측정 시점 main a1e8bfc, 2026-11-12)

줄 수는 `wc -l`(주석·공백 포함). "약"이 붙은 값은 파일 내 구간을 눈으로 센 추정치.

## 1. 전체 규모

### main (java 41 파일 / 2,064줄) 패키지별
| 패키지 | 파일 | 줄 |
|---|---|---|
| FidaApplication(루트) | 1 | 13 |
| adapter/in/schedule | 2 | 85 |
| adapter/in/web | 4 | 252 |
| adapter/out/ocr | 5 | 733 |
| adapter/out/notify | 1 | 147 |
| adapter/out/kista | 3 | 145 |
| adapter/out/scraper | 1 | 122 |
| adapter/out/sheets | 1 | 129 |
| adapter/out/heartbeat | 1 | 32 |
| application/service | 1 | 100 |
| common | 1 | 28 |
| config | 2 | 68 |
| domain/model | 6 | 108 |
| domain/port/in | 2 | 15 |
| domain/port/out | 6 | 64 |
| domain/exception | 4 | 36 |

OCR 패키지가 main의 35%(733/2064). 상위 파일: GeminiClient 220(프롬프트 약 55줄 포함), OcrResultReconciler 213, TelegramAdapter 147, GlobalExceptionHandler 131, GoogleSheetsAdapter 129, FandingScraperAdapter 122, GeminiResponseParser 122.

### test (java 17 파일 / 2,803줄, @Test 159개)
| 패키지 | 파일 | 줄 |
|---|---|---|
| adapter/out/ocr | 3 | 815 |
| adapter/out/kista | 1 | 273 |
| adapter/out/notify | 1 | 273 |
| adapter/out/scraper | 1 | 268 |
| application/service | 1 | 230 |
| adapter/in/schedule | 2 | 219 |
| adapter/in/web | 2 | 181 |
| domain/port | 1 | 118 |
| domain/model | 1 | 106 |
| adapter/out/sheets | 1 | 124 |
| architecture | 1 | 76 |
| adapter/out/heartbeat | 1 | 69 |
| common | 1 | 51 |

테스트/프로덕션 = 2803/2064 = **1.36배**. (java 합계 4,880줄 = 2,064 + 2,803 + 13? 아님: 2,064+2,803=4,867, wc total 4,880은 FidaApplication 등 루트 포함 방식 차이. 무시 가능.)

### playwright-server (node_modules 제외)
scrape-server.js 86 + scrape-fanding.js 181 = **소스 267줄**. Dockerfile 26, package.json 12, CLAUDE.md 26, package-lock.json 1,209(생성물).
- 디렉토리명은 playwright-server인데 실제 의존성은 `puppeteer ^22.0.0` (package.json:9), 코드도 puppeteer 사용 (scrape-fanding.js:16). 이름-실체 불일치.

### 설정·워크플로·문서 (줄)
build.gradle.kts 60, libs.versions.toml 34, application.yml 63 (+local 3), Dockerfile 33, docker-compose.yml 29, deploy/server/docker-compose.yml 58, Caddyfile 20, deploy/server/README.md 128, .env.example 11, ci.yml 29, fida-schedule.yml 69, server-deploy.yml 225. 인프라/워크플로 합계 약 760줄. CLAUDE.md 계열(루트 외 adapter 38, domain 29, playwright-server 26) 별도. docs/oci-migration-plan.md 105줄(OCI 이전 완료 후 잔존).

## 2. 수직 추적 (호출 순서 + 구간별 줄 수)

호출 순서: Trigger -> `TradingRecordService.process()` (application/service/TradingRecordService.java:41) -> `scraper.scrape()` (:43) -> `processPost` (:61) { `ocr.analyze` (:62) -> `TradingRecord.of` (:63) -> `runIsolated(sheet.update)` (:67) -> `runIsolated(notify.notify)` (:68) -> `sendToKista` (:69,90-99) { `kista.sendOrders` -> `notify.notifyKistaSuccess/Failure` } -> 모인 실패 rethrow (:72-76) } -> Scheduler가 성공 시 `heartbeatPort.ping()` (FandingScheduler.java:49).

| 구간 | 관여 파일(줄) | 합계 |
|---|---|---|
| 트리거 | FandingScheduler 52, FandingRunner 33, FidaOrderController 75, FromImageResponse 27, ProcessTradingRecordUseCase 5, ProcessImagesUseCase 10, OpenApiConfig 19 | 221 |
| 오케스트레이션 | TradingRecordService 100, TradingRecord 14 | 114 |
| 스크래핑 | FandingScraperAdapter 122, ScraperPort 7, ScrapedPost 11, ScraperException 11, RestTemplateConfig 31 + node 267 | Java 182 + Node 267 = 449 |
| OCR | GeminiVisionAdapter 93, GeminiClient 220, GeminiResponseParser 122, OcrResultReconciler 213, GeminiQuotaTracker 85, OcrPort 9, ParsedOrder 23, OrderItem 25, OcrException 11, CommaBigDecimalDeserializer 28 | 829 |
| 검증 | 별도 클래스 없이 3곳 분산: OcrResultReconciler(경고 수집, 213 중 약 150), ParsedOrder.requireSubmittable (ParsedOrder.java 약 12줄), KistaAdapter.validate (응답 대조 약 12줄), OrderItem.quantity 파싱(약 10줄), OcrException 등 | 약 185 |
| 시트 | GoogleSheetsAdapter 129, GoogleSheetsConfig 37, SheetPort 7, SheetException 7 | 180 |
| 알림 | TelegramAdapter 147, NotifyPort 27, NotifyException 7 | 181 |
| KISTA | KistaAdapter 67, FidaOrderRequest 59, KistaOrderResponse 19, KistaResult 17, Order 18, KistaPort 8 | 188 |
| heartbeat | HeartbeatAdapter 32, HeartbeatPort 6 | 38 |

(검증 줄 수는 OCR 합계와 중복 계상됨.) 한 번의 정상 실행이 거치는 파일은 Java 약 28개 + node 2개. 스케줄러 -> 서비스 -> 어댑터는 모두 포트 인터페이스 경유.

## 3. 구현 1개뿐인 인터페이스와 호출처

8개 인터페이스 전부 구현 1개 (프로덕션 구현 / 테스트 대역 제외).

| 인터페이스 | 구현 | 프로덕션 호출처 |
|---|---|---|
| ProcessTradingRecordUseCase | TradingRecordService | 3 (FandingScheduler:43, FandingRunner:25, FidaOrderController:46) |
| ProcessImagesUseCase | TradingRecordService | **1** (FidaOrderController:65) |
| ScraperPort | FandingScraperAdapter | **1** (TradingRecordService:43) |
| OcrPort | GeminiVisionAdapter | 1 파일, 2곳 (service:53, :62) |
| SheetPort | GoogleSheetsAdapter | **1** (service:67) |
| KistaPort | KistaAdapter | 1 파일, 2곳 (service:55, :92) |
| NotifyPort | TelegramAdapter | 7개 파일에서 사용 (7개 메서드) |
| HeartbeatPort | HeartbeatAdapter | **1** (FandingScheduler:49) |

호출처 1곳뿐인 간접층:
- `ProcessImagesUseCase` (10줄) + `TradingRecordService.process(byte[],LocalDate)` (:49-58, 10줄): 컨트롤러 1곳 전용. 하나의 서비스 클래스가 UseCase 2개를 구현하는 구조.
- `HeartbeatPort`(6) + `HeartbeatAdapter`(32): 사실상 `restTemplate.getForObject(url)` 1줄 + try/catch. 스케줄러 1곳.
- `FandingRunner`(33): useCase.process() 위임만. 5절 참조.
- `GeminiClient`(220): GeminiVisionAdapter에서만 호출. `OcrResultReconciler.reconcile()` 정적 팩토리가 내부 인스턴스를 만들어 `reconcile()` 호출 (:31-45)하는 이중 래퍼.
- `GoogleSheetsConfig` @Lazy 3단 설정 (adapter/CLAUDE.md:17): 인터페이스 + @Lazy를 위한 비용으로, TradingRecordService가 `@RequiredArgsConstructor`를 못 쓰고 명시적 생성자 유지 (service:31-38).
- ArchUnit 테스트(76줄) + PortContractTest(118줄)가 이 구조 보호용.

## 4. 같은 개념의 타입 복제 (매매표/주문)

| 타입 | 위치 | 필드 |
|---|---|---|
| ParsedOrder + OrderItem | domain/model | buy/sell(price,qty String), start, pnl, avg, holdings |
| GeminiOrderResult + RawOrderItem + LabeledValue | ocr/GeminiResponseParser.java:74-120 | OCR 원시(buy, sell, sell_rows, capital_rows, performance_rows 등 13필드) |
| TradingRecord | domain/model | date/title/url + ParsedOrder (래퍼) |
| Order (+OrderType, OrderDirection) | domain/model | KISTA 주문 행 (orderType, direction, qty Integer, price) |
| FidaOrderRequest | adapter/out/kista | tradeDate, ticker, start, pnl, avg, holdings, orders |
| KistaOrderResponse | adapter/out/kista | id, releaseDate, ticker, start, pnl, avg, holdings, orders |
| KistaResult | domain/model | id, tradeDate, ticker, start, pnl, avg, holdings, orders |
| FromImageResponse | adapter/in/web | KistaResult와 8필드 완전 동일 |
| Sheets | DTO 없음 (ValueRange 직접 조립, GoogleSheetsAdapter:86-105) | - |

- **{start, pnl, avg, holdings, orders} 필드군이 6곳**(ParsedOrder 일부, FidaOrderRequest, KistaOrderResponse, KistaResult, FromImageResponse, 원시 GeminiOrderResult)에 복제. 완전 동일 8필드가 KistaOrderResponse/KistaResult/FromImageResponse 3벌.
- 매핑 코드: FidaOrderRequest.of+toOrder 약 35줄, KistaAdapter.sendOrders의 Response->Result 필드 나열 3줄(:KistaResult 생성), FromImageResponse.from 6줄, OcrResultReconciler.toOrderItems 7줄 + ParsedOrder 조립 약 3줄, TradingRecord.of 3줄, GoogleSheetsAdapter.buildRangeData 약 25줄(padTo3 포함). **합계 약 80줄**.
- 같은 매핑을 검증하는 테스트에서 `new ParsedOrder/OrderItem/TradingRecord` 생성이 test 전체 62회 (DomainModelTest 22, Telegram 16, Task service 13, KistaAdapter 12, Sheets 11 중복 포함).

## 5. 진입 경로 중복

| 경로 | 존재 근거 / 사용 흔적 | 고유 코드(main) | 고유 테스트 |
|---|---|---|---|
| FandingScheduler (!job, cron 화~토 07:00) | **정식 경로**. CLAUDE.md "Current Status": OCI 커트오버 검증, 2026-08-04 첫 네이티브 스케줄 실행. 20분 재시도, heartbeat | 52줄 (재시도 + ping) | 164 |
| FandingRunner (job 프로필) | 2026-05-10 GH Actions one-shot용으로 신설. GH cron은 2026-08-03 제거(`fida-schedule.yml`은 workflow_dispatch만), 이후 Runner 마지막 변경 2026-10-30 refactor. 수동 재실행 때만 사용 | 33줄 | 55 |
| fida-schedule.yml (workflow_dispatch) | cron 제거(2026-08-03). 단, 2026-09-25 "수동 재실행 워크플로의 quota 파일 권한 오류 수정" 커밋 존재 = 한동안 실제 사용됨. 이후(10-30)는 보안 수정에서만 변경. 서버에 secrets/.env를 매번 생성하는 69줄 | 0 (YAML 69줄, cache 복원/저장 ~15줄) | - |
| REST POST /api/fida/orders | 서버 내 수동 트리거. README(deploy/server/README.md:108)에 서버 컨테이너 내부 호출 절차 문서화. CLAUDE.md 2026-08-04 수동 트리거로 전체 파이프라인 검증 | 컨트롤러 :43-47 (약 5줄) + OpenAPI 설명 | FidaOrderControllerTest 105 일부 |
| REST POST /orders/from-image | 마지막 변경 2026-07-10 (빈 파일 거부). 워크플로·스케줄 사용 없음. 수동 OCR 재분석용. 서비스 쪽은 시트·알림 생략하고 KISTA 즉시 전송, KISTA 실패를 예외 전파 (service:49-58) | Controller :49-75 약 27줄 + FromImageResponse 27 + ProcessImagesUseCase 10 + service.process(image) 10 + GeminiClient.detectMimeType 약 22줄(주석: from-image 때문에 필요, GeminiClient:132 주변) = **약 96줄** | Controller 테스트 일부 + service 테스트 일부 + MIME 테스트 2개 (약 70줄) |

- 4개 진입점 중 "서비스 호출만 하는" 얇은 어댑터가 3개(Scheduler/Runner/Controller). 실질 중복은 `try { useCase.process() } catch { notifyApplicationFailure }` 형태가 Runner와 Scheduler.execute에 복붙 (FandingRunner:24-31, FandingScheduler:41-48).
- Runner + GH Actions: GH 인프라(workflow 69줄, `SPRING_PROFILES_ACTIVE=job` 조합 CLAUDE.md 제약 2줄, `job` 프로필 분기 `@Profile` 2곳, Quota 캐시 로직 주석)가 "수동 재실행 전용" 한 가지 용도를 유지하기 위해 존재. 서버에서 `POST /orders` 로 동일 효과.

## 6. 테스트

### 상위 15 (줄)
1. GeminiVisionAdapterTest 726 (@Test 40)
2. TelegramAdapterTest 273 (15)
3. KistaAdapterTest 273 (17)
4. FandingScraperAdapterTest 268 (13)
5. TradingRecordServiceTest 230 (12)
6. FandingSchedulerTest 164 (10)
7. GoogleSheetsAdapterTest 124 (6)
8. PortContractTest 118 (12)
9. DomainModelTest 106 (7)
10. FidaOrderControllerTest 105 (5)
11. GlobalExceptionHandlerTest 76 (5)
12. HexagonalArchitectureTest 76
13. HeartbeatAdapterTest 69 (4)
14. FandingRunnerTest 55 (3)
15. GeminiQuotaTrackerTest 53 (3)  (CommaBigDecimalDeserializerTest 51, OcrResultReconcilerTest 36 이하)

비율 1.36배. 파일당 평균 165줄, 테스트당 평균 17.6줄.

### 구성 (전체 2,803줄)
- import/package 296, 빈 줄 494, @Test/@DisplayName 337, 나머지(본문) 1,676.
- **GeminiVisionAdapterTest 726줄 중 대부분이 어댑터가 아닌 하위 단위(Reconciler/Parser/Prompt)를 어댑터 입구로 통과시켜 검증**: 보유수량/현사이클/sell_rows 보정 시나리오 약 15개(:229-:526, 약 300줄), 재시도·503·파싱 실패 약 8개(약 150줄), 프롬프트 문자열 포함 검증 4개(:135,:143,:420,:448 등 약 50줄). 같은 입력 JSON 문자열을 테스트마다 반복 세팅.
- 한편 전용 단위테스트 OcrResultReconcilerTest는 36줄/1개뿐 — 로직 소재지와 테스트 소재지가 어긋남.
- 반복 유형 (대략):
  - 도메인 객체 세팅 반복 (`new ParsedOrder(...)`/`OrderItem`/`TradingRecord` 62회, 호출당 3~6줄): 약 250줄
  - RestTemplate mock + `ReflectionTestUtils.setField` 19회 + `@BeforeEach` 5개 (non-final @Value 컨벤션 부작용): 약 150줄
  - 같은 JSON fixture를 시나리오마다 복제(Gemini 응답 문자열): 약 300줄
  - PortContractTest 118줄: 인터페이스 메서드 시그니처를 리플렉션으로 단언 — 컴파일러가 이미 보장 (사실상 0가치), ArchUnit 76줄과 별개.
  - 목 선언 총 39개, 파일 간 반복 구도.
- 테스트 없는 대상: GlobalExceptionHandler는 76줄 테스트 있음; 노드 쪽(267줄)은 테스트 0.

## 7. 부가 기능별 줄 수와 근거

| 기능 | main 줄(약) | 테스트 줄 | 근거 (실제 실패/사례) |
|---|---|---|---|
| Gemini quota 추적 | GeminiQuotaTracker 85 + GeminiClient.recordQuota 약 12 + 상수 3 + NotifyPort.notifyGeminiQuota/Telegram 약 6 + yml/워크플로 cache 복원·저장 약 15 = **약 120** | 53 | CLAUDE.md "Gemini HTTP 429 -> OcrException 분류"; git: `2026-07-11 Gemini 429 할당량 초과`, `2026-08-26 quota 알림 오인 방지`, `2026-09-25 quota 상태 파일 권한 오류`(GH 수동 재실행) , `2026-10-30 재시도·quota 결함 수정`. 한도 20/일은 무료 티어 추정값 하드코딩(GeminiQuotaTracker `dailyLimit=20`), 알림 임계값 5. |
| OCR 재시도 (503/JSON/holdings 누락) | GeminiVisionAdapter 루프 약 55줄 + sleepQuietly 7 | 약 150 | `2026-06-26 503 1분 간격 3회 재시도`; `2026-08-14 holdings 누락 시 재요청`(운영 2026-08-14). 재시도 지연 60s 하드코딩 (retryDelayMs=60_000, 테스트가 리플렉션으로 0 세팅). |
| OCR 보정/이상치 경고 | OcrResultReconciler 213 + GeminiResponseParser 122 + 프롬프트 약 55 = **약 390** | 약 450 (대부분 GeminiVisionAdapterTest) | 운영 사례가 코드 주석에 날짜로 박혀 있음: 2026-05-28(sell 부분 누락), 07-30(current_cycle_start 10배 오판독), 08-21/09-21/09-28(누적개수 환각, 금액이 수량 필드로), git `2026-07-03/04 holdings 오파싱`, `2026-08-22`, `2026-09-17/21`. **가장 실패 근거가 두꺼운 영역** — 비대의 원인이 실제 OCR 오류 이력. |
| Sheets 503 재시도 | GoogleSheetsAdapter:30-70 약 40줄 | Sheets 테스트 일부 | git `2026-08-26 Sheets 503 재시도 추가`. 단일 사례. |
| 스케줄러 20분 후 재시도 | FandingScheduler 약 12줄 + TaskScheduler 빈(config) | 164 중 약 70 | git `2026-10-30 시트·알림 실패 분리 + 스케줄 실패 시 1회 재시도`; CLAUDE.md "재실행 안전 근거: KISTA 멱등, 시트 덮어쓰기". |
| 알림 (Telegram 7종) | TelegramAdapter 147 + NotifyPort 27 + NotifyException 7 + 토큰 마스킹 약 10 = **약 190** | 273 | notifyKistaFailure(별도 알림)·notifyOcrWarning·notifyGeminiError·notifyGeminiQuota·notifyApplicationFailure는 모두 "best-effort sendQuietly". 토큰 유출 근거: git `2026-10-30 fix(security): 텔레그램 봇 토큰 유출 차단`. |
| KISTA 응답 대조 + requireSubmittable | 약 25 | KistaAdapterTest 273 중 일부 | CLAUDE.md "KISTA 409/멱등", 도메인 불변식 이동 `2026-10-30 refactor`. |
| heartbeat | 38 | 69 | git `2026-08-03 HeartbeatPort 추가`; CLAUDE.md dead-man's switch, 2026-08-05 실제 스케줄 검증 대기 문구가 남아 있음(오래됨). 근거: 2026-08-04 07:00 첫 네이티브 실행. |
| 에러 응답 매핑 (REST 전용) | GlobalExceptionHandler 131 (@ExceptionHandler 9개) | 76 | 2개 엔드포인트용. 스케줄러/Runner 경로에서는 쓰이지 않음. git `2026-10-30 500 응답 메시지 고정`. |
| 배포 안전장치 | server-deploy.yml 225 (배포 차단 시간창, 헬스게이트, 롤백) | - | CLAUDE.md: 06:50~07:45 KST 차단, 2026-08-04 배포 결함 2건. |

합산하면 "부가 기능" 관련 코드가 main 2,064줄의 대략 55~60% (OCR 보정 390 + 재시도 55 + quota 120 + 알림 190 + Sheets 재시도 40 + 에러 매핑 131 + heartbeat 38 + 스케줄 재시도 12 등, 일부 중복).

## 8. 외부 의존·자원

build.gradle.kts / libs.versions.toml (Spring Boot 3.4.4, Java 21):
- implementation: spring-boot-starter-web, starter-actuator, google-api-client 2.7.0, google-api-services-sheets v4-rev20240514-2.0.0, springdoc-openapi-webmvc-ui 2.8.4, micrometer-registry-prometheus
- Lombok 1.18.36
- test: spring-boot-starter-test, archunit-junit5 1.3.0
- **micrometer-prometheus**(build.gradle.kts:39) + `management.endpoints.web.exposure.include: health, info, metrics, prometheus` (application.yml:13): 스크레이프 설정·소비처가 레포 어디에도 없음. Caddy는 /actuator/health만 외부 공개 (Caddyfile:5). 사용처 없는 의존으로 의심.
- springdoc: 로컬 Swagger UI 전용 (`@Tag/@Operation` 어노테이션이 컨트롤러 75줄 중 약 25줄, OpenApiConfig 19줄). 서버에서는 Caddy가 차단.

playwright-server/package.json: dependencies `puppeteer ^22.0.0` 1개. Dockerfile `FROM node:20-slim` + apt chromium. 
Dockerfile(Java): builder `eclipse-temurin:21-jdk-alpine` (--platform=$BUILDPLATFORM), runtime `eclipse-temurin:21-jre-alpine`, `JAVA_OPTS=-XX:MaxRAMPercentage=75.0 ...` (Dockerfile:26-33), ZGC.

자원 설정:
| 서비스 | 로컬 docker-compose.yml | 서버 deploy/server/docker-compose.yml |
|---|---|---|
| fida | mem_limit 없음 | **mem_limit 1536m** (주석: 평시 약 360MiB) |
| playwright-server | 없음 | **mem_limit 3g** (주석: 피크 약 1.2GiB, 이전 1.8GiB로 1536m에서 OOM, 2026-10-31) |
| caddy | - | caddy:2.9-alpine, mem_limit 64m |
호스트 6GB 중 fida 1.5G + 스크래퍼 3G + caddy 64M = 약 4.6GB 상한 예약. 실사용(평시 360MiB, 스크래핑 중 Chromium 1.2GiB)은 하루 1회·수 분.

## 9. 구조적 이상 목록

1. **PortContractTest (118줄)**: 인터페이스 메서드 이름/반환 타입을 리플렉션으로 단언 — 컴파일러가 이미 보장하는 내용. 삭제 후보.
2. **인터페이스 8개 전부 단일 구현, 5개는 호출처 1곳** (3절). ArchUnit(76줄)이 헥사고날 방향을 강제하지만 교체 가능성(다른 OCR/시트 구현)은 이력상 없음. 진입점 + 포트 + 어댑터 = 한 기능 3~4파일 구조.
3. **KistaResult / KistaOrderResponse / FromImageResponse 동일 8필드 3벌** + FidaOrderRequest 7필드 (4절): 전부 같은 개념, 필드 나열 매핑 약 15줄.
4. **FandingRunner + fida-schedule.yml + `job` 프로필 분기**: 정식 경로가 서버 스케줄러로 바뀐 뒤 수동 재실행 전용. REST /orders가 동일 효과. Runner와 Scheduler.execute에 같은 try/catch/notifyApplicationFailure 복붙.
5. **/orders/from-image 경로 전체 (약 96 main + 약 70 test)**: 마지막 변경 2026-07-10, 워크플로·스케줄 사용 흔적 없음. TradingRecordService가 이 경로 때문에 UseCase 2개 구현 + 시트/알림 생략 분기를 가짐.
6. **OcrResultReconciler 정적 팩토리 + 내부 인스턴스 이중 구조** (`reconcile(raw)` -> `new ...().reconcile()`), `warnings` 상태 필드로 가변 인스턴스. 순수 함수 한 번 호출용 객체화.
7. **OCR 응답 모델이 중복적**: buy/sell + sell_rows, capital_rows + 개별 필드, performance_rows + current_cycle_realized_pnl 등 프롬프트가 "같은 값을 두 방식으로 요청"하고 Reconciler가 재조정(예: current_cycle_start는 직접 필드 -> capital_rows 폴백, pnl은 performance_rows 우선). 비대의 구조적 원인.
8. **GeminiVisionAdapterTest 726줄이 Reconciler/Parser/프롬프트를 어댑터 입구에서 검증**, 전용 OcrResultReconcilerTest는 36줄.
9. **micrometer-prometheus 의존 + prometheus 엔드포인트 노출**: 소비처 없음.
10. **네이밍 불일치**: playwright-server 디렉토리/서비스명/CLAUDE.md 용어 vs 실제 puppeteer 사용.
11. **CLAUDE.md "@Value non-final" 컨벤션** -> `ReflectionTestUtils` 19회 + 필드 주입으로 테스트 보일러플레이트, `final+@Value` 금지 이유가 "CI에서 copyableAnnotations 미적용"으로 환경 의존 우회.
12. **GoogleSheetsConfig `@Bean @Lazy` + `@Component @Lazy` + `@Lazy SheetPort` 3단 설정** (adapter/CLAUDE.md:17): 시크릿 파일 읽기 시점 때문에 TradingRecordService 생성자 수동 유지. 시트 경로에 파일 의존이 있는 한 유지되는 복잡성.
13. **docs 잔재**: docs/oci-migration-plan.md 105줄(이전 완료), CLAUDE.md "Current Status" 장문 이력(약 20줄)이 코드와 중복 설명; 2026-08-05 검증 대기 문구가 3개월 지난 채 남음.
14. **GlobalExceptionHandler 131줄 / @ExceptionHandler 9개**: REST 엔드포인트 2개(둘 다 수동 용도)를 위한 것. 스케줄러 경로에서는 미사용.
15. 노드 사이드카 267줄은 테스트 없음, execFile로 별도 프로세스 스폰하는 구조(scrape-server.js: 요청마다 `node scrape-fanding.js` 실행).

## 한 줄 요약 숫자
main 2,064줄 / 41파일, test 2,803줄 / 17파일(1.36배), node 267줄, 인프라 YAML 약 760줄. 인터페이스 8개 모두 단일 구현, 진입점 4개(스케줄러·Runner·REST 2), 타입 복제 6벌, 가장 큰 덩어리는 OCR(733줄, 35%)과 그 테스트(815줄).
