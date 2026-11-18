package org.ps2recomp.spike;

import android.content.Context;
import android.content.SharedPreferences;

/** The app's settings (SharedPreferences "settings"). Keys: display, res_pct, sharp, controls, opacity, size, dpad, vis_<control id>. */
public final class Settings {
    public static final int DISPLAY_ORIGINAL = 0, DISPLAY_STRETCH = 1, DISPLAY_ZOOM = 2;
    public static final int CONTROLS_AUTO = 0, CONTROLS_TOUCH = 1, CONTROLS_GAMEPAD = 2;
    final SharedPreferences p;

    final int screenLines;  // the screen's short side in pixels (landscape height)

    public Settings(Context c) {
        p = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
        android.util.DisplayMetrics m = c.getResources().getDisplayMetrics();
        screenLines = Math.max(240, Math.min(m.widthPixels, m.heightPixels));
    }

    public int display() { return p.getInt("display", DISPLAY_ORIGINAL); }      // how the picture fills the screen (live)
    /** Internal resolution in percent of the screen's height, 25..100 in steps of 5 (needs a restart). 40 % until the player picks one. */
    public static final int DEFAULT_RES_PCT = 40;
    public int resPct() { return p.getInt("res_pct", DEFAULT_RES_PCT); }
    /** Card texels per PS2 pixel for a percentage: the PS2 picture is 512 lines high; 1/16 steps. */
    public float scaleFor(int pct) {
        float f = pct / 100f * screenLines / 512f;
        f = Math.round(f * 16f) / 16f;
        return Math.max(0.25f, Math.min(4f, f));
    }
    /** Graphics memory's whole scale for it (the picture shown is read from there): the nearest whole number, 1..3. */
    public int memoryScale(float f) { return Math.max(1, Math.min(3, Math.round(f))); }
    public int sharp() { return p.getInt("sharp", 1); }                         // 2D art filter 0 soft, 1 sharp, 2 sharpest (needs a restart)
    public int controls() { return p.getInt("controls", CONTROLS_AUTO); }       // touch overlay: auto / always / never (live)
    public int opacity() { return p.getInt("opacity", 50); }                    // overlay opacity 10..100, 50 = the first look (live)
    public int size() { return p.getInt("size", 100); }                         // overlay size in percent 70..140 (live)
    public boolean dpad() { return p.getBoolean("dpad", false); }
    public boolean fps() { return p.getBoolean("fps", true); }                    // the FPS counter in the top left corner (live)               // left control acts as D-pad instead of analog stick
    /** Default set from art/audit.md: L1, L2, R2 and Select did nothing in the tested race/pause menu, so they start hidden. */
    public boolean visible(String id) { return p.getBoolean("vis_" + id, !(id.equals("l1") || id.equals("l2") || id.equals("r2") || id.equals("select"))); }

    public void put(String key, int v) { p.edit().putInt(key, v).apply(); }
    public void put(String key, boolean v) { p.edit().putBoolean(key, v).apply(); }

    /** Environment of the native side: read once when the game starts. */
    public void exportEnv() {
        try {
            float f = scaleFor(resPct());
            android.system.Os.setenv("PS2_SCALE", String.valueOf(memoryScale(f)), true);
            android.system.Os.setenv("PS2_HWR_SCALE", String.valueOf(f), true);
            android.system.Os.setenv("PS2_SHARP_2D", String.valueOf(sharp()), true);
            android.system.Os.setenv("PS2_DISPLAY_MODE", String.valueOf(display()), true);
        } catch (Exception e) { /* keep the defaults */ }
    }
}
