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
