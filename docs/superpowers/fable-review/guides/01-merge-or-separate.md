# 01. fida를 kista-api에 합칠 것인가

상태: **2026-11-12 사용자 결정: D안(별도 레포 유지 + 접점 축소) 채택.** 근거 자료는 `findings/01`~`04`.

## 결론

**코드는 지금처럼 별도 레포·별도 프로세스로 두고, 계약 접점을 줄인다. 운영(배포·프록시·모니터링)은 인스턴스 통합 결정에 따라 kista-infra 쪽으로 합친다.**

합치면 없어지는 것은 작고, 새로 생기는 결합은 매매 프로세스 쪽에 걸린다. 계약이 어긋나는 문제는 합치지 않고도 20줄 안팎으로 막을 수 있다.

## 현재 구조

| 항목 | 값 | 근거 |
|---|---|---|
| fida 규모 | main Java 2,064줄(41파일), Node 267줄, test 2,803줄 | `findings/01` §1 |
| KISTA 접점 | `POST /api/internal/fida-orders` 1개, 요청 7필드, 헤더 `X-Internal-Token` | `findings/02` §1 |
| 접점 코드 | fida `adapter/out/kista` 145줄 + 도메인 `KistaResult`·`Order` 37줄 | `findings/01` §2 |
| 계약 어긋남 이력 | 6.5개월간 확정 1건(2026-07-18 응답 필드 리네임, 80분 뒤 수정, 스케줄 창 밖) | `findings/02` §4 |
| 스택 | fida Java 21·Boot 3.4.4 / kista Java 25·Boot 4.1.1(Jackson 3, springdoc 3) | `findings/02` §7 |
| 런타임 | Chromium 사이드카 이미지 1.38GB, 피크 1.2GiB, OOM 이력 1회(2026-10-31) | `findings/03` §6, `findings/04` |
| 실행 | 화~토 07:00 KST 1회, 47~110초 | `findings/04` |

fida가 보낸 값은 kista의 PRIVACY 전략 실주문 입력이다(`findings/02` §5). 그래서 "어느 쪽 장애가 어느 쪽으로 번지는가"가 판단의 중심이다.

## 선택지 비교

### A. worker role에 흡수 (기각)

fida 코드를 kista-api 코드베이스로 옮기고 worker role의 `@Scheduled`로 돌린다.

얻는 것:
- fida 쪽 `KistaAdapter`·응답 DTO·`KistaResult` 약 180줄, `INTERNAL_API_TOKEN` 복사본, `KISTA_URL`
- `TelegramAdapter`(147줄)와 `HeartbeatAdapter`(32줄)를 kista `shared/platform`으로 대체
- fida `server-deploy.yml` 225줄, 자체 Caddy

잃는 것:
- **배포 결합.** fida는 fanding 화면과 OCR 오독에 맞춰 자주 바뀐다(OCR 보정 커밋이 2026-05~10 사이 10건 이상). worker는 단일 인스턴스·stop-first·grace 200초다. 프롬프트 한 줄 고칠 때마다 매매 워커가 재기동된다.
- **스레드·메모리 결합.** OCR 재시도는 60초 sleep 3회, 스크래퍼 타임아웃은 180초다. 매매 배치와 같은 프로세스에서 돈다.
- **시크릿 범위.** fanding 계정, Gemini 키, Google 서비스 계정이 매매 프로세스 환경에 들어간다. 반대로 fida 코드는 매매 DB 접근권을 얻는다.
- **이식 비용.** Boot 3.4에서 4.1, Jackson 2에서 3, `RestTemplate`에서 `RestClient`. 기능은 그대로다.
- Chromium 사이드카는 Node라 어차피 별도 컨테이너로 남는다. "한 이미지"가 되지 않는다.

계약 타입도 완전히 없어지지 않는다. kista admin이 같은 엔드포인트와 `FidaOrderRequest`를 쓰기 때문이다(`findings/02` §1 참고).

### B. 같은 레포, 세 번째 role (기각)

kista-api `guides/05-modules-and-processes.md`의 role은 **같은 Modulith 모델을 실행 특성(HTTP 대 단일 스케줄러)으로 나눈 것**이다. fida는 그 모델의 도메인·DB·Redis를 하나도 쓰지 않는다. 세 번째 role로 넣으면 이름만 role이고 실체는 같은 이미지에 들어 있는 다른 앱이다.

- 한 이미지를 role 플래그로 띄우면 fida 변경이 web·worker 이미지 SHA를 바꾼다. 배포 계획 단계가 경로별로 걸러내야 하고, 그 로직이 새 복잡성이다.
- kista-api는 3 role을 2 role로 줄이는 중이다. 방향이 반대다.
- 이미지에 fida 의존(google-api-client 등)이 들어가고, kista의 JPA·Flyway·Redis 자동 설정을 fida role에서 꺼야 한다.

### C. 같은 레포, 별도 Gradle 서브프로젝트·별도 이미지 (차선)

`:fida`가 `:shared`의 `FidaOrderRequest`를 컴파일 의존으로 쓰고, 자체 이미지·자체 배포 단위를 가진다.

- 얻는 것: 요청 타입을 컴파일러가 지킨다. Telegram·heartbeat·`RestClients` 공용화(약 230줄). CI·배포 파이프라인 1벌.
- 잃는 것: Boot 4 이식. fida 커밋마다 kista CI(전체 스위트, Flyway 검사, bats)가 돈다. fida 작업 세션이 kista-api의 CLAUDE.md·문서를 자동 로드한다. Modulith·ArchUnit 규칙 적용 대상이 된다.
- 사용자가 "레포 하나"를 강하게 원하면 이 안이 유일하게 성립한다.

