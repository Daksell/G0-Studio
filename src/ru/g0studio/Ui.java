package ru.g0studio;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
 static final int BACK=0xff101116,SURFACE=0xff1a1a22,RAISED=0xff2b2b36,TEXT=0xfff0eff5,
     MUTED=0xffa19da9,ACCENT=0xff79cdd5,PINK=0xfff17cad,LINE=0xff383744,WARN=0xffffad90;
 final Context context;
 Ui(Context context){this.context=context;}
 int dp(float value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
 LinearLayout column(){
  LinearLayout view=new LinearLayout(context);
  view.setOrientation(LinearLayout.VERTICAL);
  return view;
 }
 LinearLayout row(){
  LinearLayout view=new LinearLayout(context);
  view.setGravity(Gravity.CENTER_VERTICAL);
  return view;
 }
 TextView text(String value,int size,int color){
  TextView view=new TextView(context);
  view.setText(value);
  view.setTextSize(size);
  view.setTextColor(color);
  return view;
 }
 TextView heading(String value,int size){
  TextView view=text(value,size,TEXT);
  view.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
  return view;
 }
 GradientDrawable round(int color,float radius){
  GradientDrawable drawable=new GradientDrawable();
  drawable.setColor(color);
  drawable.setCornerRadius(dp(radius));
  return drawable;
 }
 RippleDrawable pressable(int fill,int outline,float radius){
  GradientDrawable normal=round(fill,radius);
  if(outline!=0)normal.setStroke(dp(1),outline);
  return new RippleDrawable(ColorStateList.valueOf(0x29f0eff5),normal,round(0xffffffff,radius));
 }
 LinearLayout panel(){
  LinearLayout view=column();
  view.setBackground(round(SURFACE,16));
  view.setPadding(dp(16),dp(14),dp(16),dp(14));
  return view;
 }
 Button button(String title,Runnable action){
  Button view=new Button(context);
  view.setText(title);
  view.setAllCaps(false);
  view.setMinHeight(0);
  view.setMinimumHeight(dp(44));
  view.setMinWidth(0);
  view.setMinimumWidth(0);
  view.setTextSize(13);
  view.setPadding(dp(10),dp(7),dp(10),dp(7));
  styleButton(view,false);
  view.setOnClickListener(v->action.run());
  return view;
 }
 void styleButton(Button view,boolean primary){
  view.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},
      new int[]{0xff767482,primary?ACCENT:TEXT}));
  view.setBackground(pressable(primary?RAISED:SURFACE,primary?0xff426f78:0,14));
 }
 void add(LinearLayout parent,View child,int gapDp){
  LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-1,-2);
  size.topMargin=dp(gapDp);
  parent.addView(child,size);
 }
}
