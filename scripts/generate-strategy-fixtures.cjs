const fs=require('fs'),vm=require('vm');
const ts=require(process.env.TYPESCRIPT_MODULE || 'typescript');
const source='../Quant-Backend/src/technical-analysis/';
const enumExports={};
function evaluate(path, requires){
 const module={exports:{}};
 const text=ts.transpileModule(fs.readFileSync(path,'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022,experimentalDecorators:true}}).outputText;
 class FixedDate extends Date{static now(){return 1794268800000}}
 vm.runInNewContext(text,{module,exports:module.exports,require:requires,Date:FixedDate,console:{log(){},warn(){},error(){}},process:{env:{}}});
 return module.exports;
}
const types=evaluate(source+'types/StrategyTypes.ts',()=>({}));
const requires=name=>name.includes('@nestjs')?{Injectable:()=>x=>x}:name.includes('StrategyTypes')?types:{};
const I=evaluate(source+'service/TechnicalIndicatorService.ts',requires).TechnicalIndicatorService;
const B=evaluate(source+'service/BasicStrategyService.ts',requires).BasicStrategyService;
const A=evaluate(source+'service/AdvancedStrategyService.ts',requires).AdvancedStrategyService;
(async()=>{
 const cases=JSON.parse(fs.readFileSync('src/test/resources/indicator-reference.json'));
 const results=[];
 for(const c of cases){
  const repository={findLatestCandles:async(symbol,market,limit)=>c.candles.slice(-limit)};
  const indicators=new I();
  const basic=await new B(repository,indicators).executeAllBasicStrategies('BTCUSDT','15m');
  const advanced=await new A(repository,indicators).executeAllAdvancedStrategies('BTCUSDT','15m');
  results.push({scenario:c.scenario,expected:[...basic,...advanced]});
 }
 fs.writeFileSync('src/test/resources/strategy-reference.json',JSON.stringify(results));
 console.log('Generated original strategy outputs for four sequences');
})().catch(e=>{console.error(e);process.exit(1)});
