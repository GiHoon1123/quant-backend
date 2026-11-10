# 마이그레이션 결정

## 기술과 API

DEX 프로젝트와 버전을 동일하게 고정한다. REST API는 Spring MVC로 이전하며 기존 CommonResponse(status/message/data)와 한국어 오류를 유지한다. 미구현 경로를 일반 성공 응답으로 대체하지 않는다.

## 수치 계약

기술적 지표는 원본 JavaScript number의 알고리즘과 결과 비교를 위해 Double을 유지한다. 주문 수량·DB decimal은 실제 저장 스키마에 맞춰 BigDecimal을 사용한다. 단순히 금융 도메인이라는 이유로 모든 지표를 BigInteger로 바꾸지 않는다.

원본 RSI는 평균 손실이 0일 때 RS를 100으로 설정해 RSI 약 99.0099를 반환한다. ATR은 최근 TR의 단순 평균이다. 일반적인 지표 정의로 조용히 바꾸지 않고 원본 동작을 보존한다.

## 외부 부작용

원본의 실거래 자동 실행과 Telegram 외부 발송을 테스트 중 실행하지 않는다. 어댑터는 로컬 모의 서버로 검증하고, 자격 증명 없이 자동으로 실거래에 접근하지 않는다.

## 분석 중 발견한 원본 미완성

TechnicalIndicatorService.getCandlesForSymbol은 빈 배열을 반환한다. 원본 구현에 있는 이 미완성 경로는 정상 ATR 서비스로 간주하지 않으며, 실제 캔들 저장소 연결 여부를 별도로 기록한다.

## 계산 결과 비교 재생성

원본 TS는 수정하지 않고 TypeScript 5.9.3의 transpileModule로 메모리 변환해 실행한다. scripts/generate-reference-fixtures.cjs에서 부작용 없는 지표 함수를 실행하고 테스트 표본을 만든다. 네 가지 시세(상승·하락·진동·고정), 각 240개 캔들을 사용한다. 다시 만들려면 별도 임시 npm 경로에 typescript@5.9.3을 설치하고 TYPESCRIPT_MODULE을 해당 경로로 지정한다. JVM 테스트에는 Node가 필요 없다.

## PostgreSQL

pgJDBC 42.7.7을 사용하고 원본 initial migration의 SQL을 원문 그대로 보관했다. 기본 실행에서 스키마를 변경하지 않는다. 테스트는 별도 PostgreSQL 16 컨테이너에서 수행한다. 원본 두 번째 migration은 initial schema에 없는 confidence 열을 삭제하므로 초기 생성 시 실행하지 않는다. 기존 운영 DB의 migration 이력은 별도 확인해야 한다.

동시 캔들 저장은 원본의 조회 후 저장 방식 대신 같은 candle 키에 transaction advisory lock을 사용한다. 원본 migration은 캔들 유니크 인덱스를 실제로 만들지 않으므로 자동 스키마 변경 없이 같은 키의 경쟁을 제어하기 위한 변경이다.

## Socket.IO와 stream

공식 Java Socket.IO 서버 4.1.2, Engine.IO 서버 6.3.2(Jakarta servlet)를 Spring MVC/WebSocket에 연결했다. 기존 포트에서 /socket.io/와 /candle15m namespace를 유지한다. plain WebSocket으로 Socket.IO를 대체하지 않는다. 로컬 Node Socket.IO 4.8.1 클라이언트로 polling, WebSocket, polling→WebSocket upgrade의 구독·해지·상태 응답을 검증했다. 참고: https://socketio.github.io/engine.io-server-java/spring-integration.html

원본 connectionStatus는 JavaScript Map이라 JSON에서는 {}가 된다. 이 응답 모양을 유지한다. 원본 history API는 startTime/endTime을 응답에 싣지만 조회에 적용하지 않으므로 현재도 동일하다. 수동 trigger/complete는 원본의 잘못된 메모리 키로 기본 stream 경로에서 이벤트가 발생하지 않는 경우가 있어, 이 결함을 성공한 이벤트 실행으로 오인하지 않는다.

검증 중 외부 네트워크를 열지 않도록 MARKET_STREAM_ENABLED=true일 때만 Binance stream에 연결한다. BINANCE_FUTURES_WS_URL로 로컬 모의 endpoint를 지정할 수 있다. 원본과 다른 명시적 실행 조건이며, 실제 외부 데이터 수집을 확인한 것으로 표시하지 않는다.

## 원본 전략의 실제 범위

고급 전략 중 smart money flow, pattern recognition, Elliott wave는 원본부터 고정값을 반환한다. 이름 때문에 실제 패턴·기관 자금 분석이 구현된 것처럼 문서화하지 않는다. Kotlin도 그 기존 출력 계약을 보존하며, 4개 입력 시퀀스에 대해 원본을 직접 실행한 전략 출력(신호·근거 문자열·지표)을 비교한다. multi-timeframe 역시 원본부터 네 시간봉 모두 같은 15분 DB 조회를 사용한다. 새로 고치지 않고 차후 별도 개선 과제로 남긴다.
