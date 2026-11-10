# 구현·검증 상태

## 완료

- 원본 커밋·REST 경로 조사
- Spring MVC 뼈대와 Swagger·오류 응답
- 공통 오류 단위 테스트 2개, 빌드·ktlint 통과
- SMA/EMA/RSI/MACD/Bollinger/VWAP/거래량/ATR·임계값 계산, 캐시 TTL, 손절/익절 계산
- 원본 TypeScript를 직접 실행한 4개 시나리오(각 240봉)의 지표 전체 시계열을 Kotlin과 1e-9 허용오차로 비교
- 단위 테스트 총 16개, Gradle build·ktlint 통과

- 원본 PostgreSQL schema SQL 추출, candle_15m 저장·갱신·정렬·통계
- 실제 PostgreSQL 동시 동일 캔들 저장 8건과 값 갱신 테스트
- candle REST 조회/구독/진행 중 데이터와 ATR config API
- Binance Kline 파서·옵트인 stream/reconnect, Socket.IO 구독·해지·상태 및 이벤트 전달
- 실제 Node Socket.IO 4.8.1 클라이언트로 polling, WebSocket, transport upgrade 확인

- 기본 5개·고급 5개 전략 출력 4개 시나리오 원본 비교 통과

- 분석 집계·지표 요약과 REST 9개 경로, 원본 전체 결과 fixture 4종 비교

- Spot·Futures 서명 HTTP 어댑터: 로컬 서버에서 서명·헤더·query/form과 오류 매핑 검증

- Spot 주문 6개 REST·trade.executed 이벤트, 체결 평균·수수료·잔고 방어 테스트

## 진행 중

- 시장 데이터 모듈의 통합 시나리오와 남은 경로 이전

## 미구현

- 거래·포지션·Bitcoin DB repository 연결 (스키마 SQL 자체는 보존)
- 시장 데이터 startup DB preload·전체 history 수집 API·원본 테스트 입력 경로 및 서버 통계 방송
- Binance Spot/Futures 주문·포지션·모니터링
- 자동매매·ATR 설정 API·알림·Bitcoin transaction 처리
- 원본 테스트 전용 REST 경로

현재 완료본이 아니므로 원본 이름은 유지한다. 이 문서는 실제 검증마다 갱신한다.
