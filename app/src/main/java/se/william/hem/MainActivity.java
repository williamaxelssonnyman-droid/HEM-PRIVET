package se.william.hem;

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
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateDecelerateInterpolator;
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

    SharedPreferences prefs;
    PackageManager pm;
    float density;

    FrameLayout root;
    ImageView bg;
    LinearLayout dock;
    TextView label;
    TextClock clock;
    TextClock date;
    View clockBox;
    FrameLayout overlay;
    ObjectAnimator pan;
    String focusPkg;

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
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (pan != null) pan.cancel();
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

        bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x55000000, 0x10000000, 0x10000000, 0xAA000000}));
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
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextColor(0xEEFFFFFF);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        date.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        date.setShadowLayer(12, 0, 2, 0xAA000000);
        center.addView(date);

        clock = new TextClock(this);
        clock.setTextColor(0xF2FFFFFF);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 170);
        clock.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        clock.setLetterSpacing(-0.02f);
        clock.addOnLayoutChangeListener((v, l, t, r, btm, ol, ot, orr, ob) -> {
            int h = btm - t;
            if (h > 0) clock.getPaint().setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{0xFFFFFFFF, 0xFFE4EEF2, 0xFF9FB4BE},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        });
        clock.setIncludeFontPadding(false);
        clock.setShadowLayer(30, 0, 6, 0x99000000);
        center.addView(clock);

        clockBox = center;
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        clp.topMargin = dp(70);
        content.addView(center, clp);

        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setPadding(dp(8), dp(8), dp(8), dp(8));
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x5578C8B0, 0x332A5A50, 0x44203C38});
        glass.setCornerRadius(dp(26));
        glass.setStroke(dp(3), 0xCCD6FFEE);
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

    // ---------------------------------------------------------------- dock

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
        View grid = tile(new DotsDrawable(), false, "Alla appar", 84, this::showAllApps, null, "#grid");
        dock.addView(grid);
        View gear = tile(new GearDrawable(), false, "Inställningar", 84, this::showSettings, null, "#gear");
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
        items.add("Öppna");
        items.add(in ? "Ta bort från dockan" : "Lägg till i dockan");
        if (in) {
            items.add("Flytta vänster");
            items.add("Flytta höger");
        }
        new AlertDialog.Builder(this)
                .setTitle(appName(pkg))
                .setItems(items.toArray(new String[0]), (dlg, w) -> {
                    String c = items.get(w);
                    int i = d.indexOf(pkg);
                    if (c.equals("Öppna")) {
                        launch(pkg);
                        return;
                    } else if (c.equals("Lägg till i dockan")) {
                        d.add(pkg);
                    } else if (c.equals("Ta bort från dockan")) {
                        d.remove(pkg);
                    } else if (c.equals("Flytta vänster") && i > 0) {
                        Collections.swap(d, i, i - 1);
                    } else if (c.equals("Flytta höger") && i >= 0 && i < d.size() - 1) {
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
            Toast.makeText(this, "Kan inte öppna " + appName(p), Toast.LENGTH_SHORT).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Kan inte öppna " + appName(p), Toast.LENGTH_SHORT).show();
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
        g.setPadding(dp(40), dp(40), dp(40), dp(40));
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
            View t = tile(ban != null ? ban : icon(pkg), ban != null, appName(pkg), 176, () -> {
                closeOverlay();
                launch(pkg);
            }, () -> appMenu(pkg), pkg);
            cell.addView(t);
            TextView n = new TextView(this);
            n.setText(appName(pkg));
            n.setTextColor(0xDDFFFFFF);
            n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            n.setGravity(Gravity.CENTER);
            cell.addView(n);
            g.addView(cell);
            if (first == null) first = t;
        }
        sv.addView(g);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -1, Gravity.CENTER_HORIZONTAL);
        overlay.addView(sv, lp);
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

    void showSettings() {
        boolean panOn = prefs.getBoolean("pan", true);
        boolean h24 = prefs.getBoolean("h24", true);
        boolean showClock = prefs.getBoolean("clock", true);
        String[] items = {
                "Bakgrund",
                "Rörlig bakgrund: " + (panOn ? "På" : "Av"),
                "Klocka: " + (showClock ? "Visas" : "Dold"),
                "Klockformat: " + (h24 ? "24 h" : "12 h"),
                "Hem-knappen (slå på tjänsten Hem)",
                "Android-inställningar",
                "Återställ dockan"
        };
        new AlertDialog.Builder(this)
                .setTitle("Inställningar")
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0:
                            chooseWallpaper();
                            break;
                        case 1:
                            prefs.edit().putBoolean("pan", !panOn).apply();
                            startPan();
                            break;
                        case 2:
                            prefs.edit().putBoolean("clock", !showClock).apply();
                            applyClock();
                            break;
                        case 3:
                            prefs.edit().putBoolean("h24", !h24).apply();
                            applyClock();
                            break;
                        case 4:
                            openSafe(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                            break;
                        case 5:
                            openSafe(new Intent(Settings.ACTION_SETTINGS));
                            break;
                        case 6:
                            prefs.edit().remove("dock").apply();
                            refreshDock();
                            break;
                    }
                })
                .show();
    }

    void openSafe(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Kunde inte öppna inställningarna", Toast.LENGTH_SHORT).show();
        }
    }

    void chooseWallpaper() {
        String[] items = {"Sjö (foto)", "Nattsjö (tecknad)", "Norrsken", "Mörkblå", "Egen bild (bakgrund.jpg)"};
        new AlertDialog.Builder(this)
                .setTitle("Bakgrund")
                .setItems(items, (d, w) -> {
                    int[] map = {4, 1, 2, 3, 0};
                    prefs.edit().putInt("bg", map[w]).apply();
                    applyWallpaper();
                })
                .show();
    }

    void applyWallpaper() {
        File own = new File(getExternalFilesDir(null), "bakgrund.jpg");
        int w = prefs.getInt("bg", own.exists() ? 0 : 4);
        if (w == 0) {
            File f = new File(getExternalFilesDir(null), "bakgrund.jpg");
            Bitmap b = f.exists() ? decode(f.getAbsolutePath()) : null;
            if (b != null) {
                bg.setImageBitmap(b);
                return;
            }
            Toast.makeText(this, "Hittar ingen bakgrund.jpg – använder Sjö (foto)", Toast.LENGTH_LONG).show();
            w = 4;
        }
        bg.setImageResource(w == 4 ? R.drawable.bg_photo : w == 2 ? R.drawable.bg2 : w == 3 ? R.drawable.bg3 : R.drawable.bg1);
    }

    Bitmap decode(String path) {
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

    void applyClock() {
        boolean h24 = prefs.getBoolean("h24", true);
        String f = h24 ? "HH:mm" : "h:mm";
        clock.setFormat24Hour(f);
        clock.setFormat12Hour(f);
        clockBox.setVisibility(prefs.getBoolean("clock", true) ? View.VISIBLE : View.GONE);
    }

    void startPan() {
        if (pan != null) pan.cancel();
        bg.setScaleX(1.12f);
        bg.setScaleY(1.12f);
        bg.setTranslationX(0);
        if (!prefs.getBoolean("pan", true)) return;
        float dx = getResources().getDisplayMetrics().widthPixels * 0.05f;
        pan = ObjectAnimator.ofFloat(bg, "translationX", -dx, dx);
        pan.setDuration(40000);
        pan.setRepeatMode(ValueAnimator.REVERSE);
        pan.setRepeatCount(ValueAnimator.INFINITE);
        pan.setInterpolator(new AccelerateDecelerateInterpolator());
        pan.start();
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
