-- Extracted verbatim SQL from InitialSchema1754869396242.up; not auto-applied.

CREATE TABLE "spot_trade_records" ("id" SERIAL NOT NULL, "accountId" character varying(100), "userId" character varying(100), "symbol" character varying(20) NOT NULL, "orderId" bigint NOT NULL, "clientOrderId" character varying(100), "side" character varying(10) NOT NULL, "type" character varying(20) NOT NULL, "quantity" numeric(18,8) NOT NULL, "price" numeric(18,8) NOT NULL, "totalAmount" numeric(18,8) NOT NULL, "fee" numeric(18,8) NOT NULL, "feeAsset" character varying(10) NOT NULL, "feeRate" numeric(10,4), "status" character varying(20) NOT NULL, "source" character varying(20) NOT NULL, "executedAt" TIMESTAMP NOT NULL, "createdAt" TIMESTAMP NOT NULL DEFAULT now(), "updatedAt" TIMESTAMP NOT NULL DEFAULT now(), "strategyId" character varying(100), "metadata" json, "netAmount" numeric(18,8), CONSTRAINT "PK_c9bbbb130a6214a648d3774b8ea" PRIMARY KEY ("id"));

CREATE INDEX "idx_spot_account" ON "spot_trade_records" ("accountId") ;

CREATE INDEX "idx_spot_symbol" ON "spot_trade_records" ("symbol") ;

CREATE INDEX "idx_spot_order_id" ON "spot_trade_records" ("orderId") ;

CREATE INDEX "idx_spot_source" ON "spot_trade_records" ("source") ;

CREATE INDEX "idx_spot_executed_at" ON "spot_trade_records" ("executedAt") ;

CREATE INDEX "idx_spot_orderid" ON "spot_trade_records" ("orderId") ;

CREATE INDEX "idx_spot_source_executed" ON "spot_trade_records" ("source", "executedAt") ;

CREATE INDEX "idx_spot_symbol_executed" ON "spot_trade_records" ("symbol", "executedAt") ;

CREATE TABLE "futures_trade_records" ("id" SERIAL NOT NULL, "accountId" character varying(100), "userId" character varying(100), "symbol" character varying(20) NOT NULL, "orderId" bigint NOT NULL, "clientOrderId" character varying(100), "side" character varying(10) NOT NULL, "type" character varying(20) NOT NULL, "quantity" numeric(18,8) NOT NULL, "price" numeric(18,8) NOT NULL, "totalAmount" numeric(18,8) NOT NULL, "fee" numeric(18,8) NOT NULL, "feeAsset" character varying(10) NOT NULL, "feeRate" numeric(10,4), "status" character varying(20) NOT NULL, "source" character varying(20) NOT NULL, "leverage" numeric(10,2) NOT NULL, "marginType" character varying(20) NOT NULL, "initialMargin" numeric(18,8) NOT NULL, "maintenanceMargin" numeric(18,8), "positionSide" character varying(10) NOT NULL, "liquidationPrice" numeric(18,8), "markPrice" numeric(18,8), "pnl" numeric(18,8), "pnlPercent" numeric(10,4), "roe" numeric(10,4), "closeType" character varying(20), "closedAt" TIMESTAMP, "closeOrderId" bigint, "closePrice" numeric(18,8), "closeQuantity" numeric(18,8), "marginRatio" numeric(10,4), "isLiquidated" boolean NOT NULL DEFAULT false, "isClosed" boolean NOT NULL DEFAULT false, "maxDrawdown" numeric(18,8), "maxProfit" numeric(18,8), "holdingDuration" integer, "executedAt" TIMESTAMP NOT NULL, "createdAt" TIMESTAMP NOT NULL DEFAULT now(), "updatedAt" TIMESTAMP NOT NULL DEFAULT now(), "strategyId" character varying(100), "metadata" json, CONSTRAINT "PK_db88b2b3960399e50c8e69e8ded" PRIMARY KEY ("id"));

CREATE INDEX "idx_futures_account" ON "futures_trade_records" ("accountId") ;

CREATE INDEX "idx_futures_symbol" ON "futures_trade_records" ("symbol") ;

CREATE INDEX "idx_futures_order_id" ON "futures_trade_records" ("orderId") ;

