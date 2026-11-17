package se.william.hem;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * A big clock whose digits are made of frosted glass: they are filled with a brightened,
 * blurred copy of the wallpaper behind them and fade out towards the bottom.
 * Digits that change roll in from below while the old ones float away.
 */
public class ClockView extends View {

    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    final Rect digitBounds = new Rect();
    final OvershootInterpolator overshoot = new OvershootInterpolator(1.1f);
    final DecelerateInterpolator decel = new DecelerateInterpolator(1.8f);
    final int[] loc = new int[2];
    final Matrix m = new Matrix();

    String text = "";
    String old = "";
    float progress = 1f;
    ValueAnimator tickAnim;

    Bitmap glassNew, glassOld;
    float glassMix = 1f;
    ValueAnimator glassAnim;

    float panX = 0f;
    float panScale = 1f;
    int alphaPct = 60;
    int glow = 0x66FFFFFF;

    public ClockView(Context c) {
        super(c);
        fill.setColor(Color.WHITE);
        edge.setColor(Color.WHITE);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(c.getResources().getDisplayMetrics().density * 1.2f);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void setTypeface(Typeface t) {
        fill.setTypeface(t);
        edge.setTypeface(t);
        requestLayout();
    }

    public void setTextSizePx(float px) {
        fill.setTextSize(px);
        edge.setTextSize(px);
        fill.getTextBounds("0123456789", 0, 10, digitBounds);
        requestLayout();
    }

    public void setAlphaPercent(int a) {
        alphaPct = a;
        invalidate();
    }

    public void setGlow(int color) {
        glow = color;
        invalidate();
    }

    public void setPan(float x, float scale) {
        panX = x;
        panScale = scale;
        invalidate();
    }

    public void setTime(String t, boolean animate) {
        if (t.equals(text)) return;
        old = text;
        text = t;
        if (tickAnim != null) tickAnim.cancel();
        if (!animate || old.isEmpty()) {
            progress = 1f;
            invalidate();
            return;
        }
        progress = 0f;
        tickAnim = ValueAnimator.ofFloat(0f, 1f);
        tickAnim.setDuration(1300);
        tickAnim.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        tickAnim.start();
    }

    /** Make the frosted-glass texture from a wallpaper (cheap blur: shrink, then grow). */
    public static Bitmap makeGlass(Bitmap src) {
        if (src == null) return null;
        int w = 480, h = 270;
        Bitmap small = Bitmap.createScaledBitmap(src, 96, 54, true);
        Bitmap blurred = Bitmap.createScaledBitmap(small, w, h, true);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.7f);
        ColorMatrix bright = new ColorMatrix(new float[]{
                1.5f, 0, 0, 0, 70,
                0, 1.5f, 0, 0, 70,
                0, 0, 1.5f, 0, 75,
                0, 0, 0, 1, 0});
        cm.postConcat(bright);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        c.drawBitmap(blurred, 0, 0, p);
        return out;
    }

    public void setGlass(Bitmap g, boolean animate) {
        if (glassAnim != null) glassAnim.cancel();
        glassOld = animate ? glassNew : null;
        glassNew = g;
        if (!animate || glassOld == null) {
            glassMix = 1f;
            invalidate();
            return;
        }
        glassMix = 0f;
        glassAnim = ValueAnimator.ofFloat(0f, 1f);
        glassAnim.setDuration(2600);
        glassAnim.addUpdateListener(a -> {
            glassMix = (float) a.getAnimatedValue();
            invalidate();
        });
        glassAnim.start();
    }

    float digitHeight() {
        return digitBounds.height() > 0 ? digitBounds.height() : fill.getTextSize() * 0.75f;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = Math.round(digitHeight() * 1.35f);
        setMeasuredDimension(w, h);
    }

