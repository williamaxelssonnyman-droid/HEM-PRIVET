package se.william.hem;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
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
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
        final String value;
        final boolean stay;
        final Runnable action;

        Item(String text, Runnable action) {
            this(text, null, false, action);
        }

        Item(String text, String value, boolean stay, Runnable action) {
            this.text = text;
            this.value = value;
            this.stay = stay;
            this.action = action;
        }
    }

    SharedPreferences prefs;
    PackageManager pm;
    float density;
    final Handler handler = new Handler(Looper.getMainLooper());
    /** Wallpaper decoding happens here, never on the UI thread. */
    final ExecutorService wpThread = Executors.newSingleThreadExecutor();
    /** Loads app banners/names in the background so menus open instantly. */
    final ExecutorService iconThread = Executors.newSingleThreadExecutor();
    final Map<String, Drawable.ConstantState> bannerCache = new ConcurrentHashMap<>();
    final Map<String, Drawable.ConstantState> iconCache = new ConcurrentHashMap<>();
    final Set<String> noBanner = ConcurrentHashMap.newKeySet();
    final Map<String, String> nameCache = new ConcurrentHashMap<>();
    int wpToken = 0;
    Bitmap currentWp;
    ImageView blurView;
    boolean blurOn = false;

    FrameLayout root;
    FrameLayout panLayer;
    ImageView bgA, bgB;
    boolean aOnTop = true;
    int wpIndex = -1;
    LinearLayout appsRow;
    ClockView clock;
    FrameLayout home;
    LinearLayout bottomBar;
    boolean ambient = false;
    final Runnable ambientTask = this::enterAmbient;
    final Runnable timeTask = new Runnable() {
        @Override
        public void run() {
            updateTime(true);
            updateDate();
            handler.postDelayed(this, 1000);
        }
    };
    View clockBox;
    View gridBtn, gearBtn;
    FrameLayout overlay;
    FrameLayout panel;
    ObjectAnimator pan;
    String focusPkg;
    int tint = 0xFFB8D8E8;
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
        startSessionWatch();
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((o, n) -> hideFocusLabelIfNeeded());
        iconThread.execute(() -> {
            for (String p : allApps()) {
                appName(p);
                cardImage(p);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopSessionWatch();
        wpThread.shutdownNow();
        iconThread.shutdownNow();
        netThread.shutdownNow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyWallpaper();
        applyClock();
        animateIn = true;
        captureSessions();
        updateDate();
        refreshWeather(false);
        refreshApps();
        startPan();
        scheduleRotate();
        updateTime(false);
        handler.removeCallbacks(timeTask);
        handler.postDelayed(timeTask, 1000);
        exitAmbient(false);
        introAnimation();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (pan != null) pan.cancel();
        handler.removeCallbacks(rotateTask);
        handler.removeCallbacks(timeTask);
        handler.removeCallbacks(ambientTask);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (ambient) {
            if (e.getAction() == KeyEvent.ACTION_UP) exitAmbient(true);
            return true;
        }
        scheduleAmbient();
        return super.dispatchKeyEvent(e);
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

    // Elements of the video-style layout
    TextView dateText, weatherText;
    ImageView weatherIcon;
    WeatherDrawable weatherDrawable;
    LinearLayout pills;
    View moonBtn;
    LinearLayout watchRow;
    TextView watchTitle;
    TextView focusLabel;
    final ExecutorService netThread = Executors.newSingleThreadExecutor();

    void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);
        home = new FrameLayout(this);
        home.setClipChildren(false);
        root.addView(home, new FrameLayout.LayoutParams(-1, -1));

        panLayer = new FrameLayout(this);
        bgA = new ImageView(this);
        bgB = new ImageView(this);
        for (ImageView iv : new ImageView[]{bgA, bgB}) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            panLayer.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        }
        bgB.setAlpha(0f);
        home.addView(panLayer, new FrameLayout.LayoutParams(-1, -1));

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x50000000, 0x00000000, 0x00000000, 0x30000000, 0xA8000000}));
        home.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout content = new FrameLayout(this);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        // Old TVs crop the picture edges (overscan): keep everything inside a safe area.
        content.setPadding(dp(48), dp(27), dp(48), dp(27));
        home.addView(content, new FrameLayout.LayoutParams(-1, -1));

        // Top left: profile pills.
        pills = new LinearLayout(this);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.setClipChildren(false);
        pills.setClipToPadding(false);
        content.addView(pills, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        buildPills(-1);

        // Top right: one round glass button (screensaver / ambient mode).
        moonBtn = roundButton(new MoonDrawable(), "Screensaver", this::enterAmbient);
        moonBtn.setLayoutParams(new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP | Gravity.END));
        content.addView(moonBtn);

        // Centre: date + weather, then the giant clock.
        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout dateRow = new LinearLayout(this);
        dateRow.setOrientation(LinearLayout.HORIZONTAL);
        dateRow.setGravity(Gravity.CENTER_VERTICAL);
        dateText = smallText(14, 0xF0FFFFFF, true);
        dateRow.addView(dateText);
        weatherDrawable = new WeatherDrawable();
        weatherIcon = new ImageView(this);
        weatherIcon.setImageDrawable(weatherDrawable);
        LinearLayout.LayoutParams wil = new LinearLayout.LayoutParams(dp(16), dp(16));
        wil.setMargins(dp(14), 0, dp(5), 0);
        dateRow.addView(weatherIcon, wil);
        weatherText = smallText(14, 0xE6FFFFFF, false);
        dateRow.addView(weatherText);
        weatherIcon.setVisibility(View.GONE);
        weatherText.setVisibility(View.GONE);
        center.addView(dateRow);

        clock = new ClockView(this);
        clock.setTypeface(clockTypeface());
        clock.setTextSizePx(clockTextSize());
        LinearLayout.LayoutParams clockLp = new LinearLayout.LayoutParams(-1, -2);
        clockLp.topMargin = -dp(6);
        center.addView(clock, clockLp);

        clockBox = center;
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        clp.topMargin = dp(24);
        content.addView(center, clp);

        // Bottom: "Keep watching" glass cards, then the row of app icons.
        LinearLayout bottom = new LinearLayout(this);
        bottomBar = bottom;
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);

        watchTitle = smallText(13, 0xD9FFFFFF, true);
        watchTitle.setText("Keep watching");
        watchTitle.setPadding(dp(4), 0, 0, dp(7));
        bottom.addView(watchTitle);

        watchRow = new LinearLayout(this);
        watchRow.setOrientation(LinearLayout.HORIZONTAL);
        watchRow.setClipChildren(false);
        watchRow.setClipToPadding(false);
        bottom.addView(watchRow, new LinearLayout.LayoutParams(-1, dp(62)));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setPadding(0, dp(16), 0, dp(4));
        appsRow = new LinearLayout(this);
        appsRow.setOrientation(LinearLayout.HORIZONTAL);
        appsRow.setClipChildren(false);
        appsRow.setClipToPadding(false);
        hs.addView(appsRow);
        bottom.addView(hs);

        focusLabel = smallText(12, 0xF2FFFFFF, true);
        focusLabel.setAlpha(0f);
        bottom.addView(focusLabel, new LinearLayout.LayoutParams(-2, -2));

        gearBtn = tileButton(new GearDrawable(), "Settings", () -> showSettings(gearBtn));
        gridBtn = tileButton(new MoreDrawable(), "All apps", () -> showAllApps(gridBtn));

        content.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START));

        // Frosted backdrop for menus: a blurred snapshot of the screen, faded in.
        blurView = new ImageView(this);
        blurView.setScaleType(ImageView.ScaleType.FIT_XY);
        blurView.setVisibility(View.GONE);
        root.addView(blurView, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
    }

    TextView smallText(int sp, int color, boolean medium) {
        TextView t = new TextView(this);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setShadowLayer(8, 0, 1, 0x99000000);
        t.setSingleLine(true);
        return t;
    }

    /** Text size that makes the digits about 30 % of the screen height, like in the video. */
    float clockTextSize() {
        Paint p = new Paint();
        p.setTypeface(clockTypeface());
        p.setTextSize(100f);
        Rect r = new Rect();
        p.getTextBounds("0123456789", 0, 10, r);
        float target = getResources().getDisplayMetrics().heightPixels * 0.30f;
        return r.height() > 0 ? 100f * target / r.height() : target * 1.3f;
    }

    // ---------------------------------------------------------------- profiles

    String[] profileNames() {
        return prefs.getString("profiles", "William,Guest").split(",");
    }

    int profile() {
        return Math.max(0, Math.min(profileNames().length - 1, prefs.getInt("profile", 0)));
    }

    String dockKey() {
        int p = profile();
        return p == 0 ? "dock" : "dock_" + p;
    }

    void buildPills(int focusIndex) {
        pills.removeAllViews();
        String[] names = profileNames();
        int cur = profile();
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            boolean on = i == cur;
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setTextColor(on ? 0xFF0E1216 : 0xEEFFFFFF);
            t.setPadding(dp(14), dp(5), dp(14), dp(5));
            t.setFocusable(true);
            t.setClickable(true);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
            bg.setColor(on ? 0xF2FFFFFF : 0x2EFFFFFF);
            if (!on) bg.setStroke(dp(1), 0x40FFFFFF);
            t.setBackground(bg);
            final GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(dp(14));
            ring.setColor(Color.TRANSPARENT);
            ring.setStroke(dp(2), Color.WHITE);
            t.setOnFocusChangeListener((v, has) -> {
                v.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).setDuration(160).start();
                v.setForeground(has && !on ? ring : null);
            });
            t.setOnClickListener(v -> press(v, () -> switchProfile(idx)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, dp(8), 0);
            pills.addView(t, lp);
            if (i == focusIndex) t.post(t::requestFocus);
        }
    }

    void switchProfile(int idx) {
        if (idx == profile()) return;
        prefs.edit().putInt("profile", idx).apply();
        buildPills(idx);
        focusPkg = null;
        animateIn = true;
        refreshApps();
    }

    // ---------------------------------------------------------------- buttons

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
        off.setColor(0x2EFFFFFF);
        off.setStroke(dp(1), 0x40FFFFFF);
        sl.addState(new int[]{android.R.attr.state_focused}, on);
        sl.addState(new int[]{}, off);
        f.setBackground(sl);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        iv.setPadding(dp(9), dp(9), dp(9), dp(9));
        f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        f.setContentDescription(name);
        f.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.15f : 1f).scaleY(has ? 1.15f : 1f).setDuration(160).start();
            iv.setColorFilter(has ? 0xFF101418 : Color.WHITE);
        });
        iv.setColorFilter(Color.WHITE);
        f.setOnClickListener(v -> press(v, click));
        return f;
    }

    static final int TILE_DP = 58;

    /** Rounded-square tile like the app icons, used for ⚙ and ⋯ at the end of the row. */
    View tileButton(Drawable icon, String name, Runnable click) {
        FrameLayout f = new FrameLayout(this);
        f.setFocusable(true);
        f.setClickable(true);
        f.setBackground(glass(14, 0x2EFFFFFF));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        iv.setPadding(dp(17), dp(17), dp(17), dp(17));
        iv.setColorFilter(Color.WHITE);
        f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        f.setContentDescription(name);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(TILE_DP), dp(TILE_DP));
        lp.setMargins(dp(5), 0, dp(5), 0);
        f.setLayoutParams(lp);
        final GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(14));
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(dp(2), Color.WHITE);
        f.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.14f : 1f).scaleY(has ? 1.14f : 1f).setDuration(200)
                    .setInterpolator(new OvershootInterpolator(1.4f)).start();
            f.setForeground(has ? ring : null);
            if (has) showFocusLabel(v, name);
        });
        f.setOnClickListener(v -> press(v, click));
        return f;
    }

    /** App icon tile for the bottom row. */
    View appTile(String pkg) {
        final FrameLayout f = new FrameLayout(this);
        f.setFocusable(true);
        f.setClickable(true);
        final int radius = dp(14);
        f.setBackground(glass(14, 0x2EFFFFFF));
        f.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        f.setClipToOutline(true);
        ImageView iv = new ImageView(this);
        Drawable ic = icon(pkg);
        if (Build.VERSION.SDK_INT >= 26 && ic instanceof android.graphics.drawable.AdaptiveIconDrawable) {
            // Show adaptive icons full-bleed, like a square app tile.
            android.graphics.drawable.AdaptiveIconDrawable a = (android.graphics.drawable.AdaptiveIconDrawable) ic;
            Drawable[] layers = a.getBackground() != null
                    ? new Drawable[]{a.getBackground(), a.getForeground()}
                    : new Drawable[]{a.getForeground()};
            iv.setImageDrawable(new android.graphics.drawable.LayerDrawable(layers));
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            iv.setScaleX(1.45f);
            iv.setScaleY(1.45f);
        } else {
            iv.setImageDrawable(ic);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setPadding(dp(6), dp(6), dp(6), dp(6));
        }
        f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        final GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius);
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(dp(2), Color.WHITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(TILE_DP), dp(TILE_DP));
        lp.setMargins(dp(5), 0, dp(5), 0);
        f.setLayoutParams(lp);
        final String name = appName(pkg);
        f.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.14f : 1f).scaleY(has ? 1.14f : 1f).setDuration(200)
                    .setInterpolator(new OvershootInterpolator(1.4f)).start();
            f.setForeground(has ? ring : null);
            v.setElevation(has ? dp(10) : 0);
            if (has) {
                focusPkg = pkg;
                showFocusLabel(v, name);
            }
        });
        f.setOnClickListener(v -> press(v, () -> launch(pkg, v)));
        f.setOnLongClickListener(v -> {
            appMenu(pkg, v);
            return true;
        });
        return f;
    }

    /** The small name under the focused icon slides along with the focus. */
    void showFocusLabel(View tile, String name) {
        focusLabel.setText(name);
        focusLabel.post(() -> {
            int[] t = new int[2];
            int[] b = new int[2];
            tile.getLocationInWindow(t);
            bottomBar.getLocationInWindow(b);
            // undo the tile's own focus scale when measuring its centre
            float cx = t[0] + tile.getWidth() * tile.getScaleX() / 2f - b[0];
            float x = cx - focusLabel.getWidth() / 2f;
            if (focusLabel.getAlpha() < 0.05f) {
                focusLabel.setTranslationX(x);
                focusLabel.animate().alpha(1f).setDuration(200).start();
            } else {
                focusLabel.animate().translationX(x).alpha(1f).setDuration(220)
                        .setInterpolator(new DecelerateInterpolator(2f)).start();
            }
        });
    }

    void hideFocusLabelIfNeeded() {
        View f = getCurrentFocus();
        boolean inRow = f != null && (f.getParent() == appsRow);
        if (!inRow) focusLabel.animate().alpha(0f).setDuration(150).start();
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
                .setInterpolator(new DecelerateInterpolator(2f)).withLayer().start();
        for (View v : new View[]{pills, moonBtn}) {
            v.setAlpha(0f);
            v.animate().alpha(1f).setStartDelay(200).setDuration(600).start();
        }
    }

    // ---------------------------------------------------------------- clock

    int clockAlpha() {
        return prefs.getInt("clockAlpha", 60);
    }

    void paintClock() {
        if (clock == null) return;
        clock.setAlphaPercent(clockAlpha());
        clock.setAnimStyle(prefs.getInt("clockAnim", 0));
        clock.setGlow(withAlpha(tint, 70));
    }

    void updateTime(boolean animate) {
        boolean h24 = prefs.getBoolean("h24", true);
        String t = new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(new Date());
        clock.setTime(t, animate);
    }

    // ---------------------------------------------------------------- ambient mode

    void scheduleAmbient() {
        handler.removeCallbacks(ambientTask);
        if (prefs.getBoolean("ambient", true) && panel == null && overlay == null) {
            handler.postDelayed(ambientTask, 60_000);
        }
    }

    void enterAmbient() {
        if (panel != null || overlay != null || ambient) return;
        ambient = true;
        bottomBar.animate().alpha(0f).translationY(dp(60)).setDuration(1200)
                .setInterpolator(new AccelerateDecelerateInterpolator()).withLayer().start();
        pills.animate().alpha(0f).setDuration(800).start();
        moonBtn.animate().alpha(0f).setDuration(800).start();
        clockBox.animate().translationY(dp(70)).scaleX(1.06f).scaleY(1.06f).setDuration(1600)
                .setInterpolator(new AccelerateDecelerateInterpolator()).withLayer().start();
    }

    void exitAmbient(boolean animate) {
        ambient = false;
        long d = animate ? 600 : 0;
        bottomBar.animate().alpha(1f).translationY(0).setDuration(d)
                .setInterpolator(new DecelerateInterpolator(2f)).withLayer().start();
        pills.animate().alpha(1f).setDuration(d).start();
        moonBtn.animate().alpha(1f).setDuration(d).start();
        clockBox.animate().translationY(0).scaleX(1f).scaleY(1f).setDuration(d)
                .setInterpolator(new DecelerateInterpolator(2f)).withLayer().start();
        scheduleAmbient();
    }

    // ---------------------------------------------------------------- blur

    /*
     * The old version re-created a full-screen RenderEffect blur on every animation frame,
     * which the Chromecast's small GPU can't keep up with. Now: grab ONE tiny copy of the
     * screen (1/8 size), blur it on the CPU (a couple of milliseconds), and fade that picture
     * in. While it covers the screen, the home screen underneath stops drawing completely.
     */
    void setBlur(boolean on) {
        if (on == blurOn) return;
        blurOn = on;
        if (on) {
            if (pan != null) pan.pause();
            captureBlur();
        } else {
            if (pan != null && !ambient) pan.resume();
            home.setVisibility(View.VISIBLE);
            blurView.animate().cancel();
            blurView.animate().alpha(0f).setDuration(240).withEndAction(() -> {
                if (!blurOn) {
                    blurView.setVisibility(View.GONE);
                    blurView.setImageDrawable(null);
                }
            }).start();
        }
    }

    void captureBlur() {
        int w = Math.max(1, root.getWidth() / 8);
        int h = Math.max(1, root.getHeight() / 8);
        if (root.getWidth() <= 0) {
            showBlur(fallbackBlurSource(w, h));
            return;
        }
        final Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(getWindow(), small, result -> {
                if (!blurOn) return;
                showBlur(result == PixelCopy.SUCCESS ? small : fallbackBlurSource(small.getWidth(), small.getHeight()));
            }, handler);
        } catch (Throwable t) {
            showBlur(fallbackBlurSource(w, h));
        }
    }

    Bitmap fallbackBlurSource(int w, int h) {
        if (currentWp == null) return null;
        return Bitmap.createScaledBitmap(currentWp, Math.max(1, w), Math.max(1, h), true);
    }

    void showBlur(Bitmap src) {
        if (!blurOn) return;
        if (src == null) return;
        if (!src.isMutable() || src.getConfig() != Bitmap.Config.ARGB_8888) {
            src = src.copy(Bitmap.Config.ARGB_8888, true);
        }
        boxBlur(src, 3, 3);
        blurView.setImageBitmap(src);
        blurView.animate().cancel();
        blurView.setAlpha(0f);
        blurView.setVisibility(View.VISIBLE);
        blurView.animate().alpha(1f).setDuration(280).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (blurOn) home.setVisibility(View.INVISIBLE); // stop drawing what's hidden
                }).start();
    }

    /** Fast box blur in place, repeated a few times (looks like a gaussian blur). */
    static void boxBlur(Bitmap b, int radius, int passes) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        int[] tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int p = 0; p < passes; p++) {
            blurLine(px, tmp, w, h, radius, true);
            blurLine(tmp, px, w, h, radius, false);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    static void blurLine(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h;
        int lines = horizontal ? h : w;
        int div = r * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int rs = 0, gs = 0, bs = 0;
            for (int k = -r; k <= r; k++) {
                int c = in[idx(Math.max(0, Math.min(len - 1, k)), line, w, horizontal)];
                rs += (c >> 16) & 0xFF;
                gs += (c >> 8) & 0xFF;
                bs += c & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[idx(i, line, w, horizontal)] = 0xFF000000 | ((rs / div) << 16) | ((gs / div) << 8) | (bs / div);
                int add = in[idx(Math.min(len - 1, i + r + 1), line, w, horizontal)];
                int sub = in[idx(Math.max(0, i - r), line, w, horizontal)];
                rs += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                gs += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                bs += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    static int idx(int i, int line, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }

    void applyClock() {
        clockBox.setVisibility(prefs.getBoolean("clock", true) ? View.VISIBLE : View.INVISIBLE);
        paintClock();
        if (clock != null && !clock.text.isEmpty()) updateTime(false);
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
            if (f.exists()) {
                final boolean animate = wpIndex >= 0;
                wpIndex = -2;
                loadWallpaperAsync(() -> decodeFile(f.getAbsolutePath()), animate);
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
        final int res = WALLPAPERS[idx];
        loadWallpaperAsync(() -> decodeRes(res), animate);
    }

    interface BitmapSource {
        Bitmap get();
    }

    /** Decode + prepare the wallpaper off the UI thread, then swap it in. */
    void loadWallpaperAsync(BitmapSource src, boolean animate) {
        final int token = ++wpToken;
        wpThread.execute(() -> {
            Bitmap b = src.get();
            if (b == null) return;
            if (Build.VERSION.SDK_INT >= 24) b.prepareToDraw();
            Bitmap glass = ClockView.makeGlass(b);
            int t = pickTint(b);
            handler.post(() -> {
                if (token != wpToken || isDestroyed()) return;
                setWallpaperBitmap(b, glass, t, animate);
            });
        });
    }

    void setWallpaperBitmap(Bitmap b, Bitmap glass, int newTint, boolean animate) {
        currentWp = b;
        clock.setGlass(glass, animate);
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
                .withEndAction(() -> {
                    if (front == (aOnTop ? bgA : bgB)) return; // a newer swap is using it
                    front.setAlpha(0f);
                    front.setImageDrawable(null); // free the old picture
                }).start();
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
        clock.setPan(0, 1.12f);
        if (!prefs.getBoolean("pan", true)) return;
        float dx = getResources().getDisplayMetrics().widthPixels * 0.05f;
        pan = ObjectAnimator.ofFloat(panLayer, "translationX", -dx, dx);
        pan.setDuration(40000);
        pan.setRepeatMode(ValueAnimator.REVERSE);
        pan.setRepeatCount(ValueAnimator.INFINITE);
        pan.setInterpolator(new AccelerateDecelerateInterpolator());
        // NOTE: the clock is not told about every pan step any more. Doing that forced the
        // huge clock to be repainted 60 times a second and was the main cause of the lag.
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
            v.setElevation(has ? dp(12) : 0);
            if (Build.VERSION.SDK_INT >= 28) {
                v.setOutlineSpotShadowColor(tint);
                v.setOutlineAmbientShadowColor(tint);
            }
            f.setForeground(has ? ring : null);
            if (has) focusPkg = pkg;
        });
        f.setOnClickListener(v -> press(v, () -> launch(pkg, v)));
        f.setOnLongClickListener(v -> {
            longClick.run();
            return true;
        });
        return f;
    }

    List<String> getDock() {
        String s = prefs.getString(dockKey(), null);
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
        prefs.edit().putString(dockKey(), String.join(",", l)).apply();
    }

    void refreshApps() {
        String keep = focusPkg;
        appsRow.removeAllViews();
        View focusTarget = null;
        int i = 0;
        for (String p : getDock()) {
            if (!installed(p)) continue;
            View t = appTile(p);
            appsRow.addView(t);
            if (p.equals(keep)) focusTarget = t;
            animateTile(t, i++);
        }
        for (View b : new View[]{gearBtn, gridBtn}) {
            if (b.getParent() != null) ((ViewGroup) b.getParent()).removeView(b);
            appsRow.addView(b);
            animateTile(b, i++);
        }
        refreshWatch();
        animateIn = false;
        final View ft = focusTarget;
        appsRow.post(() -> {
            if (overlay != null || panel != null) return;
            if (ft != null) ft.requestFocus();
            else focusFirst();
        });
    }

    void animateTile(View t, int i) {
        if (!animateIn) return;
        t.setAlpha(0f);
        t.setTranslationY(dp(30));
        t.animate().alpha(1f).translationY(0).setStartDelay(260 + i * 45L).setDuration(480)
                .setInterpolator(new DecelerateInterpolator(2f)).withLayer().start();
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
        String cached = nameCache.get(p);
        if (cached != null) return cached;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(p, 0);
            String n = pm.getApplicationLabel(ai).toString();
            nameCache.put(p, n);
            return n;
        } catch (Exception e) {
            return p;
        }
    }

    /** Loads (and caches) a card picture. Safe to call from a background thread. */
    void cardImage(String p) {
        banner(p);
        if (noBanner.contains(p)) icon(p);
    }

    Drawable banner(String p) {
        Drawable.ConstantState cs = bannerCache.get(p);
        if (cs != null) return cs.newDrawable().mutate();
        if (noBanner.contains(p)) return null;
        Drawable d = null;
        try {
            Intent i = pm.getLeanbackLaunchIntentForPackage(p);
            if (i != null) d = pm.getActivityBanner(i);
            if (d == null) d = pm.getApplicationBanner(p);
        } catch (Exception ignored) {
        }
        if (d == null) {
            noBanner.add(p);
            return null;
        }
        if (d.getConstantState() != null) {
            bannerCache.put(p, d.getConstantState());
            return d.getConstantState().newDrawable().mutate();
        }
        return d;
    }

    Drawable icon(String p) {
        Drawable.ConstantState cs = iconCache.get(p);
        if (cs != null) return cs.newDrawable().mutate();
        try {
            Drawable d = pm.getApplicationIcon(p);
            if (d.getConstantState() != null) {
                iconCache.put(p, d.getConstantState());
                return d.getConstantState().newDrawable().mutate();
            }
            return d;
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
        overlay.setBackgroundColor(0x99060A10);
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
            cell.animate().alpha(1f).translationY(0).setStartDelay(200 + Math.min(i, 20) * 25L).setDuration(380)
                    .setInterpolator(new DecelerateInterpolator(2f)).withLayer().start();
            g.addView(cell);
            if (first == null) first = t;
            i++;
        }
        sv.addView(g);
        col.addView(sv, new LinearLayout.LayoutParams(-1, -1));
        overlay.addView(col, new FrameLayout.LayoutParams(-1, -1));
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        setBlur(true);
        handler.removeCallbacks(ambientTask);
        reveal(overlay, anchor, true, null);
        if (first != null) {
            final View f = first;
            overlay.postDelayed(f::requestFocus, 120);
        }
    }

    void closeOverlay(boolean animate) {
        if (overlay == null) return;
        final FrameLayout o = overlay;
        overlay = null;
        if (panel == null) setBlur(false);
        scheduleAmbient();
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
    Runnable panelRebuild;

    /** A frosted side sheet that grows out of the button you pressed. */
    void showPanel(View anchor, String title, List<Item> items) {
        showPanel(anchor, title, items, true, 0);
    }

    void showPanel(View anchor, String title, List<Item> items, boolean animate, int focusIndex) {
        boolean wasOpen = panel != null;
        if (wasOpen) {
            root.removeView(panel);
            panel = null;
        }
        panelAnchor = anchor;
        if (!wasOpen) setBlur(true);
        handler.removeCallbacks(ambientTask);

        panel = new FrameLayout(this);
        panel.setClickable(true);
        View dim = new View(this);
        dim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x22000000, 0x66000000, 0x99000000}));
        panel.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        if (animate && !wasOpen) {
            dim.setAlpha(0f);
            dim.animate().alpha(1f).setDuration(300).start();
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(22), dp(18), dp(18));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xC8262E36, 0xD8161B21, 0xE60E1216});
        bg.setCornerRadius(dp(30));
        bg.setStroke(dp(1), 0x40FFFFFF);
        card.setBackground(bg);
        card.setElevation(dp(24));
        card.setClipToPadding(false);

        // accent line in the wallpaper colour
        View accent = new View(this);
        GradientDrawable ac = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{withAlpha(tint, 255), withAlpha(tint, 0)});
        ac.setCornerRadius(dp(2));
        accent.setBackground(ac);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(56), dp(4));
        alp.setMargins(dp(12), 0, 0, dp(12));
        card.addView(accent, alp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(12), 0, dp(12), dp(14));
        card.addView(t);

        List<View> rows = new ArrayList<>();
        int i = 0;
        for (Item it : items) {
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(13), dp(16), dp(13));
            row.setFocusable(true);
            row.setClickable(true);
            GradientDrawable on = new GradientDrawable();
            on.setCornerRadius(dp(18));
            on.setColor(0xF5FFFFFF);
            StateListDrawable sl = new StateListDrawable();
            sl.addState(new int[]{android.R.attr.state_focused}, on);
            sl.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
            row.setBackground(sl);

            TextView name = new TextView(this);
            name.setText(it.text);
            name.setTextColor(0xF2FFFFFF);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView val = null;
            if (it.value != null) {
                val = new TextView(this);
                val.setText(it.value);
                val.setTextColor(0x99FFFFFF);
                val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                val.setPadding(dp(12), dp(3), dp(12), dp(3));
                GradientDrawable pill = new GradientDrawable();
                pill.setCornerRadius(dp(12));
                pill.setColor(0x1FFFFFFF);
                val.setBackground(pill);
                row.addView(val);
            }
            final TextView fv = val;
            row.setOnFocusChangeListener((v, has) -> {
                name.setTextColor(has ? 0xFF0B0F13 : 0xF2FFFFFF);
                if (fv != null) {
                    fv.setTextColor(has ? 0xFF0B0F13 : 0x99FFFFFF);
                    ((GradientDrawable) fv.getBackground()).setColor(has ? 0x1A000000 : 0x1FFFFFFF);
                }
                v.animate().scaleX(has ? 1.02f : 1f).scaleY(has ? 1.02f : 1f).setDuration(150).start();
            });
            row.setOnClickListener(v -> {
                if (it.stay) {
                    it.action.run();
                    if (panelRebuild != null) panelRebuild.run();
                    if (panel != null && panel.getTag() instanceof List) {
                        @SuppressWarnings("unchecked") List<View> r = (List<View>) panel.getTag();
                        if (index < r.size()) r.get(index).requestFocus();
                    }
                } else {
                    FrameLayout before = panel;
                    it.action.run();
                    if (panel == before) closePanel(false);
                }
            });
            if (animate) {
                row.setAlpha(0f);
                row.setTranslationX(dp(24));
                row.animate().alpha(1f).translationX(0).setStartDelay(140 + i * 30L).setDuration(320)
                        .setInterpolator(new DecelerateInterpolator(2f)).withLayer().start();
            }
            card.addView(row, new LinearLayout.LayoutParams(-1, -2));
            rows.add(row);
            i++;
        }
        panel.setTag(rows);

        ScrollView sv = new ScrollView(this);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(card);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(500), -2, Gravity.END | Gravity.CENTER_VERTICAL);
        lp.setMargins(0, dp(30), dp(60), dp(30));
        panel.addView(sv, lp);
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        if (animate) {
            card.setVisibility(View.INVISIBLE);
            reveal(card, anchor, true, null);
            card.setTranslationX(dp(30));
            card.animate().translationX(0).setDuration(450).setInterpolator(new DecelerateInterpolator(2f)).start();
        }
        if (!rows.isEmpty()) {
            final View f = rows.get(Math.max(0, Math.min(focusIndex, rows.size() - 1)));
            panel.postDelayed(f::requestFocus, 60);
        }
    }

    void closePanel(boolean animate) {
        if (panel == null) return;
        final FrameLayout p = panel;
        panel = null;
        panelRebuild = null;
        if (overlay == null) setBlur(false);
        if (animate) {
            p.animate().alpha(0f).translationX(dp(20)).setDuration(220).withLayer()
                    .withEndAction(() -> root.removeView(p)).start();
        } else {
            root.removeView(p);
        }
        if (panelAnchor != null && panelAnchor.isAttachedToWindow()) panelAnchor.requestFocus();
        else if (overlay == null) focusFirst();
        scheduleAmbient();
    }

    // ---------------------------------------------------------------- settings

    String rotateLabel(int m) {
        return m == 0 ? "Off" : m + " min";
    }

    void showSettings(View anchor) {
        panelRebuild = null;
        showPanel(anchor, "Settings", settingsItems(anchor));
        panelRebuild = () -> {
            int focus = 0;
            View fv = getCurrentFocus();
            if (panel != null && panel.getTag() instanceof List) {
                @SuppressWarnings("unchecked") List<View> r = (List<View>) panel.getTag();
                focus = Math.max(0, r.indexOf(fv));
            }
            Runnable keep = panelRebuild;
            showPanel(anchor, "Settings", settingsItems(anchor), false, focus);
            panelRebuild = keep;
        };
    }

    List<Item> settingsItems(View anchor) {
        boolean panOn = prefs.getBoolean("pan", true);
        boolean showClock = prefs.getBoolean("clock", true);
        boolean h24 = prefs.getBoolean("h24", true);
        boolean amb = prefs.getBoolean("ambient", true);
        int bgChoice = prefs.getInt("bg", -1);
        String wpLabel = bgChoice == -1 ? "Rotating" : bgChoice == 0 ? "Own picture" : WALLPAPER_NAMES[bgChoice - 1];
        List<Item> items = new ArrayList<>();
        items.add(new Item("Wallpaper", wpLabel, false, () -> chooseWallpaper(anchor)));
        items.add(new Item("Change every", rotateLabel(prefs.getInt("rotate", 5)), false, () -> chooseRotate(anchor)));
        items.add(new Item("Next wallpaper", "→", true, () -> {
            prefs.edit().putInt("bg", -1).apply();
            showWallpaper((wpIndex + 1 + WALLPAPERS.length) % WALLPAPERS.length, true);
            prefs.edit().putInt("lastWp", wpIndex).apply();
            scheduleRotate();
        }));
        items.add(new Item("Moving wallpaper", panOn ? "On" : "Off", true, () -> {
            prefs.edit().putBoolean("pan", !panOn).apply();
            startPan();
            if (pan != null) pan.pause();
        }));
        items.add(new Item("Clock", showClock ? "Shown" : "Hidden", true, () -> {
            prefs.edit().putBoolean("clock", !showClock).apply();
            applyClock();
        }));
        items.add(new Item("Clock transparency", (100 - clockAlpha()) + "%", true, () -> {
            int[] lv = {0, 20, 40, 55, 70, 85};
            int cur = 100 - clockAlpha();
            int next = lv[0];
            for (int k = 0; k < lv.length; k++) if (lv[k] == cur) next = lv[(k + 1) % lv.length];
            prefs.edit().putInt("clockAlpha", 100 - next).apply();
            paintClock();
        }));
        int anim = prefs.getInt("clockAnim", 0);
        items.add(new Item("Clock animation", anim == 0 ? "Fade" : "Roll", true, () -> {
            prefs.edit().putInt("clockAnim", anim == 0 ? 1 : 0).apply();
            paintClock();
        }));
        items.add(new Item("Clock format", h24 ? "24h" : "12h", true, () -> {
            prefs.edit().putBoolean("h24", !h24).apply();
            updateTime(true);
        }));
        items.add(new Item("Ambient mode after 1 min", amb ? "On" : "Off", true, () ->
                prefs.edit().putBoolean("ambient", !amb).apply()));
        boolean wx = prefs.getBoolean("weather", true);
        items.add(new Item("Weather", wx ? "On" : "Off", true, () -> {
            prefs.edit().putBoolean("weather", !wx).apply();
            refreshWeather(true);
        }));
        boolean media = hasMediaAccess();
        items.add(new Item("Keep watching titles", media ? "On" : "Set up", true, () -> {
            if (!media) Toast.makeText(this, "Run on your PC: adb shell cmd notification allow_listener "
                    + "se.william.hem/se.william.hem.MediaListener", Toast.LENGTH_LONG).show();
            captureSessions();
            refreshWatch();
        }));
        items.add(new Item("Home button", "Set up", false,
                () -> openSafe(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        items.add(new Item("Android settings", "Open", false, () -> openSafe(new Intent(Settings.ACTION_SETTINGS))));
        items.add(new Item("Reset apps", "Default", true, () -> {
            prefs.edit().remove(dockKey()).apply();
            refreshApps();
        }));
        return items;
    }

    void chooseRotate(View anchor) {
        List<Item> items = new ArrayList<>();
        int cur = prefs.getInt("rotate", 5);
        for (int m : ROTATE_MIN) {
            items.add(new Item(rotateLabel(m), m == cur ? "✓" : null, false, () -> {
                prefs.edit().putInt("rotate", m).apply();
                scheduleRotate();
            }));
        }
        showPanel(anchor, "Change wallpaper every", items);
    }

    void chooseWallpaper(View anchor) {
        int cur = prefs.getInt("bg", -1);
        List<Item> items = new ArrayList<>();
        items.add(new Item("All, rotating", cur == -1 ? "✓" : null, false, () -> {
            prefs.edit().putInt("bg", -1).apply();
            scheduleRotate();
        }));
        for (int i = 0; i < WALLPAPERS.length; i++) {
            final int idx = i;
            items.add(new Item(WALLPAPER_NAMES[i], cur == i + 1 ? "✓" : null, false, () -> {
                prefs.edit().putInt("bg", idx + 1).apply();
                showWallpaper(idx, true);
            }));
        }
        items.add(new Item("Own picture (bakgrund.jpg)", cur == 0 ? "✓" : null, false, () -> {
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

    // ---------------------------------------------------------------- keep watching

    MediaSessionManager.OnActiveSessionsChangedListener sessionListener;

    ComponentName listenerComponent() {
        return new ComponentName(this, MediaListener.class);
    }

    void startSessionWatch() {
        try {
            MediaSessionManager msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            sessionListener = controllers -> {
                if (controllers != null) saveSessions(controllers);
                refreshWatch();
            };
            msm.addOnActiveSessionsChangedListener(sessionListener, listenerComponent(), handler);
        } catch (Throwable ignored) {
            sessionListener = null; // listener not enabled yet: fall back to app cards
        }
    }

    void stopSessionWatch() {
        if (sessionListener == null) return;
        try {
            ((MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE)).removeOnActiveSessionsChangedListener(sessionListener);
        } catch (Throwable ignored) {
        }
    }

    boolean hasMediaAccess() {
        try {
            ((MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE)).getActiveSessions(listenerComponent());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    void captureSessions() {
        try {
            MediaSessionManager msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            saveSessions(msm.getActiveSessions(listenerComponent()));
            if (sessionListener == null) startSessionWatch();
        } catch (Throwable ignored) {
        }
    }

    static String firstText(MediaMetadata md, String... keys) {
        for (String k : keys) {
            CharSequence c = md.getText(k);
            if (c != null && c.toString().trim().length() > 0) return c.toString().trim();
        }
        return null;
    }

    static Bitmap firstBitmap(MediaMetadata md, String... keys) {
        for (String k : keys) {
            Bitmap b = md.getBitmap(k);
            if (b != null && !b.isRecycled()) return b;
        }
        return null;
    }

    File artFile(String pkg) {
        return new File(getFilesDir(), "kw_" + pkg + ".jpg");
    }

    /** Remembers what each app was last playing, newest first. */
    void saveSessions(List<MediaController> list) {
        List<String> order = new ArrayList<>(watchOrder());
        for (int i = list.size() - 1; i >= 0; i--) {
            MediaController mc = list.get(i);
            String pkg = mc.getPackageName();
            if (pkg == null || pkg.equals(getPackageName())) continue;
            MediaMetadata md = mc.getMetadata();
            if (md == null) continue;
            String title = firstText(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
            if (title == null) continue;
            String sub = firstText(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                    MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM);
            String oldTitle = prefs.getString("kw_t_" + pkg, null);
            prefs.edit().putString("kw_t_" + pkg, title)
                    .putString("kw_s_" + pkg, sub == null ? "" : sub)
                    .putLong("kw_at_" + pkg, System.currentTimeMillis()).apply();
            order.remove(pkg);
            order.add(0, pkg);
            Bitmap art = firstBitmap(md, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART,
                    MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            final File f = artFile(pkg);
            if (art != null) {
                float s = Math.min(1f, 360f / Math.max(art.getWidth(), art.getHeight()));
                final Bitmap small = Bitmap.createScaledBitmap(art, Math.max(1, Math.round(art.getWidth() * s)),
                        Math.max(1, Math.round(art.getHeight() * s)), true);
                wpThread.execute(() -> {
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                        small.compress(Bitmap.CompressFormat.JPEG, 88, out);
                    } catch (Exception ignored) {
                    }
                    handler.post(this::refreshWatch);
                });
            } else if (!title.equals(oldTitle)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        while (order.size() > 8) order.remove(order.size() - 1);
        prefs.edit().putString("kw_order", String.join(",", order)).apply();
    }

    List<String> watchOrder() {
        String s = prefs.getString("kw_order", "");
        List<String> l = new ArrayList<>();
        for (String p : s.split(",")) if (!p.isEmpty() && installed(p)) l.add(p);
        return l;
    }

    void refreshWatch() {
        if (watchRow == null) return;
        watchRow.removeAllViews();
        List<String> shown = new ArrayList<>();
        int i = 0;
        for (String pkg : watchOrder()) {
            if (shown.size() >= 3) break;
            String title = prefs.getString("kw_t_" + pkg, null);
            if (title == null) continue;
            Bitmap art = null;
            File f = artFile(pkg);
            if (f.exists()) art = BitmapFactory.decodeFile(f.getAbsolutePath());
            addWatchCard(pkg, appName(pkg).toUpperCase(Locale.ROOT), title, art, i++);
            shown.add(pkg);
        }
        // Not enough history yet: fill with your apps so the row always looks complete.
        for (String pkg : getDock()) {
            if (shown.size() >= 3) break;
            if (shown.contains(pkg) || !installed(pkg)) continue;
            addWatchCard(pkg, "OPEN APP", appName(pkg), null, i++);
            shown.add(pkg);
        }
    }

    void addWatchCard(String pkg, String label, String title, Bitmap art, int index) {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setFocusable(true);
        card.setClickable(true);
        card.setPadding(dp(8), dp(7), dp(14), dp(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0x52101419);
        bg.setStroke(dp(1), 0x2EFFFFFF);
        card.setBackground(bg);

        // thumbnail
        final int r = dp(8);
        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (art != null) thumb.setImageBitmap(art);
        else {
            Drawable ban = banner(pkg);
            thumb.setImageDrawable(ban != null ? ban : icon(pkg));
            if (ban == null) thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }
        thumb.setBackgroundColor(0x33000000);
        thumb.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        thumb.setClipToOutline(true);
        card.addView(thumb, new LinearLayout.LayoutParams(dp(84), -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, 0, 0);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView small = new ImageView(this);
        small.setImageDrawable(icon(pkg));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(12), dp(12));
        sl.setMargins(0, 0, dp(5), 0);
        top.addView(small, sl);
        TextView lab = smallText(10, 0xB3FFFFFF, true);
        lab.setText(label);
        lab.setLetterSpacing(0.08f);
        lab.setShadowLayer(0, 0, 0, 0);
        top.addView(lab);
        col.addView(top);
        TextView t = smallText(15, Color.WHITE, true);
        t.setText(title);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setShadowLayer(0, 0, 0, 0);
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        col.addView(t);
        card.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        final GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(14));
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(dp(2), Color.WHITE);
        card.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(200)
                    .setInterpolator(new OvershootInterpolator(1.2f)).start();
            card.setForeground(has ? ring : null);
            bg.setColor(has ? 0x80202833 : 0x52101419);
        });
        card.setOnClickListener(v -> press(v, () -> launch(pkg, v)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.setMargins(index == 0 ? dp(4) : dp(6), 0, dp(6), 0);
        if (animateIn) {
            card.setAlpha(0f);
            card.setTranslationY(dp(24));
            card.animate().alpha(1f).translationY(0).setStartDelay(120 + index * 80L).setDuration(520)
                    .setInterpolator(new DecelerateInterpolator(2f)).withLayer().start();
        }
        watchRow.addView(card, lp);
    }

    // ---------------------------------------------------------------- date + weather

    String lastDate = "";

    void updateDate() {
        String d = new SimpleDateFormat("EEEE d MMMM", Locale.ENGLISH).format(new Date());
        if (!d.equals(lastDate)) {
            lastDate = d;
            dateText.setText(d);
        }
    }

    static String weatherWord(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorm";
        return "";
    }

    void showWeather() {
        if (!prefs.contains("wxT") || !prefs.getBoolean("weather", true)) {
            weatherIcon.setVisibility(View.GONE);
            weatherText.setVisibility(View.GONE);
            return;
        }
        int code = prefs.getInt("wxC", 3);
        weatherDrawable.set(code, prefs.getInt("wxDay", 1) == 1);
        weatherText.setText(prefs.getInt("wxT", 0) + "°  " + weatherWord(code));
        if (weatherText.getVisibility() != View.VISIBLE) {
            weatherIcon.setVisibility(View.VISIBLE);
            weatherText.setVisibility(View.VISIBLE);
            weatherIcon.setAlpha(0f);
            weatherText.setAlpha(0f);
            weatherIcon.animate().alpha(1f).setDuration(500).start();
            weatherText.animate().alpha(1f).setDuration(500).start();
        }
    }

    void refreshWeather(boolean force) {
        showWeather();
        if (!prefs.getBoolean("weather", true)) return;
        if (!force && System.currentTimeMillis() - prefs.getLong("wxAt", 0) < 30 * 60_000L) return;
        netThread.execute(() -> {
            try {
                if (!prefs.contains("lat")) {
                    double lat = 59.3293, lon = 18.0686; // Stockholm if the lookup fails
                    for (String url : new String[]{"https://ipwho.is/", "https://ipapi.co/json/"}) {
                        try {
                            org.json.JSONObject j = getJson(url);
                            if (j.has("latitude")) {
                                lat = j.getDouble("latitude");
                                lon = j.getDouble("longitude");
                                break;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    prefs.edit().putFloat("lat", (float) lat).putFloat("lon", (float) lon).apply();
                }
                String url = String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code,is_day&timezone=auto",
                        prefs.getFloat("lat", 59.33f), prefs.getFloat("lon", 18.07f));
                org.json.JSONObject cur = getJson(url).getJSONObject("current");
                prefs.edit().putInt("wxT", (int) Math.round(cur.getDouble("temperature_2m")))
                        .putInt("wxC", cur.getInt("weather_code"))
                        .putInt("wxDay", cur.optInt("is_day", 1))
                        .putLong("wxAt", System.currentTimeMillis()).apply();
                handler.post(this::showWeather);
            } catch (Exception ignored) {
            }
        });
    }

    static org.json.JSONObject getJson(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "se.william.hem");
        try (java.io.InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new org.json.JSONObject(bo.toString("UTF-8"));
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- small line icons

    class WeatherDrawable extends Drawable {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int code = 3;
        boolean day = true;

        WeatherDrawable() {
            p.setColor(Color.WHITE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        void set(int code, boolean day) {
            this.code = code;
            this.day = day;
            invalidateSelf();
        }

        Path cloud(float x, float y, float w) {
            Path a = new Path();
            a.addCircle(x + w * 0.30f, y + w * 0.42f, w * 0.20f, Path.Direction.CW);
            Path b = new Path();
            b.addCircle(x + w * 0.56f, y + w * 0.32f, w * 0.26f, Path.Direction.CW);
            Path c = new Path();
            c.addRoundRect(new RectF(x + w * 0.08f, y + w * 0.40f, x + w * 0.92f, y + w * 0.62f), w * 0.11f, w * 0.11f, Path.Direction.CW);
            a.op(b, Path.Op.UNION);
            a.op(c, Path.Op.UNION);
            return a;
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            float x = b.left, y = b.top;
            p.setStrokeWidth(Math.max(1.5f, s * 0.08f));
            boolean clear = code <= 1;
            boolean partly = code == 2;
            boolean rain = (code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95;
            boolean snow = (code >= 71 && code <= 77) || code == 85 || code == 86;
            if (clear || partly) {
                float cx = x + s * (partly ? 0.36f : 0.5f), cy = y + s * (partly ? 0.34f : 0.5f);
                float r = s * (partly ? 0.16f : 0.22f);
                if (day) {
                    c.drawCircle(cx, cy, r, p);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.PI / 4 * i;
                        c.drawLine(cx + (float) Math.cos(a) * r * 1.5f, cy + (float) Math.sin(a) * r * 1.5f,
                                cx + (float) Math.cos(a) * r * 1.95f, cy + (float) Math.sin(a) * r * 1.95f, p);
                    }
                } else {
                    Path m = new Path();
                    m.addCircle(cx, cy, r * 1.3f, Path.Direction.CW);
                    Path cut = new Path();
                    cut.addCircle(cx + r * 0.7f, cy - r * 0.5f, r * 1.1f, Path.Direction.CW);
                    m.op(cut, Path.Op.DIFFERENCE);
                    c.drawPath(m, p);
                }
                if (clear) return;
            }
            float off = partly ? s * 0.12f : 0f;
            float top = rain || snow ? y - s * 0.08f : y + off * 0.6f;
            c.drawPath(cloud(x + off, top, s - off), p);
            if (rain) {
                for (int i = 0; i < 3; i++) {
                    float lx = x + s * (0.32f + 0.18f * i);
                    c.drawLine(lx, y + s * 0.70f, lx - s * 0.06f, y + s * 0.88f, p);
                }
            } else if (snow) {
                Paint dot = new Paint(p);
                dot.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 3; i++) c.drawCircle(x + s * (0.32f + 0.18f * i), y + s * 0.80f, s * 0.05f, dot);
            }
        }

        @Override public int getIntrinsicWidth() { return dp(16); }
        @Override public int getIntrinsicHeight() { return dp(16); }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    class MoreDrawable extends Drawable {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        MoreDrawable() {
            p.setColor(Color.WHITE);
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            float r = s * 0.09f;
            for (int i = -1; i <= 1; i++) c.drawCircle(b.exactCenterX() + i * s * 0.3f, b.exactCenterY(), r, p);
        }

        @Override public int getIntrinsicWidth() { return dp(24); }
        @Override public int getIntrinsicHeight() { return dp(24); }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    class MoonDrawable extends Drawable {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        MoonDrawable() {
            p.setColor(Color.WHITE);
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            Path m = new Path();
            m.addCircle(cx, cy, s * 0.40f, Path.Direction.CW);
            Path cut = new Path();
            cut.addCircle(cx + s * 0.20f, cy - s * 0.16f, s * 0.33f, Path.Direction.CW);
            m.op(cut, Path.Op.DIFFERENCE);
            c.drawPath(m, p);
        }

        @Override public int getIntrinsicWidth() { return dp(24); }
        @Override public int getIntrinsicHeight() { return dp(24); }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
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
