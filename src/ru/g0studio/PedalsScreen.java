package ru.g0studio;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

import static ru.g0studio.Texts.tr;

final class PedalsScreen {
 interface Host {
  Protocol.Preset preset();
  boolean connected();
  void editPedal(int effect,int parameter,float value);
  void togglePedal(int effect);
  void reorder(int fromSignalPosition,int toSignalPosition);
  void savePreset();
 }

 private static final int[] EFFECT_BY_DEVICE_ID={-1,3,1,2,0};
 private static final String[] SHORT_NAMES={"Компр.","Реверб.","Хорус","Дилей"};
 private static final int[] PEDAL_TINT={0xffe5bf90,0xffeee4a4,0xff97e3ee,0xff9bbcf8};
 private static final int[] PEDAL_FINISH={0xffa47758,0xff8c8d62,0xff678f9c,0xff3c628c};
 private static final int[] PEDAL_SHADE={0xff503629,0xff484b35,0xff294553,0xff1d304d};
 private static final float ART_WIDTH=190f,ART_HEIGHT=296f;
 private static final float KNOB_TARGET=54f;
 private static final float[][] KNOB_X={{95,56,134},{56,134,95},{134,56,95},{134,95,56}};
 private static final float[][] KNOB_Y={{48,103,103},{50,50,111},{50,50,111},{50,111,50}};
 private static final String STUDIO_BRAND="G0 STUDIO";
 private static final int MOVE_TOWARD_OUTPUT=0x100101;
 private static final int MOVE_TOWARD_INPUT=0x100102;
 private static final String[][] HELP={
  {"Порог компрессии. При меньшем значении компрессор начинает срабатывать на более тихих нотах.",
   "Степень сжатия сигнала выше порога. При большем значении разница между тихими и громкими нотами уменьшается.",
   "Выходной уровень компрессора. Помогает выровнять громкость при включении эффекта."},
  {"Окраска отражений. Чем выше значение, тем ярче звучит хвост реверберации.",
   "Длительность хвоста реверберации. Чем выше значение, тем дольше слышны отражения.",
   "Доля реверберации в звуке. При большем значении отражения слышны сильнее."},
  {"Скорость модуляции. Чем выше значение, тем быстрее колебания звука.",
   "Глубина модуляции. При большем значении колебания выражены сильнее.",
   "Доля обработанного сигнала. При большем значении хорус слышен сильнее."},
  {"Количество и длительность повторов. При большем значении эхо затухает медленнее.",
   "Громкость повторов. При большем значении эхо слышно сильнее.",
   "Интервал между повторами от 50 до 500 мс."}
 };

 private final Context context;
 private final Host host;
 private final Ui ui;
 private final LinearLayout root;
 private final ScrollView scroll;
 private int selectedEffect;
 private boolean rebuilding;

 PedalsScreen(Context context,Host host){
  this.context=context;
  this.host=host;
  ui=new Ui(context);
  root=ui.column();
  root.setPadding(ui.dp(16),ui.dp(4),ui.dp(16),ui.dp(12));
  root.setFocusableInTouchMode(true);
  scroll=new ScrollView(context);
  scroll.setVerticalScrollBarEnabled(false);
  scroll.addView(root,new ScrollView.LayoutParams(-1,-2));
 }

 View view(){
  refresh();
  return scroll;
 }

 void refresh(){
  Protocol.Preset preset=host.preset();
  rebuilding=true;
  root.removeAllViews();
  rebuilding=false;
  root.addView(controlPanel(preset));
  root.addView(signalChain(preset),below(-2,10));
  if(preset!=null){
   View save=ui.button(tr("Сохранить пресет"),()->{
    root.requestFocus();
    host.savePreset();
   });
   save.setEnabled(host.connected());
   save.setAlpha(host.connected()?1f:.45f);
   root.addView(save,below(ui.dp(48),9));
  }
 }

