# Domain Layer

- 외부 의존성 0 — Spring 어노테이션 (`@Component`, `@Service` 등) 금지
- 모델은 Java `record` 사용

## UseCase 인터페이스 (port/in)

| 인터페이스 | 역할 |
|-----------|------|
| `ProcessTradingRecordUseCase` | 전체 파이프라인 (스크래핑 → OCR → 기록 → 알림 → KISTA) |
| `ProcessImagesUseCase` | 이미지 직접 업로드 파이프라인 |
| `ProcessUrlUseCase` | URL 기반 파이프라인 |

## Port 인터페이스 (port/out)

| Port | 어댑터 |
|------|--------|
| `ScraperPort` | `FandingScraperAdapter` |
| `OcrPort` | `GeminiVisionAdapter` |
| `SheetPort` | `GoogleSheetsAdapter` |
| `NotifyPort` | `TelegramAdapter` |
| `KistaPort` | `KistaAdapter` |

## 필드 변경 시 동기화 체크리스트

| 변경 대상 | 동기화 필요 |
|-----------|------------|
| `domain/model` 필드 | 관련 Port, `TradingRecordService`, 연관 Adapter |
| `ParsedOrder` 필드 | `GeminiVisionAdapter` 파싱 로직 |
| `TradingRecord` 필드 | `GoogleSheetsAdapter`, `TelegramAdapter` 메시지 포맷 |
