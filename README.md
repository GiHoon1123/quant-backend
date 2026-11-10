# Quant Backend Kotlin

Binance 시장 데이터·기술적 분석·주문·포지션·알림 백엔드의 Kotlin/Spring 마이그레이션이다.
원본은 [Quant-Backend](https://github.com/GiHoon1123/Quant-Backend)이며 읽기 전용으로 보존한다.

## 실행

```bash
./gradlew build
./gradlew bootRun
```

기본 포트 3000, Swagger UI `/api-docs`, OpenAPI JSON `/openapi.json`.
외부 주문·알림은 검증 중 실행하지 않는다. 현재는 이전 진행 중이며 전체 기능 완료본이 아니다.

- [원본 계약 목록](docs/source-inventory.md)
- [결정 기록](docs/decisions.md)
- [구현·검증 상태](docs/migration-status.md)

Kotlin 2.3.21 · JDK 21 · Spring Boot 4.1.2 · Gradle 9.8.2 · Spring MVC · JUnit 5 · MockK.

## PostgreSQL와 시장 데이터 검증

원본 스키마는 `src/main/resources/db/original-initial-schema.sql`에 보관한다. 자동 적용하지 않는다.
테스트 전용 DB에 SQL을 적용한 뒤 `DATABASE_URL`(JDBC URL), `DATABASE_USERNAME`, `DATABASE_PASSWORD`를 설정한다.
`QUANT_DATABASE_TESTS=true ./gradlew build`로 실제 DB 테스트를 포함한다.

실시간 stream은 `MARKET_STREAM_ENABLED=true`일 때만 연결한다. `BINANCE_FUTURES_WS_URL`로 모의 서버를 지정할 수 있다.
Socket.IO는 서버와 같은 포트의 `/socket.io/`, namespace `/candle15m`이다.
외부 테스트 폴더에 `socket.io-client@4.8.1`을 설치한 뒤 `SOCKET_IO_CLIENT_MODULE`에 설치 경로를 지정하고 `node scripts/socket-smoke.cjs`를 실행하면 transport 3종을 확인한다.

## 통합 계약 검증

```sh
npm ci --prefix scripts
DATABASE_URL=jdbc:postgresql://localhost:5432/quant_test DATABASE_USERNAME=quant DATABASE_PASSWORD=quant_test npm run integration --prefix scripts
```

위 명령은 원본 schema를 적용한 전용 테스트 DB를 사용한다. 로컬 가짜 거래소 HTTP/WS와 별도 JVM을 시작해 69개 OpenAPI 경로, 서명·주문·Socket.IO 방송을 검증한다. 실제 거래소 주문이나 외부 알림을 보내지 않는다. 테스트 데이터는 테스트 DB에 저장된다.

원본의 미완성 예시/더미 동작과 외부 실행 조건은 [구현·검증 상태](docs/migration-status.md), 설계 및 계약 차이는 [결정 기록](docs/decisions.md)에 정리했다.
