package ru.g0studio;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;

import static ru.g0studio.Texts.tr;

final class G0Session implements BleLink.Listener {
 static final int STATE=1, PRESET=2, LEVELS=4;
 interface Observer {
  void changed(int flags);
  void message(String text);
  void found(BluetoothDevice device,String name);
  void scanStatus(String text,boolean finished);
  void backup(Protocol.Preset preset,String name);
 }
 private static final String[] DEFAULT_NAMES={"Fingerstyle","Band","Strumming","Solo"};
 private static final int QUICK_SLOT_PAUSE_MS=18;
 private static final int PRESET_LOAD_PAUSE_MS=12;
 private static final int PRESET_LOAD_FRAGMENT_PAUSE_MS=10;
 private final Observer observer;
 private final SharedPreferences preferences;
 private final BleLink radio;
 private final TimingJournal timingJournal;
 private final TimingBenchmark benchmark;
 private String timingTag="BASE";
 private String timingStatus=tr("Готово к измерению");
 private boolean timedConnection;
 private BluetoothDevice timingDevice;
 private boolean recoveringTiming;
 private boolean recoveryReconnectAttempted;
 private final Runnable resumeTimingRecovery=this::resumeTimingIfIdle;
 private final Handler handler=new Handler(Looper.getMainLooper());
 private final ArrayDeque<String> traffic=new ArrayDeque<>();
 private final VolumeState levels=new VolumeState();
 private final int[] pendingLevel={-1,-1,-1}, sentLevel={-1,-1,-1};
 private final Runnable flush=this::flushLevels;

 final String[] names=DEFAULT_NAMES.clone();
 Protocol.Preset preset, stored;
 int slot, mode=-1, battery=-1;
 boolean dirty, globalEffects=true;
 String status=tr("Не подключено");
 private boolean modeNotified, modeQueried, micReverbMissing;
 private boolean selecting, resetting, resetSent;
 private long quickSlotStarted;
 private int reportedPedals=-1;
 private Boolean effectsRequested;
 private int previousMaster;
 private Save save;
 private byte[] unconfirmedWrite;
 private int cancelledWrites;

 private static final class Save {
  final Protocol.Preset expected;
  final String alias;
  Save(Protocol.Preset expected,String alias){this.expected=expected;this.alias=alias;}
 }

 G0Session(Context context,SharedPreferences preferences,Observer observer){
  this.preferences=preferences;
  this.observer=observer;
  this.radio=new BleLink(context,this);
  if(BuildVariant.TIMING_DIAGNOSTICS){
   timingJournal=new TimingJournal();
   radio.timingEnabled(true);
   radio.setTimingProfile(timingTag,-1,-1);
   benchmark=new TimingBenchmark(new TimingBenchmark.Host(){
    @Override public void send(byte[] request){radio.send(request);}
    @Override public boolean canSend(){return radio.ready;}
    @Override public void restoreTiming(){radio.setTimingProfile("RECOVERY",-1,-1);}
    @Override public void mark(String event){timingJournal.note(timingTag,"CHECK",event);}
    @Override public void done(boolean success,String result){finishTiming(success,result);}
   });
  }else{
   timingJournal=null;
   benchmark=null;
  }
  previousMaster=preferences.getInt("masterBeforeMute",20);
 }

 boolean connected(){return radio.ready;}
 boolean mic(){return connected()&&(mode==0||mode==2);}
 String lastAddress(){return preferences.getString("lastG0Address","");}
 void scan(){radio.scan();}
 void stopScan(){radio.stopScan();}
 void connect(BluetoothDevice device){
  if(benchmarkActive())return;
  clearDevice();
  radio.connect(device);
 }
 void disconnect(){
  if(timingActive())return;
  radio.close();
  clearDevice();
  status(tr("Отключено"));
 }
 void destroy(){handler.removeCallbacksAndMessages(null);radio.close();}
 String log(){return String.join("\n",traffic);}
 String presetName(){return slot>=1&&slot<=4?names[slot-1]:tr("Мой звук");}
 boolean busy(){return timingActive()||radio.busy()||selecting||save!=null||resetting||unconfirmedWrite!=null;}

 private boolean benchmarkActive(){return BuildVariant.TIMING_DIAGNOSTICS&&benchmark.active();}
 boolean timingActive(){return BuildVariant.TIMING_DIAGNOSTICS&&(benchmark.active()||timedConnection);}
 String timingTag(){return timingTag;}
 String timingStatus(){return timingStatus;}
 String timingReport(){return timingJournal.report();}

