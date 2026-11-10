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
