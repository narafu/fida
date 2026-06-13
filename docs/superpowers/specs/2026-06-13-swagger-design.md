# Swagger 문서화 설계

## 목적

springdoc-openapi가 이미 의존성으로 포함되어 있고 `application.yml`에 경로 설정도 존재하나,
API 메타 정보와 엔드포인트 어노테이션이 없어 Swagger UI에 기본 정보만 노출되는 상태다.
이를 보완해 Swagger UI에서 의미 있는 문서를 제공한다.

## 범위

- API 메타 정보 (title, version, description) 설정
- 기존 단일 엔드포인트에 `@Operation`, `@ApiResponse` 어노테이션 추가

## 구성 요소

### 1. `OpenApiConfig.java` (신규)

위치: `src/main/java/com/fida/adapter/in/web/OpenApiConfig.java`

```java
@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("FIDA API")
                .version("1.0.0")
                .description("fanding.kr 자동 매매 트리거 API"));
    }
}
```

### 2. `FidaOrderController.java` (수정)

- 클래스에 `@Tag(name = "FIDA 주문", description = "매매 기록 처리 트리거")`
- `trigger()` 메서드에:
  - `@Operation(summary = "매매 실행 트리거", description = "스크래핑 → OCR → 시트 기록 → 텔레그램 알림 → KISTA 주문 전송 파이프라인 실행")`
  - `@ApiResponse(responseCode = "204", description = "파이프라인 처리 완료 (응답 바디 없음)")`

## 접근 URL

| 용도 | URL |
|------|-----|
| Swagger UI | `http://localhost:7070/swagger-ui.html` |
| OpenAPI JSON | `http://localhost:7070/api-docs` |

## 비고

- 보안 스킴 불필요: `POST /api/fida/orders`는 인증 없이 호출 가능한 내부 트리거
- springdoc 버전 2.8.4, Spring Boot 3.4.x 호환 확인됨
