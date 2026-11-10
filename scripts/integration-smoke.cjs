const assert=require('node:assert/strict'),http=require('node:http'),net=require('node:net'),{spawn}=require('node:child_process'),crypto=require('node:crypto');
const {WebSocketServer}=require(process.env.WS_MODULE||'ws');
const {io}=require(process.env.SOCKET_IO_CLIENT_MODULE||'socket.io-client');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
const probe=net.createServer();await new Promise(r=>probe.listen(0,'127.0.0.1',r));const port=probe.address().port;await new Promise(r=>probe.close(r));
let orders=0,signatures=0;const frames=[];
const fake=http.createServer((req,res)=>{
 const u=new URL(req.url,'http://fixture');let data={};
 if(u.pathname==='/rate')data={rates:{KRW:1330}};
 else if(u.pathname==='/api/v3/account')data={balances:[{asset:'USDT',free:'1000',locked:'0'}]};
 else if(u.pathname==='/api/v3/order') {orders++;data={symbol:'BTCUSDT',orderId:990001,clientOrderId:'fixture',origQuoteOrderQty:'20',executedQty:'0.0004',status:'FILLED',fills:[{price:'50000',qty:'0.0004',commission:'0.02',commissionAsset:'USDT'}]};}
 else if(u.pathname==='/fapi/v2/positionRisk')data=[];
 else if(u.pathname==='/fapi/v2/balance')data=[];
 if(u.searchParams.has('signature')){const raw=req.url.split('?')[1],query=raw.slice(0,raw.lastIndexOf('&signature='));assert.equal(u.searchParams.get('signature'),crypto.createHmac('sha256','fixture-secret').update(query).digest('hex'));signatures++;}
 res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify(data));
});
await new Promise(r=>fake.listen(0,'127.0.0.1',r));const fakePort=fake.address().port;
const ws=new WebSocketServer({server:fake});const sockets=[];ws.on('connection',socket=>sockets.push(socket));
const child=spawn('java',['-Xmx256m','-jar',process.env.QUANT_JAR||'build/libs/quant-backend-kotlin-0.0.1-SNAPSHOT.jar'],{env:{...process.env,PORT:String(port),MARKET_STREAM_ENABLED:'true',MONITORED_SYMBOLS:'BTCUSDT',BINANCE_FUTURES_WS_URL:`ws://127.0.0.1:${fakePort}/ws`,BINANCE_SPOT_BASE_URL:`http://127.0.0.1:${fakePort}`,BINANCE_FUTURES_BASE_URL:`http://127.0.0.1:${fakePort}`,BINANCE_API_KEY:'fixture-key',BINANCE_API_SECRET:'fixture-secret',EXCHANGE_RATE_URLS:`http://127.0.0.1:${fakePort}/rate`,NOTIFICATIONS_ENABLED:'false'},stdio:['ignore','pipe','pipe']});let log='';child.stdout.on('data',b=>{log+=b});child.stderr.on('data',b=>{log+=b});let socket;
try{
 const base=`http://127.0.0.1:${port}`;
 for(let attempt=0;attempt<100;attempt++){try{if((await fetch(base+'/actuator/health')).ok)break}catch{}if(attempt===99)throw Error('startup timeout: '+log.slice(-2000));await sleep(100)}
 const schema=await (await fetch(base+'/openapi.json')).json();
 const routes=require('../docs/routes.json');for(const [method,path] of routes){const converted=path.replace(/:([A-Za-z0-9_]+)/g,'{$1}');assert.ok(schema.paths[converted]?.[method.toLowerCase()],`Missing runtime route ${method} ${converted}`);}
 socket=io(base+'/candle15m',{transports:['websocket'],reconnection:false});await new Promise((resolve,reject)=>{socket.once('connected',resolve);socket.once('connect_error',reject)});socket.on('candle.update',x=>frames.push(x));socket.emit('subscribe',['BTCUSDT']);
 for(let n=0;n<100&&sockets.length===0;n++)await sleep(20);assert.ok(sockets.length);
 const k={t:1700100000000,T:1700100899999,o:'100',h:'103',l:'99',c:'102',v:'2',q:'200',n:7,V:'1',Q:'100',x:false};
 sockets[0].send(JSON.stringify({s:'BTCUSDT',k}));await sleep(100);k.x=true;sockets[0].send(JSON.stringify({s:'BTCUSDT',k}));await sleep(300);
 const response=await fetch(base+'/api/v1/trading/orders/market/buy',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({symbol:'BTCUSDT',usdtAmount:20})});const body=await response.json();assert.equal(response.status,201,JSON.stringify(body));assert.equal(body.data.avgPrice,'50000.00000000');assert.equal(orders,1);assert.equal(signatures,2);assert.ok(frames.some(f=>f.isCompleted===true));
 const invalid=await fetch(base+'/api/v1/trading/orders/market/buy',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({symbol:'BTCUSDT',usdtAmount:1})});assert.equal(invalid.status,400);assert.equal(orders,1);
 for(const input of [{symbol:'BTCUSDT',usdtAmount:'20'},{symbol:'BTCUSDT',usdtAmount:20,unexpected:true}]){const bad=await fetch(base+'/api/v1/trading/orders/market/buy',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(input)});assert.equal(bad.status,400);}
 assert.equal(orders,1);
 const risk=await fetch(base+'/api/v1/analysis/technical/risk/position-size',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({accountBalance:1000,riskPercentage:1,entryPrice:10,stopLossPrice:10})});assert.equal((await risk.json()).data.recommendedPositionSize,null);
 console.log('PASS: fake Binance HTTP signatures, actual WebSocket candle frames, actual Socket.IO delivery, order response/validation, JSON null behavior');
}catch(error){console.error(log.slice(-14000));throw error;}finally{socket?.close();child.kill('SIGTERM');await new Promise(r=>child.once('exit',r));for(const s of sockets)s.terminate();await new Promise(r=>ws.close(r));await new Promise(r=>fake.close(r));}
})().catch(e=>{console.error(e);process.exit(1)});
