#!/usr/bin/env python3
"""Actual production listener + geometry, with isolated legacy/native dispatch collaborators.
Models the pre-30 visible-root IME heuristic. Does not replace connected Android tests.
Optional argument: an alternate DriverResponsiveUi.java (baseline reproduction).
"""
from pathlib import Path
import os,sys,subprocess,tempfile
root=Path(__file__).resolve().parents[2];app=root/'app/src/main/java/com/transiva/app'
source=Path(sys.argv[1]) if len(sys.argv)>1 else app/'DriverResponsiveUi.java'
s=source.read_text();start=s.index('    static androidx.core.view.OnApplyWindowInsetsListener createInsetsListener(');opening=s.index('{',start);depth=1;end=opening+1
while depth:
 if s[end]=='{':depth+=1
 elif s[end]=='}':depth-=1
 end+=1
listener=s[start:end]
files={
'android/app/Activity.java':'package android.app;public class Activity{public boolean pip;public boolean isInPictureInPictureMode(){return pip;}}',
'android/os/Build.java':'package android.os;public class Build{public static class VERSION{public static int SDK_INT=26;}}',
'android/view/View.java':'package android.view;public class View{int l,t,r,b;public int getWidth(){return 360;}public int getPaddingLeft(){return l;}public int getPaddingTop(){return t;}public int getPaddingRight(){return r;}public int getPaddingBottom(){return b;}public void setPadding(int a,int x,int c,int d){l=a;t=x;r=c;b=d;}}',
'androidx/core/graphics/Insets.java':'package androidx.core.graphics;public class Insets{public final int left,top,right,bottom;public static final Insets NONE=new Insets(0,0,0,0);Insets(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}public static Insets of(int l,int t,int r,int b){return new Insets(l,t,r,b);}}',
'androidx/core/view/OnApplyWindowInsetsListener.java':'package androidx.core.view;public interface OnApplyWindowInsetsListener{WindowInsetsCompat onApplyWindowInsets(android.view.View v,WindowInsetsCompat i);}',
'androidx/core/view/WindowInsetsCompat.java':'''package androidx.core.view;import androidx.core.graphics.Insets;
public class WindowInsetsCompat{
 public static final WindowInsetsCompat CONSUMED=new WindowInsetsCompat(Insets.NONE,0,0,0,true,true);
 Insets bars;int ime,stable,visible;boolean consumed,typed;
 public WindowInsetsCompat(Insets b,int i,int s,int v,boolean c,boolean t){bars=b;ime=i;stable=s;visible=v;consumed=c;typed=t;}
 public boolean isConsumed(){return consumed;}
 public Insets getStableInsets(){return Insets.of(0,0,0,stable);}public Insets getSystemWindowInsets(){return bars;}
 public Insets getInsets(int mask){int bottom=(mask&1)!=0?bars.bottom:0;if((mask&2)!=0)bottom=Math.max(bottom,typed?ime:(visible>stable?visible:0));return Insets.of((mask&1)!=0?bars.left:0,(mask&1)!=0?bars.top:0,(mask&1)!=0?bars.right:0,bottom);}
 public WindowInsetsCompat nativeRoundTrip(int api){return new WindowInsetsCompat(bars,ime,stable,867,consumed,api>=30);}
 public static class Type{public static int systemBars(){return 1;}public static int ime(){return 2;}public static int displayCutout(){return 4;}}
 public static class Builder{WindowInsetsCompat v;public Builder(WindowInsetsCompat i){v=new WindowInsetsCompat(i.bars,i.ime,i.stable,i.visible,i.consumed,true);}public Builder setInsets(int mask,Insets x){if((mask&1)!=0)v.bars=x;if((mask&2)!=0)v.ime=x.bottom;return this;}public Builder setDisplayCutout(Object x){return this;}public WindowInsetsCompat build(){return v;}}
}''',
'com/transiva/app/DriverSystemBars.java':'package com.transiva.app;public class DriverSystemBars{static void updateInsets(android.app.Activity a,androidx.core.graphics.Insets b,boolean p){}}',
'com/transiva/app/DriverResponsiveUi.java':'package com.transiva.app;import android.app.Activity;import android.os.Build;import androidx.core.graphics.Insets;import androidx.core.view.WindowInsetsCompat;public class DriverResponsiveUi{static boolean fullWidth(Activity a){return true;}static int dp(Activity a,int v){return v;}'+listener+'}',
'com/transiva/app/InsetsProbe.java':'''package com.transiva.app;import android.app.Activity;import android.os.Build;import android.view.View;import androidx.core.graphics.Insets;import androidx.core.view.*;
public class InsetsProbe{static int checks;static void check(boolean b,String label){if(!b)throw new AssertionError("API "+Build.VERSION.SDK_INT+": "+label);checks++;System.out.println("PASS API "+Build.VERSION.SDK_INT+" "+label);}
 static WindowInsetsCompat input(int ime){return new WindowInsetsCompat(Insets.of(0,24,0,48),ime,48,867,false,true);}
 public static void main(String[] args){for(int api:new int[]{24,26,29,30,34,36}){Build.VERSION.SDK_INT=api;Activity a=new Activity();View content=new View();OnApplyWindowInsetsListener l=DriverResponsiveUi.createInsetsListener(a,new int[]{8,12,8,12});WindowInsetsCompat result=l.onApplyWindowInsets(content,input(300));check(content.getPaddingTop()==36&&content.getPaddingBottom()==312,"bars and keyboard pad the parent once");check(result.isConsumed(),"parent consumes legacy flags as well as typed values");WindowInsetsCompat nativeResult=result.nativeRoundTrip(api);check(nativeResult.getSystemWindowInsets().bottom==0&&nativeResult.getStableInsets().bottom==0,"native system and stable insets are consumed");int childCalls=nativeResult.isConsumed()?0:1;check(childCalls==0,"native dispatch cannot leak root visible-frame IME into a child");if(api<30)check(nativeResult.getInsets(WindowInsetsCompat.Type.ime()).bottom==867,"legacy typed IME may be inferred from root after native wrapping");else check(nativeResult.getInsets(WindowInsetsCompat.Type.ime()).bottom==0,"native typed IME is zero on Android 11+");l.onApplyWindowInsets(content,input(300));check(content.getPaddingBottom()==312,"repeat delivery never accumulates padding");l.onApplyWindowInsets(content,input(0));check(content.getPaddingBottom()==60,"keyboard dismissal restores navigation-bar spacing");a.pip=true;result=l.onApplyWindowInsets(content,input(300));if(api>=26)check(content.getPaddingBottom()==0&&result.isConsumed(),"PiP removes padding and still consumes insets");}System.out.println("WINDOW INSETS: "+checks+" checks PASS");}}
'''}
with tempfile.TemporaryDirectory(prefix='transiva-insets-') as temp:
 d=Path(temp)
 for name,value in files.items():p=d/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(value)
 subprocess.run([os.environ.get('JAVAC_BIN','javac'),'-d',str(d/'classes'),*map(str,d.rglob('*.java')),str(app/'DriverWindowGeometry.java')],check=True)
 subprocess.run(['java','-cp',str(d/'classes'),'com.transiva.app.InsetsProbe'],check=True)
