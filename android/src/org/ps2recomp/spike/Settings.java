package org.ps2recomp.spike;

import android.content.Context;
import android.content.SharedPreferences;

/** The app's settings (SharedPreferences "settings"). Keys: display, res_pct, wide, sharp, controls, opacity, size, dpad, vis_<control id>. */
public final class Settings {
    public static final int DISPLAY_ORIGINAL = 0, DISPLAY_STRETCH = 1, DISPLAY_ZOOM = 2;
    public static final int CONTROLS_AUTO = 0, CONTROLS_TOUCH = 1, CONTROLS_GAMEPAD = 2;
    final SharedPreferences p;

    final int screenLines;  // the screen's short side in pixels (landscape height)
    final int screenWidth;  // the long side (landscape width)

    public Settings(Context c) {
        p = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
        android.util.DisplayMetrics m = c.getResources().getDisplayMetrics();
        android.graphics.Point real = new android.graphics.Point();  // the whole screen, without taking the system bars away
        try {
            ((android.view.WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealSize(real);
        } catch (Exception e) { real.set(m.widthPixels, m.heightPixels); }
        screenLines = Math.max(240, Math.min(real.x, real.y));
        screenWidth = Math.max(screenLines, Math.max(real.x, real.y));
    }

    public int display() { return p.getInt("display", DISPLAY_ORIGINAL); }      // how the picture fills the screen (live)
    /** Internal resolution in percent of the screen's own pixels, 25..100 in steps of 5 (needs a restart): 100 % draws the game with one
     *  texel per screen pixel. 40 % until the player picks one. */
    public static final int DEFAULT_RES_PCT = 40;
    public int resPct() { return p.getInt("res_pct", DEFAULT_RES_PCT); }
    /** Widescreen: the game's camera widened to the screen's shape (PS2_ASPECT, the aspect knobs of the build; needs a restart). On until
     *  the player turns it off. */
    public boolean wide() { return p.getBoolean("wide", true); }
    /** The picture's width on the screen in pixels: the whole width when widescreen, else 4:3 of the height. */
    public int pictureWidth() { return wide() ? screenWidth : Math.min(screenWidth, Math.round(screenLines * 4f / 3f)); }
    /** Card texels per PS2 pixel across and down for a percentage. The game draws 512 x 256 (one field, shown on all lines), so the picture
     *  needs width / 512 across and height / 256 down to have the screen's pixels. */
    public float scaleX(int pct) { return clampScale(pct / 100f * pictureWidth() / 512f); }
    public float scaleY(int pct) { return clampScale(pct / 100f * screenLines / 256f); }
    static float clampScale(float f) { return Math.max(0.25f, Math.min(6f, f)); }
    public int pixelsX(int pct) { return Math.round(512 * scaleX(pct)); }
    public int pixelsY(int pct) { return Math.round(256 * scaleY(pct)); }
    public int sharp() { return p.getInt("sharp", 1); }                         // 2D art filter 0 soft, 1 sharp, 2 sharpest (needs a restart)
    public int controls() { return p.getInt("controls", CONTROLS_AUTO); }       // touch overlay: auto / always / never (live)
    public int opacity() { return p.getInt("opacity", 50); }                    // overlay opacity 10..100, 50 = the first look (live)
    public int size() { return p.getInt("size", 100); }                         // overlay size in percent 70..140 (live)
    public boolean dpad() { return p.getBoolean("dpad", false); }
    /** Graphics API (needs a restart): true = OpenGL ES 3.1+ (the default), false = Vulkan (OpenGL ES only when the phone has no
     *  working Vulkan). */
    public boolean gles() { return p.getBoolean("gles", true); }
    public boolean fps() { return p.getBoolean("fps", true); }                    // the FPS counter in the top left corner (live)               // left control acts as D-pad instead of analog stick
    /** Default set from art/audit.md: L1, L2, R2 and Select did nothing in the tested race/pause menu, so they start hidden. */
    public boolean visible(String id) { return p.getBoolean("vis_" + id, !(id.equals("l1") || id.equals("l2") || id.equals("r2") || id.equals("select"))); }

    public void put(String key, int v) { p.edit().putInt(key, v).apply(); }
    /** A control moved by the player (TouchOverlay's edit mode): its centre as a fraction of the screen's width and height, or null when it
     *  keeps its place in the default layout. Keys posx_<id>, posy_<id> in 1/10000. */
    public float[] position(String id) {
        if (!p.contains("posx_" + id)) return null;
        return new float[] {p.getInt("posx_" + id, 0) / 10000f, p.getInt("posy_" + id, 0) / 10000f};
    }
    public void putPosition(String id, float x, float y) {
        p.edit().putInt("posx_" + id, Math.round(x * 10000)).putInt("posy_" + id, Math.round(y * 10000)).apply();
    }
    /** Every control back to the default layout. */
    public void resetPositions() {
        android.content.SharedPreferences.Editor e = p.edit();
        for (String k : p.getAll().keySet()) if (k.startsWith("posx_") || k.startsWith("posy_")) e.remove(k);
        e.apply();
    }
    public void put(String key, boolean v) { p.edit().putBoolean(key, v).apply(); }

    /** Environment of the native side: read once when the game starts. */
    public void exportEnv() {
        try {
            android.system.Os.setenv("PS2_SCALE", "1", true);  // graphics memory stays 1x: the hw renderer's targets are drawn at PS2_HWR_SCALE and shown straight from there
            android.system.Os.setenv("PS2_HWR_SCALE", String.valueOf(scaleX(resPct())), true);
            android.system.Os.setenv("PS2_HWR_SCALE_Y", String.valueOf(scaleY(resPct())), true);
            // the rows the picture fills at this resolution: no frame target is drawn with more card rows than that (hwr g_rows)
            android.system.Os.setenv("PS2_HWR_ROWS", String.valueOf(Math.round(screenLines * resPct() / 100f)), true);
            if (wide()) android.system.Os.setenv("PS2_ASPECT", screenWidth + ":" + screenLines, true);
            else android.system.Os.unsetenv("PS2_ASPECT");
            android.system.Os.setenv("PS2_SHARP_2D", String.valueOf(sharp()), true);
            android.system.Os.setenv("PS2_DISPLAY_MODE", String.valueOf(display()), true);
            // unset: Vulkan, falling back to OpenGL ES (gpu.cpp init); "gl" asks for OpenGL ES only
            if (gles()) android.system.Os.setenv("PS2_GPU_BACKEND", "gl", true);
            else android.system.Os.unsetenv("PS2_GPU_BACKEND");
        } catch (Exception e) { /* keep the defaults */ }
    }
}
