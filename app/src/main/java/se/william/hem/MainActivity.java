package se.william.hem;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
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

    SharedPreferences prefs;
    PackageManager pm;
    float density;
    final Handler handler = new Handler(Looper.getMainLooper());

    FrameLayout root;
    FrameLayout panLayer;
    ImageView bgA, bgB;
    boolean aOnTop = true;
    int wpIndex = -1;
    LinearLayout dock;
    TextView label;
    TextClock clock;
    TextClock date;
    View clockBox;
    FrameLayout overlay;
    ObjectAnimator pan;
    String focusPkg;
    int tint = 0xFFB8D8E8;
    String lastTime = "";

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
        refreshDock();
        startPan();
        scheduleRotate();
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
        closeOverlay();
        focusPkg = null;
        focusFirst();
    }

    @Override
    public void onBackPressed() {
        // Never move the task to the back: that crashed the system on this device.
        if (overlay != null) closeOverlay();
    }

    int dp(float v) {
        return Math.round(v * density);
    }

    // ---------------------------------------------------------------- UI

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
                new int[]{0x44000000, 0x00000000, 0x22000000, 0xAA000000}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout content = new FrameLayout(this);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        // Old TVs crop the picture edges (overscan): keep everything inside a safe area.
        content.setPadding(dp(48), dp(27), dp(48), dp(27));
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);

        date = new TextClock(this);
        date.setFormat12Hour("EEEE d MMMM");
        date.setFormat24Hour("EEEE d MMMM");
        date.setTextColor(0xE6FFFFFF);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        date.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        date.setShadowLayer(10, 0, 2, 0x99000000);
        center.addView(date);

        clock = new TextClock(this);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 210);
        clock.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        clock.setLetterSpacing(-0.03f);
        clock.setShadowLayer(40, 0, 0, 0x33FFFFFF);
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
        clockLp.topMargin = -dp(14);
        center.addView(clock, clockLp);

        clockBox = center;
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        clp.topMargin = dp(30);
        content.addView(center, clp);

        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setPadding(dp(8), dp(8), dp(8), dp(8));
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x55FFFFFF, 0x22FFFFFF, 0x33FFFFFF});
        glass.setCornerRadius(dp(26));
        glass.setStroke(dp(2), 0xAAFFFFFF);
        dock.setBackground(glass);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        dlp.bottomMargin = dp(46);
        content.addView(dock, dlp);

        label = new TextView(this);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setShadowLayer(10, 0, 2, 0xCC000000);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        llp.bottomMargin = dp(12);
        content.addView(label, llp);

        setContentView(root);
    }

    // ---------------------------------------------------------------- clock

    int clockAlpha() {
        return prefs.getInt("clockAlpha", 75);
    }

    void paintClock() {
        int h = clock.getHeight();
        if (h <= 0) return;
        int a = Math.round(255 * clockAlpha() / 100f);
        int top = withAlpha(mix(tint, Color.WHITE, 0.65f), Math.min(255, a + 30));
        int mid = withAlpha(mix(tint, Color.WHITE, 0.35f), a);
        int bottom = withAlpha(tint, Math.max(40, a - 70));
        clock.getPaint().setShader(new LinearGradient(0, 0, 0, h,
                new int[]{top, mid, bottom}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        clock.invalidate();
    }

    void tickAnimation() {
        clock.setTranslationY(dp(24));
        clock.setAlpha(0f);
        clock.setScaleX(0.96f);
        clock.setScaleY(0.96f);
        clock.animate().translationY(0).alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(900).setInterpolator(new OvershootInterpolator(0.8f)).start();
    }

    void introAnimation() {
        clockBox.setAlpha(0f);
        clockBox.setTranslationY(-dp(20));
        clockBox.animate().alpha(1f).translationY(0).setDuration(1200)
                .setInterpolator(new DecelerateInterpolator()).start();
        dock.setAlpha(0f);
        dock.setTranslationY(dp(30));
        dock.animate().alpha(1f).translationY(0).setStartDelay(250).setDuration(900)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    void applyClock() {
        boolean h24 = prefs.getBoolean("h24", true);
        String f = h24 ? "HH:mm" : "h:mm";
        clock.setFormat24Hour(f);
        clock.setFormat12Hour(f);
        clockBox.setVisibility(prefs.getBoolean("clock", true) ? View.VISIBLE : View.GONE);
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
        hsv[1] = Math.min(0.75f, hsv[1]);
        hsv[2] = Math.max(0.85f, hsv[2]);
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
        Bitmap b = decodeRes(WALLPAPERS[idx]);
        setWallpaperBitmap(b, animate);
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
            introAnimation();
            return;
        }
        back.setImageBitmap(b);
        back.bringToFront();
        back.setAlpha(0f);
        back.animate().alpha(1f).setDuration(2500).withEndAction(() -> front.setAlpha(0f)).start();
        aOnTop = !aOnTop;
        ValueAnimator va = ValueAnimator.ofObject(new ArgbEvaluator(), tint, newTint);
        va.setDuration(2500);
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

    // ---------------------------------------------------------------- tiles & dock

    View tile(Drawable d, boolean banner, String name, int widthDp, Runnable click, Runnable longClick, String pkg) {
        final FrameLayout f = new FrameLayout(this);
        f.setFocusable(true);
        f.setClickable(true);
        final int radius = dp(16);
        GradientDrawable base = new GradientDrawable();
        base.setCornerRadius(radius);
        base.setColor(banner ? 0xFF202020 : 0x40FFFFFF);
        f.setBackground(base);
        f.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        f.setClipToOutline(true);

        ImageView iv = new ImageView(this);
        iv.setImageDrawable(d);
        iv.setScaleType(banner ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        int pad = banner ? 0 : dp(20);
        iv.setPadding(pad, pad, pad, pad);
        f.addView(iv, new FrameLayout.LayoutParams(-1, -1));

        final GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius);
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(dp(3), Color.WHITE);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(widthDp), dp(84));
        lp.setMargins(dp(5), dp(4), dp(5), dp(4));
        f.setLayoutParams(lp);

        f.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(140).start();
            v.setElevation(has ? dp(10) : 0);
            f.setForeground(has ? ring : null);
            if (has) {
                label.setText(name);
                focusPkg = pkg;
            }
        });
        f.setOnClickListener(v -> click.run());
        if (longClick != null) {
            f.setOnLongClickListener(v -> {
                longClick.run();
                return true;
            });
        }
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

    void refreshDock() {
        String keep = focusPkg;
        dock.removeAllViews();
        View focusTarget = null;
        for (String p : getDock()) {
            if (!installed(p)) continue;
            final String pkg = p;
            Drawable ban = banner(pkg);
            View t = tile(ban != null ? ban : icon(pkg), ban != null, appName(pkg), 150,
                    () -> launch(pkg), () -> appMenu(pkg), pkg);
            dock.addView(t);
            if (pkg.equals(keep)) focusTarget = t;
        }
        View grid = tile(new DotsDrawable(), false, "All apps", 84, this::showAllApps, null, "#grid");
        dock.addView(grid);
        View gear = tile(new GearDrawable(), false, "Settings", 84, this::showSettings, null, "#gear");
        dock.addView(gear);
        if ("#grid".equals(keep)) focusTarget = grid;
        if ("#gear".equals(keep)) focusTarget = gear;
        final View ft = focusTarget;
        dock.post(() -> {
            if (overlay != null) return;
            if (ft != null) ft.requestFocus();
            else focusFirst();
        });
    }

    void focusFirst() {
        if (dock != null && dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
    }

    void appMenu(String pkg) {
        final List<String> d = getDock();
        final boolean in = d.contains(pkg);
        List<String> items = new ArrayList<>();
        items.add("Open");
        items.add(in ? "Remove from dock" : "Add to dock");
        if (in) {
            items.add("Move left");
            items.add("Move right");
        }
        new AlertDialog.Builder(this)
                .setTitle(appName(pkg))
                .setItems(items.toArray(new String[0]), (dlg, w) -> {
                    String c = items.get(w);
                    int i = d.indexOf(pkg);
                    if (c.equals("Open")) {
                        launch(pkg);
                        return;
                    } else if (c.equals("Add to dock")) {
                        d.add(pkg);
                    } else if (c.equals("Remove from dock")) {
                        d.remove(pkg);
                    } else if (c.equals("Move left") && i > 0) {
                        Collections.swap(d, i, i - 1);
                    } else if (c.equals("Move right") && i >= 0 && i < d.size() - 1) {
                        Collections.swap(d, i, i + 1);
                    }
                    saveDock(d);
                    focusPkg = pkg;
                    refreshDock();
                })
                .show();
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

    void launch(String p) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(p);
        if (i == null) i = pm.getLaunchIntentForPackage(p);
        if (i == null) {
            Toast.makeText(this, "Can't open " + appName(p), Toast.LENGTH_SHORT).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
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

    void showAllApps() {
        closeOverlay();
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xE6000000);
        ScrollView sv = new ScrollView(this);
        sv.setClipChildren(false);
        GridLayout g = new GridLayout(this);
        g.setColumnCount(5);
        g.setPadding(dp(48), dp(40), dp(48), dp(40));
        g.setClipChildren(false);
        g.setClipToPadding(false);
        View first = null;
        for (String p : allApps()) {
            final String pkg = p;
            Drawable ban = banner(pkg);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setClipChildren(false);
            View t = tile(ban != null ? ban : icon(pkg), ban != null, appName(pkg), 150, () -> {
                closeOverlay();
                launch(pkg);
            }, () -> appMenu(pkg), pkg);
            cell.addView(t);
            TextView n = new TextView(this);
            n.setText(appName(pkg));
            n.setTextColor(0xDDFFFFFF);
            n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            n.setGravity(Gravity.CENTER);
            cell.addView(n);
            g.addView(cell);
            if (first == null) first = t;
        }
        sv.addView(g);
        overlay.addView(sv, new FrameLayout.LayoutParams(-2, -1, Gravity.CENTER_HORIZONTAL));
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        if (first != null) {
            final View f = first;
            overlay.post(f::requestFocus);
        }
    }

    void closeOverlay() {
        if (overlay != null) {
            root.removeView(overlay);
            overlay = null;
            refreshDock();
        }
    }

    // ---------------------------------------------------------------- settings

    String rotateLabel(int m) {
        return m == 0 ? "Off" : m + " min";
    }

    void showSettings() {
        boolean panOn = prefs.getBoolean("pan", true);
        boolean h24 = prefs.getBoolean("h24", true);
        boolean showClock = prefs.getBoolean("clock", true);
        int bgChoice = prefs.getInt("bg", -1);
        String wpLabel = bgChoice == -1 ? "All (rotating)" : bgChoice == 0 ? "Own picture" : WALLPAPER_NAMES[bgChoice - 1];
        String[] items = {
                "Wallpaper: " + wpLabel,
                "Change wallpaper every: " + rotateLabel(prefs.getInt("rotate", 5)),
                "Next wallpaper now",
                "Moving wallpaper: " + (panOn ? "On" : "Off"),
                "Clock: " + (showClock ? "Shown" : "Hidden"),
                "Clock transparency: " + (100 - clockAlpha()) + "%",
                "Clock format: " + (h24 ? "24h" : "12h"),
                "Home button (turn on the Home service)",
                "Android settings",
                "Reset dock"
        };
        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0:
                            chooseWallpaper();
                            break;
                        case 1:
                            chooseRotate();
                            break;
                        case 2:
                            prefs.edit().putInt("bg", -1).apply();
                            showWallpaper((wpIndex + 1 + WALLPAPERS.length) % WALLPAPERS.length, true);
                            prefs.edit().putInt("lastWp", wpIndex).apply();
                            scheduleRotate();
                            break;
                        case 3:
                            prefs.edit().putBoolean("pan", !panOn).apply();
                            startPan();
                            break;
                        case 4:
                            prefs.edit().putBoolean("clock", !showClock).apply();
                            applyClock();
                            break;
                        case 5:
                            chooseTransparency();
                            break;
                        case 6:
                            prefs.edit().putBoolean("h24", !h24).apply();
                            applyClock();
                            break;
                        case 7:
                            openSafe(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                            break;
                        case 8:
                            openSafe(new Intent(Settings.ACTION_SETTINGS));
                            break;
                        case 9:
                            prefs.edit().remove("dock").apply();
                            refreshDock();
                            break;
                    }
                })
                .show();
    }

    void chooseRotate() {
        String[] items = new String[ROTATE_MIN.length];
        for (int i = 0; i < items.length; i++) items[i] = rotateLabel(ROTATE_MIN[i]);
        new AlertDialog.Builder(this)
                .setTitle("Change wallpaper every")
                .setItems(items, (d, w) -> {
                    prefs.edit().putInt("rotate", ROTATE_MIN[w]).apply();
                    scheduleRotate();
                })
                .show();
    }

    void chooseTransparency() {
        final int[] levels = {0, 15, 25, 40, 55, 70};
        String[] items = new String[levels.length];
        for (int i = 0; i < levels.length; i++) items[i] = levels[i] + "%" + (levels[i] == 0 ? " (solid)" : "");
        new AlertDialog.Builder(this)
                .setTitle("Clock transparency")
                .setItems(items, (d, w) -> {
                    prefs.edit().putInt("clockAlpha", 100 - levels[w]).apply();
                    paintClock();
                })
                .show();
    }

    void openSafe(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't open settings", Toast.LENGTH_SHORT).show();
        }
    }

    void chooseWallpaper() {
        String[] items = new String[WALLPAPERS.length + 2];
        items[0] = "All (rotating)";
        for (int i = 0; i < WALLPAPERS.length; i++) items[i + 1] = WALLPAPER_NAMES[i];
        items[items.length - 1] = "Own picture (bakgrund.jpg)";
        new AlertDialog.Builder(this)
                .setTitle("Wallpaper")
                .setItems(items, (d, w) -> {
                    int v = w == 0 ? -1 : (w == items.length - 1 ? 0 : w);
                    prefs.edit().putInt("bg", v).apply();
                    if (v > 0) {
                        showWallpaper(v - 1, true);
                    } else if (v == 0) {
                        applyWallpaper();
                    }
                    scheduleRotate();
                })
                .show();
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
