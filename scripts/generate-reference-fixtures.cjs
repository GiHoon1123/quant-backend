const fs = require('fs');
const vm = require('vm');
const ts = require(process.env.TYPESCRIPT_MODULE || 'typescript');
const source = fs.readFileSync('../Quant-Backend/src/technical-analysis/service/TechnicalIndicatorService.ts', 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, experimentalDecorators: true } }).outputText;
const moduleObject = { exports: {} };
vm.runInNewContext(compiled, { exports: moduleObject.exports, module: moduleObject, require: () => ({ Injectable: () => target => target }), console: { log() {}, warn() {}, error() {} }, process: { env: {} } });
const indicators = new moduleObject.exports.TechnicalIndicatorService();
const cases = ['rising','falling','oscillating','flat'].map(scenario => {
 const candles = Array.from({length:240}, (_,i)=> {
  const close = scenario==='rising' ? 100+i*0.7 : scenario==='falling' ? 500-i*0.7 : scenario==='flat' ? 100 : 200+Math.sin(i/7)*10+Math.cos(i/3)*3;
  return {openTime:1700000000000+i*900000,closeTime:1700000899999+i*900000,open:close-0.2,close,high:close+2,low:close-2,volume:100+i%11,quoteVolume:1000,trades:7,takerBuyBaseVolume:50,takerBuyQuoteVolume:500};
 });
 const expected = {
  sma:indicators.calculateSMA(candles,20),ema:indicators.calculateEMA(candles,20),rsi:indicators.calculateRSI(candles),macd:indicators.calculateMACD(candles),bands:indicators.calculateBollingerBands(candles),volume:indicators.calculateVolumeAnalysis(candles),vwap:indicators.calculateVWAP(candles),atr:indicators.calculateATR(candles)
 };
 return {scenario,candles,expected};
});
fs.writeFileSync('src/test/resources/indicator-reference.json', JSON.stringify(cases));
console.log('Generated four scenarios from unmodified TypeScript indicator implementation');
