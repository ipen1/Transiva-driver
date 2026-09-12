package com.transiva.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class DriverSecurityRegressionTest {
 @Test public void terminalOrderStatesAreRecognized(){assertTrue(DriverMessageStatus.isEnded("finished"));assertTrue(DriverMessageStatus.isEnded("cancelled"));}
 @Test public void normalizationIsStable(){assertEquals("driver_accepted",DriverMessageStatus.normalize(" driver-accepted "));}
 @Test public void activeOrderAllowsChat(){assertTrue(DriverMessageStatus.canSend("driver_accepted"));assertFalse(DriverMessageStatus.canSend("pending"));}
}