 @Override public void timing(BleLink.TimingEvent event){if(BuildVariant.TIMING_DIAGNOSTICS)timingJournal.event(event);}

 void manualTiming(String tag,int gap,int fragmentGap){
  if(timingActive()||radio.busy())return;
  useTiming(tag,gap,fragmentGap);
  timingStatus=tr("Профиль для ручной проверки · ")+tag;
  timingJournal.note(tag,"MANUAL",timingStatus);
 }

 void startTiming(String tag,int gap,int fragmentGap,int rounds,int stages,boolean fullLoad){
  if(!readyForTiming())return;
  useTiming(tag,gap,fragmentGap);
  timingStatus=tr("Идёт проверка · ")+tag;
  timingDevice=radio.gatt.getDevice();
  recoveryReconnectAttempted=false;
  int alternate=slot%4+1;
  int loads=fullLoad?(stages==0?rounds:Math.max(2,rounds/6)):0;
  benchmark.start(tag,preset.copy(),alternate,rounds,loads,mic(),stages);
  observer.changed(LEVELS);
 }

 void timedReconnect(String tag,int gap,int fragmentGap){
  if(!readyForTiming()||radio.gatt==null)return;
  BluetoothDevice device=radio.gatt.getDevice();
  useTiming(tag,gap,fragmentGap);
  timedConnection=true;
  timingStatus=tr("Измеряю переподключение…");
  timingJournal.note(tag,"RECONNECT_START","");
  connect(device);
 }

 void stopTiming(){
  if(benchmark.active()){benchmark.cancel();return;}
  if(!timedConnection)return;
  timedConnection=false;
  timingStatus=tr("Измерение остановлено");
  useTiming("BASE",-1,-1);
  disconnect();
 }

 private void useTiming(String tag,int gap,int fragmentGap){
  timingTag=tag;
  radio.setTimingProfile(tag,gap,fragmentGap);
 }

 private boolean readyForTiming(){
  if(!idleDevice())return false;
  if(benchmark.recoveryPending()){
   observer.message(tr("Подключи тот же датчик для завершения восстановления."));
   return false;
  }
  if(preset==null||stored==null||slot<1||dirty||Arrays.stream(pendingLevel).anyMatch(level->level>=0)){
   observer.message(tr("Для измерения нужен считанный пресет без несохранённых изменений."));
   return false;
  }
  return true;
 }

 private void finishTiming(boolean success,String result){
  timingStatus=(success?tr("Серия завершена · "):tr("Серия прервана · "))+result;
  String event=recoveringTiming?"RECOVERY_RESULT":success?"BENCHMARK_PASS":"BENCHMARK_FAIL";
  recoveringTiming=false;
  timingJournal.note(timingTag,event,result);
  useTiming("BASE",-1,-1);
  if(benchmark.recoveryPending()){
   if(!radio.ready&&!recoveryReconnectAttempted&&timingDevice!=null){
    recoveryReconnectAttempted=true;
    handler.postDelayed(()->{
     if(!radio.ready&&radio.gatt==null&&benchmark.recoveryPending())connect(timingDevice);
    },900);
   }
  }else if(radio.ready){
   slot=0;
   preset=null;
   stored=null;
   send(10,6);
  }
  observer.changed(STATE|PRESET);
 }

 private void resumeTimingIfIdle(){
  if(!benchmark.recoveryPending()||!radio.ready||radio.gatt==null
      ||!radio.gatt.getDevice().equals(timingDevice))return;
  if(radio.busy()){
   handler.postDelayed(resumeTimingRecovery,120);
   return;
  }
  recoveringTiming=true;
  timingStatus=tr("Восстанавливаю исходный пресет…");
  timingJournal.note("RECOVERY","RECOVERY_START","");
  benchmark.resumeRecovery();
  observer.changed(LEVELS);
 }

 @Override public void acknowledged(byte[] request,byte[] reply){
  if(benchmarkActive())benchmark.acknowledged(request,reply);
 }

 private void logLine(String line){
  if(traffic.size()==80)traffic.removeFirst();
  traffic.addLast(line);
 }

