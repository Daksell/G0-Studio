package ru.g0studio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static ru.g0studio.Texts.tr;

final class TimingScreen {
 private static final int EXPORT=24;
 private static final int[] DELAYS={-1,12,6,0,18,24};
 private static final String[] TAGS={"BASE","GAP12","GAP6","GAP0","GAP18","GAP24"};
 private static final int[] FRAGMENTS={-1,30,20,10,0};
 private static final int[] STAGES={TimingBenchmark.ALL,TimingBenchmark.EQ,TimingBenchmark.PEDALS,TimingBenchmark.SLOTS,0};
 private final Activity activity;
 private final G0Session session;
 private final Ui ui;
 private AlertDialog dialog;
 private Spinner profile;
 private Spinner fragment;
 private Spinner scenario;
 private EditText rounds;
 private TextView state;
 private Button run,stop,reconnect,apply;

 TimingScreen(Activity activity,G0Session session){
  this.activity=activity;
  this.session=session;
  ui=new Ui(activity);
 }

 void show(){
  if(dialog!=null)return;
  ScrollView scroll=new ScrollView(activity);
  LinearLayout content=ui.column();
  content.setPadding(ui.dp(18),ui.dp(6),ui.dp(18),ui.dp(10));
  scroll.addView(content);
  ui.add(content,ui.text(tr("Во время серии оставь органы управления датчика свободными. Тест возвращает исходный слот и временные параметры."),13,Ui.MUTED),0);
  ui.add(content,ui.text(tr("Пауза после подтверждения"),13,Ui.TEXT),12);
  profile=new Spinner(activity);
  String[] captions=new String[DELAYS.length];
  captions[0]=tr("Штатные задержки");
  for(int index=1;index<captions.length;index++)captions[index]=DELAYS[index]+tr(" мс");
  ArrayAdapter<String> profiles=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,captions);
  profiles.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
  profile.setAdapter(profiles);
  ui.add(content,profile,2);
  profile.setSelection(indexForTag(session.timingTag()));
  ui.add(content,ui.text(tr("Пауза между частями команды"),13,Ui.TEXT),10);
  fragment=new Spinner(activity);
  String[] fragmentLabels=new String[FRAGMENTS.length];
  fragmentLabels[0]=tr("Штатная пауза · 40 мс");
  for(int index=1;index<fragmentLabels.length;index++)fragmentLabels[index]=FRAGMENTS[index]+tr(" мс");
  ArrayAdapter<String> fragments=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,fragmentLabels);
  fragments.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
  fragment.setAdapter(fragments);
  ui.add(content,fragment,2);
  for(int index=1;index<FRAGMENTS.length;index++){
   if(session.timingTag().endsWith("_F"+FRAGMENTS[index]))fragment.setSelection(index);
  }
  ui.add(content,ui.text(tr("Число циклов (1–24)"),13,Ui.TEXT),10);
  rounds=new EditText(activity);
  rounds.setInputType(InputType.TYPE_CLASS_NUMBER);
  rounds.setSingleLine(true);
  rounds.setText("8");
  ui.add(content,rounds,0);
  ui.add(content,ui.text(tr("Что измерять"),13,Ui.TEXT),8);
  scenario=new Spinner(activity);
  String[] scenarioNames={tr("Весь обмен"),tr("Только EQ"),tr("Только педали"),
      tr("Только быстрые слоты"),tr("Только полная загрузка")};
  ArrayAdapter<String> scenarios=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,scenarioNames);
  scenarios.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
  scenario.setAdapter(scenarios);
  ui.add(content,scenario,2);
  state=ui.text("",13,Ui.ACCENT);
  state.setTextIsSelectable(true);
  ui.add(content,state,7);
  run=ui.button(tr("Начать серию"),this::start);
  stop=ui.button(tr("Остановить"),session::stopTiming);
  LinearLayout actions=ui.row();
  actions.addView(run,new LinearLayout.LayoutParams(0,-2,1));
  LinearLayout.LayoutParams second=new LinearLayout.LayoutParams(0,-2,1);
  second.setMarginStart(ui.dp(6));
  actions.addView(stop,second);
  ui.add(content,actions,8);
  reconnect=ui.button(tr("Измерить переподключение"),()->{
   int index=profile.getSelectedItemPosition();
   session.timedReconnect(tag(index),DELAYS[index],fragmentGap());
   refresh();
  });
  ui.add(content,reconnect,6);
  apply=ui.button(tr("Применить для ручной проверки"),()->{
   int index=profile.getSelectedItemPosition();
   session.manualTiming(tag(index),DELAYS[index],fragmentGap());
   refresh();
  });
  ui.add(content,apply,6);
  ui.add(content,ui.button(tr("Экспорт журнала TSV"),this::export),6);
  dialog=new AlertDialog.Builder(activity).setTitle(tr("Тест задержек"))
      .setView(scroll).setPositiveButton(tr("ОК"),null).create();
  dialog.setOnDismissListener(ignored->dialog=null);
  dialog.show();
  dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
  refresh();
 }

 private int indexForTag(String tag){
  for(int index=0;index<TAGS.length;index++)if(TAGS[index].equals(tag)||tag.startsWith(TAGS[index]+"_F"))return index;
  return 0;
 }
 private int fragmentGap(){return FRAGMENTS[fragment.getSelectedItemPosition()];}
 private String tag(int index){return fragmentGap()<0?TAGS[index]:TAGS[index]+"_F"+fragmentGap();}

 private void start(){
  int count;
  try{count=Integer.parseInt(rounds.getText().toString().trim());}
  catch(NumberFormatException problem){count=0;}
  if(count<1||count>24){rounds.setError(tr("Введи число от 1 до 24"));return;}
  int index=profile.getSelectedItemPosition();
  int stages=STAGES[scenario.getSelectedItemPosition()];
  session.startTiming(tag(index),DELAYS[index],fragmentGap(),count,stages,
      stages==TimingBenchmark.ALL||stages==0);
  refresh();
 }

 void refresh(){
  if(dialog==null)return;
  boolean active=session.timingActive();
  state.setText(session.timingStatus());
  run.setEnabled(!active&&session.connected());
  reconnect.setEnabled(!active&&session.connected());
  stop.setEnabled(active);
  apply.setEnabled(!active);
  profile.setEnabled(!active);
  fragment.setEnabled(!active);
  rounds.setEnabled(!active);
  scenario.setEnabled(!active);
  dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!active);
  dialog.setCancelable(!active);
  dialog.setCanceledOnTouchOutside(false);
 }

 private void export(){
  Intent create=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/tab-separated-values")
      .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"G0-Studio-timing.tsv");
  activity.startActivityForResult(create,EXPORT);
 }

 boolean activityResult(int request,int result,Intent intent){
  if(request!=EXPORT)return false;
  if(result!=Activity.RESULT_OK||intent==null||intent.getData()==null)return true;
  byte[] report=session.timingReport().getBytes(StandardCharsets.UTF_8);
  new Thread(()->{
   String message;
   try(OutputStream file=activity.getContentResolver().openOutputStream(intent.getData(),"wt")){
    if(file==null)throw new IOException("No output stream");
    file.write(report);
    message=tr("Экспорт готов");
   }catch(IOException|SecurityException failure){message=tr("Не удалось записать журнал");}
   String resultMessage=message;
   activity.runOnUiThread(()->{
    if(!activity.isDestroyed())Toast.makeText(activity,resultMessage,Toast.LENGTH_LONG).show();
   });
  },"G0-timing-export").start();
  return true;
 }

 void destroy(){if(dialog!=null)dialog.dismiss();}
}
