# 02. fida ↔ kista-api 계약 조사 (읽기 전용, 2026-11-12)

경로 약어: F = `/Users/phs/workspace/fida/src/main/java/com/fida`, K = `/Users/phs/workspace/kista/kista-api`, KT = `K/trading-core/src/main/java/com/kista/tradingcore`, KS = `K/shared/src/main/java/com/kista/shared`.

## 1. 엔드포인트·인증·상태 코드·멱등

| 항목 | fida(송신) | kista-api(수신) |
|---|---|---|
| URL/메서드 | `POST {kista.url}/api/internal/fida-orders` (F/adapter/out/kista/KistaAdapter.java:20-21,35; `kista.url` 기본 `https://api.kista-app.com`, fida application.yml:52-54) | `@PostMapping("/fida-orders")` under `/api/internal` (KT/privacy/adapter/in/web/FidaOrderController.java:25-26,38) |
| 인증 | 헤더 `X-Internal-Token: ${INTERNAL_API_TOKEN}` (KistaAdapter.java:22,62-63) | `InternalTokenAuthFilter` (KS/platform/security/InternalTokenAuthFilter.java:21-52): `/api/internal/**` 전체에 적용, 토큰 불일치/빈값이면 **401 (바디 없음)**. SecurityConfig.java:49 `hasRole("INTERNAL")`. 헤더명 상수는 `InternalApiProperties.TOKEN_HEADER` (KS/platform/internalapi/InternalApiProperties.java) |
| 성공 | `restTemplate.postForObject` — 2xx면 모두 성공으로 취급, 200/201 구분 안 함 (KistaAdapter.java:39) | 신규 저장 201, 동일 내용 재전송 200 (FidaOrderController.java:44-46; PrivacyTradePersistenceAdapter.java:50-55) |
| 409 | fida는 별도 처리 없음. `HttpClientErrorException`이 그대로 전파 → 스케줄러 실패 알림(프로젝트 CLAUDE.md의 "409 실패 알림") | `PrivacyTradeConflictException` → 409 (KT/web/TradingExceptionHandler.java:74 근처, 매핑 `PrivacyTradeConflictException -> CONFLICT`) |
| 400 | fida 측 사전 차단: `parsedOrder.requireSubmittable()` (FidaOrderRequest.java:30) | Bean Validation 실패 → 400 ProblemDetail (TradingExceptionHandler `MethodArgumentNotValidException`); 방어 규칙 BLOCKING → `IllegalArgumentException` → 400 (PrivacyService.java:27-34) |
| 멱등 키 | 없음 (fida는 재시도 시 같은 요청 재전송) | `(release_date, ticker)` 유니크 (PrivacyTradeBaseEntity.java:22, V1__init.sql:430). 존재 시 내용 비교(`isIdentical`, PrivacyTradePersistenceAdapter.java:79-99): 금액은 scale 2 HALF_UP 정규화 비교(sameMoney, :101-107), 주문은 BUY→SELL·가격 정렬 후 순서대로 비교. 같으면 200(기존 id), 다르면 409 |
| 응답 검증 | fida가 응답을 재검증: `tradeDate==releaseDate`, `holdings`, `orders.size()` 불일치면 IllegalStateException (KistaAdapter.java:51-61); 응답 null, `orders==null`도 예외 (:40-45) | 응답은 요청 echo + 저장 id (PrivacyContractMapper.toResponse, :36-42). 따라서 fida의 응답 검증은 사실상 항상 통과(echo) — 저장값이 아니라 요청값을 되돌려주기 때문에 DB 반올림·정렬 결과는 검증되지 않음 |
| 라우팅 | fida는 공인 도메인 호출 | Caddy가 `/api/internal/fida-orders` 만 kista-trading(:8080)으로, 나머지는 kista-api로 (K/deploy/server/caddy/kista-api.caddy:20-21). 2026-09-17 라우팅 404 사고 이력(caddy:3-4) |
| 타임아웃 | RestTemplate connect 10s / read 120s (F/config/RestTemplateConfig.java:16-17), 재시도는 상위 `FandingScheduler` 20분 후 1회 | - |

참고: kista 내부의 admin(`PrivacyQueryHttpAdapter.createBase`, K/src/main/java/com/kista/api/admin/adapter/out/internal/PrivacyQueryHttpAdapter.java:42-59)도 동일한 `/api/internal/fida-orders`를 호출한다. 즉 이 엔드포인트의 소비자는 fida + kista-api admin 2곳이다.

## 2. 페이로드 필드 대조표

