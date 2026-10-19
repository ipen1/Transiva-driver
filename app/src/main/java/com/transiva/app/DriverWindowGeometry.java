package com.transiva.app;

/** Pure window geometry, shared by the runtime and regression tests. */
public final class DriverWindowGeometry {
    private DriverWindowGeometry() { }
    public static int[] padding(int[] base, int left, int top, int right, int bottom,
                                int ime, boolean pip, int width, int maxWidth) {
        if (pip) return new int[]{0, 0, 0, 0};
        int gutter = maxWidth > 0 ? Math.max(0, (width - left - right - base[0] - base[2] - maxWidth) / 2) : 0;
        return new int[]{base[0] + left + gutter, base[1] + top,
                base[2] + right + gutter, base[3] + Math.max(bottom, ime)};
    }
}
