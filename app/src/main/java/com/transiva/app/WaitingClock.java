package com.transiva.app;

import android.graphics.Color;
import java.util.Locale;

final class WaitingClock {
    private WaitingClock() {}

    static String text(long elapsed, long free) {
        long seconds = Math.abs(free - elapsed);
        return String.format(Locale.US, "%s  %02d:%02d",
                elapsed < free ? "Gratis" : "Berbayar", seconds / 60, seconds % 60);
    }

    static int color(long elapsed, long free, boolean dark) {
        float progress = free == 0 ? 1f : Math.min(1f, (float) elapsed / free);
        int green = dark ? 0xff88edb0 : 0xff10733e;
        int amber = dark ? 0xffffd385 : 0xff895500;
        int red = dark ? 0xffffa3ab : 0xffa52232;
        return progress < .5f ? blend(green, amber, progress * 2f)
                : blend(amber, red, (progress - .5f) * 2f);
    }

    private static int blend(int from, int to, float fraction) {
        return Color.rgb(
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * fraction),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * fraction),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * fraction));
    }
}