 private void clearDevice(){
  slot=0;
  mode=-1;
  battery=-1;
  preset=null;
  stored=null;
  dirty=false;
  modeNotified=false;
  modeQueried=false;
  micReverbMissing=false;
  globalEffects=true;
  effectsRequested=null;
  selecting=false;
  quickSlotStarted=0;
  resetting=false;
  resetSent=false;
  save=null;
  unconfirmedWrite=null;
  reportedPedals=-1;
  levels.clear();
  clearVolumes();
  System.arraycopy(DEFAULT_NAMES,0,names,0,names.length);
 }
 private void clearVolumes(){
  handler.removeCallbacks(flush);
  Arrays.fill(pendingLevel,-1);
  Arrays.fill(sentLevel,-1);
 }

 void reload(){
  if(!idleDevice())return;
  modeQueried=false;
  send(12,8);
  send(10,6);
 }

 boolean select(int next){
  if(next==slot&&preset!=null)return true;
  if(!idleDevice())return false;
  selecting=true;
  quickSlotStarted=SystemClock.elapsedRealtime();
  slot=next;
  preset=null;
  stored=null;
  dirty=false;
  reportedPedals=-1;
  sendQuickSlot(1,(byte)next);
  sendQuickSlot(2,(byte)next);
  observer.changed(PRESET);
  return true;
 }

 private boolean idleDevice(){
  if(!connected()){observer.message(tr("Сначала подключи G0 Pro"));return false;}
  if(busy()){observer.message(tr("Дождись окончания обмена"));return false;}
  return true;
 }
 private boolean canEdit(){
  if(!connected()||preset==null){
   observer.message(tr("Сначала прочитай настройки датчика"));return false;
  }
  if(timingActive()||selecting||save!=null||resetting||unconfirmedWrite!=null){
   observer.message(tr("Дождись окончания обмена"));return false;
  }
  return true;
 }

 void editBand(int channel,int band,float gain,float q,float hz){
  if(!canEdit()||channel==1&&!mic())return;
  if(channel==2&&gain>0){
   observer.message(tr("Для антисвиста усиление должно быть не выше 0 дБ"));return;
  }
  try{
   float[] before=preset.band(channel,band);
   if(before[0]==gain&&before[1]==q&&before[2]==hz)return;
   preset.band(channel,band,gain,q,hz);
   dirty=true;
   radio.sendLatestPresetEdit(preset.bandFrame(channel,band));
  }catch(IllegalArgumentException problem){observer.message(tr(problem.getMessage()));}
 }
 void editPedal(int effect,int parameter,float value){
  if(!canEdit()||!Protocol.validEffectParameter(effect,parameter,value)
      ||preset.effect(effect,parameter)==value)return;
  preset.effect(effect,parameter,value);
  dirty=true;
  radio.sendLatestPresetEdit(preset.effectFrame(effect));
 }
 void togglePedal(int effect){
  if(!canEdit())return;
  int flag=1<<effect;
  boolean enabling=(preset.mask&flag)==0;
  if(enabling&&(effect==2&&(preset.mask&8)!=0||effect==3&&(preset.mask&4)!=0)){
   observer.message(tr("Совместимость хоруса и делея пока не подтверждена"));return;
  }
  preset.mask^=flag;
  reportedPedals=-1;
  dirty=true;
  radio.sendLatestPresetEdit(preset.effectFrame(effect));
  observer.changed(PRESET);
 }
 void reorder(int from,int to){
  if(from==to||!canEdit())return;
  int[] order=preset.order();
  int moved=order[from];
  order[from]=order[to];
  order[to]=moved;
  try{
   preset.order(order);
   dirty=true;
   radio.send(preset.orderFrame());
   observer.changed(PRESET);
  }catch(IllegalArgumentException problem){observer.message(tr(problem.getMessage()));}
 }
 void toggleGlobal(){
  if(!connected()||effectsRequested!=null||resetting||timingActive())return;
  effectsRequested=!globalEffects;
  radio.send(Protocol.globalEffects(effectsRequested));
  observer.changed(LEVELS);
 }

