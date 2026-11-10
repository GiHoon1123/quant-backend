const {io}=require(process.env.SOCKET_IO_CLIENT_MODULE || 'socket.io-client');
const base=process.env.QUANT_HTTP_URL || 'http://127.0.0.1:3000';
(async()=>{
 for(const transports of [['polling'],['websocket'],['polling','websocket']]){
  await new Promise((resolve,reject)=>{
   const socket=io(base+'/candle15m',{transports,reconnection:false,timeout:5000});
   const timeout=setTimeout(()=>{socket.disconnect();reject(new Error('timeout '+transports))},10000);
   socket.on('connect_error',reject);
   socket.on('connected',()=>socket.emit('subscribe',['BTCUSDT','ETHUSDT']));
   socket.on('subscribed',data=>{if(data.totalSubscriptions!==2)return reject(new Error(JSON.stringify(data)));socket.emit('getSubscriptions')});
   socket.on('subscriptions',data=>{if(data.count!==2)return reject(new Error(JSON.stringify(data)));socket.emit('unsubscribe','ETHUSDT')});
   socket.on('unsubscribed',data=>{if(data.remainingSubscriptions!==1)return reject(new Error(JSON.stringify(data)));clearTimeout(timeout);console.log('PASS',transports,socket.io.engine.transport.name);socket.disconnect();resolve()});
  });
 }
})().catch(error=>{console.error(error);process.exit(1)});