### 2-1. 요청 (fida `FidaOrderRequest` ↔ kista `FidaOrderRequest`)

fida: F/adapter/out/kista/FidaOrderRequest.java:15-23 (package-private record). kista: KS/contract/privacy/FidaOrderRequest.java:19-27. kista 도메인 복제본 `FidaOrderCommand`: KT/privacy/domain/model/FidaOrderCommand.java:15-23.

| JSON 필드 | fida 타입 | kista 타입 (contract) | nullable | kista 검증 | 불일치 |
|---|---|---|---|---|---|
| tradeDate / releaseDate | `LocalDate tradeDate` | `LocalDate releaseDate` + `@JsonAlias("tradeDate")` | fida non-null(record.date()) / kista `@NotNull` | `@NotNull` | **이름 불일치(alias로 흡수)**. 의미 불일치 위험: fida는 "tradeDate"(A1 날짜 = 제목 M/D)로 보내고 kista는 "releaseDate(발행일, 거래일 D-1)"로 저장 (PrivacyDates.releaseDateFor=거래일-1일, tradeDateOf=발행일+1일 (KT/privacy/domain/model/PrivacyDates.java:11-18); 읽기 시 PrivacyTradePersistenceAdapter.findTodayTrade에서 변환). fida 응답 검증은 이 둘을 같은 값으로 비교 |
| ticker | `String` ("SOXL" 상수) | `StrategyTicker` enum | 둘 다 non-null | `@NotNull` | 타입 상이(String ↔ enum). 알 수 없는 문자열은 Jackson 400 |
| currentCycleStart | `BigDecimal` | `BigDecimal` | kista non-null | `@NotNull @Positive` | fida 쪽 null/0 방어 없음(OCR 값이 0 이하면 kista 400) |
| currentCycleRealizedPnl | `BigDecimal` (null→ZERO 치환, :46) | `BigDecimal` | non-null | `@NotNull` | 일치 (fida가 null을 0으로 바꿔 보냄 — 의미 손실 가능) |
| avgPrice | `BigDecimal` | `BigDecimal` | nullable 양쪽 | 없음 | 일치 |
| holdings | `int` | `int` | non-null | `@PositiveOrZero` | 일치 |
| orders | `List<Order>` | `List<PlannedOrder>` | fida 항상 non-null / kista contract는 null 허용 | **없음**(`@NotNull` 없음) | **kista 취약점**: contract는 `orders==null`을 허용(isBuyQuantityValid도 null 통과)하나 `PrivacyTradeValidationService.inspect`(:16-19)와 `saveBaseWithOrders`(PrivacyTradePersistenceAdapter.java:63)가 `command.orders().stream()`을 바로 호출 → null이면 NPE(500). fida는 항상 리스트를 보내 실제 발생은 없으나 admin 등 다른 호출자 기준 계약 구멍 |
| orders[].orderType | `Order.OrderType` {LOC,MOC,LIMIT} (fida 항상 LIMIT, FidaOrderRequest.java:57) | `OrderType` (kista 공용 kernel enum) | kista 선언상 nullable | 없음 | kista `OrderType`(KS/kernel/OrderType.java)은 LOC/MOC/LIMIT/MOO/MARKET 5값, fida는 3값 — fida→kista 방향은 부분집합이라 안전하나 응답은 String 에코라 무관. 반대로 admin이 MOO/MARKET으로 등록한 기준표는 fida 쪽 `Order.OrderType`으로는 역직렬화 불가(현재 fida는 kista 조회 경로가 없어 비해당). DB `order_type NOT NULL` 이므로 null이면 500 |
| orders[].direction | `Order.OrderDirection` {BUY,SELL} | `OrderDirection` kernel enum | nullable 선언 | 없음 | DB NOT NULL |
| orders[].quantity | `Integer` ("전부"→null) | `Integer` | nullable | 커스텀 `@AssertTrue isBuyQuantityValid`: BUY의 null 금지 | 일치. 추가로 SELL null은 최대 1건 (ValidationService, BLOCKING) |
| orders[].price | `BigDecimal` (null→ZERO, :58) | `BigDecimal` | 선언상 nullable | 없음 | DB `price NOT NULL numeric(12,2)`. fida는 null→0으로 보내서 통과; 0원 주문이 저장될 수 있음 |
| (kista 전용 검증) | - | 도메인 방어 규칙: SELL null 다건 BLOCKING, holdings>0 & SELL 없음 WARNING, holdings==0 & SELL 존재 BLOCKING, 명시 SELL 합>holdings BLOCKING (PrivacyTradeValidationService.java:30-70) | | | fida 쪽 `requireSubmittable()`와 규칙이 부분 중복(규칙 정의가 두 레포에 별도 존재, 동기화 장치 없음) |