### D. 별도 레포 유지 + 접점 축소 (권고)

- fida가 응답에서 읽는 필드를 `id` 하나로 줄인다. kista 응답은 요청을 그대로 되돌려주므로(`PrivacyContractMapper.toResponse`) fida의 응답 대조 검증은 항상 통과하는 코드다. 2026-07-18 사고는 이 "읽을 필요 없는 필드"를 읽다가 났다. 상세는 `02-surface-reduction.md` 태스크 3.
- 요청 쪽은 kista `ContractWireFormatTest`가 이미 `tradeDate` 별칭을 고정한다. 응답 쪽 `id` 필드명 고정 단언 1개만 추가하면 양방향이 잠긴다(cross-repo 항목).
- 이후 계약 표면: 요청 7필드(kista 테스트로 고정) + 응답 `id`(kista 테스트로 고정). 타입 공유 아티팩트를 만들 이유가 없어진다.

### E. 지금 그대로 (기각)

응답 8필드 복제 3벌과 무의미한 응답 검증이 남는다. D의 비용이 작아 E를 고를 이유가 없다.

## 권고 이유 요약

1. **장애 영역이 다르다.** fida의 실패 원인은 fanding 화면 변경, Gemini 오독·한도, Chromium 메모리다. 전부 매매와 무관한 외부 요인이고, 실주문을 내는 프로세스에서 떼어 두는 편이 맞다.
2. **변경 주기가 다르다.** fida는 외부 사이트에 끌려 자주 바뀌고, 매매 워커는 재기동 비용이 크다.
3. **결합이 이미 얇다.** 엔드포인트 1개, 필드 7개. 합쳐서 지우는 코드(약 400줄 + YAML)보다 이식·role 관리로 생기는 일이 많다.
4. **중복의 대부분은 코드가 아니라 운영에 있다.** Caddy 2벌, 배포 워크플로 2방식, 배포 가드 2개, 모니터 중복이다(`findings/02` §8). 이건 레포를 합치지 않아도 인스턴스 통합과 kista-infra 편입으로 없앨 수 있다.

## 인스턴스 통합과의 관계 (fable-infra 소관)

**2026-11-12 사용자 확정(fable-api 경유 전달): 인스턴스는 "운영 1대 + 최소 대기 1대"로 가고, fida는 통합 여부와 무관하게 운영 호스트(kista-server)로 옮긴다.** 상세는 `kista-infra/docs/superpowers/fable-review/guides/03-instance-topology.md`. infra 권고는 playwright-server 컨테이너를 DB·Redis 네트워크(`data_net`)에 넣지 않고 운영 `.env`를 주지 않는 것이다.

같은 날 kista-api 구조도 확정됐다: 한 코드베이스·한 이미지에 `kista-web`과 `kista-worker` 두 role. `POST /api/internal/fida-orders`는 URL을 유지하고 worker가 받는다. 요청·응답 타입은 `shared.contract`에서 privacy 모듈 `api` 패키지로 옮겨진다(`kista-api/.../guides/05-modules-and-processes.md` 5절 X5). fida 쪽 JSON 필드명에는 영향이 없다.

그래서 남은 질문은 "코드베이스를 합칠 것인가" 하나다. 레포 분리와 호스트 분리는 별개이고, fida는 평시 0.5GB, 피크 1.5GB를 하루 2분 쓴다(`findings/04`). 운영 호스트로 옮기면:

- fida 자체 Caddy 삭제. 헬스 공개가 필요하면 kista Caddy에 스니펫 1개.
- `KISTA_URL`을 내부 네트워크 주소로 바꾸면(infra 안: Caddy 내부 리스너 `http://caddy:8080`, fida 컨테이너는 `shared_net`에만. `kista-infra/.../guides/03-instance-topology.md` 5-4절) 공인 도메인 왕복과 Caddy 라우팅 의존(2026-09-17 404 사고 원인)이 없어진다.
- fida 배포를 kista-infra `reconcile.sh`에 편입하면 fida `server-deploy.yml`의 SSH 배포·헬스게이트·롤백 약 130줄이 빠진다.
- 확인할 것: 07:00~07:02 KST에 kista 배치와 메모리 피크가 겹치는지, playwright 컨테이너 `mem_limit`을 유지할지.

## 사용자가 결정할 것

- D(권고) / C(레포 통합, 별도 이미지) / A·B(role 흡수) 중 선택
- D를 고르면 `02`~`04` 지침서를 그대로 실행한다. C를 고르면 `02`~`04`를 먼저 끝내 fida를 줄인 뒤 이식한다(옮길 코드가 줄어든다).

## cross-repo 항목 (fable-api에 전달)

1. kista `ContractWireFormatTest`에 응답 `id` 필드명 고정 단언 추가. fida가 응답에서 읽는 유일한 필드다.
2. kista contract `FidaOrderRequest.orders`에 `@NotNull` 없음. null이면 `command.orders().stream()`에서 NPE로 500이 날 수 있다(코드 읽기 기반, 재현 안 함). `findings/02` §2-1.
3. 요청 `tradeDate`를 kista가 `releaseDate`(발행일 = 거래일 − 1)로 저장한다. fida는 매매표 제목의 M/D를 보낸다. 이름만 별칭으로 흡수했고 의미가 같은지는 양쪽 어디에도 적혀 있지 않다. kista 쪽에서 의미를 확정해 계약 주석에 남길 것.
4. fida의 `requireSubmittable()`과 kista `PrivacyTradeValidationService` 규칙이 일부 겹친다. 기준은 kista 쪽이고 fida 것은 400을 미리 피하는 사전 차단이다. fida에서 지우지 않는다(이유: KISTA 실패 알림보다 원인 메시지가 구체적).
