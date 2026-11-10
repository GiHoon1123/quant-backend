# 구현·검증 상태

## 완료

- 원본 커밋·REST 경로 조사
- Spring MVC 뼈대와 Swagger·오류 응답
- 공통 오류 단위 테스트 2개, 빌드·ktlint 통과
- SMA/EMA/RSI/MACD/Bollinger/VWAP/거래량/ATR·임계값 계산, 캐시 TTL, 손절/익절 계산
- 원본 TypeScript를 직접 실행한 4개 시나리오(각 240봉)의 지표 전체 시계열을 Kotlin과 1e-9 허용오차로 비교
- 단위 테스트 총 16개, Gradle build·ktlint 통과

## 진행 중

- PostgreSQL 캔들 저장소·시장 데이터 API 이전

## 미구현

- PostgreSQL 저장 경로와 전체 기존 스키마 연결
- 15분봉 수집·히스토리·구독 및 Socket.IO 계약
- 분석 서비스·기본/고급 전략과 API
- Binance Spot/Futures 주문·포지션·모니터링
- 자동매매·ATR 설정 API·알림·Bitcoin transaction 처리
- 원본 테스트 전용 REST 경로

현재 완료본이 아니므로 원본 이름은 유지한다. 이 문서는 실제 검증마다 갱신한다.
