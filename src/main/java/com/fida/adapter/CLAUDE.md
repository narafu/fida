# Adapter Layer

의존성 방향: `adapter → (Port 경유) → application`. application 구현체 직접 참조 금지.

## File Interaction Rules

| 수정 파일 | 함께 수정 필요 |
|-----------|---------------|
| `domain/model` 필드 변경 | 관련 Port, TradingRecordService, 연관 Adapter |
| `ParsedOrder` 필드 변경 | `GeminiVisionAdapter` 파싱 로직 동기화 |
| `TradingRecord` 필드 변경 | `GoogleSheetsAdapter`, `TelegramAdapter` 메시지 포맷 |
| 새 환경변수 추가 | `.env.example` 반드시 업데이트 |
| 옵셔널 아웃바운드 어댑터 추가 | `application.yml`에 `url: ${VAR:}` 추가 + `@ConditionalOnProperty("x.url")` + 포트를 `Optional<XxxPort>`로 주입 |

## Lazy Bean 패턴

외부 파일/자격증명을 읽는 `@Bean`(예: GoogleSheetsConfig): `@Bean @Lazy` + `@Component @Lazy` + 주입 지점 `@Lazy InterfacePort` 3단 설정 필수 — 하나라도 빠지면 Spring 기동 시 파일 읽기 실패.

## 인바운드 웹 레이어

- `FidaOrderController`: `/api/fida/orders` 3개 엔드포인트 — 모두 204 반환, 응답 바디 없음
- `GlobalExceptionHandler`: `@RestControllerAdvice` 전역 예외 처리 — 새 예외 타입 추가 시 여기도 확인
- `OpenApiConfig`: Swagger UI 설정 — springdoc 버전은 루트 CLAUDE.md Key Constraints 참조
