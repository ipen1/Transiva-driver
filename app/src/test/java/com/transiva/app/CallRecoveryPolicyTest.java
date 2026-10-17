package com.transiva.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class CallRecoveryPolicyTest {
 @Test public void candidatesHaveOnlyTwoRetries(){assertEquals(500,CallRecoveryPolicy.candidateDelay(1));assertEquals(1500,CallRecoveryPolicy.candidateDelay(2));assertEquals(-1,CallRecoveryPolicy.candidateDelay(3));}
 @Test public void legacyAndCalleeNeverStartAnOffer(){CallRecoveryPolicy p=new CallRecoveryPolicy();p.begin(0);assertFalse(p.takeRestart(3000,true,false));assertFalse(p.takeRestart(3000,false,true));assertFalse(CallRecoveryPolicy.supports("v=0"));}
 @Test public void retriesAreSpacedAndCapped(){CallRecoveryPolicy p=new CallRecoveryPolicy();p.begin(0);assertFalse(p.takeRestart(2000,true,true));assertTrue(p.takeRestart(3000,true,true));assertFalse(p.takeRestart(7000,true,true));assertTrue(p.takeRestart(12000,true,true));assertFalse(p.takeRestart(22000,true,true));assertTrue(p.expired(25000));}
 @Test public void reconnectionResetsOutageButNotCallLimit(){CallRecoveryPolicy p=new CallRecoveryPolicy();for(int i=0;i<3;i++){p.begin(i*30000);assertTrue(p.takeRestart(i*30000+3000,true,true));assertTrue(p.takeRestart(i*30000+12000,true,true));p.connected();}p.begin(100000);assertFalse(p.takeRestart(103000,true,true));assertTrue(CallRecoveryPolicy.supports("v=0\r\n"+CallRecoveryPolicy.CAPABILITY+"\r\n"));}
 @Test public void capabilityPreservesSdpLineBoundaries(){String marked=CallRecoveryPolicy.advertise("v=0\r\n");assertFalse(marked.contains("\r\n\r\n"));assertEquals(marked,CallRecoveryPolicy.advertise(marked));assertFalse(CallRecoveryPolicy.supports(CallRecoveryPolicy.CAPABILITY+"0\r\n"));}
}