 private LinearLayout signalChain(Protocol.Preset preset){
  LinearLayout card=ui.column();
  SignalRail rail=new SignalRail();
  int[] order=usableOrder(preset);
  for(int position=0;position<order.length;position++){
   int effect=EFFECT_BY_DEVICE_ID[order[position]];
   SignalPedal tile=new SignalPedal(effect,position,isEnabled(preset,effect),selectedEffect==effect);
   tile.setOnClickListener(v->{
    if(selectedEffect==effect)return;
    root.requestFocus();
    selectedEffect=effect;
    refresh();
   });
   if(preset!=null)attachReorder(tile,preset,position);
   LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,ui.dp(90),1);
   params.setMargins(ui.dp(2),0,ui.dp(2),0);
   rail.addView(tile,params);
  }
  card.addView(rail,new LinearLayout.LayoutParams(-1,ui.dp(90)));
  if(preset!=null)card.addView(ui.text(tr("Удерживай и перетащи плитку, чтобы изменить порядок."),11,Ui.MUTED),below(-2,5));
  return card;
 }

 private void attachReorder(View tile,Protocol.Preset preset,int position){
  tile.setOnLongClickListener(v->{
   if(preset!=host.preset())return false;
   return v.startDragAndDrop(ClipData.newPlainText("effect-order",""),new View.DragShadowBuilder(v),
       Integer.valueOf(position),0);
  });
  tile.setOnDragListener((v,event)->{
   switch(event.getAction()){
    case DragEvent.ACTION_DRAG_STARTED:
     return event.getLocalState() instanceof Integer;
    case DragEvent.ACTION_DRAG_ENTERED:
     v.setAlpha(.55f);
     return true;
    case DragEvent.ACTION_DRAG_EXITED:
    case DragEvent.ACTION_DRAG_ENDED:
     v.setAlpha(1f);
     return true;
    case DragEvent.ACTION_DROP:
     if(!(event.getLocalState() instanceof Integer)||preset!=host.preset())return false;
     v.setAlpha(1f);
     moveEffect((Integer)event.getLocalState(),position);
     return true;
    default:
     return true;
   }
  });
  tile.setAccessibilityDelegate(new View.AccessibilityDelegate(){
   @Override public void onInitializeAccessibilityNodeInfo(View view,AccessibilityNodeInfo info){
    super.onInitializeAccessibilityNodeInfo(view,info);
    if(position<3)info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
        MOVE_TOWARD_OUTPUT,tr("Сдвинуть к выходу")));
    if(position>0)info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
        MOVE_TOWARD_INPUT,tr("Сдвинуть ко входу")));
   }
   @Override public boolean performAccessibilityAction(View view,int action,Bundle args){
    if(preset==host.preset()){
     if(action==MOVE_TOWARD_OUTPUT&&position<3){moveEffect(position,position+1);return true;}
     if(action==MOVE_TOWARD_INPUT&&position>0){moveEffect(position,position-1);return true;}
    }
    return super.performAccessibilityAction(view,action,args);
   }
  });
 }

 private void moveEffect(int from,int to){
  if(from==to||from<0||from>3||to<0||to>3)return;
  root.requestFocus();
  host.reorder(from,to);
  refresh();
 }

 private LinearLayout controlPanel(Protocol.Preset preset){
  int effect=selectedEffect;
  boolean on=isEnabled(preset,effect);
  int tint=PEDAL_TINT[effect];
  LinearLayout card=ui.column();
  LinearLayout heading=ui.row();
  heading.setGravity(Gravity.CENTER_VERTICAL);
  TextView title=ui.text(tr(Protocol.FX[effect]),23,Ui.TEXT);
  title.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
  heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
  TextView help=ui.button("ⓘ",()->showHelp(effect));
  help.setTextSize(19);
  help.setPadding(0,0,0,0);
  help.setContentDescription(tr("Описание параметров · ")+tr(Protocol.FX[effect]));
  heading.addView(help,new LinearLayout.LayoutParams(ui.dp(44),ui.dp(44)));
  card.addView(heading,new LinearLayout.LayoutParams(-1,-2));
  LinearLayout row=ui.row();
  row.setGravity(Gravity.CENTER_VERTICAL);
  int span=context.getResources().getConfiguration().screenWidthDp-32;
  int width=Math.min(222,Math.round(Math.max(220,span)*.58f));
  int height=Math.round(width*ART_HEIGHT/ART_WIDTH);
  PedalFace face=new PedalFace(effect,on);
  row.addView(face,new LinearLayout.LayoutParams(ui.dp(width),ui.dp(height)));
  LinearLayout values=ui.column();
  values.setPadding(ui.dp(10),0,0,0);
  row.addView(values,new LinearLayout.LayoutParams(0,-2,1));
  card.addView(row,below(-2,6));
  if(preset==null){
   values.addView(ui.text(tr("Подключи датчик, чтобы настроить педали текущего пресета."),13,Ui.MUTED));
   return card;
  }
  for(int parameter=0;parameter<3;parameter++)addControl(face,values,preset,effect,parameter);

  Runnable toggle=()->{
   if(preset!=host.preset())return;
   root.requestFocus();
   host.togglePedal(effect);
  };
  String switchLabel=tr(Protocol.FX[effect])+(on?tr(" · Выключить"):tr(" · Включить"));
  View footswitch=new View(context);
  footswitch.setContentDescription(switchLabel);
  footswitch.setFocusable(true);
  footswitch.setOnClickListener(v->toggle.run());
  face.addView(footswitch,new ViewGroup.LayoutParams(-2,-2));
  TextView power=ui.button(on?tr("● Вкл"):tr("○ Выкл"),toggle);
  power.setTextColor(on?tint:Ui.MUTED);
  power.setContentDescription(switchLabel);
  values.addView(power,below(ui.dp(45),10));
  return card;
 }

 private void addControl(PedalFace face,LinearLayout column,Protocol.Preset preset,int effect,int parameter){
  boolean time=Protocol.isDelayTime(effect,parameter);
  float min=time?Protocol.DELAY_TIME_MIN_MS:0f;
  float max=time?Protocol.DELAY_TIME_MAX_MS:1f;
  String label=tr(Protocol.PARAM[effect][parameter]);

  TextView heading=ui.text(label+(time?" · "+tr(" мс").trim():""),12,Ui.MUTED);
  heading.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
  column.addView(heading,below(-2,parameter==0?0:5));

  EditText field=new EditText(context);
  field.setId(View.generateViewId());
  field.setTextColor(Ui.TEXT);
  field.setTextSize(17);
  field.setPadding(ui.dp(3),0,ui.dp(3),ui.dp(3));
  field.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
  field.setSingleLine(true);
  field.setSelectAllOnFocus(true);
  field.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
  field.setImeOptions(EditorInfo.IME_ACTION_DONE|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
  field.setBackgroundTintList(ColorStateList.valueOf(Ui.LINE));
  field.setText(number(preset.effect(effect,parameter)));
  heading.setLabelFor(field.getId());

  Dial dial=new Dial(label,preset.effect(effect,parameter),min,max,time,effect,value->{
   if(preset==host.preset()){
    host.editPedal(effect,parameter,value);
    field.setError(null);
    syncControl(field,preset.effect(effect,parameter));
   }
   return preset.effect(effect,parameter);
  });
  dial.setOnClickListener(v->{
   field.requestFocus();
   InputMethodManager ime=(InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE);
   if(ime!=null)ime.showSoftInput(field,InputMethodManager.SHOW_IMPLICIT);
  });
  face.addView(dial,new ViewGroup.LayoutParams(-2,-2));
  column.addView(field,new LinearLayout.LayoutParams(-1,ui.dp(40)));

  field.setOnEditorActionListener((v,action,event)->{
   boolean enter=event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER;
   if(action!=EditorInfo.IME_ACTION_DONE&&!enter)return false;
   if(enter&&event.getAction()!=KeyEvent.ACTION_DOWN)return true;
   if(commitNumeric(field,dial,preset,effect,parameter)){
    InputMethodManager ime=(InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE);
    if(ime!=null)ime.hideSoftInputFromWindow(field.getWindowToken(),0);
    root.requestFocus();
   }
   return true;
  });
  field.setOnFocusChangeListener((v,focused)->{
   if(!focused&&!rebuilding&&field.isAttachedToWindow()&&!commitNumeric(field,dial,preset,effect,parameter)){
    field.setError(null);
    syncControl(field,preset.effect(effect,parameter));
   }
  });
 }

 private boolean commitNumeric(EditText field,Dial dial,Protocol.Preset preset,int effect,int parameter){
  if(preset!=host.preset())return false;
  float prior=preset.effect(effect,parameter);
  String entry=field.getText().toString().trim();
  if(entry.equals(number(prior))){field.setError(null);return true;}
  float value;
  try{
   value=Float.parseFloat(entry.replace(',','.'));
  }catch(NumberFormatException e){value=Float.NaN;}
  if(value==prior){field.setError(null);return true;}
  if(!Protocol.validEffectParameter(effect,parameter,value)){
   field.setError(Protocol.isDelayTime(effect,parameter)
       ?tr("Допустимо ")+Protocol.DELAY_TIME_MIN_MS+"–"+Protocol.DELAY_TIME_MAX_MS+tr(" мс")
       :tr("Допустимо 0…1"));
   return false;
  }
  host.editPedal(effect,parameter,value);
  float confirmed=preset.effect(effect,parameter);
  field.setError(null);
  syncControl(field,confirmed);
  dial.setValue(confirmed);
  return true;
 }

 private void syncControl(EditText field,float raw){
  String display=number(raw);
  if(!display.contentEquals(field.getText()))field.setText(display);
 }

 private void showHelp(int effect){
  StringBuilder copy=new StringBuilder();
  for(int i=0;i<HELP[effect].length;i++){
   if(i>0)copy.append("\n\n");
   copy.append(tr(Protocol.PARAM[effect][i])).append(" — ").append(tr(HELP[effect][i]));
  }
  new AlertDialog.Builder(context).setTitle(tr(Protocol.FX[effect])).setMessage(copy.toString())
      .setPositiveButton(tr("ОК"),null).show();
 }

 private LinearLayout.LayoutParams below(int height,int margin){
  LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,height);
  params.topMargin=ui.dp(margin);
  return params;
 }

 private static int[] usableOrder(Protocol.Preset preset){
  if(preset!=null){
   int[] order=preset.order();
   int seen=0;
   for(int id:order)if(id>=1&&id<=4)seen|=1<<id;
   if(seen==0b11110)return order;
  }
  return new int[]{4,2,3,1};
 }

 private static boolean isEnabled(Protocol.Preset preset,int effect){
  return preset!=null&&(preset.mask&(1<<effect))!=0;
 }

 private static String number(float value){
  return Float.isFinite(value)?new BigDecimal(Float.toString(value)).setScale(5,RoundingMode.HALF_UP)
      .stripTrailingZeros().toPlainString():"";
 }

 private final class SignalRail extends LinearLayout {
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);

  SignalRail(){
   super(context);
   setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
   setWillNotDraw(false);
   paint.setColor(0xff657185);
   paint.setStrokeWidth(ui.dp(1.5f));
   paint.setStrokeCap(Paint.Cap.ROUND);
  }

  @Override protected void onDraw(Canvas canvas){
   super.onDraw(canvas);
   float y=ui.dp(38);
   canvas.drawLine(0,y,getWidth(),y,paint);
   for(int i=1;i<getChildCount();i++){
    float x=(getChildAt(i-1).getRight()+getChildAt(i).getLeft())*.5f;
    canvas.drawLine(x-ui.dp(2),y-ui.dp(3),x+ui.dp(2),y,paint);
    canvas.drawLine(x-ui.dp(2),y+ui.dp(3),x+ui.dp(2),y,paint);
   }
  }
 }

 private final class SignalPedal extends LinearLayout {
  SignalPedal(int effect,int position,boolean active,boolean selected){
   super(context);
   setOrientation(VERTICAL);
   setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);
   setFocusable(true);
   setClickable(true);
   setSelected(selected);
   setPadding(ui.dp(2),ui.dp(4),ui.dp(2),ui.dp(2));
   setContentDescription((position+1)+". "+tr(Protocol.FX[effect])+
       (active?tr(" · Вкл"):tr(" · Выкл")));

   int tint=PEDAL_TINT[effect];
   if(selected){
    GradientDrawable outline=ui.round(0x08ffffff,14);
    outline.setStroke(ui.dp(1),tint);
    setBackground(outline);
   }
   PedalThumbnail icon=new PedalThumbnail(effect,active);
   icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
   addView(icon,new LinearLayout.LayoutParams(ui.dp(40),ui.dp(61)));

   TextView name=ui.text((position+1)+" "+(active?"● ":"")+tr(SHORT_NAMES[effect]),10,
       active?tint:selected?0xffcdcad6:Ui.MUTED);
   name.setGravity(Gravity.CENTER);
   name.setSingleLine(true);
   name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
   addView(name,new LinearLayout.LayoutParams(-1,ui.dp(20)));
  }
 }

 private final class PedalFace extends ViewGroup {
  private final int effect;
  private final PedalSkin skin;

  PedalFace(int effect,boolean active){
   super(context);
   this.effect=effect;
   skin=new PedalSkin(effect,active);
   setWillNotDraw(false);
   setClipChildren(false);
   setClipToPadding(false);
  }

  @Override protected void onMeasure(int widthSpec,int heightSpec){
   int width=MeasureSpec.getSize(widthSpec),height=MeasureSpec.getSize(heightSpec);
   setMeasuredDimension(width,height);
   float scale=artScale(width,height);
   for(int index=0;index<getChildCount();index++){
    boolean dial=index<3;
    int measuredWidth=Math.round((dial?KNOB_TARGET:156)*scale);
    int measuredHeight=dial?measuredWidth:Math.round(116*scale);
    getChildAt(index).measure(MeasureSpec.makeMeasureSpec(measuredWidth,MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(measuredHeight,MeasureSpec.EXACTLY));
   }
  }

  @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){
   float scale=artScale(getWidth(),getHeight());
   float offsetX=(getWidth()-ART_WIDTH*scale)*.5f;
   float offsetY=(getHeight()-ART_HEIGHT*scale)*.5f;
   for(int index=0;index<getChildCount();index++){
    View child=getChildAt(index);
    float x=index<3?KNOB_X[effect][index]:95;
    float y=index<3?KNOB_Y[effect][index]:232;
    int w=child.getMeasuredWidth(),h=child.getMeasuredHeight();
    int childLeft=Math.round(offsetX+x*scale-w*.5f);
    int childTop=Math.round(offsetY+y*scale-h*.5f);
    child.layout(childLeft,childTop,childLeft+w,childTop+h);
   }
  }

  @Override protected void onDraw(Canvas canvas){
   super.onDraw(canvas);
   skin.draw(canvas,getWidth(),getHeight(),getChildCount()==0);
  }
 }

 private final class PedalThumbnail extends View {
  private final PedalSkin skin;
  private final Paint dimmingPaint;

  PedalThumbnail(int effect,boolean active){
   super(context);
   skin=new PedalSkin(effect,active);
   dimmingPaint=active?null:new Paint();
   if(dimmingPaint!=null)dimmingPaint.setColorFilter(new LightingColorFilter(0xff8f8f8f,0));
  }

  @Override protected void onDraw(Canvas canvas){
   super.onDraw(canvas);
   int layer=dimmingPaint==null?-1:canvas.saveLayer(0,0,getWidth(),getHeight(),dimmingPaint);
   skin.draw(canvas,getWidth(),getHeight(),true);
   if(layer!=-1)canvas.restoreToCount(layer);
  }
 }

 private static float artScale(int width,int height){return Math.min(width/ART_WIDTH,height/ART_HEIGHT);}

 private static final class PedalSkin {
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  private final int effect,tint;
  private final boolean active;
  private final Shader finish, inset, metal;
  private final Shader led;
  private final KnobArtwork thumbnailKnob;
  private static final Typeface FACE_TYPE=Typeface.create("sans-serif-medium",Typeface.NORMAL);

  PedalSkin(int effect,boolean active){
   this.effect=effect;
   this.active=active;
   tint=PEDAL_TINT[effect];
   thumbnailKnob=new KnobArtwork(effect);
   finish=new LinearGradient(22,8,173,286,
       new int[]{tint,PEDAL_FINISH[effect],PEDAL_SHADE[effect]},new float[]{0,.34f,1},Shader.TileMode.CLAMP);
   inset=new LinearGradient(27,17,151,149,
       new int[]{0x9041424b,0x55494c50,0x332d2a31},null,Shader.TileMode.CLAMP);
   metal=new LinearGradient(79,201,111,242,
       new int[]{0xffebedf0,0xff959ca4,0xffe6e6eb,0xff676e79,0xffc2c5ca},
       null,Shader.TileMode.CLAMP);
   led=new RadialGradient(95,174,13,new int[]{tint,0x005bcef1},null,Shader.TileMode.CLAMP);
  }

  void draw(Canvas canvas,int width,int height,boolean staticDials){
   if(width==0||height==0)return;
   canvas.save();
   float scale=artScale(width,height);
   canvas.translate((width-ART_WIDTH*scale)*.5f,(height-ART_HEIGHT*scale)*.5f);
   canvas.scale(scale,scale);
   paint.setStyle(Paint.Style.FILL);
   paint.setShader(null);

   paint.setColor(0xff09090c);
   canvas.drawRoundRect(1,150,20,192,5,5,paint);
   canvas.drawRoundRect(170,150,189,192,5,5,paint);
   paint.setColor(0xff5a5d68);
   canvas.drawRoundRect(3,154,9,187,3,3,paint);
   canvas.drawRoundRect(181,154,187,187,3,3,paint);
   paint.setColor(0x90000000);
   canvas.drawRoundRect(12,10,177,294,19,19,paint);
   paint.setColor(0xff22222a);
   canvas.drawRoundRect(10,5,180,289,19,19,paint);
   paint.setShader(finish);
   canvas.drawRoundRect(13,6,177,284,16,16,paint);
   paint.setShader(null);
   paint.setColor(0x55ffffff);
   paint.setStrokeWidth(1.8f);
   paint.setStyle(Paint.Style.STROKE);
   canvas.drawRoundRect(16,9,174,281,14,14,paint);
   paint.setStyle(Paint.Style.FILL);

   paint.setColor(0x67373740);
   canvas.drawRoundRect(23,14,167,152,10,10,paint);
   paint.setShader(inset);
   canvas.drawRoundRect(25,16,165,151,8,8,paint);
   paint.setShader(null);
   drawScrew(canvas,24,22);
   drawScrew(canvas,166,22);
   drawScrew(canvas,24,268);
   drawScrew(canvas,166,268);

   for(int parameter=0;parameter<3;parameter++){
    float x=KNOB_X[effect][parameter],y=KNOB_Y[effect][parameter];
    if(staticDials)thumbnailKnob.draw(canvas,x,y,22,parameter==1?.68f:.5f,false);
    inscription(canvas,tr(Protocol.PARAM[effect][parameter]).toUpperCase(Locale.ROOT),x,y+31,8,64,
        0xfff2e9df);
   }

   if(active){
    paint.setShader(led);
    canvas.drawCircle(95,174,13,paint);
    paint.setShader(null);
   }
   paint.setColor(active?tint:0xff4c4249);
   canvas.drawCircle(95,174,4,paint);
   inscription(canvas,tr(Protocol.FX[effect]).toUpperCase(Locale.ROOT),95,195,12,142,
       0xfff3ecdf);

   paint.setColor(0x88000000);
   canvas.drawCircle(96,224,21,paint);
   paint.setShader(metal);
   canvas.drawCircle(95,220,19,paint);
   paint.setShader(null);
   paint.setColor(0xff444853);
   canvas.drawCircle(95,220,13,paint);
   paint.setShader(metal);
   canvas.drawCircle(95,219,10.5f,paint);
   paint.setShader(null);
   paint.setColor(active?0x832c2b33:0x50303038);
   canvas.drawRoundRect(45,251,145,269,5,5,paint);
   inscription(canvas,STUDIO_BRAND,95,263,9,90,active?tint:0xffded5cd);
   canvas.restore();
  }

  private void drawScrew(Canvas canvas,float x,float y){
   paint.setColor(0xb0424655);
   canvas.drawCircle(x,y,3.7f,paint);
   paint.setColor(0xafdadbe4);
   paint.setStrokeWidth(.9f);
   canvas.drawLine(x-2,y+1.2f,x+2,y-1.2f,paint);
  }

  private void inscription(Canvas canvas,String text,float x,float y,float size,float width,int color){
   paint.setColor(color);
   paint.setTypeface(FACE_TYPE);
   paint.setTextAlign(Paint.Align.CENTER);
   paint.setTextSize(size);
   float measured=paint.measureText(text);
   if(measured>width)paint.setTextSize(size*width/measured);
   canvas.drawText(text,x,y,paint);
  }
 }

 private static final class KnobArtwork {
  private static final float START=135, SWEEP=270;
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Shader bevel, cap;
  private final int pointer;

  KnobArtwork(int effect){
   boolean dark=effect==0||effect==3;
   int light=dark?0xff666976:effect==1?0xfff5edda:0xffdfe3d5;
   int shadow=dark?0xff323441:effect==1?0xffa4977f:0xff879b96;
   pointer=dark?0xfff6d7ad:0xfffaf7ef;
   bevel=new LinearGradient(-.7f,-.75f,.7f,.75f,
       new int[]{0xff696c77,0xff292b35,0xff101117,0xff3b3e49},null,Shader.TileMode.CLAMP);
   cap=new RadialGradient(-.26f,-.34f,1.05f,light,shadow,Shader.TileMode.CLAMP);
  }

  void draw(Canvas canvas,float x,float y,float radius,float amount,boolean ticks){
   if(radius<=0)return;
   canvas.save();
   canvas.translate(x,y);
   canvas.scale(radius,radius);
   paint.setShader(null);
   paint.setStyle(Paint.Style.FILL);
   paint.setStrokeCap(Paint.Cap.ROUND);
   if(ticks){
    for(int index=0;index<=10;index++){
     double a=Math.toRadians(START+index*SWEEP/10);
     float cx=(float)Math.cos(a),cy=(float)Math.sin(a);
     boolean major=index%5==0;
     paint.setStrokeWidth(major?.044f:.027f);
     paint.setColor(major?0xbceeeaf0:0x86e2dfe5);
     canvas.drawLine(cx*1.06f,cy*1.06f,cx*(major?1.17f:1.13f),cy*(major?1.17f:1.13f),paint);
    }
   }

   paint.setColor(0xa6000000);
   canvas.drawCircle(0,0,1.025f,paint);
   paint.setColor(0xff15161c);
   canvas.drawCircle(0,0,.99f,paint);
   paint.setShader(bevel);
   canvas.drawCircle(0,0,.94f,paint);
   paint.setShader(null);
   paint.setColor(0xff171820);
   canvas.drawCircle(0,0,.76f,paint);
   paint.setShader(cap);
   canvas.drawCircle(0,0,.68f,paint);
   paint.setShader(null);
   paint.setStyle(Paint.Style.STROKE);
   paint.setStrokeWidth(.022f);
   paint.setColor(0x4ce8e7ee);
   canvas.drawCircle(0,0,.68f,paint);

   double angle=Math.toRadians(START+SWEEP*amount);
   float cos=(float)Math.cos(angle),sin=(float)Math.sin(angle);
   paint.setColor(0xff171820);
   paint.setStrokeWidth(.12f);
   canvas.drawLine(cos*.68f,sin*.68f,cos*.89f,sin*.89f,paint);
   paint.setColor(pointer);
   paint.setStrokeWidth(.074f);
   canvas.drawLine(cos*.68f,sin*.68f,cos*.89f,sin*.89f,paint);
   canvas.restore();
  }
 }

 private interface DialCommit {float changed(float value);}

 private final class Dial extends View {
  private final KnobArtwork artwork;
  private final String label;
  private final float min,max;
  private final boolean milliseconds;
  private final DialCommit callback;
  private final int slop;
  private float value,preview,downY,lastY,lastAngle,fraction;
  private boolean moved,verticalGesture,angleReady;
  private int pointerId=-1;

  Dial(String label,float value,float min,float max,boolean milliseconds,int effect,DialCommit callback){
   super(context);
   this.label=label;
   this.min=min;
   this.max=max;
   this.milliseconds=milliseconds;
   artwork=new KnobArtwork(effect);
   this.callback=callback;
   slop=ViewConfiguration.get(context).getScaledTouchSlop();
   setClickable(true);
   setFocusable(true);
   setValue(value);
  }

  void setValue(float value){
   this.value=value;
   setContentDescription(label+" "+number(value)+(milliseconds?tr(" мс"):"")+tr(". Нажми для ввода значения"));
   invalidate();
  }

  @Override protected void onDraw(Canvas canvas){
   super.onDraw(canvas);
   float x=getWidth()/2f,y=getHeight()/2f;
   artwork.draw(canvas,x,y,Math.min(x,y)*.82f,normalize(moved?preview:value),true);
  }

  @Override public boolean onTouchEvent(MotionEvent event){
   switch(event.getActionMasked()){
    case MotionEvent.ACTION_DOWN:
     pointerId=event.getPointerId(0);
     moved=false;
     downY=lastY=event.getY();
     lastAngle=angleFromCenter(event.getX(),event.getY());
     angleReady=true;
     preview=value;
     fraction=normalize(value);
     verticalGesture=insideDeadZone(event.getX(),event.getY());
     holdGesture(true);
     return true;
    case MotionEvent.ACTION_MOVE:
     int index=event.findPointerIndex(pointerId);
     if(index>=0)adjustDial(event.getX(index),event.getY(index));
     return true;
    case MotionEvent.ACTION_POINTER_UP:
     if(event.getPointerId(event.getActionIndex())==pointerId)finishTouch(false);
     return true;
    case MotionEvent.ACTION_UP:
     if(pointerId>=0)finishTouch(true);
     return true;
    case MotionEvent.ACTION_CANCEL:
     pointerId=-1;
     moved=false;
     holdGesture(false);
     invalidate();
     return true;
    default:
     return true;
   }
  }

  private void adjustDial(float x,float y){
   float change;
   if(verticalGesture){
    if(!moved&&Math.abs(y-downY)<slop)return;
    change=(lastY-y)/ui.dp(120);
    lastY=y;
   }else{
    if(insideDeadZone(x,y)){angleReady=false;return;}
    float angle=angleFromCenter(x,y);
    if(!angleReady){angleReady=true;lastAngle=angle;return;}
    float delta=(angle-lastAngle+540)%360-180;
    if(!moved&&Math.abs(delta)<7)return;
    change=delta/KnobArtwork.SWEEP;
    lastAngle=angle;
   }
   moved=true;
   fraction=clamp(fraction+change);
   preview=scaled(fraction);
   invalidate();
  }

  private boolean insideDeadZone(float x,float y){
   return Math.hypot(x-getWidth()/2f,y-getHeight()/2f)<Math.min(getWidth(),getHeight())*.19f;
  }

  private float angleFromCenter(float x,float y){
   return (float)Math.toDegrees(Math.atan2(y-getHeight()/2f,x-getWidth()/2f));
  }

  private void finishTouch(boolean allowClick){
   boolean changed=moved;
   pointerId=-1;
   moved=false;
   holdGesture(false);
   if(changed){commit(preview);invalidate();}
   else if(allowClick)performClick();
  }

  @Override public boolean performClick(){
   super.performClick();
   return true;
  }

  @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
   super.onInitializeAccessibilityNodeInfo(info);
   info.setClassName("android.widget.SeekBar");
   info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
       AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,min,max,min+(max-min)*normalize(value)));
   info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
   info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
   info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
  }

  @Override public boolean performAccessibilityAction(int action,Bundle arguments){
   if(action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()&&arguments!=null){
    commit(scaled(normalize(arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE))));
    return true;
   }
   if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD||action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD){
    commit(scaled(clamp(normalize(value)+(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD?.02f:-.02f))));
    return true;
   }
   return super.performAccessibilityAction(action,arguments);
  }

  private void commit(float next){
   if(next==value)return;
   setValue(callback.changed(next));
  }

  private void holdGesture(boolean hold){
   ViewParent parent=getParent();
   if(parent!=null)parent.requestDisallowInterceptTouchEvent(hold);
  }

  private float scaled(float normalized){
   float magnitude=min+normalized*(max-min);
   return milliseconds?Math.round(magnitude):Math.round(100*magnitude)/100f;
  }

  private float normalize(float input){
   return Float.isFinite(input)?clamp((input-min)/(max-min)):0f;
  }

  private float clamp(float value){return Math.max(0f,Math.min(1f,value));}
 }
}
