const fs=require('fs'),vm=require('vm'),ts=require(process.env.TYPESCRIPT_MODULE||'typescript');process.env.TZ='UTC';
const moduleObject={exports:{}};
const source=ts.transpileModule(fs.readFileSync('../quant-backend-nestjs/src/technical-analysis/service/TechnicalIndicatorService.ts','utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022,experimentalDecorators:true}}).outputText;
class FixedDate extends Date{constructor(...args){super(...(args.length?args:['2026-11-10T00:00:00Z']))} static now(){return 1794268800000}}
vm.runInNewContext(source,{module:moduleObject,exports:moduleObject.exports,Date:FixedDate,require:()=>({Injectable:()=>x=>x}),console:{log(){},warn(){},error(){}},process:{env:{}}});
const service=new moduleObject.exports.TechnicalIndicatorService();
const cases=JSON.parse(fs.readFileSync('src/test/resources/indicator-reference.json'));
fs.writeFileSync('src/test/resources/comprehensive-reference.json',JSON.stringify(cases.map(c=>({scenario:c.scenario,expected:service.generateComprehensiveReport(c.candles)}))));
