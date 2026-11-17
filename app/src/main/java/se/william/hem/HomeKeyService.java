package se.william.hem;

import android.accessibilityservice.AccessibilityService;
import android.app.SearchManager;
import android.content.ComponentName;
import android.net.Uri;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 1. Makes the remote's Home button open Hem, without disabling Google's own home screen
 *    (disabling it made the system crash on this Chromecast).
 * 2. Runs the phone remote (see RemoteServer). Because this is an accessibility service it
 *    keeps running while other apps are open, and it can press D-pad keys in any app.
 */
public class HomeKeyService extends AccessibilityService {

    private final Handler main = new Handler(Looper.getMainLooper());
    private RemoteServer remote;
    private TextView codeOverlay;
    private final Map<String, byte[]> iconCache = new ConcurrentHashMap<>();

    static final List<String> FAVORITES = Arrays.asList(
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.formulaone.production",
            "se.svt.android.svtplay");

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        remote = new RemoteServer(this, this::handle);
        remote.start();
    }

    @Override
    public void onDestroy() {
        if (remote != null) remote.stop();
        hideCode();
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (!openHem()) return false;
            }
            return true;
        }
        return false;
    }

    private boolean openHem() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    // ================================================================ remote

    private RemoteServer.Response handle(String method, String path, Map<String, String> q, byte[] body) {
        try {
            if (path.equals("/") || path.equals("/static/index.html")) {
                return RemoteServer.Response.of("text/html; charset=utf-8", asset("remote/index.html"));
            }
            if (path.equals("/static/manifest.json")) {
                return RemoteServer.Response.of("application/manifest+json", asset("remote/manifest.json"));
            }
            if (path.equals("/static/icon.png")) {
                return RemoteServer.Response.of("image/png", asset("remote/icon.png"));
            }
            if (path.equals("/pair/start")) {
                String code = remote.newPairCode();
                main.post(() -> showCode(code));
                return RemoteServer.Response.json("{\"ok\":true}");
            }
            if (path.equals("/hello")) {
                main.post(this::hideCode);
                return RemoteServer.Response.json("{\"ok\":true,\"name\":\"" + esc(Build.MODEL) + "\"}");
            }
            if (path.startsWith("/key/")) {
                boolean ok = key(path.substring(5));
                return RemoteServer.Response.json("{\"ok\":" + ok + "}");
            }
            if (path.equals("/text")) {
                String text = new String(body, StandardCharsets.UTF_8);
                boolean ok = runOnMain(() -> typeText(text, "1".equals(q.get("enter"))));
                return RemoteServer.Response.json("{\"ok\":" + ok + "}");
            }
            if (path.equals("/launch")) {
                String p = q.get("p");
                boolean ok = runOnMain(() -> launch(p));
                return RemoteServer.Response.json("{\"ok\":" + ok + "}");
            }
            if (path.equals("/apps")) {
                return RemoteServer.Response.json(appsJson());
            }
            if (path.equals("/icon")) {
                byte[] png = icon(q.get("p"));
                return png == null ? null : RemoteServer.Response.of("image/png", png);
            }
            if (path.equals("/ask")) {
                String text = new String(body, StandardCharsets.UTF_8);
                final String[] msg = {""};
                runOnMain(() -> {
                    msg[0] = ask(text);
                    return true;
                });
                return RemoteServer.Response.json("{\"msg\":\"" + esc(msg[0]) + "\"}");
            }
            if (path.equals("/now")) {
                return RemoteServer.Response.json(nowPlaying());
            }
        } catch (Exception e) {
            return RemoteServer.Response.error(500, esc(String.valueOf(e.getMessage())));
        }
        return null;
    }

    /** Presses one remote button. Works in every app since Android 13 (this TV runs 14). */
    private boolean key(String name) {
        switch (name) {
            case "up":
                return dpad(16 /* GLOBAL_ACTION_DPAD_UP */);
            case "down":
                return dpad(17 /* GLOBAL_ACTION_DPAD_DOWN */);
            case "left":
                return dpad(18 /* GLOBAL_ACTION_DPAD_LEFT */);
            case "right":
                return dpad(19 /* GLOBAL_ACTION_DPAD_RIGHT */);
            case "ok":
                return dpad(20 /* GLOBAL_ACTION_DPAD_CENTER */);
            case "back":
                return performGlobalAction(GLOBAL_ACTION_BACK);
            case "home":
                return runOnMain(this::openHem);
            case "google_home":
                return performGlobalAction(GLOBAL_ACTION_HOME);
            case "settings":
                return runOnMain(() -> start(new Intent(Settings.ACTION_SETTINGS)));
            case "playpause":
                return media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            case "next":
                return media(KeyEvent.KEYCODE_MEDIA_NEXT);
            case "prev":
                return media(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            case "ff":
                return media(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
            case "rew":
                return media(KeyEvent.KEYCODE_MEDIA_REWIND);
            case "volup":
                return volume(AudioManager.ADJUST_RAISE);
            case "voldown":
                return volume(AudioManager.ADJUST_LOWER);
            case "mute":
                return volume(AudioManager.ADJUST_TOGGLE_MUTE);
            default:
                return false;
        }
    }

    private boolean dpad(int action) {
        if (Build.VERSION.SDK_INT < 33) return false;
        return performGlobalAction(action);
    }

    private boolean media(int code) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now + 20, KeyEvent.ACTION_UP, code, 0));
        return true;
    }

    private boolean volume(int direction) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        am.adjustSuggestedStreamVolume(direction, AudioManager.STREAM_MUSIC, AudioManager.FLAG_SHOW_UI);
        return true;
    }

    private boolean start(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean launch(String p) {
        if (p == null) return false;
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(p);
        if (i == null) i = pm.getLaunchIntentForPackage(p);
        return i != null && start(i);
    }

    /** Puts text into the text field that has focus on the TV (search boxes etc.). */
    private boolean typeText(String text, boolean enter) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (field == null || !field.isEditable()) field = findEditable(root, 0);
        if (field == null) return false;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (ok && enter && Build.VERSION.SDK_INT >= 30) {
            field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        }
        return ok;
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 30) return null;
        if (n.isEditable() && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findEditable(n.getChild(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    private String appsJson() {
        PackageManager pm = getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
        List<String[]> apps = new ArrayList<>();
        for (ResolveInfo r : list) {
            String p = r.activityInfo.packageName;
            if (p.equals(getPackageName())) continue;
            apps.add(new String[]{p, String.valueOf(r.loadLabel(pm))});
        }
        Collections.sort(apps, (a, b) -> {
            int fa = FAVORITES.indexOf(a[0]), fb = FAVORITES.indexOf(b[0]);
            if (fa < 0) fa = 99;
            if (fb < 0) fb = 99;
            if (fa != fb) return fa - fb;
            return a[1].compareToIgnoreCase(b[1]);
        });
        StringBuilder sb = new StringBuilder("{\"apps\":[");
        for (int k = 0; k < apps.size(); k++) {
            if (k > 0) sb.append(',');
            sb.append("{\"p\":\"").append(esc(apps.get(k)[0]))
                    .append("\",\"name\":\"").append(esc(apps.get(k)[1]))
                    .append("\",\"fav\":").append(FAVORITES.contains(apps.get(k)[0])).append('}');
        }
        return sb.append("]}").toString();
    }

    private byte[] icon(String p) {
        if (p == null) return null;
        byte[] cached = iconCache.get(p);
        if (cached != null) return cached;
        try {
            Drawable d = getPackageManager().getApplicationIcon(p);
            int size = 144;
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
            byte[] png = out.toByteArray();
            iconCache.put(p, png);
            return png;
        } catch (Exception e) {
            return null;
        }
    }

    /** What is playing right now (needs the MediaListener to be allowed, same as "Keep watching"). */
    private String nowPlaying() {
        try {
            MediaSessionManager msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            List<MediaController> sessions =
                    msm.getActiveSessions(new ComponentName(this, MediaListener.class));
            for (MediaController mc : sessions) {
                MediaMetadata md = mc.getMetadata();
                PlaybackState st = mc.getPlaybackState();
                String title = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_TITLE);
                if (title == null || title.isEmpty()) continue;
                String sub = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (sub == null) sub = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
                boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
                PackageManager pm = getPackageManager();
                String appName = mc.getPackageName();
                try {
                    appName = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(mc.getPackageName(), 0)));
                } catch (Exception ignored) {
                }
                return "{\"title\":\"" + esc(title) + "\",\"sub\":\"" + esc(sub == null ? "" : sub)
                        + "\",\"app\":\"" + esc(appName) + "\",\"p\":\"" + esc(mc.getPackageName())
                        + "\",\"playing\":" + playing + "}";
            }
        } catch (Exception ignored) {
            // listener not allowed yet
        }
        return "{}";
    }

    // ================================================================ "ask": free command bar, no AI service

    private static final String[] VERBS = {
            "kan du ", "jag vill se ", "jag vill kolla på ", "sätt på ", "titta på ", "kolla på ",
            "starta ", "öppna ", "spela ", "kör ", "visa ", "sök efter ", "sök på ", "sök ",
            "please ", "put on ", "watch ", "play ", "open ", "start ", "launch ", "search for ", "search "};

    /**
     * Understands simple sentences like "starta spiderman", "spela f1 på youtube", "öppna netflix".
     * A title goes to Google TV's own search, which knows which of your subscriptions has it.
     */
    private String ask(String raw) {
        String q = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[.!?]+$", "").trim();
        boolean again = true;
        while (again) {
            again = false;
            for (String v : VERBS) {
                if (q.startsWith(v)) {
                    q = q.substring(v.length()).trim();
                    again = true;
                }
            }
        }
        if (q.isEmpty()) return "Say what you want to watch, like “start Spider-Man”.";

        // "... på netflix" / "... on youtube"
        String targetPkg = null, targetName = null;
        for (String sep : new String[]{" på ", " on ", " i ", " in "}) {
            int i = q.lastIndexOf(sep);
            if (i > 0) {
                String[] app = findApp(q.substring(i + sep.length()).trim());
                if (app != null) {
                    targetPkg = app[0];
                    targetName = app[1];
                    q = q.substring(0, i).trim();
                    break;
                }
            }
        }

        // Just an app name: open it.
        if (targetPkg == null) {
            String[] app = findApp(q);
            if (app != null) {
                return launch(app[0]) ? "Opening " + app[1] + "." : "Couldn’t open " + app[1] + ".";
            }
        }

        String title = q;
        if (targetPkg != null && targetPkg.equals("com.google.android.youtube.tv")) {
            Intent yt = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(title)));
            yt.setPackage(targetPkg);
            if (start(yt)) return "Searching YouTube for “" + title + "”.";
        }
        if (targetPkg != null && targetPkg.equals("com.netflix.ninja")) {
            Intent nf = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.netflix.com/search?q=" + Uri.encode(title)));
            nf.setPackage(targetPkg);
            if (start(nf)) return "Searching Netflix for “" + title + "”.";
        }

        // Google TV's search finds the title across your services and offers "Watch on …".
        Intent gs = new Intent("android.search.action.GLOBAL_SEARCH");
        gs.putExtra(SearchManager.QUERY, title);
        if (start(gs)) {
            return "Looking for “" + title + "” on your TV"
                    + (targetName != null ? " (pick " + targetName + " in the results)." : ".");
        }
        Intent s2 = new Intent(Intent.ACTION_SEARCH);
        s2.putExtra(SearchManager.QUERY, title);
        s2.setPackage("com.google.android.katniss");
        if (start(s2)) return "Looking for “" + title + "” on your TV.";
        if (targetPkg != null && launch(targetPkg)) return "Opened " + targetName + ". Search for “" + title + "” there.";
        return "Couldn’t start a search on the TV.";
    }

    /** Finds an installed TV app by (part of) its name, e.g. "f1", "svt", "yt". Returns {package, label}. */
    private String[] findApp(String name) {
        String n = name.toLowerCase(Locale.ROOT).replace(" ", "");
        if (n.isEmpty()) return null;
        if (n.equals("yt")) n = "youtube";
        if (n.equals("f1") || n.equals("formel1") || n.equals("formula1")) n = "f1tv";
        PackageManager pm = getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        String[] best = null;
        for (ResolveInfo r : pm.queryIntentActivities(i, 0)) {
            String label = String.valueOf(r.loadLabel(pm));
            String l = label.toLowerCase(Locale.ROOT).replace(" ", "");
            if (l.equals(n)) return new String[]{r.activityInfo.packageName, label};
            if (best == null && n.length() >= 3 && l.startsWith(n)) best = new String[]{r.activityInfo.packageName, label};
        }
        return best;
    }

    // ================================================================ pairing code on the TV

    private void showCode(String code) {
        hideCode();
        TextView tv = new TextView(this);
        tv.setText("Phone remote\n" + code);
        tv.setTextColor(0xFFFFFFFF);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        tv.setLineSpacing(0, 1.1f);
        tv.setLetterSpacing(0.08f);
        int pad = (int) (36 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad * 2, pad, pad * 2, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6101418);
        bg.setCornerRadius(pad);
        bg.setStroke(2, 0x40FFFFFF);
        tv.setBackground(bg);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(tv, lp);
            codeOverlay = tv;
            main.postDelayed(this::hideCode, 120_000);
        } catch (Exception ignored) {
        }
    }

    private void hideCode() {
        if (codeOverlay == null) return;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(codeOverlay);
        } catch (Exception ignored) {
        }
        codeOverlay = null;
    }

    // ================================================================ helpers

    interface BoolTask {
        boolean run();
    }

    /** Runs on the UI thread and waits for the result (accessibility calls must be made there). */
    private boolean runOnMain(BoolTask task) {
        final boolean[] result = {false};
        CountDownLatch done = new CountDownLatch(1);
        main.post(() -> {
            try {
                result[0] = task.run();
            } catch (Exception ignored) {
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return result[0];
    }

    private byte[] asset(String name) throws Exception {
        AssetManager am = getAssets();
        try (InputStream in = am.open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }
}