### 2-2. 응답 (fida `KistaOrderResponse` ↔ kista `FidaOrderResponse`)

fida: F/adapter/out/kista/KistaOrderResponse.java:11-19. kista: KS/contract/privacy/FidaOrderResponse.java:14-23 (+ 내부 `OrderItem` :25-39). fida는 이를 다시 도메인 `KistaResult`(F/domain/model/KistaResult.java:10-19)로 복사.

| JSON 필드 | fida 타입 | kista 타입 | 불일치 |
|---|---|---|---|
| id | `UUID` | `UUID` | 일치 |
| releaseDate | `LocalDate releaseDate` → KistaResult.`tradeDate` | `LocalDate releaseDate` | fida 내부에서 필드명이 releaseDate→tradeDate로 다시 바뀜(요청 쪽 이름과 응답 쪽 이름이 비대칭: 요청 tradeDate, 응답 releaseDate) |
| ticker | `String` | `StrategyTicker` enum | 직렬화 문자열로 호환 |
| currentCycleStart | `BigDecimal` | `BigDecimal` | 일치 |
| currentCycleRealizedPnl | `BigDecimal` | `BigDecimal` | 일치 |
| avgPrice | `BigDecimal` | `BigDecimal` | 일치 (nullable) |
| holdings | `int` | `int` | 일치 |
| orders | `List<Order>` (fida 도메인 Order: orderType enum, direction enum, Integer quantity, BigDecimal price) | `List<OrderItem>` (direction **String**, orderType **String**, Integer, BigDecimal) | kista는 enum이 아니라 String으로 에코, fida는 enum으로 역직렬화 → 알 수 없는 값이면 fida 쪽 역직렬화 실패. **필드 순서도 상이**(fida Order: orderType,direction,quantity,price / kista: direction,orderType,quantity,price) — JSON이라 무해 |
| 상태 코드 | 200/201 구분 없음 | 201/200 | fida는 "신규 저장 vs 기존 멱등"을 알림에 쓰지 않음 (KistaResult에 created 플래그 없음) |

역직렬화는 양쪽 모두 unknown 필드 무시 (Spring Boot 기본 FAIL_ON_UNKNOWN=false, KT/privacy/domain/model/FidaPlannedOrder.java:6-7 주석). 계약 변경 시 컴파일러가 잡아주는 연결이 없고(별개 레포·타입 복제), kista 쪽 contract 주석에 "외부 FIDA 프로젝트도 호출하므로 필드명·shape을 바꾸지 말 것"(KS/contract/privacy/FidaOrderRequest.java:17)이 유일한 장치. kista 쪽 `ContractWireFormatTest`(K/shared/src/test/java/com/kista/shared/contract/ContractWireFormatTest.java:26-47)가 `tradeDate` 별칭 역직렬화를 고정한다. fida 쪽은 `KistaAdapterTest`(F 테스트)가 Mockito로 RestTemplate을 목킹해 자기 record만 검증하므로 두 레포를 잇는 공유 계약 테스트(스키마·픽스처 공유)는 없다.

## 3. 계약 타입 복제 수

같은 "FIDA 주문 기준표" 개념을 나르는 타입(레포 · 계층 · 파일:줄 수):

| # | 레포 | 계층 | 타입 | 줄 수 |
|---|---|---|---|---|
| 1 | fida | adapter 요청 DTO | `FidaOrderRequest` (+ 정적 팩토리 `of`, 변환 로직 포함) | 59 |
| 2 | fida | adapter 응답 DTO | `KistaOrderResponse` | 19 |
| 3 | fida | 도메인 결과 | `KistaResult` (응답 DTO를 그대로 복사한 8필드 record) | 19 |
| 4 | fida | 도메인 주문 | `Order` (+ 중첩 enum OrderType·OrderDirection) — 요청·응답·결과 3곳이 공유 | 18 |
| 5 | kista | contract(shared) 요청 | `FidaOrderRequest` + 중첩 `PlannedOrder` | 43 |
| 6 | kista | contract(shared) 응답 | `FidaOrderResponse` + 중첩 `OrderItem` | 40 |
| 7 | kista | 도메인 command | `FidaOrderCommand` (contract 요청과 필드·검증 애너테이션·`@AssertTrue`까지 동일 복제) | 32 |
| 8 | kista | 도메인 주문 | `FidaPlannedOrder` (=`PlannedOrder` 복제) | 16 |
| 9 | kista | 영속 엔티티 | `PrivacyTradeBaseEntity` + `PrivacyTradeBaseOrderEntity` | 55 + 45 |
| 10 | kista | 도메인 조회 | `PrivacyTradeBase`(+내부 PrivacyTrade), `PrivacyTradeBaseView`(+OrderLine) | 44 + 27 |
| 11 | kista | contract 조회 응답 | `PrivacyTradeBaseResponse`(+OrderLine) | 26 |
| 12 | kista | admin 응답 | `AdminPrivacyBaseResponse` | 52 |

