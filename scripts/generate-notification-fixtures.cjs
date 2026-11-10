const fs=require('fs'),vm=require('vm');
const ts=require(process.env.TYPESCRIPT_MODULE||'typescript');
process.env.TZ='UTC';
const mod={exports:{}};let captured;
const source=ts.transpileModule(fs.readFileSync('../quant-backend-nestjs/src/notification/infra/client/TelegramClient.ts','utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022,experimentalDecorators:true}}).outputText;
const axios={post:async(url,payload)=>{captured=payload.text;return {status:200}}};
vm.runInNewContext(source,{module:mod,exports:mod.exports,Date,console:{log(){},warn(){},error(){}},require:name=>name==='axios'?{default:axios}:name==='@nestjs/common'?{Injectable:()=>x=>x}:{} });
(async()=>{
const client=new mod.exports.TelegramClient({get:()=> 'fixture'});const fixtures=[];
for(const signal of ['BUY','SELL','STRONG_BUY']){
 const result={signal,price:50000,timestamp:new Date('2026-11-10T00:00:00Z'),indicators:{SMA5:49900,SMA20:50100,SMA50:49500,SMA200:48000,EMA12:49950,EMA26:50020,VWAP:49980,RSI:72.5,MACD:1.2,MACDSignal:0.8,MACDHist:0.4,Volume:2500,AvgVolume:1000,VolumeRatio:2.5,OBV:-2000,prevPrice:49800,exchangeRate:1350.5}};
 await client.sendAnalysisResult('BTCUSDT',result);fixtures.push({symbol:'BTCUSDT',result,expected:captured});
}
fs.writeFileSync('src/test/resources/notification-reference.json',JSON.stringify(fixtures));
})().catch(e=>{console.error(e);process.exit(1)});
