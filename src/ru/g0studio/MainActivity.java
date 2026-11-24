package ru.g0studio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashSet;
import java.util.Set;

import static ru.g0studio.Texts.tr;

public final class MainActivity extends Activity implements G0Session.Observer,
    EqScreen.Host, PedalsScreen.Host, LibraryScreen.Host {
 private static final String[] PAGES={"Звук","Педали","Пресеты","Настройки"};
 static final int SOUND=0,PEDALS=1,LIBRARY=2,SETTINGS=3;
 private static final int BLUETOOTH_REQUEST=31;
 private Ui ui;
 private G0Session session;
 private EqScreen equalizer;
 private PedalsScreen pedals;
 private LibraryScreen library;
 private TimingScreen timingScreen;
 private ConsoleScreen console;
 private FrameLayout frame;
 private TextView connection;
 private int page;
 private int consolePage;
 private boolean showingCurve;
 private Protocol.Preset preview;
 private String previewName;
 private AlertDialog scanner;
 private LinearLayout devices;
 private TextView scanStatus;
 private final Set<String> listed=new HashSet<>();

 @Override public void onCreate(Bundle state){
  super.onCreate(state);
  Texts.init(this,language());
  ui=new Ui(this);
  session=new G0Session(this,getPreferences(0),this);
  library=new LibraryScreen(this,this);
  if(BuildVariant.TIMING_DIAGNOSTICS)timingScreen=new TimingScreen(this,session);
  pedals=new PedalsScreen(this,this);
  render();
 }
 @Override protected void onDestroy(){
  if(scanner!=null)scanner.dismiss();
  if(timingScreen!=null)timingScreen.destroy();
  if(session!=null)session.destroy();
  super.onDestroy();
 }
 @Override protected void onActivityResult(int request,int result,Intent data){
  super.onActivityResult(request,result,data);
  if(timingScreen==null||!timingScreen.activityResult(request,result,data))library.onActivityResult(request,result,data);
 }

 private void render(){
  LinearLayout root=ui.column();
  root.setBackgroundColor(Ui.BACK);
  root.setOnApplyWindowInsetsListener((view,insets)->{
   int left=insets.getSystemWindowInsetLeft(),right=insets.getSystemWindowInsetRight();
   int top=insets.getSystemWindowInsetTop(),bottom=insets.getSystemWindowInsetBottom();
   if(Build.VERSION.SDK_INT>=30){
    int types=WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout();
    if(equalizer!=null)types|=WindowInsets.Type.ime();
    Insets safe=insets.getInsets(types);
    left=safe.left;right=safe.right;top=safe.top;bottom=safe.bottom;
   }
   view.setPadding(left,top,right,bottom);
   return insets;
  });
  setContentView(root);
  root.requestApplyInsets();
  if(equalizer!=null){
   mount(root,equalizer.view(),new LinearLayout.LayoutParams(-1,-1));
   return;
  }
  addHeader(root);
  if(page!=SETTINGS)addConsoleTabs(root);
  frame=new FrameLayout(this);
  root.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
  showCurrentPage();
  addNavigation(root);
 }

 private void addHeader(LinearLayout root){
  LinearLayout header=ui.column();
  header.setPadding(ui.dp(16),ui.dp(11),ui.dp(16),ui.dp(4));
  boolean settings=page==SETTINGS;
  TextView name=ui.heading(settings?tr("Настройки"):"G0 STUDIO",25);
  name.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
  name.setLetterSpacing(settings?-.025f:.045f);
  name.setGravity(Gravity.CENTER_VERTICAL);
  header.addView(name,new LinearLayout.LayoutParams(-1,ui.dp(43)));
  connection=null;
  if(settings){
   connection=ui.text(session.status,14,session.connected()?Ui.ACCENT:Ui.MUTED);
   connection.setGravity(Gravity.CENTER_VERTICAL);
   connection.setMinHeight(ui.dp(29));
   header.addView(connection,new LinearLayout.LayoutParams(-1,-2));
  }
  root.addView(header,new LinearLayout.LayoutParams(-1,-2));
 }

 private void addConsoleTabs(LinearLayout root){
  LinearLayout bar=ui.row();
  bar.setPadding(ui.dp(16),0,ui.dp(16),ui.dp(5));
  for(int index=0;index<SETTINGS;index++){
   final int target=index;
   boolean current=page==index;
   LinearLayout tab=navigationItem(tr(PAGES[index]),index,Ui.PINK,14,current,()->showPage(target));
   tab.setBackground(ui.pressable(current?Ui.RAISED:Ui.SURFACE,current?0xff79506a:0,14));
   LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(0,ui.dp(62),1);
   if(index>0)size.setMarginStart(ui.dp(6));
   bar.addView(tab,size);
  }
  root.addView(bar);
 }

 private void addNavigation(LinearLayout root){
  LinearLayout bar=ui.row();
  bar.setPadding(ui.dp(16),ui.dp(3),ui.dp(16),ui.dp(5));
  boolean settings=page==SETTINGS;
  LinearLayout consoleTab=navigationItem(tr("Консоль"),NavIcon.CONSOLE,settings?Ui.MUTED:Ui.TEXT,
      13,!settings,()->showPage(consolePage));
  LinearLayout settingsTab=navigationItem(tr("Настройки"),NavIcon.SETTINGS,settings?Ui.TEXT:Ui.MUTED,
      13,settings,()->showPage(SETTINGS));
  bar.addView(consoleTab,new LinearLayout.LayoutParams(0,ui.dp(52),1));
  bar.addView(settingsTab,new LinearLayout.LayoutParams(0,ui.dp(52),1));
  root.addView(bar);
 }

 private LinearLayout navigationItem(String title,int icon,int color,int font,boolean selected,Runnable action){
  LinearLayout item=ui.column();
  item.setGravity(Gravity.CENTER);
  item.setBackground(ui.pressable(Ui.BACK,0,12));
  item.setContentDescription(title);
  item.setFocusable(true);
  item.setSelected(selected);
  item.setOnClickListener(v->action.run());
  NavIcon symbol=new NavIcon(this,icon,color);
  item.addView(symbol,new LinearLayout.LayoutParams(ui.dp(23),ui.dp(23)));
  TextView caption=ui.text(title,font,color);
  caption.setGravity(Gravity.CENTER);
  caption.setSingleLine(true);
  caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
  LinearLayout.LayoutParams captionSize=new LinearLayout.LayoutParams(-1,-2);
  captionSize.topMargin=ui.dp(2);
  item.addView(caption,captionSize);
  return item;
 }

 private void showPage(int target){
  if(page==target)return;
  page=target;
  if(target!=SETTINGS)consolePage=target;
  render();
 }

 private void showCurrentPage(){
  if(frame==null||equalizer!=null)return;
  frame.removeAllViews();
  View content;
  if(page==SOUND){
   console=new ConsoleScreen(this,session);
   content=console.view();
  }else if(page==PEDALS){
   console=null;
   pedals.refresh();
   content=pedals.view();
  }else{
   console=null;
   content=page==LIBRARY?library.libraryView():library.settingsView();
  }
  mount(frame,content,new FrameLayout.LayoutParams(-1,-1));
 }
 private void mount(ViewGroup parent,View child,ViewGroup.LayoutParams size){
  if(child.getParent() instanceof ViewGroup)((ViewGroup)child.getParent()).removeView(child);
  parent.addView(child,size);
 }

 @Override public void changed(int flags){
  if(isFinishing()||isDestroyed())return;
  if(timingScreen!=null)timingScreen.refresh();
  if(connection!=null){
   connection.setText(session.status);
   connection.setTextColor(session.connected()?Ui.ACCENT:Ui.MUTED);
  }
  if(equalizer!=null){
   if((flags&(G0Session.STATE|G0Session.PRESET))!=0)equalizer.refresh();
   return;
  }
  if(page==SOUND&&console!=null){
   if((flags&(G0Session.STATE|G0Session.PRESET))!=0)showCurrentPage();
   else console.updateLevels();
  }else if(page==PEDALS&&(flags&(G0Session.STATE|G0Session.PRESET))!=0)pedals.refresh();
  else if(page==SETTINGS&&(flags&G0Session.STATE)!=0
      ||page==LIBRARY&&(flags&(G0Session.STATE|G0Session.PRESET))!=0)showCurrentPage();
 }

 boolean curve(){return showingCurve&&preset()!=null;}
 void curve(boolean show){showingCurve=show;showCurrentPage();}
 void chooseSlot(int slot){
  if(session.preset!=null&&session.slot==slot&&preview==null){
   curve(true);return;
  }
  Runnable choice=()->{
   if(session.select(slot)){
    preview=null;
    showingCurve=true;
    showCurrentPage();
   }
  };
  if(session.dirty&&session.slot!=slot){
   new AlertDialog.Builder(this)
       .setMessage(tr("Переключить слот и оставить изменения без сохранения?"))
       .setNegativeButton(tr("Отмена"),null)
       .setPositiveButton(tr("Переключить"),(d,w)->choice.run()).show();
  }else choice.run();
 }
 void openEq(){
  if(preset()==null){message(tr("Сначала прочитай настройки датчика"));return;}
  equalizer=new EqScreen(this,this);
  setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
  render();
 }
 @Override public void closeEq(){
  equalizer=null;
  setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
  render();
 }
 @Override public void onBackPressed(){
  if(equalizer!=null){closeEq();return;}
  if(page==SETTINGS){showPage(consolePage);return;}
  if(page!=SOUND){showPage(SOUND);return;}
  if(showingCurve){curve(false);return;}
  super.onBackPressed();
 }

 @Override public Protocol.Preset preset(){return preview!=null?preview:session.preset;}
 @Override public String presetName(){return preview!=null?previewName:session.presetName();}
 @Override public boolean micEnabled(){return session.mic();}
 @Override public void changeBand(int channel,int band,float gain,float q,float hz){
  if(preview==null)session.editBand(channel,band,gain,q,hz);
  else try{
   preview.band(channel,band,gain,q,hz);
  }catch(IllegalArgumentException problem){message(tr(problem.getMessage()));}
 }
 @Override public boolean connected(){return session.connected();}
 @Override public void editPedal(int effect,int parameter,float value){
  if(preview==null)session.editPedal(effect,parameter,value);
  else if(Protocol.validEffectParameter(effect,parameter,value))preview.effect(effect,parameter,value);
 }
 @Override public void togglePedal(int effect){
  if(preview==null)session.togglePedal(effect);
  else{preview.mask^=1<<effect;pedals.refresh();}
 }
 @Override public void reorder(int from,int to){
  if(preview==null)session.reorder(from,to);
  else try{
   int[] order=preview.order();
   int id=order[from];order[from]=order[to];order[to]=id;
   preview.order(order);
   pedals.refresh();
  }catch(IllegalArgumentException problem){message(tr(problem.getMessage()));}
 }
 boolean canSave(){return preview==null&&session.canSave();}
 void saveLocalCopy(){library.saveCurrent();}
 @Override public void savePreset(){
  if(!canSave()){message(tr("Выбери и прочитай нужный быстрый слот."));return;}
  int slot=session.slot;
  new AlertDialog.Builder(this).setTitle(tr("Сохранить пресет в слот ")+slot+"?")
      .setMessage(tr("Текущий звук будет записан в быстрый слот «")+session.presetName()
          +tr("». Предыдущие считанные настройки сохранятся в телефоне."))
      .setNegativeButton(tr("Отмена"),null)
      .setPositiveButton(tr("Сохранить"),(d,w)->{
       if(session.slot==slot&&canSave())session.saveCurrent();
      }).show();
 }
 @Override public int mode(){return session.mode;}
 @Override public int activeSlot(){return session.canSave()?session.slot:0;}
 @Override public void previewPreset(Protocol.Preset data,String name){
  preview=data.copy();previewName=name;showingCurve=true;page=SOUND;consolePage=SOUND;render();
 }
 @Override public void applyPreset(Protocol.Preset data,String name,boolean store){
  preview=null;
  session.apply(data,name,store);
 }
 @Override public void disconnect(){preview=null;session.disconnect();}
 @Override public void reload(){session.reload();}
 @Override public void reset(){session.reset();}
 @Override public String logText(){return session.log();}
 @Override public void openTiming(){if(timingScreen!=null)timingScreen.show();}
 @Override public String language(){return getPreferences(0).getString("language","ru");}
 @Override public void language(String code){
  getPreferences(0).edit().putString("language",code).apply();
  Texts.init(this,code);
  session.status=tr(connected()?"G0 Pro подключён":"Не подключено");
  pedals=new PedalsScreen(this,this);
  render();
 }
 @Override public void refreshPage(){showCurrentPage();}
 @Override public void backup(Protocol.Preset data,String name){library.backupPreset(data,name);}
 @Override public void message(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}

 @Override public void connect(){
  String[] permissions=Build.VERSION.SDK_INT>=31
      ?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}
      :new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
  for(String permission:permissions){
   if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED){
    requestPermissions(permissions,BLUETOOTH_REQUEST);return;
   }
  }
  scan();
 }
 @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
  super.onRequestPermissionsResult(code,permissions,results);
  if(code!=BLUETOOTH_REQUEST||results.length==0)return;
  for(int result:results)if(result!=PackageManager.PERMISSION_GRANTED){
   message(tr("Для подключения нужен доступ к Bluetooth"));return;
  }
  scan();
 }
 private void scan(){
  if(scanner!=null)return;
  listed.clear();
  ScrollView scroll=new ScrollView(this);
  LinearLayout content=ui.column();
  content.setPadding(ui.dp(16),ui.dp(8),ui.dp(16),ui.dp(14));
  content.setMinimumHeight(ui.dp(165));
  scroll.addView(content);
  scanStatus=ui.text(tr("Поиск G0 Pro…"),13,Ui.MUTED);
  content.addView(scanStatus);
  devices=ui.column();
  ui.add(content,devices,7);
  scanner=new AlertDialog.Builder(this).setTitle(tr("Устройства G0 Pro")).setView(scroll)
      .setNegativeButton(tr("Отмена"),null).create();
  scanner.setOnDismissListener(dialog->{scanner=null;session.stopScan();});
  scanner.show();
  BluetoothManager manager=(BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
  BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
  if(adapter!=null&&BluetoothAdapter.checkBluetoothAddress(session.lastAddress()))
   found(adapter.getRemoteDevice(session.lastAddress()),tr("Последний G0 Pro"));
  session.scan();
 }
 @Override public void found(BluetoothDevice device,String name){
  if(scanner==null||!listed.add(device.getAddress()))return;
  ui.add(devices,ui.button(name+"\n"+device.getAddress(),()->{
   scanner.dismiss();
   preview=null;
   showingCurve=false;
   session.connect(device);
   page=SOUND;
   consolePage=SOUND;
   render();
  }),6);
 }
 @Override public void scanStatus(String text,boolean finished){
  if(scanner==null)return;
  scanStatus.setText(finished&&listed.isEmpty()&&text.equals(tr("Поиск завершён."))
      ?tr("G0 Pro не найден. Проверь, что датчик включён и отключён от Double+."):text);
 }
}
