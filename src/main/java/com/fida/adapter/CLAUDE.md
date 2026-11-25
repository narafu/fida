# Adapter Layer

의존성 방향: `adapter → (Port 경유) → application`. application 구현체 직접 참조 금지.

## 함께 수정할 파일

| 수정 파일 | 함께 수정 필요 |
|-----------|---------------|
| `domain/model` 필드 | 관련 Port, `TradingRecordService`, 연관 Adapter |
| `ParsedOrder` 필드 | `OcrResultReconciler` 보정 로직 |
| `TradingRecord` 필드 | `GoogleSheetsAdapter`, `TelegramAdapter` 메시지 포맷 |
| 새 환경변수 | `.env.example` (변수마다 주석 한 줄) |
| 옵셔널 아웃바운드 어댑터 | `application.yml`에 `url: ${VAR:}` + `@ConditionalOnProperty("x.url")` + `Optional<XxxPort>` 주입 |

## Lazy Bean 3단 설정 (Google Sheets)

외부 파일·자격증명을 읽는 `@Bean`(`GoogleSheetsConfig`)은 `@Bean @Lazy` + `@Component @Lazy` + 주입 지점 `@Lazy SheetPort` 3단이 모두 필요하다. 하나라도 빠지면 기동 시 파일 읽기에 실패한다. `TradingRecordService`가 `@RequiredArgsConstructor` 대신 명시적 생성자를 쓰는 이유다.

## 아웃바운드 OCR

- 구조: `GeminiClient`(HTTP 1회·quota·오류 분류) / `GeminiResponseParser`(텍스트→DTO) / `OcrResultReconciler`(순수 보정·경고) / `GeminiVisionAdapter`(3회 재시도, 최종 결과의 경고만 알림)
- HTTP 429·`RESOURCE_EXHAUSTED`는 `OcrException("Gemini API 일일한도 초과")`로 분류 — 일반 통신 오류로 뭉개지 않는다
- 매수·매도: `order_tables`의 헤더 라벨("매수가"/"매도가")로만 방향 결정 — 위치 기반 판단이 매도 3행을 buy로 넣은 사례(2026-11-26). 매수·매도 테이블이 각 1개가 아니면 재요청, 3회 모두 실패하면 OcrException(잘못된 방향 주문 전송 차단)
- 보유수량은 `holding_qty`·`cumulative_qty` 두 후보로만 결정 (`resolveHoldings()`). 소수 값은 금액 오독으로 보고 제외 + 경고
- 프롬프트는 화면 위치가 아닌 라벨 텍스트 기준 — 레이아웃이 바뀌면 위치 힌트가 엉뚱한 행(하단 "누적실현수익")을 가리킨다
- JSON 모드(`responseMimeType=application/json`)를 쓰지만 코드펜스·한글 후처리는 방어적으로 유지

## 스케줄

- 07:00 실패 시 `TaskScheduler`로 20분 뒤 1회만 재시도(재시도 실패는 알림만). 예약은 메모리상이라 대기 중 재기동·배포 시 유실된다 — 배포 금지 창(화~토 06:55~07:40 KST)이 유일한 보호
- 재실행이 안전한 근거: KISTA가 `(releaseDate, ticker)` 유니크로 멱등(같은 내용 200, 다르면 409 — 중복 주문 없음), 시트는 고정 셀 덮어쓰기