 int level(int index){
  if(pendingLevel[index]>=0)return pendingLevel[index];
  if(sentLevel[index]>=0)return sentLevel[index];
  return index==0?levels.pickup:index==1?levels.microphone:levels.master;
 }
 boolean volumeAvailable(int index){return connected()&&!timingActive()&&(index!=1||mic())&&level(index)>=0;}
 void setVolume(int index,int value){
  if(!volumeAvailable(index))return;
  pendingLevel[index]=Math.max(0,Math.min(100,value));
  handler.removeCallbacks(flush);
  handler.postDelayed(flush,120);
  observer.changed(LEVELS);
 }
 void mute(){
  if(!volumeAvailable(2))return;
  int value=level(2);
  if(value>0){
   previousMaster=value;
   preferences.edit().putInt("masterBeforeMute",value).apply();
  }
  setVolume(2,value>0?0:previousMaster);
 }
 private void flushLevels(){
  if(!connected())return;
  if(busy()){handler.postDelayed(flush,100);return;}
  for(int index=0;index<3;index++){
   if(index==1&&!mic())pendingLevel[index]=-1;
   if(pendingLevel[index]<0)continue;
   sentLevel[index]=pendingLevel[index];
   pendingLevel[index]=-1;
   radio.send(VolumeState.set(index+2,sentLevel[index]));
   handler.postDelayed(flush,100);
   return;
  }
 }

 boolean canSave(){return connected()&&preset!=null&&stored!=null&&stored.slot()==slot;}
 void saveCurrent(){
  if(!canSave()||!idleDevice())return;
  backup();
  writeSlot(null);
 }
 private void backup(){
  observer.backup(stored.copy(),tr("Резерв слота ")+slot+" · "+presetName());
 }
 private void writeSlot(String name){
  Protocol.Preset expected=preset.copy();
  expected.syncMask();
  save=new Save(expected,name);
  send(10,0,(byte)slot);
  readSlot();
 }
 void apply(Protocol.Preset supplied,String name,boolean store){
  if(!canSave()||mode<0){
   observer.message(tr("Подключись и выбери аппаратный слот 1–4"));return;
  }
  if(!idleDevice())return;
  backup();
  Protocol.Preset next=supplied.copy();
  next.data[0]=(byte)slot;
  if(!mic()){
   System.arraycopy(preset.data,181,next.data,181,96);
   next.micReverb=preset.micReverb;
  }
  preset=next;
  reportedPedals=-1;
  dirty=true;
  for(int channel=0;channel<3;channel++){
   if(channel==1&&!mic())continue;
   for(int band=0;band<(channel==2?4:8);band++)sendPresetLoad(next.bandFrame(channel,band));
  }
  sendPresetLoad(next.orderFrame());
  for(int effect=0;effect<4;effect++)sendPresetLoad(next.effectFrame(effect));
  if(mic()&&!micReverbMissing&&next.micReverb!=null)sendPresetLoad(Protocol.frame(14,0,next.micReverb));
  if(store){
   byte[] encoded=name.getBytes(StandardCharsets.UTF_8);
   if(encoded.length<=32){
    byte[] rename=new byte[33+encoded.length];
    rename[0]=(byte)slot;
    System.arraycopy(encoded,0,rename,1,encoded.length);
    radio.send(Protocol.frame(10,4,rename));
   }
   writeSlot(name);
  }
  observer.changed(PRESET);
 }
 void reset(){
  if(!idleDevice())return;
  resetting=true;
  resetSent=false;
  status(tr("Сброс настроек G0 Pro…"));
  send(12,Protocol.SERIAL_NUMBER);
 }

 private void send(int command,int option,byte... data){
  radio.send(Protocol.frame(command,option,data));
 }
 private void sendQuickSlot(int option,byte target){
  byte[] frame=Protocol.frame(10,option,new byte[]{target});
  if(BuildVariant.TIMING_DIAGNOSTICS)radio.send(frame);
  else radio.sendTimed(frame,QUICK_SLOT_PAUSE_MS,-1);
 }
 private void sendPresetLoad(byte[] frame){
  if(BuildVariant.TIMING_DIAGNOSTICS)radio.send(frame);
  else radio.sendTimed(frame,PRESET_LOAD_PAUSE_MS,PRESET_LOAD_FRAGMENT_PAUSE_MS);
 }
 private void readSlot(){if(slot>=1&&slot<=4)send(10,2,(byte)slot);}
 private void readMic(){if(mic()&&!micReverbMissing)send(14,32);}

