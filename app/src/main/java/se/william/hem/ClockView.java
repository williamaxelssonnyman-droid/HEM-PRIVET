package se.william.hem;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
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
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * A big clock whose digits are made of frosted glass: they are filled with a brightened,
 * blurred copy of the wallpaper behind them and fade out towards the bottom.
 * Digits that change roll in from below while the old ones float away.
 *
 * Performance: every digit is rendered ONCE into a small bitmap ("sprite") together with its
 * glass fill and edge highlight, and the glow is a pre-blurred quarter-size alpha mask.
 * Animating therefore only moves a few bitmaps around, which the TV's GPU handles easily.
 * Nothing here redraws unless the time, the wallpaper or the settings change.
 */
public class ClockView extends View {

    static final int GLOW_SCALE = 4;

    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    final Paint spritePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    final Paint glowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    final Rect digitBounds = new Rect();
    final OvershootInterpolator overshoot = new OvershootInterpolator(1.1f);
    final DecelerateInterpolator decel = new DecelerateInterpolator(1.8f);
    final Matrix m = new Matrix();

    final Map<String, Bitmap> sprites = new HashMap<>();
    final Map<Character, Bitmap> glows = new HashMap<>();
    final Set<String> usedThisFrame = new HashSet<>();
    int locX = Integer.MIN_VALUE, locY = Integer.MIN_VALUE;

    String text = "";
    String old = "";
    float progress = 1f;
    ValueAnimator tickAnim;

    Bitmap glassNew, glassOld;
    float glassMix = 1f;
    ValueAnimator glassAnim;

    float panX = 0f;
    float panScale = 1.12f;
    int alphaPct = 60;
    int glow = 0x66FFFFFF;
    /** 0 = soft cross-fade in place (default), 1 = roll in from below. */
    int animStyle = 0;

