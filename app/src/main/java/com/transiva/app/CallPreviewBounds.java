package com.transiva.app;

/** Pixel bounds for the inset camera; margins also fit in very small/PiP-sized stages. */
final class CallPreviewBounds {
    final int width, height, margin;
    private CallPreviewBounds(int width, int height, int margin) {
        this.width=width;this.height=height;this.margin=margin;
    }
    static CallPreviewBounds fit(int stageWidth,int stageHeight,int requestedWidth,int requestedHeight,int gutter) {
        int w=Math.max(1,stageWidth),h=Math.max(1,stageHeight);
        int margin=Math.max(0,Math.min(gutter,Math.min((w-1)/2,(h-1)/2)));
        int height=Math.max(1,Math.min(requestedHeight,h-2*margin));
        int width=Math.max(1,Math.min(requestedWidth,Math.min(w-2*margin,Math.round(height*.72f))));
        return new CallPreviewBounds(width,height,margin);
    }
}