 @Override public void status(String value){
  status=value;
  if(!connected()){
   if(benchmarkActive())benchmark.failed(value);
   if(BuildVariant.TIMING_DIAGNOSTICS&&timedConnection&&value.startsWith(tr("Отключено")))endTimedConnection(false,value);
   clearDevice();
  }
  else if(radio.gatt!=null){
   String address=radio.gatt.getDevice().getAddress();
   if(!address.equals(lastAddress()))preferences.edit().putString("lastG0Address",address).apply();
  }
  logLine(value);
  observer.changed(STATE);
 }
 @Override public void found(BluetoothDevice device,String name){observer.found(device,name);}
 @Override public void scanStatus(String value,boolean finished){observer.scanStatus(value,finished);}
 @Override public void failed(String error){
  if(benchmarkActive()){benchmark.failed(error);return;}
  if(BuildVariant.TIMING_DIAGNOSTICS&&timedConnection)endTimedConnection(false,error);
  boolean unread=unconfirmedWrite!=null||selecting;
  radio.cancel();
  clearVolumes();
  effectsRequested=null;
  unconfirmedWrite=null;
  resetting=false;
  selecting=false;
  quickSlotStarted=0;
  save=null;
  if(unread){preset=null;stored=null;dirty=false;}
  status=tr("Ошибка · ")+tr(error);
  logLine(status);
  observer.message(tr(error));
  observer.changed(STATE|PRESET|LEVELS);
 }
 @Override public void presetWriteTimedOut(byte[] command,int queued){
  if(benchmarkActive()){
   benchmark.failed((command[2]&255)+"/"+(command[3]&255)+" TIMEOUT");
   return;
  }
  if(preset==null||slot<1){failed(tr("Датчик не подтвердил запись. Перечитай настройки."));return;}
  clearVolumes();
  save=null;
  unconfirmedWrite=command;
  cancelledWrites=queued;
  status(tr("Проверка записи · читаю настройки датчика"));
  readSlot();
 }

 @Override public void packet(byte[] packet){packet(packet,false);}
 @Override public void packet(byte[] packet,boolean modeReply){
  int command=packet[2]&255,option=packet[3]&255;
  byte[] data=Arrays.copyOfRange(packet,6,packet.length-2);
  if(command!=140||option!=0)logLine("RX "+command+"/"+option+" · "+data.length+tr(" байт"));
  if(benchmarkActive()){
   if(command==138&&option==6&&data.length==1)benchmark.slotNotice(data[0]&255);
   return;
  }
  try{
   if(command==140)systemPacket(packet,option,data,modeReply);
   else if(command==138&&option==6&&data.length==1)activeSlot(data[0]&255);
   else if(command==138&&option==2&&data.length==325)acceptPreset(data);
   else if(command==141&&option==Protocol.GLOBAL_EFFECT_OPTION&&data.length==1){
    if(effectsRequested!=null&&data[0]==0)globalEffects=effectsRequested;
    effectsRequested=null;
    observer.changed(LEVELS);
   }else if(command==141&&option==2&&data.length==1){
    reportedPedals=data[0]&15;
    if(preset!=null&&preset.mask!=reportedPedals){
     preset.mask=reportedPedals;
     observer.changed(PRESET);
    }
   }else if(command==142&&option==32){
    if(data.length==1&&(data[0]&255)==255){
     micReverbMissing=true;
     if(preset!=null)preset.micReverb=null;
    }
    if(data.length==13&&preset!=null){
     preset.micReverb=data;
     if(stored!=null&&stored.slot()==slot)stored.micReverb=data.clone();
    }
   }
  }catch(IllegalArgumentException problem){failed(tr("Не удалось разобрать ответ: ")+tr(problem.getMessage()));}
 }