CREATE INDEX "idx_futures_source" ON "futures_trade_records" ("source") ;

CREATE INDEX "idx_futures_position_side" ON "futures_trade_records" ("positionSide") ;

CREATE INDEX "idx_futures_closed_at" ON "futures_trade_records" ("closedAt") ;

CREATE INDEX "idx_futures_liquidated" ON "futures_trade_records" ("isLiquidated") ;

CREATE INDEX "idx_futures_closed" ON "futures_trade_records" ("isClosed") ;

CREATE INDEX "idx_futures_executed_at" ON "futures_trade_records" ("executedAt") ;

CREATE INDEX "idx_futures_closed_executed" ON "futures_trade_records" ("isClosed", "executedAt") ;

CREATE INDEX "idx_futures_position_executed" ON "futures_trade_records" ("positionSide", "executedAt") ;

CREATE INDEX "idx_futures_orderid" ON "futures_trade_records" ("orderId") ;

CREATE INDEX "idx_futures_source_executed" ON "futures_trade_records" ("source", "executedAt") ;

CREATE INDEX "idx_futures_symbol_executed" ON "futures_trade_records" ("symbol", "executedAt") ;

CREATE TYPE "public"."bitcoin_transactions_purpose_enum" AS ENUM('exchange_deposit', 'exchange_withdraw', 'wallet_transfer', 'payment', 'consolidation', 'dca_purchase', 'unknown');

CREATE TABLE "bitcoin_transactions" ("id" SERIAL NOT NULL, "accountId" character varying(100), "userId" character varying(100), "txid" character varying(64) NOT NULL, "blockHeight" integer, "blockHash" character varying(64), "confirmations" integer NOT NULL DEFAULT '0', "timestamp" TIMESTAMP NOT NULL, "size" integer NOT NULL, "vsize" integer NOT NULL, "weight" integer NOT NULL, "fee" numeric(18,8) NOT NULL, "feeRate" numeric(10,2) NOT NULL, "purpose" "public"."bitcoin_transactions_purpose_enum" NOT NULL DEFAULT 'unknown', "netAmount" numeric(18,8) NOT NULL, "isIncoming" boolean NOT NULL DEFAULT false, "isOutgoing" boolean NOT NULL DEFAULT false, "relatedSpotTradeId" integer, "relatedFuturesTradeId" integer, "tradeType" character varying(20), "relatedExchange" character varying(50), "tags" json, "inputAddresses" json NOT NULL, "outputAddresses" json NOT NULL, "primaryInputAddress" character varying(100), "primaryOutputAddress" character varying(100), "inputs" json NOT NULL, "outputs" json NOT NULL, "rawData" json, "isParsed" boolean NOT NULL DEFAULT false, "parsedAt" TIMESTAMP, "parsedBy" character varying(50), "createdAt" TIMESTAMP NOT NULL DEFAULT now(), "updatedAt" TIMESTAMP NOT NULL DEFAULT now(), CONSTRAINT "UQ_693a2ad604fda78542ca8316cc1" UNIQUE ("txid"), CONSTRAINT "PK_7c08599a3d5ed67350f8d1ccc5c" PRIMARY KEY ("id"));

CREATE INDEX "idx_btc_account" ON "bitcoin_transactions" ("accountId") ;

CREATE INDEX "idx_btc_tx_id" ON "bitcoin_transactions" ("txid") ;

CREATE INDEX "idx_btc_block_height" ON "bitcoin_transactions" ("blockHeight") ;

CREATE INDEX "idx_btc_timestamp" ON "bitcoin_transactions" ("timestamp") ;

CREATE INDEX "idx_btc_purpose" ON "bitcoin_transactions" ("purpose") ;

CREATE INDEX "idx_btc_spot_trade_id" ON "bitcoin_transactions" ("relatedSpotTradeId") ;

CREATE INDEX "idx_btc_futures_trade_id" ON "bitcoin_transactions" ("relatedFuturesTradeId") ;

CREATE INDEX "idx_btc_trade_type" ON "bitcoin_transactions" ("tradeType") ;

CREATE INDEX "idx_btc_is_parsed" ON "bitcoin_transactions" ("isParsed") ;

