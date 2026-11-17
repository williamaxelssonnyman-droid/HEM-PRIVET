package se.william.hem;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    static final String PREFS = "hem";
    static final String[] DEFAULT_DOCK = {
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.formulaone.production",
            "se.svt.android.svtplay"
    };
    static final int[] WALLPAPERS = {
            R.drawable.wp1, R.drawable.wp2, R.drawable.wp3, R.drawable.wp4,
            R.drawable.wp5, R.drawable.wp6, R.drawable.wp7
    };
    static final String[] WALLPAPER_NAMES = {
            "Aurora over the peak", "Aurora over snowy mountains", "Dark ridges",
            "Midnight peak", "Pink dusk", "Aurora over frozen lake", "Night lake"
    };
    static final int[] ROTATE_MIN = {0, 1, 5, 10, 30, 60};

    static class Item {
        final String text;
        final Runnable action;

        Item(String text, Runnable action) {
            this.text = text;
            this.action = action;
        }
    }

    SharedPreferences prefs;
    PackageManager pm;
    float density;
    final Handler handler = new Handler(Looper.getMainLooper());

    FrameLayout root;
    FrameLayout panLayer;
    ImageView bgA, bgB;
    boolean aOnTop = true;
    int wpIndex = -1;
    LinearLayout appsRow;
    TextView appsTitle;
    TextClock clock;
    TextClock date;
    View clockBox;
    View gridBtn, gearBtn;
    FrameLayout overlay;
    FrameLayout panel;
    ObjectAnimator pan;
    String focusPkg;
    int tint = 0xFFB8D8E8;
    String lastTime = "";
    boolean animateIn = true;

    final Runnable rotateTask = new Runnable() {
        @Override
        public void run() {
            int every = prefs.getInt("rotate", 5);
            if (every > 0 && prefs.getInt("bg", -1) == -1) {
                showWallpaper((wpIndex + 1) % WALLPAPERS.length, true);
                prefs.edit().putInt("lastWp", wpIndex).apply();
            }
            scheduleRotate();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        pm = getPackageManager();
        density = getResources().getDisplayMetrics().density;
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyWallpaper();
        applyClock();
        animateIn = true;
        refreshApps();
        startPan();
        scheduleRotate();
        introAnimation();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (pan != null) pan.cancel();
        handler.removeCallbacks(rotateTask);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        closePanel(false);
        closeOverlay(false);
        focusPkg = null;
        focusFirst();
    }

    @Override
    public void onBackPressed() {
        // Never move the task to the back: that crashed the system on this device.
        if (panel != null) closePanel(true);
        else if (overlay != null) closeOverlay(true);
    }

    int dp(float v) {
        return Math.round(v * density);
    }

    // ---------------------------------------------------------------- UI

    Typeface clockTypeface() {
        try {
            Typeface t = new Typeface.Builder(getAssets(), "fonts/clock.ttf")
                    .setFontVariationSettings("'wght' 300").build();
            if (t != null) return t;
        } catch (Throwable ignored) {
        }
        return Typeface.create("sans-serif-condensed-light", Typeface.NORMAL);
    }

    GradientDrawable glass(float radiusDp, int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(radiusDp));
        g.setColor(fill);
        g.setStroke(dp(1), 0x55FFFFFF);
        return g;
    }

    void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);

        panLayer = new FrameLayout(this);
        bgA = new ImageView(this);
        bgB = new ImageView(this);
        for (ImageView iv : new ImageView[]{bgA, bgB}) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            panLayer.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        }
        bgB.setAlpha(0f);
        root.addView(panLayer, new FrameLayout.LayoutParams(-1, -1));

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x40000000, 0x00000000, 0x10000000, 0xB0000000}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout content = new FrameLayout(this);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        // Old TVs crop the picture edges (overscan): keep everything inside a safe area.
        content.setPadding(dp(48), dp(27), dp(48), dp(27));
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        // Top right: round glass buttons.
        LinearLayout topRight = new LinearLayout(this);
        topRight.setOrientation(LinearLayout.HORIZONTAL);
        gridBtn = roundButton(new DotsDrawable(), "All apps", () -> showAllApps(gridBtn));
        gearBtn = roundButton(new GearDrawable(), "Settings", () -> showSettings(gearBtn));
        topRight.addView(gridBtn);
        topRight.addView(gearBtn);
        content.addView(topRight, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));

        // Centre: date + big see-through clock.
        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);

        date = new TextClock(this);
        date.setFormat12Hour("EEEE d MMMM");
        date.setFormat24Hour("EEEE d MMMM");
        date.setTextColor(0xD9FFFFFF);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        date.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        date.setShadowLayer(10, 0, 2, 0x88000000);
        center.addView(date);

        clock = new TextClock(this);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 230);
        clock.setTypeface(clockTypeface());
        clock.setIncludeFontPadding(false);
        clock.setLetterSpacing(0.01f);
        clock.addOnLayoutChangeListener((v, l, t, r, btm, ol, ot, orr, ob) -> paintClock());
        clock.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override
            public void afterTextChanged(Editable e) {
                String now = e.toString();
                if (!lastTime.isEmpty() && !now.equals(lastTime)) tickAnimation();
                lastTime = now;
            }
        });
        LinearLayout.LayoutParams clockLp = new LinearLayout.LayoutParams(-2, -2);
        clockLp.topMargin = -dp(6);
        center.addView(clock, clockLp);

        clockBox = center;
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        clp.topMargin = dp(8);
        content.addView(center, clp);

        // Bottom left: "Apps" title + row of app cards.
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);

        appsTitle = new TextView(this);
        appsTitle.setText("Your apps");
        appsTitle.setTextColor(0xEEFFFFFF);
        appsTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        appsTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        appsTitle.setShadowLayer(8, 0, 2, 0xAA000000);
        appsTitle.setPadding(dp(6), 0, 0, dp(6));
        bottom.addView(appsTitle);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setPadding(0, dp(8), 0, dp(10));
        appsRow = new LinearLayout(this);
        appsRow.setOrientation(LinearLayout.HORIZONTAL);
        appsRow.setClipChildren(false);
        appsRow.setClipToPadding(false);
        hs.addView(appsRow);
        bottom.addView(hs);

        content.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START));

        setContentView(root);
    }

    View roundButton(Drawable icon, String name, Runnable click) {
        FrameLayout f = new FrameLayout(this);
        f.setFocusable(true);
        f.setClickable(true);
        StateListDrawable sl = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setShape(GradientDrawable.OVAL);
        on.setColor(0xEEFFFFFF);
        GradientDrawable off = new GradientDrawable();
        off.setShape(GradientDrawable.OVAL);
        off.setColor(0x33FFFFFF);
        off.setStroke(dp(1), 0x55FFFFFF);
        sl.addState(new int[]{android.R.attr.state_focused}, on);
        sl.addState(new int[]{}, off);
        f.setBackground(sl);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        iv.setPadding(dp(12), dp(12), dp(12), dp(12));
        f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48));
        lp.setMargins(dp(6), 0, 0, 0);
        f.setLayoutParams(lp);
        f.setContentDescription(name);
        f.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.15f : 1f).scaleY(has ? 1.15f : 1f).setDuration(160).start();
            iv.setColorFilter(has ? 0xFF101418 : Color.WHITE);
        });
        iv.setColorFilter(Color.WHITE);
        f.setOnClickListener(v -> press(v, click));
        return f;
    }

    // ---------------------------------------------------------------- animation helpers

    /** Quick "press" squish, then run the action. */
    void press(View v, Runnable then) {
        v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(160)
                            .setInterpolator(new OvershootInterpolator(2f)).start();
                    then.run();
                }).start();
    }

    int[] centerIn(View anchor, View target) {
        int[] a = new int[2];
        int[] t = new int[2];
        anchor.getLocationOnScreen(a);
        target.getLocationOnScreen(t);
        return new int[]{a[0] + anchor.getWidth() / 2 - t[0], a[1] + anchor.getHeight() / 2 - t[1]};
    }

    void reveal(View target, View anchor, boolean open, Runnable end) {
        target.post(() -> {
            if (!target.isAttachedToWindow()) return;
            int[] c = anchor != null && anchor.isAttachedToWindow()
                    ? centerIn(anchor, target) : new int[]{target.getWidth() / 2, target.getHeight() / 2};
            float far = (float) Math.hypot(Math.max(c[0], target.getWidth() - c[0]),
                    Math.max(c[1], target.getHeight() - c[1]));
            Animator a = ViewAnimationUtils.createCircularReveal(target, c[0], c[1], open ? 0 : far, open ? far : 0);
            a.setDuration(open ? 480 : 320);
            a.setInterpolator(open ? new DecelerateInterpolator(1.6f) : new AccelerateInterpolator());
            a.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (end != null) end.run();
                }
            });
            target.setVisibility(View.VISIBLE);
            a.start();
        });
    }

    void introAnimation() {
        clockBox.setAlpha(0f);
        clockBox.setScaleX(0.92f);
        clockBox.setScaleY(0.92f);
        clockBox.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(1100)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
        appsTitle.setAlpha(0f);
        appsTitle.animate().alpha(1f).setStartDelay(200).setDuration(600).start();
    }

    void tickAnimation() {
        clock.setTranslationY(dp(28));
        clock.setAlpha(0f);
        clock.animate().translationY(0).alpha(1f).setDuration(900)
                .setInterpolator(new OvershootInterpolator(0.9f)).start();
    }

    // ---------------------------------------------------------------- clock

    int clockAlpha() {
        return prefs.getInt("clockAlpha", 60);
    }

    void paintClock() {
        int h = clock.getHeight();
        if (h <= 0) return;
        int a = Math.round(255 * clockAlpha() / 100f);
        int top = withAlpha(mix(tint, Color.WHITE, 0.55f), Math.min(255, a + 25));
        int mid = withAlpha(tint, a);
        int bottom = withAlpha(mix(tint, Color.WHITE, 0.25f), Math.max(25, a - 90));
        clock.getPaint().setShader(new LinearGradient(0, 0, 0, h,
                new int[]{top, mid, bottom}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        clock.invalidate();
    }

    void applyClock() {
        boolean h24 = prefs.getBoolean("h24", true);
        String f = h24 ? "HH:mm" : "h:mm";
        clock.setFormat24Hour(f);
        clock.setFormat12Hour(f);
        clockBox.setVisibility(prefs.getBoolean("clock", true) ? View.VISIBLE : View.INVISIBLE);
        paintClock();
    }

    static int withAlpha(int c, int a) {
        return (Math.max(0, Math.min(255, a)) << 24) | (c & 0xFFFFFF);
    }

    static int mix(int c1, int c2, float t) {
        int r = (int) (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t);
        int g = (int) (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t);
        int b = (int) (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t);
        return Color.rgb(r, g, b);
    }

    /** Most colourful bright colour in the wallpaper, used to tint the see-through clock. */
    static int pickTint(Bitmap src) {
        if (src == null) return 0xFFB8D8E8;
        Bitmap s = Bitmap.createScaledBitmap(src, 48, 27, true);
        float best = -1;
        int bestC = 0xFFB8D8E8;
        long r = 0, g = 0, b = 0;
        int n = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < 27; y++) {
            for (int x = 0; x < 48; x++) {
                int c = s.getPixel(x, y);
                r += Color.red(c);
                g += Color.green(c);
                b += Color.blue(c);
                n++;
                Color.colorToHSV(c, hsv);
                float score = hsv[1] * (0.3f + hsv[2]);
                if (hsv[2] > 0.25f && score > best) {
                    best = score;
                    bestC = c;
                }
            }
        }
        if (best < 0.15f) bestC = Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
        Color.colorToHSV(bestC, hsv);
        hsv[1] = Math.min(0.7f, hsv[1]);
        hsv[2] = Math.max(0.9f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    // ---------------------------------------------------------------- wallpaper

    void scheduleRotate() {
        handler.removeCallbacks(rotateTask);
        int every = prefs.getInt("rotate", 5);
        if (every > 0) handler.postDelayed(rotateTask, every * 60_000L);
    }

    void applyWallpaper() {
        int choice = prefs.getInt("bg", -1);
        if (choice == 0) {
            File f = new File(getExternalFilesDir(null), "bakgrund.jpg");
            Bitmap b = f.exists() ? decodeFile(f.getAbsolutePath()) : null;
            if (b != null) {
                setWallpaperBitmap(b, wpIndex >= 0);
                wpIndex = -2;
                return;
            }
            Toast.makeText(this, "No bakgrund.jpg found – using the built-in wallpapers", Toast.LENGTH_LONG).show();
            choice = -1;
        }
        int idx = choice > 0 ? choice - 1 : prefs.getInt("lastWp", 0) % WALLPAPERS.length;
        if (idx != wpIndex) showWallpaper(idx, wpIndex >= 0);
    }

    void showWallpaper(int idx, boolean animate) {
        wpIndex = idx;
        setWallpaperBitmap(decodeRes(WALLPAPERS[idx]), animate);
    }

    void setWallpaperBitmap(Bitmap b, boolean animate) {
        final int newTint = pickTint(b);
        ImageView front = aOnTop ? bgA : bgB;
        ImageView back = aOnTop ? bgB : bgA;
        if (!animate) {
            front.setImageBitmap(b);
            front.setAlpha(1f);
            back.setAlpha(0f);
            tint = newTint;
            paintClock();
            return;
        }
        back.setImageBitmap(b);
        back.bringToFront();
        back.setAlpha(0f);
        back.setScaleX(1.06f);
        back.setScaleY(1.06f);
        back.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(2600)
                .withEndAction(() -> front.setAlpha(0f)).start();
        aOnTop = !aOnTop;
        ValueAnimator va = ValueAnimator.ofObject(new ArgbEvaluator(), tint, newTint);
        va.setDuration(2600);
        va.addUpdateListener(an -> {
            tint = (int) an.getAnimatedValue();
            paintClock();
        });
        va.start();
    }

    Bitmap decodeRes(int res) {
        try {
            return BitmapFactory.decodeResource(getResources(), res);
        } catch (Throwable t) {
            return null;
        }
    }

    Bitmap decodeFile(String path) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int s = 1;
            while (o.outWidth / s > 2560 || o.outHeight / s > 1600) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            return BitmapFactory.decodeFile(path, o2);
        } catch (Throwable t) {
            return null;
        }
    }

    void startPan() {
        if (pan != null) pan.cancel();
        panLayer.setScaleX(1.12f);
        panLayer.setScaleY(1.12f);
        panLayer.setTranslationX(0);
        if (!prefs.getBoolean("pan", true)) return;
        float dx = getResources().getDisplayMetrics().widthPixels * 0.05f;
        pan = ObjectAnimator.ofFloat(panLayer, "translationX", -dx, dx);
        pan.setDuration(40000);
        pan.setRepeatMode(ValueAnimator.REVERSE);
        pan.setRepeatCount(ValueAnimator.INFINITE);
        pan.setInterpolator(new AccelerateDecelerateInterpolator());
        pan.start();
    }

    // ---------------------------------------------------------------- app cards

    View appCard(String pkg, int widthDp, int heightDp, Runnable longClick) {
        final FrameLayout f = new FrameLayout(this);
        f.setFocusable(true);
        f.setClickable(true);
        final int radius = dp(14);
        GradientDrawable base = glass(14, 0x33FFFFFF);
        f.setBackground(base);
        f.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        f.setClipToOutline(true);

        Drawable ban = banner(pkg);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(ban != null ? ban : icon(pkg));
        iv.setScaleType(ban != null ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        if (ban == null) iv.setPadding(dp(18), dp(18), dp(18), dp(18));
        f.addView(iv, new FrameLayout.LayoutParams(-1, -1));

        // Soft shine on top of the card, like glass.
        View shine = new View(this);
        shine.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x30FFFFFF, 0x00FFFFFF}));
        f.addView(shine, new FrameLayout.LayoutParams(-1, dp(heightDp) / 2));

        final GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius);
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(dp(3), Color.WHITE);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(widthDp), dp(heightDp));
        lp.setMargins(dp(6), dp(4), dp(6), dp(4));
        f.setLayoutParams(lp);

        final String name = appName(pkg);
        f.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).translationY(has ? -dp(6) : 0)
                    .setDuration(220).setInterpolator(new OvershootInterpolator(1.4f)).start();
            v.setElevation(has ? dp(14) : 0);
            f.setForeground(has ? ring : null);
            if (has) {
                appsTitle.setText(name);
                focusPkg = pkg;
            }
        });
        f.setOnClickListener(v -> press(v, () -> launch(pkg, v)));
        f.setOnLongClickListener(v -> {
            longClick.run();
            return true;
        });
        return f;
    }

    List<String> getDock() {
        String s = prefs.getString("dock", null);
        List<String> out = new ArrayList<>();
        if (s == null) {
            for (String p : DEFAULT_DOCK) if (installed(p)) out.add(p);
            saveDock(out);
        } else if (!s.isEmpty()) {
            out.addAll(Arrays.asList(s.split(",")));
        }
        return out;
    }

    void saveDock(List<String> l) {
        prefs.edit().putString("dock", String.join(",", l)).apply();
    }

    void refreshApps() {
        String keep = focusPkg;
        appsRow.removeAllViews();
        View focusTarget = null;
        int i = 0;
        for (String p : getDock()) {
            if (!installed(p)) continue;
            final String pkg = p;
            final View[] holder = new View[1];
            View t = appCard(pkg, 176, 99, () -> appMenu(pkg, holder[0]));
            holder[0] = t;
            appsRow.addView(t);
            if (pkg.equals(keep)) focusTarget = t;
            if (animateIn) {
                t.setAlpha(0f);
                t.setTranslationY(dp(40));
                t.animate().alpha(1f).translationY(0).setStartDelay(150 + i * 70L).setDuration(520)
                        .setInterpolator(new DecelerateInterpolator(2f)).start();
            }
            i++;
        }
        animateIn = false;
        appsTitle.setText("Your apps");
        final View ft = focusTarget;
        appsRow.post(() -> {
            if (overlay != null || panel != null) return;
            if (ft != null) ft.requestFocus();
            else focusFirst();
        });
    }

    void focusFirst() {
        if (appsRow != null && appsRow.getChildCount() > 0) appsRow.getChildAt(0).requestFocus();
        else if (gridBtn != null) gridBtn.requestFocus();
    }

    void appMenu(String pkg, View anchor) {
        final List<String> d = getDock();
        final boolean in = d.contains(pkg);
        List<Item> items = new ArrayList<>();
        items.add(new Item("Open", () -> launch(pkg, anchor)));
        if (in) {
            items.add(new Item("Move left", () -> moveApp(pkg, -1)));
            items.add(new Item("Move right", () -> moveApp(pkg, 1)));
            items.add(new Item("Remove from home", () -> {
                d.remove(pkg);
                saveDock(d);
                focusPkg = null;
                refreshApps();
            }));
        } else {
            items.add(new Item("Add to home", () -> {
                d.add(pkg);
                saveDock(d);
                closeOverlay(false);
                focusPkg = pkg;
                refreshApps();
            }));
        }
        showPanel(anchor, appName(pkg), items);
    }

    void moveApp(String pkg, int dir) {
        List<String> d = getDock();
        int i = d.indexOf(pkg);
        int j = i + dir;
        if (i < 0 || j < 0 || j >= d.size()) return;
        Collections.swap(d, i, j);
        saveDock(d);
        focusPkg = pkg;
        refreshApps();
    }

    // ---------------------------------------------------------------- apps

    boolean installed(String p) {
        try {
            pm.getApplicationInfo(p, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    String appName(String p) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(p, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return p;
        }
    }

    Drawable banner(String p) {
        try {
            Intent i = pm.getLeanbackLaunchIntentForPackage(p);
            if (i != null) {
                Drawable d = pm.getActivityBanner(i);
                if (d != null) return d;
            }
            return pm.getApplicationBanner(p);
        } catch (Exception e) {
            return null;
        }
    }

    Drawable icon(String p) {
        try {
            return pm.getApplicationIcon(p);
        } catch (Exception e) {
            return new ColorDrawable(Color.DKGRAY);
        }
    }

    /** Opens an app so that it grows out of the card you clicked. */
    void launch(String p, View from) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(p);
        if (i == null) i = pm.getLaunchIntentForPackage(p);
        if (i == null) {
            Toast.makeText(this, "Can't open " + appName(p), Toast.LENGTH_SHORT).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (from != null && from.isAttachedToWindow()) {
                ActivityOptions o = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight());
                startActivity(i, o.toBundle());
            } else {
                startActivity(i);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Can't open " + appName(p), Toast.LENGTH_SHORT).show();
        }
    }

    List<String> allApps() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN);
            q.addCategory(cat);
            for (ResolveInfo r : pm.queryIntentActivities(q, 0)) {
                String p = r.activityInfo.packageName;
                if (p.equals(getPackageName())) continue;
                if (!m.containsKey(p)) m.put(p, appName(p));
            }
        }
        List<String> l = new ArrayList<>(m.keySet());
        l.sort((a, b) -> m.get(a).compareToIgnoreCase(m.get(b)));
        return l;
    }

    View overlayAnchor;

    void showAllApps(View anchor) {
        closeOverlay(false);
        overlayAnchor = anchor;
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xD8060A10);
        overlay.setVisibility(View.INVISIBLE);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(56), dp(32), dp(56), dp(32));
        TextView title = new TextView(this);
        title.setText("All apps");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(6), 0, 0, dp(10));
        col.addView(title);
        ScrollView sv = new ScrollView(this);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        GridLayout g = new GridLayout(this);
        g.setColumnCount(5);
        g.setPadding(0, dp(8), 0, dp(24));
        g.setClipChildren(false);
        g.setClipToPadding(false);
        View first = null;
        int i = 0;
        for (String p : allApps()) {
            final String pkg = p;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setClipChildren(false);
            final View[] holder = new View[1];
            View t = appCard(pkg, 150, 84, () -> appMenu(pkg, holder[0]));
            holder[0] = t;
            cell.addView(t);
            TextView n = new TextView(this);
            n.setText(appName(pkg));
            n.setTextColor(0xCCFFFFFF);
            n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            n.setGravity(Gravity.CENTER);
            n.setPadding(0, dp(2), 0, dp(10));
            cell.addView(n);
            cell.setAlpha(0f);
            cell.setTranslationY(dp(20));
            cell.animate().alpha(1f).translationY(0).setStartDelay(200 + Math.min(i, 20) * 25L).setDuration(380).start();
            g.addView(cell);
            if (first == null) first = t;
            i++;
        }
        sv.addView(g);
        col.addView(sv, new LinearLayout.LayoutParams(-1, -1));
        overlay.addView(col, new FrameLayout.LayoutParams(-1, -1));
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        reveal(overlay, anchor, true, null);
        if (first != null) {
            final View f = first;
            overlay.postDelayed(f::requestFocus, 120);
        }
        appsTitle.setText("Your apps");
    }

    void closeOverlay(boolean animate) {
        if (overlay == null) return;
        final FrameLayout o = overlay;
        overlay = null;
        if (animate) {
            reveal(o, overlayAnchor, false, () -> root.removeView(o));
            if (overlayAnchor != null) overlayAnchor.requestFocus();
        } else {
            root.removeView(o);
        }
        refreshApps();
    }

    // ---------------------------------------------------------------- glass panel (menus)

    View panelAnchor;

    void showPanel(View anchor, String title, List<Item> items) {
        closePanel(false);
        panelAnchor = anchor;
        panel = new FrameLayout(this);
        panel.setBackgroundColor(0x66000000);
        panel.setClickable(true);
        panel.setAlpha(0f);
        panel.animate().alpha(1f).setDuration(250).start();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(16), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xE6202830, 0xF0101418});
        bg.setCornerRadius(dp(26));
        bg.setStroke(dp(1), 0x66FFFFFF);
        card.setBackground(bg);
        card.setVisibility(View.INVISIBLE);
        card.setElevation(dp(20));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(12), 0, dp(12), dp(10));
        card.addView(t);

        View first = null;
        int i = 0;
        for (Item it : items) {
            TextView row = new TextView(this);
            row.setText(it.text);
            row.setTextColor(0xF0FFFFFF);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            row.setPadding(dp(14), dp(11), dp(14), dp(11));
            row.setFocusable(true);
            row.setClickable(true);
            StateListDrawable sl = new StateListDrawable();
            GradientDrawable on = new GradientDrawable();
            on.setCornerRadius(dp(14));
            on.setColor(0xF2FFFFFF);
            sl.addState(new int[]{android.R.attr.state_focused}, on);
            sl.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
            row.setBackground(sl);
            row.setOnFocusChangeListener((v, has) -> {
                ((TextView) v).setTextColor(has ? 0xFF0E1216 : 0xF0FFFFFF);
                v.animate().translationX(has ? dp(4) : 0).setDuration(150).start();
            });
            row.setOnClickListener(v -> {
                closePanel(false);
                it.action.run();
            });
            row.setAlpha(0f);
            row.setTranslationY(dp(10));
            row.animate().alpha(1f).translationY(0).setStartDelay(160 + i * 35L).setDuration(260).start();
            card.addView(row, new LinearLayout.LayoutParams(-1, -2));
            if (first == null) first = row;
            i++;
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(card);
        panel.addView(sv, new FrameLayout.LayoutParams(dp(470), -2, Gravity.CENTER));
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        reveal(card, anchor, true, null);
        if (first != null) {
            final View f = first;
            panel.postDelayed(f::requestFocus, 60);
        }
    }

    void closePanel(boolean animate) {
        if (panel == null) return;
        final FrameLayout p = panel;
        panel = null;
        if (animate) {
            p.animate().alpha(0f).setDuration(220).withEndAction(() -> root.removeView(p)).start();
        } else {
            root.removeView(p);
        }
        if (panelAnchor != null && panelAnchor.isAttachedToWindow()) panelAnchor.requestFocus();
    }

    // ---------------------------------------------------------------- settings

    String rotateLabel(int m) {
        return m == 0 ? "Off" : m + " min";
    }

    void showSettings(View anchor) {
        boolean panOn = prefs.getBoolean("pan", true);
        boolean h24 = prefs.getBoolean("h24", true);
        boolean showClock = prefs.getBoolean("clock", true);
        int bgChoice = prefs.getInt("bg", -1);
        String wpLabel = bgChoice == -1 ? "All (rotating)" : bgChoice == 0 ? "Own picture" : WALLPAPER_NAMES[bgChoice - 1];
        List<Item> items = new ArrayList<>();
        items.add(new Item("Wallpaper: " + wpLabel, () -> chooseWallpaper(anchor)));
        items.add(new Item("Change wallpaper every: " + rotateLabel(prefs.getInt("rotate", 5)), () -> chooseRotate(anchor)));
        items.add(new Item("Next wallpaper now", () -> {
            prefs.edit().putInt("bg", -1).apply();
            showWallpaper((wpIndex + 1 + WALLPAPERS.length) % WALLPAPERS.length, true);
            prefs.edit().putInt("lastWp", wpIndex).apply();
            scheduleRotate();
        }));
        items.add(new Item("Moving wallpaper: " + (panOn ? "On" : "Off"), () -> {
            prefs.edit().putBoolean("pan", !panOn).apply();
            startPan();
        }));
        items.add(new Item("Clock: " + (showClock ? "Shown" : "Hidden"), () -> {
            prefs.edit().putBoolean("clock", !showClock).apply();
            applyClock();
        }));
        items.add(new Item("Clock transparency: " + (100 - clockAlpha()) + "%", () -> chooseTransparency(anchor)));
        items.add(new Item("Clock format: " + (h24 ? "24h" : "12h"), () -> {
            prefs.edit().putBoolean("h24", !h24).apply();
            applyClock();
        }));
        items.add(new Item("Home button (turn on the Home service)",
                () -> openSafe(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        items.add(new Item("Android settings", () -> openSafe(new Intent(Settings.ACTION_SETTINGS))));
        items.add(new Item("Reset apps", () -> {
            prefs.edit().remove("dock").apply();
            refreshApps();
        }));
        showPanel(anchor, "Settings", items);
    }

    void chooseRotate(View anchor) {
        List<Item> items = new ArrayList<>();
        for (int m : ROTATE_MIN) {
            items.add(new Item(rotateLabel(m), () -> {
                prefs.edit().putInt("rotate", m).apply();
                scheduleRotate();
            }));
        }
        showPanel(anchor, "Change wallpaper every", items);
    }

    void chooseTransparency(View anchor) {
        List<Item> items = new ArrayList<>();
        for (int lvl : new int[]{0, 20, 40, 55, 70, 85}) {
            items.add(new Item(lvl + "%" + (lvl == 0 ? " (solid)" : ""), () -> {
                prefs.edit().putInt("clockAlpha", 100 - lvl).apply();
                paintClock();
            }));
        }
        showPanel(anchor, "Clock transparency", items);
    }

    void chooseWallpaper(View anchor) {
        List<Item> items = new ArrayList<>();
        items.add(new Item("All (rotating)", () -> {
            prefs.edit().putInt("bg", -1).apply();
            scheduleRotate();
        }));
        for (int i = 0; i < WALLPAPERS.length; i++) {
            final int idx = i;
            items.add(new Item(WALLPAPER_NAMES[i], () -> {
                prefs.edit().putInt("bg", idx + 1).apply();
                showWallpaper(idx, true);
            }));
        }
        items.add(new Item("Own picture (bakgrund.jpg)", () -> {
            prefs.edit().putInt("bg", 0).apply();
            applyWallpaper();
        }));
        showPanel(anchor, "Wallpaper", items);
    }

    void openSafe(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't open settings", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- icons

    class DotsDrawable extends Drawable {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        DotsDrawable() {
            p.setColor(Color.WHITE);
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            float cell = s / 3f;
            float r = cell * 0.28f;
            float x0 = b.centerX() - s / 2f, y0 = b.centerY() - s / 2f;
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++)
                    c.drawCircle(x0 + cell * (i + 0.5f), y0 + cell * (j + 0.5f), r, p);
        }

        @Override public int getIntrinsicWidth() { return dp(44); }
        @Override public int getIntrinsicHeight() { return dp(44); }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    class GearDrawable extends Drawable {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);

        GearDrawable() {
            p.setColor(Color.WHITE);
            clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            float r = s / 2f;
            int save = c.saveLayer(new RectF(b), null);
            c.drawCircle(cx, cy, r * 0.68f, p);
            Path tooth = new Path();
            tooth.addRoundRect(new RectF(cx - r * 0.17f, cy - r, cx + r * 0.17f, cy - r * 0.5f), r * 0.06f, r * 0.06f, Path.Direction.CW);
            Matrix m = new Matrix();
            for (int i = 0; i < 8; i++) {
                Path t = new Path(tooth);
                m.setRotate(i * 45f, cx, cy);
                t.transform(m);
                c.drawPath(t, p);
            }
            c.drawCircle(cx, cy, r * 0.28f, clear);
            c.restoreToCount(save);
        }

        @Override public int getIntrinsicWidth() { return dp(46); }
        @Override public int getIntrinsicHeight() { return dp(46); }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
