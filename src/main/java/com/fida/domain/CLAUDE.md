# Domain Layer

- 외부 의존성 0 — Spring 어노테이션 (`@Component`, `@Service` 등) 금지
- 모델은 Java `record` 사용

## 필드 변경 시 동기화 체크리스트

| 변경 대상 | 동기화 필요 |
|-----------|------------|
| `domain/model` 필드 | 관련 Port, `TradingRecordService`, 연관 Adapter |
| `ParsedOrder` 필드 | `GeminiVisionAdapter` 파싱 로직 |
| `TradingRecord` 필드 | `GoogleSheetsAdapter`, `TelegramAdapter` 메시지 포맷 |
