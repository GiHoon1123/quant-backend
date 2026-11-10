# 마이그레이션 구현·검증 상태

## 구현

원본 커밋 `67b75b9b5d11e39f26ebad1cc09916ca83fa5521`의 REST 69개 경로를 이전했다. 실행 중인 Spring OpenAPI에서도 69개를 전수 확인했다.

- 시장 데이터: PostgreSQL 캔들 저장/갱신·조회·통계, 제한된 메모리 캐시, 과거 데이터 배치 수집·재시도·재개, Binance WS 수신/재연결
- Socket.IO: 같은 HTTP 포트의 `/candle15m`, polling/WebSocket/upgrade, 구독·해제·최신/갱신/완료 이벤트
- 분석: SMA/EMA/VWAP/RSI/MACD/Bollinger/거래량/ATR, 기본 5개·고급 5개 전략, 9개 분석 REST, 새 캔들의 ATR 캐시·확장 분석 이벤트
- 거래: Spot 6개·Futures 12개 REST, HMAC 서명·query/form 계약, 금액/수량·마진 검증, 포지션 수명과 동적 스트림
- 저장: 원본 스키마의 현물·선물·Bitcoin 모델, 거래 이벤트 저장·청산 갱신, 기간/계정 필터·조회·집계
- 자동매매: 조건·ATR/기본 비율·포지션 크기·글로벌 신호 소비, 원본 고정 응답 3개 API
- 알림: 로컬 기술 이벤트 소비, HOLD 제외·분 단위 중복 방지, Telegram 객관적 분석 리포트·HTTP 발송 경계·통계
- 환율 3개, ATR 설정 3개, 통합 테스트 7개·레거시 테스트 12개·캔들 테스트 1개 REST

## 검증

- Gradle build·ktlint 및 전용 PostgreSQL 16 테스트
- 원본 TypeScript 지표·전략·집계 분석: 각 240봉의 4개 시퀀스 전체 수치 비교
- 원본 Telegram 리포트 3종·종합 리포트 4종 전체 문자열 비교
- 실제 DB의 동일 캔들 동시 저장 8건·업데이트·decimal·기간 필터·거래 종료·더미 Bitcoin 데이터 검증
- 별도 JVM + 로컬 Binance HTTP/WS + 실제 Socket.IO 클라이언트 종단 간 검증: 요청 서명·캔들 프레임·구독 방송·주문 201·잘못된 입력 400·비정상 숫자 JSON null
- 원본 TypeORM 저장소를 PostgreSQL에서 직접 실행해 집계 alias 결함 확인

## 보존한 원본의 미완성 동작

- 고급 전략 일부는 상수/예시이며 실제 다중 시간봉을 조회하지 않는다.
- 자동매매 status/backtest는 고정 예시, manual-signal은 실제 신호를 발행하지 않는다.
- Bitcoin 파서는 온체인 조회가 아닌 `dummy-parser-v1` 데이터 생성이다.
- 레거시 cleanup은 실제 삭제를 하지 않고 real-event-chain은 원본의 구현 중 응답을 반환한다.
- 지정가 매수 feeRate 인자·전환 주문 bigint ID·집계 alias 등의 기존 결함을 별도 개선으로 위장하지 않았다.

## 운영 조건과 차이

실거래·실제 알림·운영 DB로 검증하지 않았다. 외부 거래는 `TRADING_ENABLED=true`, 외부 알림은 `NOTIFICATIONS_ENABLED=true`, 실시간 시장 구독은 `MARKET_STREAM_ENABLED=true`로 명시적으로 활성화한다. 개발 기본 시작에는 키나 외부 연결이 필요하지 않다.

원본 내부 helper 중 호출자가 없는 개별/고급 알림 템플릿과 장식용 로그 메서드는 공개 REST·현재 이벤트 흐름에 연결되지 않아 그대로 번역하지 않았다. 현재 실행 경로의 계약을 검증 대상으로 삼았으며 내부 TypeScript 클래스 API 자체의 호환성을 제공하지 않는다.

동기 캔들 저장 후 완료 이벤트를 내보내는 순서, 스크리닝 순차 처리, HTTP timeout·기본 외부 실행 제한 등 의도적인 차이는 `decisions.md`에 기록했다. 실행 경로 검증 후 Kotlin 저장소는 quant-backend, 원본은 quant-backend-nestjs로 정리했다.
