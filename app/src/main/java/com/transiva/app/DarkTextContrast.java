package com.transiva.app;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.TextView;

final class DarkTextContrast {
    private static final int[][] STATES = {
            {-android.R.attr.state_enabled},
            {android.R.attr.state_enabled,android.R.attr.state_pressed},
            {android.R.attr.state_enabled,android.R.attr.state_focused},
            {android.R.attr.state_enabled,android.R.attr.state_checked},
            {android.R.attr.state_enabled,android.R.attr.state_selected},
            {}
    };

    static void apply(View view) {
        if (!(view instanceof TextView)) return;
        TextView text=(TextView)view;
        int background=background(view);
        ColorStateList current=text.getTextColors(), readable=adapt(current,background);
        if (readable!=current) text.setTextColor(readable);
        current=text.getHintTextColors();
        readable=adapt(current,background);
        if (readable!=current) text.setHintTextColor(readable);
        current=text.getLinkTextColors();
        readable=adapt(current,background);
        if (readable!=current) text.setLinkTextColor(readable);

        if (!(text.getText() instanceof Spanned)) return;
        Spanned original=(Spanned)text.getText();
        Spannable styled=original instanceof Spannable?(Spannable)original:null;
        for (ForegroundColorSpan span:original.getSpans(0,original.length(),ForegroundColorSpan.class)) {
            if (span.getClass()!=ForegroundColorSpan.class) continue;
            int contrast=readable(span.getForegroundColor(),background);
            if (contrast==span.getForegroundColor()) continue;
            if (styled==null) styled=new SpannableString(original);
            int start=styled.getSpanStart(span), end=styled.getSpanEnd(span), flags=styled.getSpanFlags(span);
            if (start<0 || start>=end) continue;
            styled.removeSpan(span);
            styled.setSpan(new ForegroundColorSpan(contrast),start,end,flags);
        }
        if (styled!=null && styled!=original) text.setText(styled);
    }

    private static ColorStateList adapt(ColorStateList input,int background) {
        if (input==null) return null;
        int[] colors=new int[STATES.length];
        boolean changed=false;
        for (int i=0;i<colors.length;i++) {
            int original=input.getColorForState(STATES[i],input.getDefaultColor());
            colors[i]=readable(original,background);
            changed|=original!=colors[i];
        }
        return changed?new ColorStateList(STATES,colors):input;
    }

    private static int background(View view) {
        for (View parent=view;parent!=null;parent=parent.getParent() instanceof View?(View)parent.getParent():null) {
            int solid=drawableColor(parent.getBackground());
            if (Color.alpha(solid)>=225) return solid;
        }
        return 0xff122235;
    }

    private static int drawableColor(Drawable drawable) {
        if (drawable instanceof ColorDrawable) return ((ColorDrawable)drawable).getColor();
        if (drawable instanceof GradientDrawable) {
            GradientDrawable gradient=(GradientDrawable)drawable;
            ColorStateList solid=gradient.getColor();
            if (solid!=null) return solid.getColorForState(gradient.getState(),solid.getDefaultColor());
            int[] colors=gradient.getColors();
            return colors==null||colors.length==0?Color.TRANSPARENT:colors[colors.length/2];
        }
        if (drawable instanceof InsetDrawable) return drawableColor(((InsetDrawable)drawable).getDrawable());
        if (drawable instanceof LayerDrawable) {
            LayerDrawable layers=(LayerDrawable)drawable;
            for (int i=layers.getNumberOfLayers()-1;i>=0;i--) {
                if (layers.getId(i)==android.R.id.mask) continue;
                int solid=drawableColor(layers.getDrawable(i));
                if (Color.alpha(solid)>=225) return solid;
            }
        }
        if (drawable!=null && drawable.getCurrent()!=drawable) return drawableColor(drawable.getCurrent());
        return Color.TRANSPARENT;
    }

    private static int readable(int original,int background) {
        int alpha=Color.alpha(original);
        if (alpha==0 || ratio(blend(background,original,alpha),background)>=4.5) return original;
        int target=ratio(Color.WHITE,background)>=ratio(Color.BLACK,background)?Color.WHITE:Color.BLACK;
        for (int step=32;step<255;step+=32) {
            int candidate=blend(original,target,step);
            if (ratio(candidate,background)>=4.5) return candidate;
        }
        return target;
    }

    private static int blend(int start,int end,int weight) {
        int base=255-weight;
        return Color.rgb((Color.red(start)*base+Color.red(end)*weight)/255,
                (Color.green(start)*base+Color.green(end)*weight)/255,
                (Color.blue(start)*base+Color.blue(end)*weight)/255);
    }

    private static double ratio(int a,int b) {
        double l=luminance(a),r=luminance(b);
        return (Math.max(l,r)+.05)/(Math.min(l,r)+.05);
    }

    private static double luminance(int color) {
        return .2126*linear(Color.red(color))+.7152*linear(Color.green(color))+.0722*linear(Color.blue(color));
    }

    private static double linear(int color) {
        double s=color/255d;
        return s<=.04045?s/12.92:Math.pow((s+.055)/1.055,2.4);
    }
}