    public ClockView(Context c) {
        super(c);
        fill.setColor(Color.WHITE);
        edge.setColor(Color.WHITE);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(c.getResources().getDisplayMetrics().density * 1.2f);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    void clearSprites(boolean alsoGlow) {
        sprites.clear();
        if (alsoGlow) glows.clear();
    }

    public void setTypeface(Typeface t) {
        fill.setTypeface(t);
        edge.setTypeface(t);
        clearSprites(true);
        requestLayout();
    }

    public void setTextSizePx(float px) {
        fill.setTextSize(px);
        edge.setTextSize(px);
        fill.getTextBounds("0123456789", 0, 10, digitBounds);
        clearSprites(true);
        requestLayout();
    }

    public void setAlphaPercent(int a) {
        if (a == alphaPct) return;
        alphaPct = a;
        clearSprites(false); // the edge strength depends on it
        invalidate();
    }

    public void setGlow(int color) {
        if (color == glow) return;
        glow = color;
        invalidate();
    }

    /** The glass is aligned once; it no longer follows the moving wallpaper every frame. */
    public void setPan(float x, float scale) {
        if (x == panX && scale == panScale) return;
        panX = x;
        panScale = scale;
        clearSprites(false);
        invalidate();
    }

    public void setAnimStyle(int s) {
        animStyle = s;
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
        tickAnim.setDuration(animStyle == 0 ? 750 : 1300);
        tickAnim.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        tickAnim.start();
    }

    /** Make the frosted-glass texture from a wallpaper (cheap blur: shrink, then grow). Thread-safe. */
    public static Bitmap makeGlass(Bitmap src) {
        if (src == null) return null;
        int w = 480, h = 270;
        Bitmap small = Bitmap.createScaledBitmap(src, 96, 54, true);
        Bitmap blurred = Bitmap.createScaledBitmap(small, w, h, true);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        ColorMatrix cm = new ColorMatrix();
        // More colour, less white: like the video, the digits take the wallpaper's hues.
        cm.setSaturation(1.9f);
        ColorMatrix bright = new ColorMatrix(new float[]{
                1.35f, 0, 0, 0, 45,
                0, 1.35f, 0, 0, 45,
                0, 0, 1.35f, 0, 55,
                0, 0, 0, 1, 0});
        cm.postConcat(bright);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        c.drawBitmap(blurred, 0, 0, p);
        small.recycle();
        blurred.recycle();
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

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        clearSprites(true);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        // Position in the window from the layout alone (ignores running animations), so the
        // glass lines up with the wallpaper the same way every time.
        int x = 0, y = 0;
        View v = this;
        while (v != null) {
            x += v.getLeft() - v.getScrollX();
            y += v.getTop() - v.getScrollY();
            ViewParent p = v.getParent();
            v = p instanceof View ? (View) p : null;
        }
        if (x != locX || y != locY) {
            locX = x;
            locY = y;
            clearSprites(false);
        }
    }

    float baseline() {
        return getHeight() / 2f + digitHeight() / 2f;
    }

    /** Glass fill in this view's coordinates. */
    Shader glassShader(Bitmap g) {
        float dh = digitHeight();
        float baseline = baseline();
        float top = baseline - dh;
        Shader fade = new LinearGradient(0, top, 0, baseline + dh * 0.1f,
                new int[]{0xFFFFFFFF, 0xF5FFFFFF, 0x80FFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        if (g == null) {
            return new LinearGradient(0, top, 0, baseline,
                    new int[]{0xFFFFFFFF, 0x55FFFFFF}, null, Shader.TileMode.CLAMP);
        }
        View rootView = getRootView();
        float rw = rootView.getWidth() > 0 ? rootView.getWidth() : getWidth();
        float rh = rootView.getHeight() > 0 ? rootView.getHeight() : getHeight();
        BitmapShader bs = new BitmapShader(g, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        m.reset();
        m.setScale(rw / g.getWidth(), rh / g.getHeight());
        m.postScale(panScale, panScale, rw / 2f, rh / 2f);
        m.postTranslate(panX, 0);
        m.postTranslate(-(locX == Integer.MIN_VALUE ? 0 : locX), -(locY == Integer.MIN_VALUE ? 0 : locY));
        bs.setLocalMatrix(m);
        return new ComposeShader(bs, fade, PorterDuff.Mode.DST_IN);
    }

    int spritePad() {
        return Math.round(digitHeight() * 0.06f) + 4;
    }

    /** One character, filled with glass and outlined, rendered once at its resting place. */
    Bitmap sprite(Bitmap g, char ch, float x) {
        String key = System.identityHashCode(g) + "|" + ch + "|" + Math.round(x);
        usedThisFrame.add(key);
        Bitmap b = sprites.get(key);
        if (b != null) return b;
        if (getWidth() <= 0 || getHeight() <= 0) return null;
        String s = String.valueOf(ch);
        int pad = spritePad();
        int w = Math.max(1, Math.round(fill.measureText(s)) + pad * 2);
        b = Bitmap.createBitmap(w, getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.translate(-(x - pad), 0); // draw in view coordinates
        float baseline = baseline();
        float dh = digitHeight();
        fill.setShader(glassShader(g));
        fill.setAlpha(255);
        c.drawText(s, x, baseline, fill);
        fill.setShader(null);
        // Very faint glassy edge on the top half only: the fill carries the look, not an outline.
        edge.setShader(new LinearGradient(0, baseline - dh, 0, baseline - dh * 0.4f,
                new int[]{0x40FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        edge.setAlpha(255);
        c.drawText(s, x, baseline, edge);
        edge.setShader(null);
        sprites.put(key, b);
        return b;
    }

    /** Soft glow around a character: a blurred quarter-size alpha mask, tinted when drawn. */
    Bitmap glowSprite(char ch) {
        Bitmap b = glows.get(ch);
        if (b != null) return b;
        if (getHeight() <= 0) return null;
        float s = 1f / GLOW_SCALE;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(fill.getTypeface());
        p.setTextSize(fill.getTextSize() * s);
        p.setColor(Color.WHITE);
        float r = Math.max(1f, digitHeight() * 0.12f * s);
        p.setMaskFilter(new BlurMaskFilter(r, BlurMaskFilter.Blur.NORMAL));
        int pad = (int) Math.ceil(r * 2.5f) + 2;
        String str = String.valueOf(ch);
        int w = Math.round(p.measureText(str)) + pad * 2;
        int h = Math.round(getHeight() * s) + pad * 2;
        b = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(b);
        c.drawText(str, pad, pad + baseline() * s, p);
        glows.put(ch, b);
        return b;
    }

    void drawChar(Canvas canvas, char ch, float x, float dy, float scale, float pivotY,
                  float alpha, Bitmap[] layers, float[] layerAlpha) {
        drawChar(canvas, ch, x, dy, scale, pivotY, alpha, 1f, layers, layerAlpha);
    }

    void drawChar(Canvas canvas, char ch, float x, float dy, float scale, float pivotY,
                  float alpha, float glowMul, Bitmap[] layers, float[] layerAlpha) {
        if (alpha <= 0.004f) return;
        int base = Math.round(255 * alphaPct / 100f);
        canvas.save();
        canvas.translate(0, dy);
        if (scale != 1f) {
            float cx = x + fill.measureText(String.valueOf(ch)) / 2f;
            canvas.scale(scale, scale, cx, pivotY);
        }

        Bitmap gl = glowSprite(ch);
        if (gl != null) {
            int ga = Math.round(Color.alpha(glow) * alpha * glowMul * base / 255f);
            glowPaint.setColor((Math.max(0, Math.min(255, ga)) << 24) | (glow & 0xFFFFFF));
            int pad = (int) Math.ceil(Math.max(1f, digitHeight() * 0.12f / GLOW_SCALE) * 2.5f) + 2;
            canvas.save();
            canvas.translate(x - pad * GLOW_SCALE, -pad * GLOW_SCALE);
            canvas.scale(GLOW_SCALE, GLOW_SCALE);
            canvas.drawBitmap(gl, 0, 0, glowPaint);
            canvas.restore();
        }
        int pad = spritePad();
        for (int l = 0; l < layers.length; l++) {
            if (layerAlpha[l] <= 0.01f) continue;
            Bitmap sp = sprite(layers[l], ch, x);
            if (sp == null) continue;
            spritePaint.setAlpha(Math.max(0, Math.min(255, Math.round(base * alpha * layerAlpha[l]))));
            canvas.drawBitmap(sp, x - pad, 0, spritePaint);
        }
        canvas.restore();
    }

    float[] positions(String s, float total) {
        float[] xs = new float[s.length()];
        float x = (getWidth() - total) / 2f;
        for (int i = 0; i < s.length(); i++) {
            xs[i] = Math.round(x);
            x += fill.measureText(s, i, i + 1);
        }
        return xs;
    }

    /**
     * Inspired by the clock in the video: the changing digit melts into the next one in place.
     * The old digit fades out while shrinking slightly, the new one fades in from a touch larger,
     * and the glow swells briefly in the middle so the change reads as a soft "breath".
     */
    void drawCrossFade(Canvas canvas, float[] xn, float[] xo, boolean sameLen, int[] order,
                       int changedCount, float pivotY, Bitmap[] layers, float[] layerAlpha) {
        float stagger = changedCount > 1 ? 0.12f : 0f;
        float span = 1f - stagger * Math.max(0, changedCount - 1);
        // Old characters that are going away.
        for (int i = 0; i < old.length(); i++) {
            boolean changed = !sameLen || old.charAt(i) != text.charAt(i);
            if (!changed) continue;
            int ord = sameLen ? order[i] : Math.min(i, changedCount - 1);
            float p = Math.max(0f, Math.min(1f, (progress - stagger * ord) / span));
            float out = Math.min(1f, p * 1.4f);           // gone a bit before the end
            float e = decel.getInterpolation(out);
            drawChar(canvas, old.charAt(i), xo[i], 0, 1f - 0.05f * e, pivotY,
                    1f - e, 1f + 1.6f * (float) Math.sin(Math.PI * p), layers, layerAlpha);
        }
        // New characters (unchanged ones just sit still).
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (order[i] < 0) {
                drawChar(canvas, c, xn[i], 0, 1f, pivotY, 1f, layers, layerAlpha);
                continue;
            }
            float p = Math.max(0f, Math.min(1f, (progress - stagger * order[i]) / span));
            float in = Math.max(0f, (p - 0.15f) / 0.85f);  // starts just after the old one
            float e = decel.getInterpolation(in);
            drawChar(canvas, c, xn[i], 0, 1.05f - 0.05f * e, pivotY,
                    e, 1f + 1.6f * (float) Math.sin(Math.PI * p), layers, layerAlpha);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (text.isEmpty() || getWidth() <= 0) return;
        usedThisFrame.clear();
        float dh = digitHeight();
        float baseline = baseline();
        float shift = dh * 0.55f;
        float pivotY = baseline - dh / 2f;

        float[] xn = positions(text, fill.measureText(text));
        float[] xo = old.isEmpty() ? xn : positions(old, fill.measureText(old));
        boolean sameLen = old.length() == text.length();

        int changedCount = 0;
        int[] order = new int[text.length()];
        for (int i = 0; i < text.length(); i++) {
            boolean ch = !sameLen || old.charAt(i) != text.charAt(i);
            order[i] = ch ? changedCount++ : -1;
        }

        Bitmap[] layers = {glassOld, glassNew};
        float[] layerAlpha = {glassOld == null ? 0f : 1f - glassMix, glassOld == null ? 1f : glassMix};

        if (animStyle == 0 && progress < 1f) {
            drawCrossFade(canvas, xn, xo, sameLen, order, changedCount, pivotY, layers, layerAlpha);
            return;
        }

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (order[i] < 0 || progress >= 1f) {
                drawChar(canvas, c, xn[i], 0, 1f, pivotY, 1f, layers, layerAlpha);
                continue;
            }
            float delay = changedCount > 1 ? 0.18f * order[i] : 0f;
            float span = 1f - 0.18f * Math.max(0, changedCount - 1);
            float p = Math.max(0f, Math.min(1f, (progress - delay) / span));
            // old character floats up and fades
            if (sameLen && i < old.length()) {
                float po = decel.getInterpolation(p);
                drawChar(canvas, old.charAt(i), xo[i], -shift * po, 1f - 0.1f * po, pivotY,
                        1f - po, layers, layerAlpha);
            }
            // new character rolls in from below with a little bounce
            float pn = overshoot.getInterpolation(p);
            drawChar(canvas, c, xn[i], shift * (1f - pn), 1f, pivotY,
                    Math.min(1f, p * 1.6f), layers, layerAlpha);
        }

        // Once everything is at rest, forget sprites that are no longer on screen.
        if (progress >= 1f && (glassOld == null || glassMix >= 1f)) {
            Iterator<String> it = sprites.keySet().iterator();
            while (it.hasNext()) if (!usedThisFrame.contains(it.next())) it.remove();
            if (glassMix >= 1f) glassOld = null;
        }
    }
}
