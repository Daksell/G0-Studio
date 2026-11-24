package ru.g0studio;

import java.nio.*;
import java.util.*;
import java.io.*;

public final class Protocol {
 public static final int GLOBAL_EFFECT_OPTION=1;
 public static final int SYSTEM_INFO=0x0c, WORK_MODE=0x05, SERIAL_NUMBER=0x06, FACTORY_RESET=0x07;
 public static boolean isWorkModeRead(byte[] request){
  return request!=null&&request.length==8&&(request[2]&255)==SYSTEM_INFO&&request[3]==WORK_MODE;
 }
 public static byte[] globalEffects(boolean enabled){
  return frame(13,GLOBAL_EFFECT_OPTION,new byte[]{(byte)(enabled?1:0)});
 }
 public static byte[] factoryReset(byte[] serial){
  if(serial.length!=4)throw new IllegalArgumentException("Ожидался 4-байтовый серийный номер G0 Pro");
  return frame(SYSTEM_INFO,FACTORY_RESET,serial);
 }

 /** Only this auxiliary read is known to return FF on otherwise working G0 Pro units. */
 public static boolean unsupportedOptionalReply(byte[] request,byte[] reply){return request!=null&&request.length>=8&&(request[2]&255)==14&&(request[3]&255)==32&&reply.length==9&&(reply[2]&255)==142&&(reply[3]&255)==32&&(reply[6]&255)==255;}
 public static boolean retryableRead(byte[] p){if(p==null||p.length<8)return false;int c=p[2]&255,o=p[3]&255;return c==12&&(o==8||o==9)&&p.length==8||c==10&&o==6&&p.length==8||c==10&&o==2&&p.length==9&&(p[6]&255)>=1&&(p[6]&255)<=4;}
 public static boolean isPresetEdit(byte[] request){
  if(request.length<8)return false;
  int command=request[2]&255,option=request[3]&255;
  if(command>=1&&command<=3){
   return request.length==20&&option>=16&&option<=(command==3?19:23);
  }
  return request.length==21&&command>=4&&command<=7&&option==0;
 }
 public static int postReplyPauseMillis(byte[] request){
  int command=request[2]&255,option=request[3]&255;
  if(command==SYSTEM_INFO&&option==9)return 60;
  if(command==10&&option==1)return 5;
  if(command==10&&option==0)return 12;
  if(command>=1&&command<=3&&isPresetEdit(request))return 16;
  return 24;
 }
 public static boolean presetEditMatches(byte[] request,Preset preset){
  if(!isPresetEdit(request))return false;
  int command=request[2]&255;
  if(command<=3){
   int channel=command==2?0:command==1?1:2;
   float[] band=preset.band(channel,(request[3]&255)-16);
   return near(getf(request,6),band[1],.001f)&&near(getf(request,10),band[0],.01f)
       &&near(getf(request,14),band[2],.1f);
  }
  for(int effect=0;effect<CMD.length;effect++){
   if(CMD[effect]!=command)continue;
   if(((preset.mask>>effect)&1)!=(request[18]&255))return false;
   for(int parameter=0;parameter<3;parameter++){
    float tolerance=isDelayTime(effect,parameter)?.5f:.001f;
    if(!near(getf(request,6+4*parameter),preset.effect(effect,parameter),tolerance))return false;
   }
   return true;
  }
  return false;
 }
 private static boolean near(float expected,float actual,float tolerance){
  return Math.abs(expected-actual)<=tolerance;
 }
 public static final String[] FX={"Компрессор","Реверберация","Хорус","Задержка"};
 public static final int[] CMD={7,6,4,5}, BASE={53,37,21,5};
 public static final String[][] PARAM={{"Threshold","Ratio","Level"},{"Tone","Feedback","Mix"},{"Rate","Depth","Mix"},{"Feedback","Level","Time"}};
 public static final int DELAY_TIME_MIN_MS=50, DELAY_TIME_MAX_MS=500;
 public static boolean isDelayTime(int effect,int parameter){return effect==3&&parameter==2;}
 public static boolean validEffectParameter(int effect,int parameter,float value){
  if(isDelayTime(effect,parameter))return value>=DELAY_TIME_MIN_MS&&value<=DELAY_TIME_MAX_MS;
  return value>=0&&value<=1;
 }
 public static byte[] frame(int c,int o,byte[] d){byte[] b=new byte[d.length+8];b[0]=85;b[1]=(byte)170;b[2]=(byte)c;b[3]=(byte)o;put16(b,4,d.length);System.arraycopy(d,0,b,6,d.length);int s=0;for(int i=2;i<b.length-2;i++)s+=b[i]&255;put16(b,b.length-2,s);return b;}
 public static int u16(byte[] b,int p){return (b[p]&255)|((b[p+1]&255)<<8);}
 static void put16(byte[] b,int p,int n){b[p]=(byte)n;b[p+1]=(byte)(n>>8);}
 public static float getf(byte[] b,int p){return ByteBuffer.wrap(b,p,4).order(ByteOrder.LITTLE_ENDIAN).getFloat();}
 public static void putf(byte[] b,int p,float f){if(!Float.isFinite(f))throw new IllegalArgumentException("Недопустимое число");ByteBuffer.wrap(b,p,4).order(ByteOrder.LITTLE_ENDIAN).putFloat(f);}
 public static void puti(byte[] b,int p,int n){ByteBuffer.wrap(b,p,4).order(ByteOrder.LITTLE_ENDIAN).putInt(n);}
 public static boolean valid(byte[] p){if(p.length<8||p[0]!=85||(p[1]&255)!=170||u16(p,4)+8!=p.length)return false;int sum=0;for(int i=2;i<p.length-2;i++)sum+=p[i]&255;return (sum&65535)==u16(p,p.length-2);}
 public static byte[] floats(float... fs){byte[] p=new byte[fs.length*4];for(int i=0;i<fs.length;i++)putf(p,i*4,fs[i]);return p;}
 public static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.US,"%02x",v&255));return s.toString();}
 public static byte[] unhex(String s){if(s.length()%2!=0)throw new IllegalArgumentException("Неверный HEX");byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;}
 public interface Receiver{void packet(byte[] p);}
 public static final class Decoder {
  byte[] pending=new byte[0];
  public void feed(byte[] b,Receiver r){byte[] n=Arrays.copyOf(pending,pending.length+b.length);System.arraycopy(b,0,n,pending.length,b.length);int p=0;while(n.length-p>=2){if(n[p]!=85||(n[p+1]&255)!=170){p++;continue;}if(n.length-p<6)break;int l=u16(n,p+4)+8;if(l>4104){p++;continue;}if(n.length-p<l)break;byte[] f=Arrays.copyOfRange(n,p,p+l);if(valid(f)){r.packet(f);p+=l;}else p++;}pending=Arrays.copyOfRange(n,p,n.length);if(pending.length>8192)pending=new byte[0];}
 }
 public static final class Preset {
  public byte[] data;public int mask;public byte[] micReverb;
  public Preset(byte[] d){if(d.length!=325)throw new IllegalArgumentException("Ожидалось 325 байт G0 Pro");data=d.clone();for(int i=0;i<4;i++)if(data[BASE[i]]==1)mask|=1<<i;validate();}
  public Preset copy(){Preset p=new Preset(data);p.mask=mask;p.micReverb=micReverb==null?null:micReverb.clone();return p;}
  public int slot(){return data[0]&255;}
  public float[] band(int ch,int i){int start=ch==0?85:ch==1?181:277;int count=ch==2?4:8;if(i<0||i>=count)throw new IllegalArgumentException("Полоса");int o=start+i*12;return new float[]{getf(data,o),getf(data,o+4),getf(data,o+8)};}
  public void band(int ch,int i,float gain,float q,float hz){if(!Float.isFinite(gain)||!Float.isFinite(q)||!Float.isFinite(hz)||q<=0||hz<20||hz>22000||gain< -12||gain>12)throw new IllegalArgumentException("Нужны 20–22000 Гц, −12…+12 дБ и Q > 0");int o=(ch==0?85:ch==1?181:277)+12*i;putf(data,o,gain);putf(data,o+4,q);putf(data,o+8,hz);}
  public byte[] bandFrame(int ch,int i){float[] b=band(ch,i);return frame(ch==0?2:ch==1?1:3,16+i,floats(b[1],b[0],b[2]));}
  public float effect(int fx,int param){return getf(data,BASE[fx]+4+4*param);}
  public void effect(int fx,int param,float f){putf(data,BASE[fx]+4+4*param,f);}
  public byte[] effectFrame(int fx){byte[] p=new byte[13];System.arraycopy(data,BASE[fx]+4,p,0,12);p[12]=(byte)((mask>>fx)&1);return frame(CMD[fx],0,p);}
  public int[] order(){return new int[]{data[1]&15,(data[1]>>4)&15,data[2]&15,(data[2]>>4)&15};}
  public void order(int[] v){int[] s=v.clone();Arrays.sort(s);if(!Arrays.equals(s,new int[]{1,2,3,4}))throw new IllegalArgumentException("Неверный порядок");data[1]=(byte)((v[1]<<4)|v[0]);data[2]=(byte)((v[3]<<4)|v[2]);data[3]=5;}
  public byte[] orderFrame(){return frame(13,0,new byte[]{data[1],data[2],data[3],0});}
  public void syncMask(){for(int i=0;i<4;i++)puti(data,BASE[i],(mask>>i)&1);}
  void validate(){for(int c=0;c<3;c++)for(int i=0;i<(c==2?4:8);i++){float[] v=band(c,i);if(!Float.isFinite(v[0])||!Float.isFinite(v[1])||!Float.isFinite(v[2])||v[1]<=0||v[2]<0||v[2]>100000)throw new IllegalArgumentException("Неверные данные EQ");}}
 }
}
