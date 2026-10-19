package com.transiva.app;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
public class DriverWindowGeometryTest {
    private final int[] base = {8, 12, 8, 12};
    @Test public void keyboardAndNavigationOverlapInsteadOfAdding() {
        assertArrayEquals(new int[]{8,36,8,312}, DriverWindowGeometry.padding(base,0,24,0,48,300,false,360,840));
    }
    @Test public void keyboardClosingRestoresNavigationPadding() {
        assertArrayEquals(new int[]{8,36,8,60}, DriverWindowGeometry.padding(base,0,24,0,48,0,false,360,840));
    }
    @Test public void landscapeCutoutAndSideNavigationRemainSafe() {
        assertArrayEquals(new int[]{48,12,56,12}, DriverWindowGeometry.padding(base,40,0,48,0,0,false,800,840));
    }
    @Test public void pipRemovesAllPaddingEvenWithStaleInsets() {
        assertArrayEquals(new int[]{0,0,0,0}, DriverWindowGeometry.padding(base,40,24,48,48,300,true,240,840));
    }
    @Test public void tabletContentIsCenteredInsideSafeWidth() {
        assertArrayEquals(new int[]{180,36,180,60}, DriverWindowGeometry.padding(base,0,24,0,48,0,false,1200,840));
    }
    @Test public void mapAndCameraUseEntireSafeWidth() {
        assertArrayEquals(new int[]{8,36,8,60}, DriverWindowGeometry.padding(base,0,24,0,48,0,false,1200,0));
    }
}
