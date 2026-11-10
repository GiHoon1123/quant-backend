# 원본 분석

원본 커밋: `67b75b9b5d11e39f26ebad1cc09916ca83fa5521`

TypeScript 153개 파일, 약 28,700줄(주석 포함). 기존 단위 테스트 파일은 없다.

## REST 계약

| 메서드 | 경로 | 원본 |
|---|---|---|
| GET | `/api/v1/exchange-rate/usd-krw` | `src/common/web/ExchangeRateController.ts` |
| GET | `/api/v1/exchange-rate/service-status` | `src/common/web/ExchangeRateController.ts` |
| GET | `/api/v1/exchange-rate/force-refresh` | `src/common/web/ExchangeRateController.ts` |
| GET | `/auto-trading/status` | `src/auto-trading/web/AutoTradingController.ts` |
| POST | `/auto-trading/manual-signal` | `src/auto-trading/web/AutoTradingController.ts` |
| GET | `/auto-trading/backtest/:symbol` | `src/auto-trading/web/AutoTradingController.ts` |
| GET | `/auto-trading/atr/config` | `src/auto-trading/web/ATRController.ts` |
| PUT | `/auto-trading/atr/config` | `src/auto-trading/web/ATRController.ts` |
| GET | `/auto-trading/atr/cache/status` | `src/auto-trading/web/ATRController.ts` |
| GET | `/api/v1/analysis/technical/analyze/:symbol` | `src/technical-analysis/web/TechnicalAnalysisController.ts` |
| POST | `/api/v1/analysis/technical/screen` | `src/technical-analysis/web/TechnicalAnalysisController.ts` |
| GET | `/api/v1/analysis/technical/buy-signals` | `src/technical-analysis/web/TechnicalAnalysisController.ts` |
| GET | `/api/v1/analysis/technical/monitor` | `src/technical-analysis/web/TechnicalAnalysisController.ts` |
| GET | `/api/v1/analysis/technical/indicators/:symbol` | `src/technical-analysis/web/TechnicalAnalysisController.ts` |
| POST | `/api/v1/analysis/technical/strategy-scan` | `src/technical-analysis/web/TechnicalAnalysisController.ts` |
| GET | `/api/v1/analysis/technical/advanced/:symbol` | `src/technical-analysis/web/TechnicalAnalysisController.ts` |
| POST | `/api/v1/analysis/technical/risk/position-size` | `src/technical-analysis/web/TechnicalAnalysisController.ts` |
| GET | `/api/v1/analysis/technical/health` | `src/technical-analysis/web/TechnicalAnalysisController.ts` |
| POST | `/api/v1/test/legacy/candle15m` | `src/test/web/TestController.ts` |
| POST | `/api/v1/test/legacy/event-chain/:symbol` | `src/test/web/TestController.ts` |
| POST | `/api/v1/test/legacy/candle/generate/:symbol` | `src/test/web/TestController.ts` |
| POST | `/api/v1/test/legacy/analysis/:symbol` | `src/test/web/TestController.ts` |
| POST | `/api/v1/test/legacy/notification` | `src/test/web/TestController.ts` |
| POST | `/api/v1/test/legacy/multi-symbol` | `src/test/web/TestController.ts` |
| GET | `/api/v1/test/legacy/performance/:symbol` | `src/test/web/TestController.ts` |
| GET | `/api/v1/test/legacy/status` | `src/test/web/TestController.ts` |
| POST | `/api/v1/test/legacy/cleanup` | `src/test/web/TestController.ts` |
| POST | `/api/v1/test/legacy/telegram/:symbol` | `src/test/web/TestController.ts` |
| POST | `/api/v1/test/legacy/real-event-chain/:symbol` | `src/test/web/TestController.ts` |
| POST | `/api/v1/test/legacy/comprehensive-report/:symbol` | `src/test/web/TestController.ts` |
| POST | `/api/v1/test/candles/15m` | `src/test/web/TestCandleController.ts` |
| POST | `/api/v1/test/spot/stop-loss-take-profit` | `src/test/web/IntegratedTestController.ts` |
| POST | `/api/v1/test/monitoring/open` | `src/test/web/IntegratedTestController.ts` |
| POST | `/api/v1/test/monitoring/close` | `src/test/web/IntegratedTestController.ts` |
| POST | `/api/v1/test/futures/stop-loss-take-profit` | `src/test/web/IntegratedTestController.ts` |
| POST | `/api/v1/test/config/defaults` | `src/test/web/IntegratedTestController.ts` |
| POST | `/api/v1/test/auto-trading/calculate` | `src/test/web/IntegratedTestController.ts` |
| POST | `/api/v1/test/health` | `src/test/web/IntegratedTestController.ts` |
| POST | `/api/v1/trading/positions/position/open` | `src/futures/web/FuturesController.ts` |
| POST | `/api/v1/trading/positions/position/close` | `src/futures/web/FuturesController.ts` |
| POST | `/api/v1/trading/positions/leverage` | `src/futures/web/FuturesController.ts` |
| POST | `/api/v1/trading/positions/margin-type` | `src/futures/web/FuturesController.ts` |
| GET | `/api/v1/trading/positions/positions` | `src/futures/web/FuturesController.ts` |
| GET | `/api/v1/trading/positions/balances` | `src/futures/web/FuturesController.ts` |
| GET | `/api/v1/trading/positions/positions/high-risk` | `src/futures/web/FuturesController.ts` |
| POST | `/api/v1/trading/positions/transfer` | `src/futures/web/FuturesController.ts` |
| POST | `/api/v1/trading/positions/position/switch` | `src/futures/web/FuturesController.ts` |
| POST | `/api/v1/trading/positions/position/add` | `src/futures/web/FuturesController.ts` |
| POST | `/api/v1/trading/positions/position/reduce` | `src/futures/web/FuturesController.ts` |
| POST | `/api/v1/trading/positions/position/close-all` | `src/futures/web/FuturesController.ts` |
| GET | `/api/v1/market/candles/15m/latest/:symbol` | `src/market-data/web/candle/Candle15MController.ts` |
| GET | `/api/v1/market/candles/15m/latest` | `src/market-data/web/candle/Candle15MController.ts` |
| GET | `/api/v1/market/candles/15m/history/:symbol` | `src/market-data/web/candle/Candle15MController.ts` |
| GET | `/api/v1/market/candles/15m/statistics` | `src/market-data/web/candle/Candle15MController.ts` |
| GET | `/api/v1/market/candles/15m/subscription/status` | `src/market-data/web/candle/Candle15MController.ts` |
| POST | `/api/v1/market/candles/15m/subscription/add/:symbol` | `src/market-data/web/candle/Candle15MController.ts` |
| POST | `/api/v1/market/candles/15m/subscription/remove/:symbol` | `src/market-data/web/candle/Candle15MController.ts` |
| GET | `/api/v1/market/candles/15m/ongoing/:symbol` | `src/market-data/web/candle/Candle15MController.ts` |
| POST | `/api/v1/market/candles/15m/trigger/complete/:symbol` | `src/market-data/web/candle/Candle15MController.ts` |
| POST | `/api/v1/market/history/15m/collect/:symbol` | `src/market-data/web/candle/BinanceHistoryController.ts` |
| POST | `/api/v1/market/history/15m/collect/:symbol/range` | `src/market-data/web/candle/BinanceHistoryController.ts` |
| GET | `/api/v1/market/history/15m/stats/:symbol` | `src/market-data/web/candle/BinanceHistoryController.ts` |
| GET | `/api/v1/market/history/15m/symbols` | `src/market-data/web/candle/BinanceHistoryController.ts` |
| POST | `/api/v1/trading/orders/market/buy` | `src/order/web/OrderController.ts` |
| POST | `/api/v1/trading/orders/market/sell` | `src/order/web/OrderController.ts` |
| POST | `/api/v1/trading/orders/limit/buy` | `src/order/web/OrderController.ts` |
| POST | `/api/v1/trading/orders/limit/sell` | `src/order/web/OrderController.ts` |
| POST | `/api/v1/trading/orders/cancel` | `src/order/web/OrderController.ts` |
| GET | `/api/v1/trading/orders/balances` | `src/order/web/OrderController.ts` |

## 비 HTTP 경계

- PostgreSQL의 candle_15m, 거래 기록, 포지션, 전략, Bitcoin transaction 테이블 및 기존 migration
- Binance Spot/Futures REST(HMAC), Futures WebSocket candle stream
- Socket.IO candle gateway의 subscribe/unsubscribe/getSubscriptions 및 방송 이벤트
- 분석·자동매매·손절/익절·Telegram 이벤트 연결

각 경로의 구현 여부는 migration-status.md에서 관리한다. 목록이 존재한다고 구현 완료를 의미하지 않는다.