- 같은 개념의 record/class: fida 4개(자체 3 + 공유 Order) + kista 최소 9개 종류(5~8의 4개 + 엔티티 2 + 조회 3~4개) ≈ **양쪽 합쳐 약 13~15개 타입**. fida→kista 방향 **요청 한 번에 거치는 표현**은 fida 1(`FidaOrderRequest`) → kista contract 1 → command 1 → entity 1(+order entity) → 응답 contract 1 → fida 응답 DTO 1 → fida KistaResult 1 = **7개 표현**.
- kista-api 안에서 Fida* 이름 타입은 **3계층에 반복**: ① shared contract(`FidaOrderRequest/Response`, 83줄) ② trading-core 도메인(`FidaOrderCommand`, `FidaPlannedOrder`, 48줄) ③ 영속(Privacy* 엔티티, 이름은 Privacy지만 동일 필드). 4번째로 admin(`:api`)이 `createBase`에서 contract `FidaOrderRequest/Response`를 import해 재사용 (PrivacyQueryHttpAdapter.java:4-5).
- 직접 복제 증거: `FidaOrderCommand`와 contract `FidaOrderRequest`는 동일한 필드 7개·애너테이션·`isBuyQuantityValid` 로직을 두 번 작성(FidaOrderRequest.java:19-34 vs FidaOrderCommand.java:15-31). 이 둘 사이를 `PrivacyContractMapper.toCommand(FidaOrderRequest)`(62줄 파일의 :44-49)가 필드 단위로 복사. 응답은 `toResponse(UUID, FidaOrderCommand)`로 command→contract 재변환.
- fida 쪽: `KistaOrderResponse → KistaResult`는 8필드를 1:1 복사(KistaAdapter.java:46-48).
- 유래: kista 커밋 d35db2047(2026-09-30)이 root↔trading-core 간 own-type 20쌍을 :shared contract로 단일화했으나, 외부(fida)와의 경계는 구조상 공유 불가라 별도 유지.

## 4. 스키마 어긋남·계약 관련 이력 (날짜순, `git log` 기준)

kista-api (`git log -i --grep=fida`, 발췌) / fida (`git log -- adapter/out/kista ...`):