 private void systemPacket(byte[] packet,int option,byte[] data,boolean modeReply){
  if(option==8&&data.length==133){
   int charge=data[0]&255;
   if(charge<=100)battery=charge;
   int reported=data[4]&255;
   if(!modeNotified)acceptMode(reported,true);
   for(int index=0;index<4;index++){
    int start=5+index*32,end=start;
    while(end<start+32&&data[end]!=0)end++;
    String value=new String(data,start,end-start,StandardCharsets.UTF_8).trim();
    names[index]=preferences.getString("alias"+(index+1),value.isEmpty()?DEFAULT_NAMES[index]:value);
   }
   observer.changed(PRESET);
  }else if(option==0&&data.length==1&&(data[0]&255)<=100&&battery!=(data[0]&255)){
   battery=data[0]&255;
   observer.changed(LEVELS);
  }else if(option==Protocol.WORK_MODE&&data.length==1){
   int reported=data[0]&255;
   if(reported>=0&&reported<=2&&(!modeReply||!modeNotified)){
    if(!modeReply)modeNotified=true;
    acceptMode(reported,modeReply);
   }
  }else if(option==4&&data.length==1&&data[0]==0&&sentLevel[2]>=0){
   levels.confirmMaster(sentLevel[2]);
   sentLevel[2]=-1;
   observer.changed(LEVELS);
  }else if(option==Protocol.SERIAL_NUMBER&&resetting&&!resetSent){
   if(data.length!=4){failed(tr("Датчик не вернул серийный номер для сброса."));return;}
   resetSent=true;
   radio.send(Protocol.factoryReset(data));
  }else if(option==Protocol.FACTORY_RESET&&resetting&&resetSent&&data.length==1&&data[0]==0){
   SharedPreferences.Editor editor=preferences.edit();
   for(int index=1;index<=4;index++)editor.remove("alias"+index);
   editor.apply();
   disconnect();
   observer.message(tr("Заводские настройки восстановлены. Подключи G0 Pro снова."));
  }
  if(levels.accept(packet)){
   if(data.length==1&&option>=2&&option<=3)sentLevel[option-2]=-1;
   if(option==10)sentLevel[2]=-1;
   if(levels.master>0)previousMaster=levels.master;
   observer.changed(LEVELS);
  }
 }
 private void acceptMode(int reported,boolean snapshot){
  if(reported<0||reported>2||reported==mode||(snapshot&&reported==1&&mode>=0))return;
  boolean wasMic=mic();
  mode=reported;
  if(!wasMic&&mic()&&preset!=null)readMic();
  observer.changed(STATE|LEVELS);
 }
 private void activeSlot(int active){
  if(active<1||active>4)return;
  if(active==slot&&selecting)return;
  boolean switched=slot>0&&active!=slot;
  if(active!=slot){
   if(slot!=0&&radio.busy()){
    radio.cancel();
    observer.message(tr("Слот изменён на датчике. Предыдущая очередь остановлена."));
   }
   slot=active;
   quickSlotStarted=0;
   preset=null;
   stored=null;
   dirty=false;
   reportedPedals=-1;
   save=null;
   unconfirmedWrite=null;
   observer.changed(PRESET);
  }
  if(switched)sendQuickSlot(2,(byte)slot);
  else readSlot();
 }
 private void acceptPreset(byte[] data){
  if((data[0]&255)!=slot)return;
  Protocol.Preset read=new Protocol.Preset(data);
  boolean recovery=unconfirmedWrite!=null;
  if(recovery){
   String message=Protocol.presetEditMatches(unconfirmedWrite,read)
       ?tr("Датчик вернул записанное значение.")
       :tr("Датчик вернул другое значение. Показаны прочитанные настройки.");
   if(cancelledWrites>0)message+=tr(" Остальные команды в очереди отменены.");
   logLine(message);
   observer.message(message);
   unconfirmedWrite=null;
   reportedPedals=-1;
  }
  if(save!=null){
   if(Arrays.equals(save.expected.data,data)){
    if(save.alias!=null){
     names[slot-1]=save.alias;
     preferences.edit().putString("alias"+slot,save.alias).apply();
    }
    observer.message(tr("Слот сохранён и проверен чтением"));
   }else observer.message(tr("Проверка не совпала. Показаны данные датчика, резервная копия сохранена."));
   save=null;
  }
  if(!recovery||stored==null)stored=read.copy();
  if(reportedPedals>=0)read.mask=reportedPedals;
  preset=read;
  dirty=recovery;
  selecting=false;
  status=tr("G0 Pro подключён");
  if(quickSlotStarted!=0){
   logLine(tr("Смена слота ")+slot+" · "+(SystemClock.elapsedRealtime()-quickSlotStarted)+tr(" мс"));
   quickSlotStarted=0;
  }
  if(BuildVariant.TIMING_DIAGNOSTICS){
   timingJournal.note(timingTag,"PRESET_READY",Integer.toString(slot));
   if(timedConnection)endTimedConnection(true,"");
  }
  observer.changed(STATE|PRESET);
  if((mode<=1)&&!modeQueried&&!modeNotified){
   modeQueried=true;
   send(12,Protocol.WORK_MODE);
  }
  readMic();
  if(BuildVariant.TIMING_DIAGNOSTICS&&benchmark.recoveryPending()){
   handler.removeCallbacks(resumeTimingRecovery);
   handler.post(resumeTimingRecovery);
  }
 }

 private void endTimedConnection(boolean success,String detail){
  timedConnection=false;
  timingJournal.note(timingTag,success?"RECONNECT_PASS":"RECONNECT_FAIL",detail);
  timingStatus=success?tr("Подключение и чтение пресета подтверждены")
      :tr("Переподключение прервано · ")+detail;
 }
}
