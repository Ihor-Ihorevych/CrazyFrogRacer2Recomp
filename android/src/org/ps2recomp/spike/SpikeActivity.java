package org.ps2recomp.spike;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Process;
import android.system.Os;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import org.libsdl.app.SDLActivity;

/** Launcher: fixed disc path in the app's own external files folder, live compiling off, immersive fullscreen, touch overlay and settings. */
public class SpikeActivity extends SDLActivity implements SettingsPanel.Host {
    Settings settings;
    TouchOverlay overlay;
    SettingsPanel panel;
    ImageView gear;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        settings = new Settings(this);
        try {
            // there is no compiler on a phone: code the build did not translate stops the game instead
            Os.setenv("PS2_EE_LIVE", "0", true);
            Os.setenv("PS2_VU_LIVE", "0", true);
            Os.setenv("PS2_GPU_BACKEND", "vulkan", true);
            // the hardware renderer (docs/hw-renderer-design.md, M2) draws by default; PS2_RENDERER=exact in env.txt (read below) brings the exact one back
            Os.setenv("PS2_RENDERER", "hw", true);
            // debug hook: extra KEY=VALUE lines from files/env.txt (runtime switches to try without a rebuild)
            java.io.File envFile = new java.io.File(getExternalFilesDir(null), "env.txt");
            if (envFile.exists()) {
                Os.umask(0);  // debug files (recorded VU programs, logs) readable by adb
                for (String line : new String(java.nio.file.Files.readAllBytes(envFile.toPath()), "UTF-8").split("\n")) {
                    int eq = line.indexOf('=');
                    if (eq > 0 && !line.startsWith("#")) Os.setenv(line.substring(0, eq).trim(), line.substring(eq + 1).trim(), true);
                }
            }
        } catch (Exception e) { /* spike */ }
        settings.exportEnv();  // PS2_SCALE, PS2_SHARP_2D, PS2_DISPLAY_MODE: the native side reads them when it starts
        // debug: env.txt wins over the saved settings too (read again after exportEnv)
        try {
            java.io.File again = new java.io.File(getExternalFilesDir(null), "env.txt");
            if (again.exists()) {
                for (String line : new String(java.nio.file.Files.readAllBytes(again.toPath()), "UTF-8").split("\n")) {
                    int eq = line.indexOf('=');
                    if (eq > 0 && !line.startsWith("#")) Os.setenv(line.substring(0, eq).trim(), line.substring(eq + 1).trim(), true);
                }
            }
        } catch (Exception e) { /* debug only */ }
        // the picture goes under the camera cutout too (the cutout sits on a short edge in landscape)
        getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        super.onCreate(savedInstanceState);
        deviceHandler.postDelayed(deviceTick, 3000);
        askForPerformance();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        overlay = new TouchOverlay(this, settings);
        addContentView(overlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // the gear stays when the overlay is hidden (gamepad): the way to the settings
        gear = new ImageView(this);
        int gearRes = getResources().getIdentifier("ic_settings", "drawable", getPackageName());
        if (gearRes != 0) gear.setImageResource(gearRes);
        gear.setAlpha(0.45f);
        int pad = (int) (10 * getResources().getDisplayMetrics().density);
        gear.setPadding(pad, pad, pad, pad);
        gear.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openSettings(); } });
        int size = (int) (48 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
        lp.setMargins(0, (int) (6 * getResources().getDisplayMetrics().density), 0, 0);
        addContentView(gear, lp);
        // the FPS counter, top left (out of the camera cutout)
        float dd = getResources().getDisplayMetrics().density;
        fpsView = new android.widget.TextView(this);
        fpsView.setTextColor(0xFFF2EDE6);
        fpsView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        fpsView.getPaint().setFakeBoldText(true);
        fpsView.setText("-- FPS");
        android.graphics.drawable.GradientDrawable fbg = new android.graphics.drawable.GradientDrawable();
        fbg.setColor(0x991C1814);
        fbg.setCornerRadius(10 * dd);
        fpsView.setBackground(fbg);
        fpsView.setPadding((int) (8 * dd), (int) (3 * dd), (int) (8 * dd), (int) (3 * dd));
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        fl.setMargins((int) (8 * dd), (int) (120 * dd), 0, 0);  // below the L1 / L2 buttons
        addContentView(fpsView, fl);
        fpsView.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets in) {
                android.view.DisplayCutout c = in.getDisplayCutout();
                ((FrameLayout.LayoutParams) v.getLayoutParams()).leftMargin = (int) (8 * dd) + (c == null ? 0 : c.getSafeInsetLeft());
                return in;
            }
        });
        applyFps();
        gear.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets in) {
                android.view.DisplayCutout c = in.getDisplayCutout();
                ((FrameLayout.LayoutParams) v.getLayoutParams()).rightMargin = c == null ? 0 : c.getSafeInsetRight();
                return in;
            }
        });
    }

    @Override
    protected String[] getLibraries() { return new String[] { "c++_shared", "SDL2", "main" }; }

    @Override
    protected String[] getArguments() {
        String dir = getExternalFilesDir(null).getAbsolutePath();
        java.util.ArrayList<String> args = new java.util.ArrayList<>(java.util.Arrays.asList("--disc", dir + "/disc.iso", "--window", "--memcard", dir + "/mc.ps2"));
        // debug: a race.state in the app folder is loaded at start, so every test starts in the same place (quick save F5 files work too)
        java.io.File race = new java.io.File(dir, "race.state");
        if (race.exists()) {
            args.add("--load-state");
            args.add(race.getAbsolutePath());
        }
        return args.toArray(new String[0]);
    }

    /** Landscape only: SDL must never rotate the activity to portrait (its orientation hint is forced to landscape too). */
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        super.setOrientationBis(w, h, resizable, "LandscapeLeft LandscapeRight");
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    void openSettings() {
        if (panel != null && panel.getParent() != null) return;
        panel = new SettingsPanel(this, settings, this);
        addContentView(panel, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (panel != null && panel.getParent() != null && e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) panel.close();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // PS2_PROF: the system's own view of heat and power once a second, into the profiler's output (so the log shows throttling directly)
    private final android.os.Handler deviceHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable deviceTick = new Runnable() {
        @Override
        public void run() {
            try {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                // debug: recorded VU programs are written readable only by the app; make them readable for adb pull
                for (String d : new String[] { "vu1rec", "vu0rec" }) {
                    java.io.File[] files = new java.io.File(getExternalFilesDir(null), d).listFiles();
                    if (files != null) for (java.io.File f : files) try { Os.chmod(f.getAbsolutePath(), 0666); } catch (Exception e) { /* debug only */ }
                }
                Intent battery = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                int temp = battery != null ? battery.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) : -1;
                int plugged = battery != null ? battery.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) : -1;
                String text = "thermal status " + pm.getCurrentThermalStatus() + " (0 none, 1 light, 2 moderate, 3 severe, 4 critical)"
                        + ", headroom " + pm.getThermalHeadroom(10) + " (1.0 = throttling starts)"
                        + ", display " + getWindowManager().getDefaultDisplay().getRefreshRate() + " Hz"
                        + ", battery " + (temp / 10.0) + " C, plugged " + plugged + ", power saver " + pm.isPowerSaveMode();
                nativeDeviceNote(text);
            } catch (Throwable e) { /* native side not up yet, or no such call */ }
            deviceHandler.postDelayed(this, 1000);
        }
    };

    private static native void nativeDeviceNote(String text);
    private static native float[] nativeFps();  // { pictures per second, the console's rate }
    android.widget.TextView fpsView;
    // the FPS counter: twice a second while it is shown
    private final Runnable fpsTick = new Runnable() {
        @Override
        public void run() {
            try {
                float[] f = nativeFps();
                if (f[0] > 0) fpsView.setText(String.format(java.util.Locale.US, "%.0f FPS  %d%%", f[0], Math.round(f[0] * 100f / f[1])));
            } catch (Throwable e) { /* the native side is not up yet */ }
            if (settings.fps()) deviceHandler.postDelayed(this, 500);
        }
    };

    void applyFps() {
        deviceHandler.removeCallbacks(fpsTick);
        boolean on = settings.fps();
        fpsView.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) deviceHandler.post(fpsTick);
    }
    private static native int[] nativeThreadIds();

    // ---- asking Android for performance (PS2_ANDROID_PERF=0 in env.txt turns all of it off, to compare) ----
    private android.os.PerformanceHintManager.Session hintSession;
    private int hintThreadCount = -1;
    private final Runnable perfTick = new Runnable() {
        @Override
        public void run() {
            try {
                int[] tids = nativeThreadIds();
                if (tids.length > 0 && tids.length != hintThreadCount) {
                    // a thread joined since the last session: make a new one over all the heavy threads
                    if (hintSession != null) hintSession.close();
                    android.os.PerformanceHintManager phm = getSystemService(android.os.PerformanceHintManager.class);
                    hintSession = phm.createHintSession(tids, 20_000_000L);  // a picture every 20 ms (50 Hz)
                    hintThreadCount = tids.length;
                    android.util.Log.i("ps2perf", "hint session over " + tids.length + " threads: " + (hintSession != null));
                }
                // work took longer than the target: the system raises the processor frequency for those threads
                if (hintSession != null) hintSession.reportActualWorkDuration(30_000_000L);
            } catch (Throwable e) { /* no such service, or the native side is not up yet */ }
            deviceHandler.postDelayed(this, 40);
        }
    };

    private void askForPerformance() {
        String off = null;
        try { off = Os.getenv("PS2_ANDROID_PERF"); } catch (Throwable e) { /* none */ }
        if (off != null && off.equals("0")) return;
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm.isSustainedPerformanceModeSupported()) getWindow().setSustainedPerformanceMode(true);
            android.util.Log.i("ps2perf", "sustained performance supported " + pm.isSustainedPerformanceModeSupported());
        } catch (Throwable e) { /* none */ }
        try {
            // the game state API (Android 13+): tells the system this is gameplay that must not be interrupted or scaled back
            android.app.GameManager gm = getSystemService(android.app.GameManager.class);
            gm.setGameState(new android.app.GameState(false, android.app.GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE));
            android.util.Log.i("ps2perf", "game mode " + gm.getGameMode());
        } catch (Throwable e) { /* none */ }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        deviceHandler.postDelayed(perfTick, 4000);
    }

    @Override
    public void settingsChanged(boolean live) {
        overlay.applySettings();
        applyFps();
        try { TouchOverlay.nativeDisplayMode(settings.display()); } catch (UnsatisfiedLinkError e) { /* native side not up yet */ }
    }

    /** Relaunches the app in a fresh process (the resolution and the filter are read when the native side starts). */
    @Override
    public void restartGame() {
        Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);
        // an alarm-clock alarm may start an activity from the background (Android 10+ blocks other ways) and needs no permission
        long at = System.currentTimeMillis() + 500;
        ((AlarmManager) getSystemService(ALARM_SERVICE)).setAlarmClock(new AlarmManager.AlarmClockInfo(at, pi), pi);
        finishAndRemoveTask();
        Process.killProcess(Process.myPid());
    }
}
