package ru.g0studio;
import static ru.g0studio.Texts.tr;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.os.*;
import java.util.*;

@SuppressWarnings("deprecation")
public final class BleLink {
 public interface Listener{
  void status(String s);
  void found(BluetoothDevice d,String name);
  void packet(byte[] p);
  default void packet(byte[] p,boolean modeReadReply){packet(p);}
  void failed(String s);
  default void scanStatus(String message,boolean finished){status(message);}
  default void presetWriteTimedOut(byte[] command,int queued){
   failed(tr("Датчик не подтвердил запись. Перечитай настройки."));
  }
  default void timing(TimingEvent event){}
  default void acknowledged(byte[] request,byte[] reply){}
 }
 public static final class TimingEvent {
  public final String phase,tag;
  public final long timeNs,commandId;
  public final int command,option,value,bytes,attempt,gapMs;
  public final byte[] frame;
  private TimingEvent(String phase,String tag,long timeNs,long commandId,int command,int option,
      int value,int bytes,int attempt,int gapMs,byte[] frame){
   this.phase=phase;this.tag=tag;this.timeNs=timeNs;this.commandId=commandId;
   this.command=command;this.option=option;this.value=value;this.bytes=bytes;
   this.attempt=attempt;this.gapMs=gapMs;this.frame=frame==null?null:frame.clone();
  }
 }
 private static final class TimingProfile {
  final String tag;
  final int gapMs,fragmentGapMs;
  final boolean strictPresetReplies;
  TimingProfile(String tag,int gapMs,int fragmentGapMs,boolean strictPresetReplies){
   this.tag=tag;this.gapMs=gapMs;this.fragmentGapMs=fragmentGapMs;
   this.strictPresetReplies=strictPresetReplies;
  }
  int replyPause(byte[] frame){return gapMs<0?Protocol.postReplyPauseMillis(frame):gapMs;}
  int fragmentPause(){return fragmentGapMs<0?40:fragmentGapMs;}
 }
 private static final TimingProfile DEFAULT_PROFILE=new TimingProfile("default",-1,-1,false);
 final Context ctx;final Listener listener;final Handler h=new Handler(Looper.getMainLooper());
 BluetoothGatt gatt;BluetoothGattCharacteristic tx,rx;boolean ready=false;
 private BluetoothGatt closingGatt;
 private Runnable afterDisconnect;
 private DeviceScan deviceScan;
 private static final int TIMEOUT_RECOVERY_PAUSE_MS=300;
 private static final int GATT_CONNECTION_TIMEOUT=0x08;
 private final ArrayDeque<QueuedCommand> queue=new ArrayDeque<>();
 private final ArrayDeque<PendingWrite> writeReceipts=new ArrayDeque<>();
 private final Runnable resumeQueue=this::pump;
 private volatile boolean timingEnabled;
 private TimingProfile timingProfile=DEFAULT_PROFILE;
 private long timingId;
 private QueuedCommand activeCommand;
 private long nextSendAt;
 private boolean currentFullySent;
 byte[] current;int serial=0,epoch=0,readRetries=0;
 private static final class QueuedCommand{
  byte[] frame;
  final boolean replaceable;
  TimingProfile profile;
  long id;
  QueuedCommand(byte[] frame,boolean replaceable,TimingProfile profile,long id){
   this.frame=frame;this.replaceable=replaceable;this.profile=profile;this.id=id;
  }
 }
 private static final class PendingWrite {
  final QueuedCommand command;
  final int attempt,bytes;
  PendingWrite(QueuedCommand command,int attempt,int bytes){
   this.command=command;this.attempt=attempt;this.bytes=bytes;
  }
 }
 Protocol.Decoder decoder=new Protocol.Decoder();
 static final UUID SERVICE=UUID.fromString("0000fee9-0000-1000-8000-00805f9b34fb"),WRITE=UUID.fromString("d44bc439-abfd-45a2-b575-925416129600"),NOTIFY=UUID.fromString("d44bc439-abfd-45a2-b575-925416129601"),CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
 public BleLink(Context c,Listener l){ctx=c;listener=l;}
 public void timingEnabled(boolean enabled){
  timingEnabled=enabled;
  if(!enabled)writeReceipts.clear();
 }
 public void setTimingProfile(String tag,int gapMs){setTimingProfile(tag,gapMs,-1);}
 public void setTimingProfile(String tag,int gapMs,int fragmentGapMs){
  if(gapMs< -1||fragmentGapMs< -1)throw new IllegalArgumentException("Invalid timing profile");
  timingProfile=(tag==null||"default".equals(tag))&&gapMs<0&&fragmentGapMs<0?DEFAULT_PROFILE:
      new TimingProfile(tag==null?"trial":tag,gapMs,fragmentGapMs,true);
 }
 private void linkEvent(String phase,int value){
  if(timingEnabled)listener.timing(new TimingEvent(phase,timingProfile.tag,SystemClock.elapsedRealtimeNanos(),
      0,-1,-1,value,0,0,-1,null));
 }
 private void commandEvent(String phase,QueuedCommand command,long time,int value,int bytes,int attempt,byte[] frame){
  if(!timingEnabled||command==null||command.id==0)return;
  listener.timing(new TimingEvent(phase,command.profile.tag,time,command.id,
      command.frame[2]&255,command.frame[3]&255,value,bytes,attempt,command.profile.replyPause(command.frame),frame));
 }
 private void commandEvent(String phase,QueuedCommand command,int attempt){
  if(command==null||command.id==0)return;
  byte[] frame=command.frame;
  commandEvent(phase,command,SystemClock.elapsedRealtimeNanos(),firstValue(frame),frame.length,attempt,frame);
 }
 private static int firstValue(byte[] frame){return frame.length>8?frame[6]&255:-1;}
 void say(String s){listener.status(s);}
 public void scan(){
  stopScan();
  DeviceScan scan=new DeviceScan();
  deviceScan=scan;
  listener.scanStatus(closingGatt==null?tr("Поиск G0 Pro…"):tr("Завершаем предыдущее подключение…"),false);
  runAfterDisconnect(scan::start);
 }
 public void stopScan(){
  DeviceScan scan=deviceScan;
  deviceScan=null;
  if(scan==null)return;
  h.removeCallbacks(scan.timeout);
  if(scan.scanner!=null){
   try{scan.scanner.stopScan(scan);}catch(IllegalStateException|SecurityException ignored){}
  }
  linkEvent("SCAN_STOP",-1);
 }
 private final class DeviceScan extends ScanCallback{
  BluetoothLeScanner scanner;
  boolean reportedFound;
  final Runnable timeout=()->finish(tr("Поиск завершён."));
  void start(){
   if(deviceScan!=this)return;
   try{
    BluetoothManager manager=(BluetoothManager)ctx.getSystemService(Context.BLUETOOTH_SERVICE);
    BluetoothAdapter adapter=manager.getAdapter();
    if(adapter==null||!adapter.isEnabled()){finish(tr("Включи Bluetooth телефона."));return;}
    scanner=adapter.getBluetoothLeScanner();
    if(scanner==null){finish(tr("Поиск Bluetooth недоступен. Попробуй ещё раз."));return;}
    listener.scanStatus(tr("Поиск G0 Pro…"),false);
    linkEvent("SCAN_START",-1);
    scanner.startScan(null,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),this);
    h.postDelayed(timeout,12000);
   }catch(IllegalStateException|SecurityException e){finish(tr("Не удалось запустить поиск Bluetooth."));}
  }
  void finish(String message){
   if(deviceScan!=this)return;
   stopScan();
   listener.scanStatus(message,true);
  }
  @Override public void onScanResult(int type,ScanResult result){
   h.post(()->{
    if(deviceScan!=this)return;
    ScanRecord record=result.getScanRecord();
    String name=record==null?null:record.getDeviceName();
    if(name==null)try{name=result.getDevice().getName();}catch(SecurityException ignored){}
    if(name!=null&&name.toUpperCase(Locale.US).contains("G0")){
     if(!reportedFound){reportedFound=true;linkEvent("SCAN_FOUND",result.getRssi());}
     listener.found(result.getDevice(),name);
    }
   });
  }
  @Override public void onScanFailed(int error){
   h.post(()->{
    if(deviceScan!=this)return;
    linkEvent("SCAN_FAILED",error);
    finish(tr("Ошибка поиска Bluetooth (")+error+tr("). Попробуй ещё раз."));
   });
  }
 }
 private void runAfterDisconnect(Runnable action){
  if(closingGatt==null)action.run();
  else afterDisconnect=action;
 }
 public void connect(BluetoothDevice device){
  linkEvent("CONNECT_REQUEST",-1);
  close();
  say(tr("Подключение…"));
  runAfterDisconnect(()->startConnection(device));
 }
 private void startConnection(BluetoothDevice device){
  linkEvent("GATT_START",-1);
  try{
   gatt=device.connectGatt(ctx,false,callback,BluetoothDevice.TRANSPORT_LE);
  }catch(IllegalStateException|SecurityException e){gatt=null;}
  BluetoothGatt attempt=gatt;
  if(attempt==null){linkEvent("CONNECT_FAILED",-1);listener.failed(tr("Не удалось начать подключение к G0 Pro."));return;}
  h.postDelayed(()->{
   if(gatt!=attempt||ready)return;
   linkEvent("CONNECT_TIMEOUT",-1);
   close();
   listener.failed(tr("G0 Pro не ответил. Проверь, что датчик включён, и попробуй снова."));
  },15000);
 }
 public void close(){
  afterDisconnect=null;
  stopScan();
  epoch++;
  ready=false;
  cancel();
  writeReceipts.clear();
  nextSendAt=0;
  tx=null;
  rx=null;
  decoder=new Protocol.Decoder();
  BluetoothGatt previous=gatt;
  gatt=null;
  if(previous==null)return;
  linkEvent("DISCONNECT_REQUEST",-1);
  closingGatt=previous;
  try{
   previous.disconnect();
   h.postDelayed(()->finishDisconnect(previous),2000);
  }catch(IllegalStateException|SecurityException e){finishDisconnect(previous);}
 }
 private void finishDisconnect(BluetoothGatt previous){
  if(previous!=closingGatt)return;
  try{previous.close();}catch(IllegalStateException|SecurityException ignored){}
  closingGatt=null;
  Runnable action=afterDisconnect;
  afterDisconnect=null;
  if(action!=null)action.run();
 }
 public boolean busy(){return current!=null||!queue.isEmpty();}
 public void send(byte[] frame){enqueue(frame,false,timingProfile);}
 public void sendLatestPresetEdit(byte[] frame){enqueue(frame,Protocol.isPresetEdit(frame),timingProfile);}
 /** Overrides scheduling for this frame. A value of -1 keeps that part of the active profile. */
 public void sendTimed(byte[] frame,int postReplyPauseMs,int fragmentPauseMs){
  if(postReplyPauseMs< -1||fragmentPauseMs< -1)throw new IllegalArgumentException("Invalid timing profile");
  TimingProfile inherited=timingProfile;
  enqueue(frame,false,new TimingProfile(inherited.tag,
      postReplyPauseMs<0?inherited.gapMs:postReplyPauseMs,
      fragmentPauseMs<0?inherited.fragmentGapMs:fragmentPauseMs,inherited.strictPresetReplies));
 }
 private void enqueue(byte[] frame,boolean replaceable,TimingProfile profile){
  if(!ready){listener.failed(tr("Сначала подключи G0 Pro"));return;}
  if(replaceable){
   Iterator<QueuedCommand> previous=queue.descendingIterator();
   while(previous.hasNext()){
    QueuedCommand queued=previous.next();
    if(!queued.replaceable)break;
    if(queued.frame[2]==frame[2]&&queued.frame[3]==frame[3]){
     commandEvent("COALESCED",queued,0);
     queued.frame=frame.clone();
     queued.profile=profile;
     queued.id=timingEnabled?++timingId:0;
     commandEvent("QUEUE",queued,0);
     return;
    }
   }
  }
  if(queue.size()>100){listener.failed(tr("Слишком много команд"));return;}
  QueuedCommand queued=new QueuedCommand(frame.clone(),replaceable,profile,timingEnabled?++timingId:0);
  queue.add(queued);
  commandEvent("QUEUE",queued,0);
  pump();
 }
 public void cancel(){
  serial++;
  commandEvent("CANCELLED",activeCommand,readRetries+1);
  for(QueuedCommand pending:queue)commandEvent("CANCELLED",pending,0);
  queue.clear();
  activeCommand=null;
  current=null;
  currentFullySent=false;
  h.removeCallbacks(resumeQueue);
 }
 void pump(){
  if(current!=null||queue.isEmpty()||!ready)return;
  h.removeCallbacks(resumeQueue);
  long delay=nextSendAt-SystemClock.uptimeMillis();
  if(delay>0){h.postDelayed(resumeQueue,delay);return;}
  activeCommand=queue.remove();
  current=activeCommand.frame;
  readRetries=0;
  android.util.Log.i("G0StudioBLE","TX "+Protocol.hex(current));
  sendChunk(current,0,++serial,epoch);
 }
 private void commandTimedOut(byte[] frame,int id,int generation){
  if(current!=frame||serial!=id||epoch!=generation)return;
  String command=(frame[2]&255)+"/"+(frame[3]&255);
  if(Protocol.isWorkModeRead(frame)){
   commandEvent("TIMEOUT",activeCommand,readRetries+1);
   activeCommand=null;
   current=null;
   currentFullySent=false;
   serial++;
   android.util.Log.i("G0StudioBLE","Work-mode read did not reply; keeping the reported mode");
   pump();
   return;
  }
  if(Protocol.retryableRead(frame)&&readRetries<2){
   commandEvent("ATTEMPT_TIMEOUT",activeCommand,readRetries+1);
   readRetries++;
   decoder=new Protocol.Decoder();
   android.util.Log.i("G0StudioBLE","Retry read "+command+" attempt="+readRetries);
   commandEvent("RETRY",activeCommand,readRetries+1);
   sendChunk(frame,0,++serial,epoch);
   return;
  }
  int queued=queue.size();
  commandEvent("TIMEOUT",activeCommand,readRetries+1);
  activeCommand=null;
  cancel();
  nextSendAt=SystemClock.uptimeMillis()+TIMEOUT_RECOVERY_PAUSE_MS;
  android.util.Log.w("G0StudioBLE","Timeout "+command+"; queued commands cancelled="+queued);
  if(Protocol.isPresetEdit(frame))listener.presetWriteTimedOut(frame,queued);
  else listener.failed(tr("Датчик не подтвердил команду ")+command+tr(". Очередь остановлена."));
 }
 void sendChunk(byte[] p,int off,int id,int generation){
  if(id!=serial||generation!=epoch||!ready)return;
  if(off==0)currentFullySent=false;
  int end=Math.min(p.length,off+20);
  byte[] chunk=Arrays.copyOfRange(p,off,end);
  tx.setWriteType((tx.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0
      ?BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE:BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
  tx.setValue(chunk);
  boolean sent;
  QueuedCommand sending=activeCommand;
  int attempt=readRetries+1;
  if(sending.id!=0)commandEvent(off==0?"TX":"FRAGMENT",sending,SystemClock.elapsedRealtimeNanos(),
      off==0?firstValue(p):off,off==0?p.length:chunk.length,attempt,off==0?p:null);
  try{sent=gatt.writeCharacteristic(tx);}catch(Exception e){sent=false;}
  if(!sent){
   commandEvent("WRITE_FAILED",sending,attempt);
   activeCommand=null;
   cancel();
   listener.failed(tr("Bluetooth не принял запись. Переподключись и перечитай настройки."));
   return;
  }
  if(timingEnabled&&sending.id!=0){
   if(writeReceipts.size()==64)writeReceipts.remove();
   writeReceipts.add(new PendingWrite(sending,attempt,chunk.length));
  }
  if(end==p.length){
   currentFullySent=true;
   h.postDelayed(()->commandTimedOut(p,id,generation),Protocol.isWorkModeRead(p)?1600:5000);
  }else h.postDelayed(()->sendChunk(p,end,id,generation),sending.profile.fragmentPause());
 }
 final BluetoothGattCallback callback=new BluetoothGattCallback(){
  public void onConnectionStateChange(BluetoothGatt g,int status,int state){
   h.post(()->{
    if(g==closingGatt){
     if(state==BluetoothProfile.STATE_DISCONNECTED){linkEvent("DISCONNECTED",status);finishDisconnect(g);}
     return;
    }
    if(g!=gatt)return;
    if(state==BluetoothProfile.STATE_CONNECTED&&status==BluetoothGatt.GATT_SUCCESS){
     linkEvent("GATT_CONNECTED",status);
     say(tr("Обнаружение служб…"));
     linkEvent("SERVICE_REQUEST",-1);
     if(!g.discoverServices()){
      close();
      listener.failed(tr("Не удалось запросить службы G0 Pro."));
     }
    }else if(state==BluetoothProfile.STATE_DISCONNECTED){
     linkEvent("DISCONNECTED",status);
     close();
     finishDisconnect(g);
     say(status==GATT_CONNECTION_TIMEOUT?tr("Отключено · потеря связи"):tr("Отключено · код ")+status);
    }
   });
  }
  public void onServicesDiscovered(BluetoothGatt g,int status){h.post(()->{if(g!=gatt)return;linkEvent("SERVICES",status);if(status!=0){listener.failed(tr("GATT discovery status: ")+status);close();return;}tx=null;rx=null;StringBuilder discovered=new StringBuilder();
for(BluetoothGattService service:g.getServices()){discovered.append(service.getUuid()).append(" ");for(BluetoothGattCharacteristic ch:service.getCharacteristics())discovered.append(ch.getUuid()).append("[").append(ch.getProperties()).append("] ");}
android.util.Log.i("G0StudioBLE","Services: "+discovered);
UUID ffc1=UUID.fromString("0000ffc1-0000-1000-8000-00805f9b34fb"),ffc2=UUID.fromString("0000ffc2-0000-1000-8000-00805f9b34fb");
for(BluetoothGattService service:g.getServices()){BluetoothGattCharacteristic w=service.getCharacteristic(ffc1),n=service.getCharacteristic(ffc2);if(w!=null&&n!=null){tx=w;rx=n;break;}}
if(tx==null){BluetoothGattService service=g.getService(SERVICE);if(service!=null){tx=service.getCharacteristic(WRITE);rx=service.getCharacteristic(NOTIFY);}}
if(tx==null||rx==null||(tx.getProperties()&(BluetoothGattCharacteristic.PROPERTY_WRITE|BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE))==0||(rx.getProperties()&(BluetoothGattCharacteristic.PROPERTY_NOTIFY|BluetoothGattCharacteristic.PROPERTY_INDICATE))==0){listener.failed(tr("Compatible G0 Pro FFC1/FFC2 service not found. Details in G0StudioBLE log."));close();return;}
android.util.Log.i("G0StudioBLE","Using service "+tx.getService().getUuid()+" write="+tx.getUuid()+" notify="+rx.getUuid());BluetoothGattDescriptor d=rx.getDescriptor(CCCD);if(d==null||!g.setCharacteristicNotification(rx,true)){listener.failed(tr("Не удалось включить уведомления"));close();return;}d.setValue((rx.getProperties()&BluetoothGattCharacteristic.PROPERTY_NOTIFY)!=0?BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE:BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);linkEvent("SUBSCRIBE_REQUEST",-1);if(!g.writeDescriptor(d)){listener.failed(tr("Не удалось подписаться на датчик"));close();}});}
  public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status){h.post(()->{if(g!=gatt)return;if(status!=0){linkEvent("SUBSCRIBE_FAILED",status);listener.failed(tr("Ошибка подписки: ")+status);close();return;}ready=true;linkEvent("READY",status);say(tr("G0 Pro подключён · чтение…"));send(Protocol.frame(12,9,new byte[0]));send(Protocol.frame(12,8,new byte[0]));send(Protocol.frame(10,6,new byte[0]));});}
  @Override public void onMtuChanged(BluetoothGatt g,int mtu,int status){
   if(timingEnabled)h.post(()->{if(g==gatt)linkEvent(status==BluetoothGatt.GATT_SUCCESS?"MTU":"MTU_FAILED",
       status==BluetoothGatt.GATT_SUCCESS?mtu:status);});
  }
  @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic characteristic,int status){
   if(!timingEnabled)return;
   long receivedAt=SystemClock.elapsedRealtimeNanos();
   h.post(()->{
    if(g!=gatt||!timingEnabled)return;
    PendingWrite write=writeReceipts.poll();
    if(write!=null)commandEvent("WRITE_DONE",write.command,receivedAt,status,write.bytes,write.attempt,null);
    else listener.timing(new TimingEvent("WRITE_DONE",timingProfile.tag,receivedAt,0,-1,-1,status,0,0,-1,null));
   });
  }
  public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){
   long receivedAt=timingEnabled?SystemClock.elapsedRealtimeNanos():0;
   byte[] b=c.getValue();
   if(b==null)return;
   byte[] copy=b.clone();
   h.post(()->{
    if(g!=gatt)return;
    if(receivedAt!=0&&timingEnabled)listener.timing(new TimingEvent("NOTIFY",timingProfile.tag,receivedAt,
        0,-1,-1,-1,copy.length,0,-1,null));
    decoder.feed(copy,p->{if(g==gatt)acceptPacket(p,receivedAt);});
   });
  }
 };
 private void acceptPacket(byte[] packet,long receivedAt){
  android.util.Log.i("G0StudioBLE","RX "+Protocol.hex(packet));
  byte[] sent=current;
  QueuedCommand exchange=activeCommand;
  boolean match=sent!=null&&currentFullySent&&(packet[2]&255)==((sent[2]&255)|128)&&packet[3]==sent[3];
  if(match&&Protocol.isPresetEdit(sent)&&packet.length!=9)match=false;
  if(receivedAt!=0&&timingEnabled)listener.timing(new TimingEvent("RX",match?exchange.profile.tag:timingProfile.tag,receivedAt,
      match?exchange.id:0,packet[2]&255,packet[3]&255,firstValue(packet),packet.length,
      match?readRetries+1:0,match?exchange.profile.replyPause(sent):-1,packet));
  boolean wrongSlot=match&&packet[2]==(byte)0x8a&&packet[3]==2&&packet.length==333&&packet[6]!=sent[6];
  boolean invalidTrialPresetRead=match&&exchange.profile.strictPresetReplies&&packet[2]==(byte)0x8a&&
      (packet[3]==2&&packet.length!=333||packet[3]==6&&(packet.length!=9||firstValue(packet)<1||firstValue(packet)>4));
  if(wrongSlot||invalidTrialPresetRead){
   commandEvent("UNEXPECTED_REPLY",exchange,receivedAt,firstValue(packet),packet.length,readRetries+1,packet);
   return;
  }
  boolean optional=match&&Protocol.unsupportedOptionalReply(sent,packet);
  boolean modeReply=match&&Protocol.isWorkModeRead(sent);
  boolean rejected=match&&!optional&&!modeReply&&!VolumeState.isLevelReply(packet)&&packet.length==9&&packet[6]!=0
      &&!(packet[2]==(byte)0x8a&&(packet[3]==2||packet[3]==6));
  if(match){
   nextSendAt=SystemClock.uptimeMillis()+exchange.profile.replyPause(sent);
   activeCommand=null;
   current=null;
   currentFullySent=false;
   serial++;
   commandEvent(rejected?"REJECTED":optional?"OPTIONAL":"ACK",exchange,
       receivedAt,firstValue(packet),packet.length,readRetries+1,packet);
   if(optional)android.util.Log.i("G0StudioBLE","Auxiliary mic-reverb read unavailable; continuing");
   if(rejected){
    cancel();
    listener.failed(tr("Датчик отклонил команду: ")+(packet[6]&255)+" ["+(sent[2]&255)+"/"+(sent[3]&255)+"]");
   }
  }
  listener.packet(packet,modeReply);
  if(match&&!rejected&&!optional)listener.acknowledged(sent,packet);
  if(match&&!rejected)pump();
 }
}
