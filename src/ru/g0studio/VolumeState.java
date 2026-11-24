package ru.g0studio;

/** Authoritative levels received from G0 Pro, independent of preset EQ data. */
public final class VolumeState {
 public int pickup=-1,microphone=-1,master=-1;
 public boolean pickupLive=false,microphoneLive=false;
 private boolean masterLive;
 public void clear(){pickup=microphone=master=-1;pickupLive=microphoneLive=masterLive=false;}
 public void confirmMaster(int value){master=value;masterLive=true;}
 public static byte[] set(int option,int percent){if((option!=2&&option!=3&&option!=4)||percent<0||percent>100)throw new IllegalArgumentException("Volume must be 0..100");return Protocol.frame(12,option,new byte[]{(byte)percent});}
 public static boolean isLevelReply(byte[] p){if(!Protocol.valid(p)||p.length!=9||(p[2]&255)!=140)return false;int option=p[3]&255;return (option==2||option==3||option==10)&&(p[6]&255)<=100;}
 public boolean accept(byte[] p){if(!Protocol.valid(p)||(p[2]&255)!=140)return false;int option=p[3]&255;if(option==8&&p.length==141){int a=p[7]&255,b=p[8]&255,c=p[9]&255;if(a>100||b>100||c>100)return false;if(!pickupLive)pickup=a;if(!microphoneLive)microphone=b;if(!masterLive)master=c;return true;}if(!isLevelReply(p))return false;int v=p[6]&255;if(option==2){pickup=v;pickupLive=true;}else if(option==3){microphone=v;microphoneLive=true;}else confirmMaster(v);return true;}
}