| 날짜 | 레포 | 커밋 | 내용 | 한쪽만 먼저 바뀐 흔적 |
|---|---|---|---|---|
| 2026-04-24~28 | kista | 36ebf5e64, c093c6ff1, cdc22a9c7 | FidaOrderRequest·UseCase 신설 | 초기 구축 |
| 2026-04-29 | fida | 1c7fd85, e72e5d6 | KistaAdapter·도메인 신설 | 초기 구축 |
| 2026-05-21 | 양쪽 | kista d9444f5c1·35c52c9fb·8e1d7d4be(멱등)·5e2b60f36(BUY null 검증)·925b14919(응답에 master 정보)·b803c47c5(스키마 불일치 해소) / fida 6170823(헤더 INTERNAL_API_TOKEN)·d38f4df(응답 검증)·2b51a6a(전량 주문) | 하루에 양쪽이 연달아 수정. 같은 날 fida `fix: KistaAdapter 수정(INTERNAL_API_TOKEN 헤더 추가)`, `fix: 환경변수 오류 수정`, `@Value final 제거` 3건이 이어진 것은 송수신 어긋남 보정 흔적 |
| 2026-05-22 | kista | e70baccad | avg_price NOT NULL 제약 해소 (avgPrice nullable화) | kista DB 제약이 계약(avgPrice nullable)과 어긋나 있던 것을 사후 수정 |
| 2026-05-26 | kista | 4c62b1ea7 | tradeDate KST↔UTC 명시 변환 도입 | 날짜 의미 변경 (계약 의미 영향) |
| 2026-06-14 | kista | 819c55d03 | **false 409**: 조회가 `>=`라 미래 레코드를 잡아 409 오발생 → `=`로 수정 | kista 단독 버그, fida 영향 = 실패 알림 |
| 2026-06-27 | fida | d947e36 | currentCycleStart null/0이면 전송 전 IllegalArgumentException (kista `@Positive` 400 회피·원인 명확화) | **OCR 값 0을 보내 kista 검증 400** — fida가 kista 검증을 사후에 학습 |
| 2026-07-18 20:01 | kista | 1db233f85 | `tradeDate`→`releaseDate` 리네임. 요청은 `@JsonAlias("tradeDate")`로 하위호환, **응답 필드는 단순 리네임** | **한쪽만 먼저 바뀜** |
| 2026-07-18 21:21 | fida | b275d0f | "kista-api 응답 필드 tradeDate→releaseDate 리네임 반영": 응답 `tradeDate`가 Jackson에 조용히 무시돼 null → `KistaAdapter.validate()`가 매번 IllegalStateException | **장애·수정 흔적 확정**(fida 커밋 메시지가 버그 존재를 명시): kista 커밋 후 80분 뒤 fida 수정. 단 2026-07-18은 토요일 저녁이라 스케줄 창(화~토 07:00)과 겹치지 않아 실제 파이프라인 실패 여부는 미확인(실행 로그 없음). 응답 역직렬화가 unknown 필드 무시라 컴파일/테스트로 안 잡히고 런타임 검증에서 터짐. 이 시차에 fida가 실행되면 kista에는 저장됐는데 fida는 실패 처리되는 상태가 가능 (kista는 멱등이라 재시도 200) |
| 2026-07-18 20:05 | kista | 23b3f78ed | 별칭 테스트 치환 방향 교정 | 위 리네임 직후 테스트가 잘못된 방향을 검증하고 있었음 |
| 2026-08-04 | fida | defc171, 6d97545 | KISTA_URL 죽은 fly.dev 폴백 제거 (api.kista-app.com) | 배포 설정 어긋남(조용한 폴백) |
| 2026-09-08 | kista | 3e9f11f05 | **409 오탐**: FIDA 재계산 평단 115.318 vs 저장값 115.32(numeric(12,2) 반올림) → 멱등 비교 scale 2 HALF_UP 정규화 | kista 단독, DB 스케일과 계약(BigDecimal 무제한)의 불일치가 원인 |
| 2026-09-15~17 | kista | de693b5c3, f631ba52c, 8a5ab07c8 (+Caddy 주석 "2026-09-17 fida-orders 404") | trading-core 별도 프로세스 분리 후 `/api/internal/fida-orders` 라우팅이 kista-api로 가서 404 | **kista 내부 구조 변경이 fida 호출 경로를 깨뜨림** — 이후 `CaddyRoutingTest`로 경로 대조 (caddy 파일 :3-4) |
| 2026-09-30 | kista | d35db2047 | wire 계약을 `com.kista.contract`(shared)로 단일화, "FidaOrderRequest는 외부 FIDA 호출 shape(tradeDate alias 포함) 유지" | 외부 계약 보존 의식이 생김 |
| 2026-10-30 | fida | 83c0502, 578ccc5 | 시트·알림 실패가 KISTA 전송을 막지 않도록 분리, 스케줄 실패 시 1회 재시도, 수량·전송 불변식을 도메인으로 이동 | 계약 변경은 아님(처리 순서) |

요약: **스키마 변경 중 장애로 이어진 확정 사례는 2026-07-18 응답 필드 리네임 1건**(80분 시차). 409 관련 kista 단독 버그 2건(2026-06-14, 2026-09-08), 라우팅 404 1건(2026-09-17), 검증 학습 보정 1건(2026-06-27). 공통 패턴: 계약을 컴파일러/공유 테스트가 지키지 않고 unknown 필드 무시 + 양쪽 독립 배포라서 런타임에야 발견된다.

## 5. kista-api가 fida 데이터를 쓰는 방식

