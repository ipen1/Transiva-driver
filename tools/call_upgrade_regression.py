#!/usr/bin/env python3
"""Host checks for identity, preview bounds and the JNI mapping verifier."""
from pathlib import Path
import subprocess
import tempfile
ROOT = Path(__file__).resolve().parents[1]
APP = ROOT/'app/src/main/java/com/transiva/app'
with tempfile.TemporaryDirectory() as directory:
    d=Path(directory)
    stub=d/'org/json/JSONObject.java';stub.parent.mkdir(parents=True)
    stub.write_text('package org.json; public class JSONObject {public String optString(String k){return "";}}')
    probe=d/'CallUpgradeProbe.java'
    probe.write_text('''package com.transiva.app;
public class CallUpgradeProbe {
 static int checks;
 static void check(boolean ok){checks++;if(!ok)throw new AssertionError("check "+checks);}
 public static void main(String[] args){
  check(CallPeerIdentity.first(null,"null"," Citra ").equals("Citra"));
  check(CallPeerIdentity.initials("Citra Novitasari").equals("CN"));
  check(CallPeerIdentity.initials("😀 Ayu").equals("😀A"));
  check(CallPeerIdentity.photoUrl(null).isEmpty());
  check(CallPeerIdentity.photoUrl("uploads/c.jpg").equals("https://transiva.my.id/server/uploads/c.jpg"));
  check(CallPeerIdentity.photoUrl("/server/uploads/c.jpg").equals("https://transiva.my.id/server/uploads/c.jpg"));
  check(CallPeerIdentity.photoUrl("http://transiva.my.id/server/c.jpg").equals("https://transiva.my.id/server/c.jpg"));
  check(CallPeerIdentity.photoUrl("http://other.test/c.jpg").isEmpty());
  for(int w:new int[]{1,16,60,92,180,320,1080})for(int h:new int[]{1,12,80,128,320,1920}){
   CallPreviewBounds b=CallPreviewBounds.fit(w,h,92,128,12);
   check(b.width>0&&b.height>0&&b.margin>=0&&b.width+2*b.margin<=w&&b.height+2*b.margin<=h);
  }
  System.out.println("PASS: "+checks+" identity/preview checks; JSON field parsing excluded");
 }
}''')
    subprocess.run(['javac','-d',str(d),str(stub),str(probe),*[str(APP/(n+'.java')) for n in ['CallPeerIdentity','CallPreviewBounds','DriverApiConfig']]],check=True)
    subprocess.run(['java','-cp',str(d),'com.transiva.app.CallUpgradeProbe'],check=True)
    required={'org.jni_zero.JniInit':['init','crashIfMultiplexingMisaligned'],'org.jni_zero.JniUtil':['mapToArray','arrayToMap'],'org.webrtc.PeerConnectionFactory':[],'org.webrtc.audio.WebRtcAudioRecord':[],'org.webrtc.audio.WebRtcAudioTrack':[]}
    mapping=''.join(n+' -> '+n+':\n'+''.join('    void '+m+'() -> '+m+'\n' for m in methods) for n,methods in required.items())
    test=d/'mapping.txt';test.write_text(mapping)
    gate=['python3',str(ROOT/'tools/verify_webrtc_jni.py'),str(test)]
    subprocess.run(gate,check=True)
    for bad in [mapping.replace('org.jni_zero.JniInit -> org.jni_zero.JniInit:', 'org.jni_zero.JniInit -> a.b:'),mapping.replace('    void init() -> init\n','')]:
        test.write_text(bad)
        result=subprocess.run(gate,capture_output=True,text=True)
        assert result.returncode!=0
    print('PASS: synthetic mapping gate detects renamed classes and missing methods')
