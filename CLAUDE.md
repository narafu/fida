# CLAUDE.md

## 코드 철학

- 반복 코드보다 재사용성·가독성·최신 문법을 우선
- 디자인 패턴, 객체지향, SOLID 원칙 준수. Hexagonal Architecture, EDD, DDD, CQRS, TDA 설계 지향
- 코드 철학에 맞지 않는 코드나 개념적으로 어색한 위치의 클래스를 발견하면 개선을 제안할 것

## RESTful 설계 원칙

- URI는 명사·복수형, 메서드는 GET/POST/PUT/PATCH/DELETE 의미대로, 오류는 400·404·409, 외부 서비스 오류는 503
- 도메인 객체 직접 반환 금지 — 전용 Response DTO(`from()` 팩토리). 201 응답은 `Location` 헤더 포함

## 주석 규칙

- 신규 코드에는 주석을 함께 작성. 필드는 `// 역할 한 줄`, 비즈니스 로직 블록 직전에 단계 설명 한 줄
- Javadoc·블록 주석 금지 — `//` 인라인만 사용

## Commands

Gradle 8.14는 JDK 25에서 실행되지 않는다. 기본 JDK가 25면 앞에 `JAVA_HOME=$(/usr/libexec/java_home -v 21)`을 붙인다.

```bash
bash gradlew build                                                            # 컴파일 + 테스트 + JAR
bash gradlew test --tests "com.fida.architecture.HexagonalArchitectureTest"   # 아키텍처 규칙만
bash gradlew bootRun --args='--spring.profiles.active=local'                  # 로컬 실행
curl -X POST http://localhost:7070/api/fida/orders                            # 전체 파이프라인 수동 트리거
```

## Architecture

Hexagonal Architecture — ArchUnit 테스트로 의존성 방향(`adapter → application(port 경유) → domain`) 강제.

```
domain/model/         ← 외부 의존성 0 (Spring 어노테이션 금지), record 사용
domain/port/in|out/   ← UseCase / 외부 연동 포트
application/service/  ← UseCase 구현체 (@Service만 허용, adapter 직접 참조 금지)
adapter/in/           ← schedule(FandingScheduler), web(FidaOrderController 등)
adapter/out/          ← scraper, ocr, sheets, notify, kista, heartbeat
common/, config/      ← 유틸리티, 인프라 빈 설정
playwright-server/    ← Node.js 사이드카 (Java로 이식 금지)
```

어댑터 규칙·OCR 보정 이유: `src/main/java/com/fida/adapter/CLAUDE.md`. 배포·서버 운영: `deploy/server/README.md`. 환경변수: `.env.example`.

## Key Constraints

- 서버 포트 **7070** (KISTA가 8080 사용). Dockerfile `EXPOSE`, compose healthcheck도 7070
- DB 없음 (JPA/DataSource 추가 금지), HTTP는 RestTemplate 전용 (WebClient 금지)
- 스케줄 화~토 07:00 KST (`cron = "0 0 7 * * TUE-SAT"`, 변경 금지). 정식 실행 경로는 OCI 상시 서버의 `FandingScheduler`
- KISTA 전송 실패는 파이프라인 실패가 아니다 (별도 실패 알림)
- Gemini 모델 `gemini-2.5-flash-lite` 고정
- Google Sheets 셀 범위 고정: `A1, C2:D4, C5:D7, A8, C8, D8`. **A8 = "현사이클 시작"**(`current_cycle_start`) — "잔금"과 혼동 금지
- springdoc 2.7.0+ 유지 — 2.6.x는 Spring Framework 6.2와 `NoSuchMethodError: ControllerAdviceBean` 충돌
- Lombok `@Value` 설정 필드는 **non-final** — `final`+`@Value`는 CI에서 `No qualifying bean of type 'String'`. 테스트에서는 `ReflectionTestUtils.setField`로 값 주입
- Mockito로 `RestTemplate.postForObject` 스텁 시 URL 매처는 `any()` 대신 `anyString()` (varargs 오버로드)
- 서브에이전트가 Java import를 고치며 BOM을 넣어 `compileJava`가 깨진 사례가 있다. 일괄 제거: `grep -rl $'\xef\xbb\xbf' src --include="*.java" | while read f; do sed -i '' '1s/^\xef\xbb\xbf//' "$f"; done`
- 커밋 전 author 확인: `narafu <narafu@kakao.com>`