fida가 보낸 기준표는 `PrivacyService.executeFidaOrder`(KT/privacy/application/service/PrivacyService.java:25-40)가 방어 규칙(`PrivacyTradeValidationService`)으로 점검한 뒤(BLOCKING이면 `PrivacyAlertRaisedEvent` 발행 + 400, WARNING이면 알림만) `PrivacyTradePersistenceAdapter.saveBaseWithOrders`로 `trading_ref.privacy_trade_bases`(마스터: release_date, ticker, current_cycle_start, realized_pnl, avg_price, holdings, 유니크 `(release_date,ticker)`)와 `trading_ref.privacy_trade_base_orders`(주문 명세: direction, order_type, price numeric(12,2), quantity nullable)에 cascade 저장한다 (V1__init.sql:256-285). 읽는 쪽은 trading-core 매매 경로 — `StrategyOrderPlanBuilder`(trading/application/service/StrategyOrderPlanBuilder.java:75)와 `TradingPriceFetcher`(:82)가 `privacyTradePort.findTodayTrade(today)`로 "발행일 = 거래일-1" 기준표를 읽어 PRIVACY 전략(`PrivacyStrategy`)의 주문 계획 입력으로 쓴다. `PrivacyBaseGuard`(54줄)는 장전·마감 배치 시점에 기준표를 재점검해 이상하면 해당 전략 주문을 건너뛴다(`SkipReason.PRIVACY_BASE_BLOCKED`). 그 외 `StrategyHistoryQueryService`(:64, 시작금액 미리보기)·`BacktestService`(:175-178, 백테스트)가 읽고, admin이 `/api/internal/privacy/**`로 조회·수동 보정한다. 즉 **fida 데이터는 kista가 실제 KIS/토스 주문을 내는 입력(기준가·평단·보유수량·계획 주문)**이며, 07:00 fida 전송이 없거나 틀리면 다음 거래일 PRIVACY 전략 주문이 차단/오주문 위험이다(fida 전송 → kista 저장 → 당일 장전 점검 → 주문 실행의 순서 결합, fida 쪽 CLAUDE.md의 "KISTA 주문 전송 활성화됨"과 일치). fida 쪽은 KIS를 직접 호출하지 않는다.

fida 수신·처리 관련 kista-api 파일(main): 약 **22개 파일 973줄** — trading-core privacy 모듈(전체 31파일/1,054줄 중 fida 수신 관련) + shared contract 2개(83줄) + `InternalTokenAuthFilter`(~52줄) 포함, 측정식: `FidaOrderController 47 + PrivacyContractMapper 62 + FidaOrderCommand 32 + FidaPlannedOrder 16 + PrivacyService 41 + PrivacyTradeValidationService 81 + PrivacyUseCase 10 + ValidationUseCase + PrivacyTradePort + PrivacyAlertRaisedEvent + persistence 패키지(어댑터 243·엔티티 55+45·레포 26) + 예외·결과·리포트·Dates·Base 도메인 + contract 83 + 토큰필터`. 여기에 admin 보정 경로(`:api` admin PrivacyQueryHttpAdapter 등 5파일 357줄)와 `PrivacyBaseInternalController` 등 조회·수정 컨트롤러가 별도. 테스트는 FidaOrderControllerTest 125줄 + PrivacyServiceTest 130줄 등.

## 6. kista-api 배포·프로세스 구조 (05-modules-and-processes.md 요약)

- 현재: Gradle 3개 서브프로젝트(`:api` 16,501줄 / `:trading-core` 35,581줄 / `:shared` 3,436줄), 프로세스 3 role = kista-api(웹, blue/green), kista-scheduler(비매매 @Scheduled), kista-trading(trading-core, 매매 배치+매매 API, 단일 인스턴스·stop-first·stop_grace 200s).
- root↔trading-core는 컴파일 의존 0, 내부 HTTP(`INTERNAL_API_BASE_URL`)·Redis로만 통신. 경계 코드 약 89파일 3,430줄.
- 가이드 권고(D1, 미결정): 한 코드베이스·한 Modulith 모델로 합치고 **web role**(모든 HTTP API, blue/green·다중 인스턴스) + **worker role**(@Scheduled·매매 배치·Redis 컨슈머, 단일 인스턴스·stop-first)로 분리. role은 `scheduler.enabled` 계열 플래그로 구분.
- **fida 수신부 위치**: 현재는 **kista-trading(trading-core)** 프로세스 — Caddy가 `/api/internal/fida-orders`만 kista-trading:8080으로 라우팅 (caddy:20-21). 가이드 권고 구조에서는 컨트롤러이므로 **web role**. 단, 저장 후 읽는 쪽(PrivacyBaseGuard·주문 계획)은 worker role이라 web(수신)→DB→worker(소비)의 DB 경유 결합이 됨.
- 배포: kista-api `server-deploy.yml`이 kista-infra에 repository_dispatch(config SHA + role별 이미지 SHA) 후 대기. 서버 적용·헬스 게이트·롤백은 kista-infra `reconcile.sh` 소유. Caddy·Redis도 kista-infra 소유.
- fida는 kista-api와 **별도 OCI 인스턴스(B)**에서 독립 운영, kista 배포 파이프라인과 무관 (kista deploy/server/README.md:159-165).

