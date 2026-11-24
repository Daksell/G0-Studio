package ru.g0studio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

final class NavIcon extends View {
 static final int CONSOLE=3, SETTINGS=4;
 private final int icon;
 private final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
 private final Path path=new Path();

 NavIcon(Context context,int icon,int color){
  super(context);
  this.icon=icon;
  ink.setColor(color);
  ink.setStyle(Paint.Style.STROKE);
  ink.setStrokeWidth(1.65f);
  ink.setStrokeJoin(Paint.Join.ROUND);
  ink.setStrokeCap(Paint.Cap.ROUND);
  setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
 }

 @Override protected void onDraw(Canvas canvas){
  float size=Math.min(getWidth(),getHeight());
  canvas.save();
  canvas.translate((getWidth()-size)/2,(getHeight()-size)/2);
  canvas.scale(size/24,size/24);
  path.reset();
  switch(icon){
   case MainActivity.SOUND:
    canvas.drawRoundRect(3,3,21,21,2,2,ink);
    path.moveTo(6,12);
    path.lineTo(9,12);
    path.lineTo(10.5f,8.6f);
    path.lineTo(12.5f,15.5f);
    path.lineTo(14.6f,10.4f);
    path.lineTo(16,12);
    path.lineTo(18,12);
    canvas.drawPath(path,ink);
    break;
   case MainActivity.PEDALS:
    canvas.drawRoundRect(3.5f,1.8f,20.5f,22.2f,2.5f,2.5f,ink);
    canvas.drawCircle(8,6.7f,1.45f,ink);
    canvas.drawCircle(16,6.7f,1.45f,ink);
    canvas.drawCircle(12,10.7f,1.15f,ink);
    canvas.drawLine(5,14.5f,19,14.5f,ink);
    canvas.drawCircle(12,18.2f,1.65f,ink);
    break;
   case MainActivity.LIBRARY:
    canvas.drawRoundRect(3,6,17,21,2,2,ink);
    path.moveTo(7,3);
    path.lineTo(18.5f,3);
    path.quadTo(21,3,21,5.5f);
    path.lineTo(21,17);
    canvas.drawPath(path,ink);
    canvas.drawLine(9,11,14,11,ink);
    canvas.drawLine(9,16,14,16,ink);
    canvas.drawPoint(6,11,ink);
    canvas.drawPoint(6,16,ink);
    break;
   case CONSOLE:
    canvas.drawRoundRect(3.2f,3.2f,20.8f,20.8f,2.3f,2.3f,ink);
    for(int index=0;index<3;index++){
     float x=7.7f+index*4.3f;
     float y=index==1?14.5f:9.6f+index;
     canvas.drawLine(x,6.8f,x,17.2f,ink);
     canvas.drawLine(x-1.35f,y,x+1.35f,y,ink);
    }
    break;
   case SETTINGS:
    for(int point=0;point<24;point++){
     double angle=Math.PI*point/12;
     float radius=point%4==0||point%4==3?7.6f:10f;
     float x=12+(float)Math.cos(angle)*radius;
     float y=12+(float)Math.sin(angle)*radius;
     if(point==0)path.moveTo(x,y);
     else path.lineTo(x,y);
    }
    path.close();
    canvas.drawPath(path,ink);
    canvas.drawCircle(12,12,3.3f,ink);
    break;
   default:
    break;
  }
  canvas.restore();
 }
}