    Shader glassShader(Bitmap g, float top, float bottom) {
        View rootView = getRootView();
        float rw = rootView.getWidth() > 0 ? rootView.getWidth() : getWidth();
        float rh = rootView.getHeight() > 0 ? rootView.getHeight() : getHeight();
        getLocationInWindow(loc);
        BitmapShader bs = new BitmapShader(g, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        m.reset();
        m.setScale(rw / g.getWidth(), rh / g.getHeight());
        m.postScale(panScale, panScale, rw / 2f, rh / 2f);
        m.postTranslate(panX, 0);
        m.postTranslate(-loc[0] - getTranslationX(), -loc[1] - getTranslationY());
        bs.setLocalMatrix(m);
        LinearGradient fade = new LinearGradient(0, top, 0, bottom,
                new int[]{0xFFFFFFFF, 0xF0FFFFFF, 0x26FFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        return new ComposeShader(bs, fade, PorterDuff.Mode.DST_IN);
    }

    float[] positions(String s, float total) {
        float[] xs = new float[s.length()];
        float x = (getWidth() - total) / 2f;
        for (int i = 0; i < s.length(); i++) {
            xs[i] = x;
            x += fill.measureText(s, i, i + 1);
        }
        return xs;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (text.isEmpty()) return;
        float dh = digitHeight();
        float baseline = getHeight() / 2f + dh / 2f;
        float top = baseline - dh;
        float shift = dh * 0.55f;
        int base = Math.round(255 * alphaPct / 100f);

        float totalNew = fill.measureText(text);
        float[] xn = positions(text, totalNew);
        float[] xo = old.isEmpty() ? xn : positions(old, fill.measureText(old));
        boolean sameLen = old.length() == text.length();

        // which characters changed (in order, for a staggered roll)
        int changedCount = 0;
        int[] order = new int[text.length()];
        for (int i = 0; i < text.length(); i++) {
            boolean ch = !sameLen || old.charAt(i) != text.charAt(i);
            order[i] = ch ? changedCount++ : -1;
        }

        Bitmap[] layers = {glassOld, glassNew};
        float[] layerAlpha = {1f - glassMix, glassMix};
        fill.setShadowLayer(dh * 0.12f, 0, 0, glow);
        for (int l = 0; l < 2; l++) {
            Bitmap g = layers[l];
            if (layerAlpha[l] <= 0.01f) continue;
            if (g != null) fill.setShader(glassShader(g, top, baseline + dh * 0.1f));
            else {
                fill.setShader(new LinearGradient(0, top, 0, baseline,
                        new int[]{0xFFFFFFFF, 0x55FFFFFF}, null, Shader.TileMode.CLAMP));
            }
            float la = layerAlpha[l];
            for (int i = 0; i < text.length(); i++) {
                String c = text.substring(i, i + 1);
                if (order[i] < 0 || progress >= 1f) {
                    fill.setAlpha(Math.round(base * la));
                    canvas.drawText(c, xn[i], baseline, fill);
                    continue;
                }
                float delay = changedCount > 1 ? 0.18f * order[i] : 0f;
                float span = 1f - 0.18f * Math.max(0, changedCount - 1);
                float p = Math.max(0f, Math.min(1f, (progress - delay) / span));
                // old char floats up and fades
                if (sameLen && i < old.length()) {
                    float po = decel.getInterpolation(p);
                    fill.setAlpha(Math.round(base * la * (1f - po)));
                    canvas.save();
                    canvas.translate(0, -shift * po);
                    canvas.scale(1f - 0.1f * po, 1f - 0.1f * po, xo[i], baseline - dh / 2f);
                    canvas.drawText(old.substring(i, i + 1), xo[i], baseline, fill);
                    canvas.restore();
                }
                // new char rolls in from below with a little bounce
                float pn = overshoot.getInterpolation(p);
                fill.setAlpha(Math.round(base * la * Math.min(1f, p * 1.6f)));
                canvas.save();
                canvas.translate(0, shift * (1f - pn));
                canvas.drawText(c, xn[i], baseline, fill);
                canvas.restore();
            }
        }
        fill.setShader(null);

        // thin glassy edge highlight
        edge.setShader(new LinearGradient(0, top, 0, baseline,
                new int[]{0x66FFFFFF, 0x10FFFFFF}, null, Shader.TileMode.CLAMP));
        for (int i = 0; i < text.length(); i++) {
            float y = 0;
            int a = 255;
            if (order[i] >= 0 && progress < 1f) {
                float p = Math.max(0f, Math.min(1f, progress * 1.2f - 0.2f));
                y = shift * (1f - overshoot.getInterpolation(p));
                a = Math.round(255 * p);
            }
            edge.setAlpha(Math.round(a * Math.min(1f, alphaPct / 60f)));
            canvas.drawText(text, i, i + 1, xn[i], baseline + y, edge);
        }
    }
}