## 7. 빌드·런타임 비교 (합칠 때 충돌 지점)

| 항목 | fida | kista-api | 충돌 포인트 |
|---|---|---|---|
| Java | 21 (toolchain 21, Docker temurin:21) | **25** (toolchain 25, Docker temurin:25) | 합치려면 fida를 25로 올리거나 별도 유지 |
| Spring Boot | 3.4.4 | **4.1.1** (Spring Framework 7) | Boot 메이저 차이. Boot 4는 Jackson 3 기본·모듈화 starter(`starter-flyway`, `starter-webmvc-test` 등 분리) — fida 코드 이식 시 import/테스트 슬라이스 변경 필요 |
| Gradle | wrapper 8.14.4, 단일 모듈 | wrapper 9.8.2, 3 서브프로젝트 + version catalog | 래퍼 버전 상이 |
| springdoc | 2.8.4 | 3.1.1 | 메이저 차이(fida CLAUDE.md의 2.6.x 충돌 이슈와 같은 계열 위험) |
| Lombok | 1.18.36 (+`@Value` non-final 제약 문서화) | 1.18.48 | 마이너 차이 |
| ArchUnit | 1.3.0 (헥사고날 단일 테스트) | 1.5.1 + Spring Modulith 2.1.1 | fida는 Modulith 미사용 — 합치면 모듈 경계(`package-info`) 선언 필요 |
| DB | **없음** (JPA/DataSource 금지, 시트·텔레그램) | PostgreSQL + JPA + Flyway(5개 스키마) + Redis + Modulith JDBC events | fida CLAUDE.md "DB 없음, JPA/DataSource 추가 금지" 원칙과 정면 충돌 |
| HTTP 클라이언트 | `RestTemplate` 전용(WebClient 금지), 타임아웃 120s/180s | `RestClient` 기반 (`shared/platform/http/RestClients.java`, `HttpClientTimeouts`) | `RestTemplate`은 Spring 7에서 deprecated 방향 — 통일 시 RestClient로 |
| 보안 | actuator 헬스만 공개(Caddy), 앱 인증 없음 | Spring Security + JWT(oauth2-resource-server) + InternalTokenAuthFilter | 합치면 fida 엔드포인트(/api/fida/orders 등)에 인증 정책 필요 |
| 가상 스레드 | `spring.threads.virtual.enabled=true`, ZGC | (미확인) | |
| Google/Playwright | google-api-client 2.7.0, sheets v4, Node.js playwright-server 사이드카(mem 3g) | Firebase admin 등 | playwright 사이드카는 Java 이식 금지(fida 규칙), 메모리 큰 사이드카 |
| 스케줄 | 내장 `FandingScheduler` 화~토 07:00 + 20분 후 재시도, profile `job` one-shot | `@Scheduled` + `scheduler.enabled` 플래그 role | 합치면 worker role 소속; 프로필 `job`/`!job` 개념과 role 플래그 일원화 필요 |
| 포트 | 7070 | 8080 | |
| 텔레그램 | 자체 `TelegramAdapter` | `shared/platform/telegram/TelegramHttpClient` | 중복 구현 |

## 8. 시크릿·모니터링·파이프라인 중복

근거: fida `.github/workflows/{ci,fida-schedule,server-deploy}.yml`(29+69+225줄), `deploy/server/{docker-compose.yml 58, Caddyfile 20, README.md 128}`; kista-api `.github/workflows/{ci 77, server-deploy 232}.yml`, `deploy/server/{docker-compose.yml 115, caddy/, README.md 179}`, `deploy/hooks/*`.

