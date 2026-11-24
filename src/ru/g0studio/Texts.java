package ru.g0studio;

import android.content.Context;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

final class Texts {
 private static final Map<String,String[]> translations=new HashMap<>();
 private static boolean loaded;
 private static int language;

 private Texts(){}

 static void init(Context context,String code){
  if(!loaded){
   try(BufferedReader reader=new BufferedReader(new InputStreamReader(
       context.getAssets().open("translations.tsv"),StandardCharsets.UTF_8))){
    for(String line;(line=reader.readLine())!=null;){
     String[] entry=line.replace("\ufeff","").replace("\\n","\n").split("\t",-1);
     if(entry.length==3)translations.put(entry[0],entry);
    }
    loaded=true;
   }catch(IOException e){
    android.util.Log.e("G0Studio","Could not load translations",e);
   }
  }
  language="en".equals(code)?1:"fr".equals(code)?2:0;
 }

 static String tr(String text){
  if(language==0||text==null)return text;
  String[] entry=translations.get(text);
  return entry==null?text:entry[language];
 }
}
