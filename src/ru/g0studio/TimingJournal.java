package ru.g0studio;

import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayDeque;

final class TimingJournal {
 static final String COLUMNS="profile\ttime_ns\tevent\tid\tcommand\toption\tbytes\tvalue\tattempt\tgap_ms\tnote";
 private final ArrayDeque<String> rows=new ArrayDeque<>();

 void event(BleLink.TimingEvent event){
  append(event.tag+"\t"+event.timeNs+"\t"+event.phase+"\t"+event.commandId+"\t"
      +event.command+"\t"+event.option+"\t"+event.bytes+"\t"+event.value+"\t"
      +event.attempt+"\t"+event.gapMs+"\t");
 }

 void note(String profile,String phase,String message){
  append(profile+"\t"+SystemClock.elapsedRealtimeNanos()+"\t"+phase
      +"\t0\t-1\t-1\t0\t-1\t0\t-1\t"+message.replace('\n',' ').replace('\t',' '));
 }

 private void append(String row){
  if(rows.size()==24000)rows.removeFirst();
  rows.addLast(row);
  Log.i("G0Timing",row);
 }

 String report(){
  return COLUMNS+"\n"+String.join("\n",rows)+"\n";
 }
}