CREATE INDEX "idx_btc_futures_trade" ON "bitcoin_transactions" ("relatedFuturesTradeId") ;

CREATE INDEX "idx_btc_spot_trade" ON "bitcoin_transactions" ("relatedSpotTradeId") ;

CREATE INDEX "idx_btc_purpose_time" ON "bitcoin_transactions" ("purpose", "timestamp") ;

CREATE INDEX "idx_btc_blockheight" ON "bitcoin_transactions" ("blockHeight") ;

CREATE INDEX "idx_btc_txid" ON "bitcoin_transactions" ("txid") ;

CREATE TABLE "candle_15m" ("id" SERIAL NOT NULL, "symbol" character varying(20) NOT NULL, "market" character varying(10) NOT NULL, "timeframe" character varying(5) NOT NULL DEFAULT '15m', "openTime" TIMESTAMP NOT NULL, "closeTime" TIMESTAMP NOT NULL, "open" numeric(20,8) NOT NULL, "high" numeric(20,8) NOT NULL, "low" numeric(20,8) NOT NULL, "close" numeric(20,8) NOT NULL, "volume" numeric(20,8) NOT NULL, "quoteVolume" numeric(20,8) NOT NULL, "trades" integer NOT NULL, "takerBuyBaseVolume" numeric(20,8) NOT NULL, "takerBuyQuoteVolume" numeric(20,8) NOT NULL, "createdAt" TIMESTAMP NOT NULL DEFAULT now(), "updatedAt" TIMESTAMP NOT NULL DEFAULT now(), CONSTRAINT "PK_70a1f00427fa8fab91c78c2759f" PRIMARY KEY ("id")); COMMENT ON COLUMN "candle_15m"."symbol" IS '거래 심볼 (예: BTCUSDT, ETHUSDT, ADAUSDT)'; COMMENT ON COLUMN "candle_15m"."market" IS '시장 구분 (FUTURES: 선물거래, SPOT: 현물거래)'; COMMENT ON COLUMN "candle_15m"."timeframe" IS '시간봉 구분 (현재는 15m 고정, 향후 확장 가능)'; COMMENT ON COLUMN "candle_15m"."openTime" IS '캔들 시작 시간 (UTC 기준, 15분 간격)'; COMMENT ON COLUMN "candle_15m"."closeTime" IS '캔들 종료 시간 (UTC 기준)'; COMMENT ON COLUMN "candle_15m"."open" IS '시가 - 15분봉 시작 시점의 첫 거래 가격'; COMMENT ON COLUMN "candle_15m"."high" IS '고가 - 15분봉 기간 중 최고 거래 가격'; COMMENT ON COLUMN "candle_15m"."low" IS '저가 - 15분봉 기간 중 최저 거래 가격'; COMMENT ON COLUMN "candle_15m"."close" IS '종가 - 15분봉 종료 시점의 마지막 거래 가격'; COMMENT ON COLUMN "candle_15m"."volume" IS '거래량 - 기본 자산 기준 총 거래량 (예: BTC 수량)'; COMMENT ON COLUMN "candle_15m"."quoteVolume" IS '거래대금 - 견적 자산 기준 총 거래 금액 (예: USDT 금액)'; COMMENT ON COLUMN "candle_15m"."trades" IS '거래 횟수 - 15분봉 기간 중 총 개별 거래 건수'; COMMENT ON COLUMN "candle_15m"."takerBuyBaseVolume" IS '능동 매수 거래량 - 시장가 매수로 체결된 기본 자산량'; COMMENT ON COLUMN "candle_15m"."takerBuyQuoteVolume" IS '능동 매수 거래대금 - 시장가 매수로 체결된 견적 자산 금액'; COMMENT ON COLUMN "candle_15m"."createdAt" IS '레코드 생성 시간 - 데이터베이스 최초 저장 시점'; COMMENT ON COLUMN "candle_15m"."updatedAt" IS '레코드 수정 시간 - 마지막 업데이트 시점';

CREATE INDEX "idx_candle_created_at" ON "candle_15m" ("createdAt") ;

CREATE INDEX "idx_candle_symbol_market_time" ON "candle_15m" ("symbol", "market", "openTime") ;

CREATE INDEX "idx_candle_symbol_market" ON "candle_15m" ("symbol", "market") ;