| 요소 | fida | kista-api | 중복/차이 |
|---|---|---|---|
| 배포 트리거 | main push → `server-deploy.yml`, SSH+scp로 `/opt/fida/` 직접 배포 (server-deploy.yml:95-160) | main push → Plan → 테스트 → 이미지 빌드 → `kista-infra`에 `repository_dispatch`, 서버 적용은 kista-infra `reconcile.sh` (server-deploy.yml:3-5,205-211) | 방식이 다름: fida는 워크플로가 SSH 직접, kista는 별도 infra 레포 위임. 합치면 한쪽 방식으로 일원화 필요 |
| 헬스 게이트 | 배포 후 fida·playwright 컨테이너 healthy 확인 (server-deploy.yml:183-200) | kista-infra reconcile.sh가 수행 | 둘 다 있음, 구현 위치 상이 |
| 자동 롤백 | 헬스 게이트 실패 시 직전 이미지로 롤백 (같은 단계) | reconcile.sh + Caddy 스니펫 원복 (커밋 2773d528c) | 양쪽 있음 |
| 배포 가드 | 화~토 06:50~07:45 KST 배포 차단 (server-deploy.yml:103-118) | 매매 배치 실행 중 배포 차단(정밀화, 2773d528c) | 목적 동일(스케줄 실행 중 재기동 금지), 규칙 별개 |
| 리버스 프록시(Caddy) | 자체 `fida-caddy`(caddy:2.9-alpine, mem 64m), `/actuator/health`만 공개 나머지 404 (Caddyfile:1-20) | kista-infra 소유 Caddy + `deploy/server/caddy/kista-api.caddy` 스니펫, 경로별 3개 upstream | **Caddy 2벌**(인스턴스 별도). fida는 외부 공개 엔드포인트가 헬스뿐 |
| 도메인·IP | `fida.kista-app.com`, 예약 공인 IP 168.110.113.178 | `api.kista-app.com`, 별도 OCI 인스턴스 | 별도 인스턴스 2대 |
| 텔레그램 | `TELEGRAM_BOT_TOKEN/CHAT_ID`, 매매·KISTA 성공/실패·스케줄 실패 알림 (TelegramAdapter) | 같은 변수명, `shared/platform/telegram/TelegramHttpClient` + webhook, 관리자 알림 (`PrivacyAlertNotifier` 등) | **같은 봇/채팅 시크릿 2곳 보관 가능성**(동일 토큰 여부는 값 확인 불가) + 구현 2벌 |
| healthchecks.io | `HEARTBEAT_URL` 1개 (`fida-scheduler`, 화~토 07:00 KST) | `HEARTBEAT_OPEN_URL/CLOSE_URL/CRYPTO_URL` 3개 (K/.env.example:55-58, SchedulerJobRunner.java:37) | 같은 서비스·같은 패턴(성공 시 핑), 구현 2벌 |
| UptimeRobot | fida.kista-app.com/actuator/health | `{API_DOMAIN}/actuator/health` 5분 (K deploy/server/README.md:155) | 같은 서비스 2모니터 |
| `INTERNAL_API_TOKEN` | fida `.env`/GH Secrets/서버 `.env` (`kista.internal-token`) | kista `internal.api.token`, 수신 검증·kista↔trading 내부 호출에도 같은 토큰 사용 (InternalApiProperties) | **동일 시크릿을 양쪽에 수동 복사 보관** — 회전 시 두 곳 동시 갱신 필요. 개행 누락으로 KISTA_URL이 토큰에 흡수된 사고(2026-08-04) 이력 |
| `KISTA_URL` | 별도 설정 | - | 합치면 불필요(프로세스 내 호출) |
| 이미지 레지스트리 | GHCR (fida + playwright-server 이미지) | GHCR | 동일 |
| 테스트 게이트 | 전체 스위트 후 배포 | 전체 스위트 + Flyway 검사 + shellcheck/bats | 중복 개념 |
| 서버 시크릿 파일 | `/opt/fida/secrets/service-account.json`(Google), non-root UID 권한 이슈 이력 | kista-infra 소유 `.env`/secrets | 각자 수동 배치 |

결론(측정값): 두 레포에 **배포 파이프라인 요소 7종(배포 트리거·헬스 게이트·롤백·가드·Caddy·텔레그램·healthchecks)이 각각 독립 구현**돼 있고, 양쪽이 **공유하는 시크릿 값은 최소 2종**(`INTERNAL_API_TOKEN`, 텔레그램 토큰/채팅 — 후자는 같은 값 여부 미확인)이다. 서버 인스턴스는 2대, Caddy 2벌, 모니터 중복 UptimeRobot/healthchecks 각 2~4건.

## 미확인 / 한계

- 두 레포의 Telegram 시크릿이 실제로 같은 봇인지는 값 확인 불가(읽기 전용, 시크릿 열람 안 함).
- 2026-07-18 리네임 시차 동안 fida 실행이 있었는지(로그) 확인 못함 — 토요일 저녁이라 스케줄 창 밖으로 추정.
- kista Caddy 2026-09-17 fida-orders 404의 정확한 fida 측 영향(실패 알림 여부)은 fida 이력에 별도 커밋이 없어 미확인.
- kista contract의 `orders`/`price`/enum null 허용과 서비스 NPE 가능성은 코드 읽기 기반 추론이며 실행으로 재현하지 않음.
