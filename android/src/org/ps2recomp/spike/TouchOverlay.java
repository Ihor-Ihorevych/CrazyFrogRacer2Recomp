package org.ps2recomp.spike;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.DisplayCutout;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

/**
 * Translucent on-screen pad: left stick (or D-pad), face buttons, shoulders, Start/Select. Multi-touch, feeds the native pad.
 * Every control is one entry of CONTROLS (id, label, pad bit, layout, icon): its visibility comes from Settings ("vis_<id>") and its icon
 * from the drawable named ic_<icon> when that resource exists (else the label is drawn), so changing the look is a data change.
 */
public class TouchOverlay extends View implements InputManager.InputDeviceListener {
    static native void nativeSet(int buttons, int lx, int ly);
    static native void nativeDisplayMode(int mode);

    // PS2 pad bits, as the Button enum of host/window.cpp
    static final int SELECT = 1, START = 1 << 3, UP = 1 << 4, RIGHT = 1 << 5, DOWN = 1 << 6, LEFT = 1 << 7,
        L2 = 1 << 8, R2 = 1 << 9, L1 = 1 << 10, R1 = 1 << 11, TRI = 1 << 12, CIRCLE = 1 << 13, CROSS = 1 << 14, SQUARE = 1 << 15;
    static final long HOLD_MS = 90;  // a tap (adb input tap lasts a few ms) must outlast the game's frame poll

    /** One control definition. Layout in dp relative to an anchor: ax/ay = 0 left/top, 1 right/bottom. */
    static final class Def {
        final String id, title, label, icon; final int mask; final boolean round; final float ax, ay, dx, dy, w, h;
        Def(String id, String title, String label, String icon, int mask, boolean round, float ax, float ay, float dx, float dy, float w, float h) {
            this.id = id; this.title = title; this.label = label; this.icon = icon; this.mask = mask; this.round = round;
            this.ax = ax; this.ay = ay; this.dx = dx; this.dy = dy; this.w = w; this.h = h;
        }
    }

    /** The buttons. icon = name of the drawable ic_<icon> (res/drawable), the label is the fallback when it is missing. */
    static final Def[] CONTROLS = {
        new Def("gas", "Gas (Cross)", "GAS", "accelerate", CROSS, true, 1, 1, -130, -130, 100, 100),
        new Def("brake", "Brake (Square)", "BRK", "brake", SQUARE, true, 1, 1, -240, -100, 72, 72),
        new Def("r1", "R1", "R1", "r1", R1, true, 1, 1, -231, -203, 70, 70),  // used in races: beside the gas
        new Def("circle", "Circle", "O", "circle", CIRCLE, true, 1, 1, -90, -240, 66, 66),
        new Def("triangle", "Triangle", "^", "triangle", TRI, true, 1, 1, -173, -295, 66, 66),
        new Def("l2", "L2", "L2", "l2", L2, false, 0, 0, 20, 16, 72, 45), new Def("l1", "L1", "L1", "l1", L1, false, 0, 0, 20, 68, 72, 45),
        new Def("r2", "R2", "R2", "r2", R2, false, 1, 0, -92, 16, 72, 45),
        new Def("select", "Select", "SEL", "select", SELECT, false, 0.5f, 1, -84, -54, 72, 45),
        new Def("start", "Start", "START", "start", START, false, 0.5f, 1, 12, -54, 72, 45),
    };
    /** The stick/D-pad switch and the left stick are controls too (no pad bit): the same visibility switches. */
    static final Def MODE = new Def("mode", "Stick / D-pad switch", "", "dpad", 0, false, 0.5f, 0, -70, 12, 140, 40);
    static final Def STICK = new Def("stick", "Left stick", "", "stick_base", 0, true, 0, 1, 0, 0, 0, 0);

    /** Everything a settings panel lists: the buttons, then the stick and the switch. */
    static Def[] all() {
        Def[] a = new Def[CONTROLS.length + 2];
        System.arraycopy(CONTROLS, 0, a, 0, CONTROLS.length);
        a[CONTROLS.length] = STICK;
        a[CONTROLS.length + 1] = MODE;
        return a;
    }

    final class Btn {
        final Def def; final RectF r = new RectF(); Drawable icon; boolean visible = true;
        Btn(Def def) { this.def = def; }
    }

    final Btn[] btns = new Btn[CONTROLS.length];
    final Btn modeBtn = new Btn(MODE);
    boolean showStick = true;
    final Settings settings;
    boolean dpadMode = false;
    float d, scale = 1f;
    int insetL, insetR, alphaK = 50;
    int controlsMode = Settings.CONTROLS_AUTO;
    boolean padFound = false;
    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    Drawable stickBase, stickThumb, modeIcon;
    int stickId = -1; float sx, sy, px, py;  // stick origin and thumb, in px
    int stickRadius;
    // Edit mode (Settings > Move controls): the controls are dragged to new places, the game gets no input meanwhile; Done keeps the places
    // (Settings.putPosition, fractions of the screen), Reset brings the default layout back.
    boolean editing = false;
    Runnable onEditDone;
    int dragId = -1, dragWhat = -2;  // dragWhat: index into btns, -1 the stick
    float dragDx, dragDy;
    final RectF doneR = new RectF(), resetR = new RectF();
    float stickCx = -1, stickCy = -1;  // the stick's home when the player moved it (px), -1 the default
    final long[] lastDown = new long[16];
    int heldNow, sentButtons = -1, sentX = -1, sentY = -1, touchMask;
    final InputManager im;
    final Runnable release = new Runnable() { public void run() { recompute(touchMask); } };

    public TouchOverlay(Context c, Settings s) {
        super(c);
        settings = s;
        d = c.getResources().getDisplayMetrics().density;
        fill.setStyle(Paint.Style.FILL); line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(2 * d);
        text.setTextAlign(Paint.Align.CENTER); text.setTextSize(15 * d);
        for (int i = 0; i < btns.length; i++) { btns[i] = new Btn(CONTROLS[i]); btns[i].icon = icon(CONTROLS[i].icon); }
        stickBase = icon(STICK.icon); stickThumb = icon("stick_thumb"); modeIcon = icon("dpad");
        im = (InputManager) c.getSystemService(Context.INPUT_SERVICE);
        im.registerInputDeviceListener(this, null);
        setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) { applyInsets(in); return in; }
        });
        applySettings();
        refreshControllers();
    }

    /** The drawable ic_<name> of the app, null when it does not exist (the label is drawn then). */
    Drawable icon(String name) {
        Context c = getContext();
        int id = c.getResources().getIdentifier("ic_" + name, "drawable", c.getPackageName());
        if (id == 0) return null;
        try { return c.getDrawable(id).mutate(); } catch (Exception e) { return null; }
    }

    void applyInsets(WindowInsets in) {
        DisplayCutout cut = in == null ? null : in.getDisplayCutout();
        int l = cut == null ? 0 : cut.getSafeInsetLeft(), r = cut == null ? 0 : cut.getSafeInsetRight();  // keep the controls out of the camera cutout
        if (l != insetL || r != insetR) { insetL = l; insetR = r; layoutButtons(); invalidate(); }
    }

    /** Reads the settings: visibility, size, opacity, controls mode. Called at start and whenever the panel changes something. */
    public void applySettings() {
        for (Btn b : btns) b.visible = settings.visible(b.def.id);
        modeBtn.visible = false;  // the stick / D-pad switch is in Settings (Left control) only: no button on the game screen
        showStick = settings.visible("stick");
        dpadMode = settings.dpad();
        scale = settings.size() / 100f;
        alphaK = settings.opacity();
        controlsMode = settings.controls();
        stickRadius = (int) (70 * d * scale);
        layoutButtons();
        updateVisibility();
        invalidate();
    }

    int a(int base) { return Math.min(255, base * alphaK / 50); }

    void refreshControllers() {
        boolean found = false;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice dev = InputDevice.getDevice(id);
            if (dev == null || dev.isVirtual()) continue;
            int s = dev.getSources();
            if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) found = true;
        }
        padFound = found;
        updateVisibility();
    }

    /** Auto: hidden while a physical pad is connected; Force touch: always shown; Force gamepad: never shown. */
    void updateVisibility() {
        if (editing) { setVisibility(View.VISIBLE); return; }
        boolean show = controlsMode == Settings.CONTROLS_TOUCH || (controlsMode == Settings.CONTROLS_AUTO && !padFound);
        setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) { stickId = -1; java.util.Arrays.fill(lastDown, 0); touchMask = 0; heldNow = 0; send(0, 128, 128); }
    }

    @Override public void onInputDeviceAdded(int id) { refreshControllers(); }
    @Override public void onInputDeviceRemoved(int id) { refreshControllers(); }
    @Override public void onInputDeviceChanged(int id) { refreshControllers(); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { layoutButtons(); }

    void layoutButtons() {
        int w = getWidth(), h = getHeight();
        float usable = w - insetL - insetR;
        for (Btn b : btns) {
            Def f = b.def;
            float bw = f.w * d * scale, bh = f.h * d * scale;
            float[] at = settings.position(f.id);
            if (at != null && w > 0 && h > 0) {
                float cx = at[0] * w, cy = at[1] * h;
                b.r.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2);
                continue;
            }
            float x = insetL + f.ax * usable + f.dx * d * scale, y = f.ay * h + f.dy * d * scale;
            b.r.set(x, y, x + bw, y + bh);
        }
        float[] st = settings.position(STICK.id);
        if (st != null && w > 0 && h > 0) { stickCx = st[0] * w; stickCy = st[1] * h; } else { stickCx = stickCy = -1; }
        float bw = 120 * d, bh = 44 * d, gap = 12 * d;
        doneR.set(w / 2f + gap / 2, 16 * d, w / 2f + gap / 2 + bw, 16 * d + bh);
        resetR.set(w / 2f - gap / 2 - bw, 16 * d, w / 2f - gap / 2, 16 * d + bh);
        float mx = insetL + MODE.ax * usable - (modeIcon != null ? 24 : 70) * d;  // the switch keeps its size
        modeBtn.r.set(mx, 12 * d, mx + (modeIcon != null ? 48 : 140) * d, 52 * d);
    }

    float stickHomeX() { return stickCx >= 0 ? stickCx : insetL + 130 * d * scale; }
    float stickHomeY() { return stickCy >= 0 ? stickCy : getHeight() - 110 * d * scale; }

    /** Edit mode on: shown even when a gamepad hides the controls, the game's input released. `done` runs when the player taps Done. */
    public void startEdit(Runnable done) {
        editing = true;
        onEditDone = done;
        dragId = -1;
        stickId = -1;
        java.util.Arrays.fill(lastDown, 0);
        touchMask = 0;
        heldNow = 0;
        send(0, 128, 128);
        setVisibility(View.VISIBLE);
        invalidate();
    }

    void endEdit() {
        editing = false;
        dragId = -1;
        updateVisibility();
        invalidate();
        if (onEditDone != null) onEditDone.run();
    }

    boolean editTouch(MotionEvent e) {
        int action = e.getActionMasked(), i = e.getActionIndex();
        int w = getWidth(), h = getHeight();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            float x = e.getX(i), y = e.getY(i);
            if (doneR.contains(x, y)) { endEdit(); return true; }
            if (resetR.contains(x, y)) { settings.resetPositions(); layoutButtons(); invalidate(); return true; }
            if (dragId >= 0) return true;
            for (int k = btns.length - 1; k >= 0; k--) {
                Btn b = btns[k];
                if (b.visible && b.r.contains(x, y)) { dragId = e.getPointerId(i); dragWhat = k; dragDx = b.r.centerX() - x; dragDy = b.r.centerY() - y; return true; }
            }
            if (showStick && Math.hypot(x - stickHomeX(), y - stickHomeY()) <= stickRadius) {
                dragId = e.getPointerId(i); dragWhat = -1; dragDx = stickHomeX() - x; dragDy = stickHomeY() - y;
            }
            return true;
        }
        if (dragId < 0) return true;
        int at = e.findPointerIndex(dragId);
        if (at < 0) { dragId = -1; return true; }
        float cx = Math.max(0, Math.min(w, e.getX(at) + dragDx)), cy = Math.max(0, Math.min(h, e.getY(at) + dragDy));
        if (dragWhat >= 0) {
            Btn b = btns[dragWhat];
            float bw = b.r.width(), bh = b.r.height();
            b.r.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2);
        } else {
            stickCx = cx; stickCy = cy;
        }
        boolean up = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || (action == MotionEvent.ACTION_POINTER_UP && e.getPointerId(i) == dragId);
        if (up && w > 0 && h > 0) {
            settings.putPosition(dragWhat >= 0 ? btns[dragWhat].def.id : STICK.id, cx / w, cy / h);
            dragId = -1;
        }
        invalidate();
        return true;
    }

    void drawIcon(Canvas c, Drawable ic, RectF r, float inset, int alpha) {
        ic.setBounds((int) (r.left + inset), (int) (r.top + inset), (int) (r.right - inset), (int) (r.bottom - inset));
        ic.setAlpha(alpha);
        ic.draw(c);
    }

    @Override protected void onDraw(Canvas c) {
        if (editing) c.drawColor(0x66000000);
        text.setColor((a(0xDD) << 24) | 0xFFFFFF);
        for (Btn b : btns) {
            if (!b.visible) continue;
            boolean on = (heldNow & b.def.mask) != 0;
            if (b.icon != null) {  // the drawable is the whole button face (art/make_controls.py)
                if (on) {
                    fill.setColor((a(0x70) << 24) | 0xFFFFFF);
                    float g = 5 * d;
                    RectF glow = new RectF(b.r.left - g, b.r.top - g, b.r.right + g, b.r.bottom + g);
                    if (b.def.round) c.drawOval(glow, fill); else c.drawRoundRect(glow, glow.height() * 0.36f, glow.height() * 0.36f, fill);
                }
                drawIcon(c, b.icon, b.r, on ? Math.min(b.r.width(), b.r.height()) * 0.04f : 0, on ? 255 : a(0xC8));
                continue;
            }
            fill.setColor(((on ? a(0x88) : a(0x40)) << 24) | 0xFFFFFF); line.setColor((a(0x99) << 24) | 0xFFFFFF);
            if (b.def.round) { c.drawOval(b.r, fill); c.drawOval(b.r, line); } else { c.drawRoundRect(b.r, 8 * d, 8 * d, fill); c.drawRoundRect(b.r, 8 * d, 8 * d, line); }
            c.drawText(b.def.label, b.r.centerX(), b.r.centerY() + 5 * d, text);
        }
        if (modeBtn.visible) {
            if (modeIcon == null) {
                fill.setColor((a(0x40) << 24) | 0xFFFFFF); line.setColor((a(0x99) << 24) | 0xFFFFFF);
                c.drawRoundRect(modeBtn.r, 8 * d, 8 * d, fill); c.drawRoundRect(modeBtn.r, 8 * d, 8 * d, line);
            }
            if (modeIcon != null) {  // shows the mode that a tap switches to
                float s = modeBtn.r.height(), cx = modeBtn.r.centerX(), cy = modeBtn.r.centerY();
                Drawable ic = dpadMode ? stickBase : modeIcon;
                if (ic != null) drawIcon(c, ic, new RectF(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2), 0, a(0xDD));
            } else c.drawText(dpadMode ? "D-PAD (tap: stick)" : "STICK (tap: D-pad)", modeBtn.r.centerX(), modeBtn.r.centerY() + 5 * d, text);
        }
        if (showStick) {
            float cx = stickId >= 0 ? sx : stickHomeX(), cy = stickId >= 0 ? sy : stickHomeY();
            RectF base = new RectF(cx - stickRadius, cy - stickRadius, cx + stickRadius, cy + stickRadius);
            float tx = stickId >= 0 ? px : cx, ty = stickId >= 0 ? py : cy, tr = 28 * d * scale;
            RectF thumb = new RectF(tx - tr, ty - tr, tx + tr, ty + tr);
            if (stickBase != null) drawIcon(c, stickBase, base, 0, a(0xC8));
            else { fill.setColor((a(0x40) << 24) | 0xFFFFFF); line.setColor((a(0x99) << 24) | 0xFFFFFF); c.drawCircle(cx, cy, stickRadius, fill); c.drawCircle(cx, cy, stickRadius, line); }
            if (stickThumb != null) drawIcon(c, stickThumb, thumb, 0, stickId >= 0 ? 255 : a(0xB0));
            else { fill.setColor(((stickId >= 0 ? a(0x99) : a(0x50)) << 24) | 0xFFFFFF); c.drawCircle(tx, ty, tr, fill); }
        }
        if (editing) drawEditChrome(c);
    }

    void drawEditChrome(Canvas c) {
        Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);
        dash.setStyle(Paint.Style.STROKE);
        dash.setStrokeWidth(2 * d);
        dash.setColor(0xFFE58A2E);
        dash.setPathEffect(new android.graphics.DashPathEffect(new float[] {8 * d, 6 * d}, 0));
        float g = 4 * d;
        for (Btn b : btns) if (b.visible) c.drawRoundRect(new RectF(b.r.left - g, b.r.top - g, b.r.right + g, b.r.bottom + g), 10 * d, 10 * d, dash);
        if (showStick) c.drawCircle(stickHomeX(), stickHomeY(), stickRadius + g, dash);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTextAlign(Paint.Align.CENTER);
        t.setTextSize(15 * d);
        t.setFakeBoldText(true);
        for (int k = 0; k < 2; k++) {
            RectF r = k == 0 ? doneR : resetR;
            fill.setColor(k == 0 ? 0xFFE58A2E : 0xCC3A332C);
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
            t.setColor(k == 0 ? 0xFF1C1814 : 0xFFF2EAE0);
            c.drawText(k == 0 ? "Done" : "Reset", r.centerX(), r.centerY() + 5 * d, t);
        }
        t.setFakeBoldText(false);
        t.setTextSize(13 * d);
        t.setColor(0xFFF2EAE0);
        c.drawText("Drag the controls where you want them", getWidth() / 2f, doneR.bottom + 24 * d, t);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (editing) return editTouch(e);
        int action = e.getActionMasked(), upIndex = (action == MotionEvent.ACTION_POINTER_UP) ? e.getActionIndex() : -1;
        if (action == MotionEvent.ACTION_CANCEL) { stickId = -1; recompute(0); return true; }
        int mask = 0, w = getWidth();
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == upIndex || action == MotionEvent.ACTION_UP) continue;
            int id = e.getPointerId(i); float x = e.getX(i), y = e.getY(i);
            if (id == stickId) { px = x; py = y; continue; }
            boolean fresh = (action == MotionEvent.ACTION_DOWN) || (action == MotionEvent.ACTION_POINTER_DOWN && i == e.getActionIndex());
            if (fresh && modeBtn.visible && modeBtn.r.contains(x, y)) { dpadMode = !dpadMode; settings.put("dpad", dpadMode); continue; }
            int m = btnMask(x, y);
            // the stick starts where the thumb lands: the left part of the screen, or around its place when the player moved it
            boolean stickZone = stickCx >= 0 ? Math.hypot(x - stickCx, y - stickCy) < stickRadius * 2.2f : (x < w * 0.4f && y > 70 * d);
            if (showStick && stickId < 0 && fresh && m == 0 && stickZone) { stickId = id; sx = px = x; sy = py = y; continue; }
            mask |= m;
        }
        int up = (action == MotionEvent.ACTION_UP) ? e.getPointerId(0) : (upIndex >= 0 ? e.getPointerId(upIndex) : -2);
        if (up == stickId) stickId = -1;
        recompute(mask);
        return true;
    }

    int btnMask(float x, float y) {
        int m = 0;
        float pad = 8 * d;
        for (Btn b : btns)
            if (b.visible && x >= b.r.left - pad && x <= b.r.right + pad && y >= b.r.top - pad && y <= b.r.bottom + pad) m |= b.def.mask;
        return m;
    }

    void recompute(int mask) {
        touchMask = mask;
        long now = SystemClock.uptimeMillis();
        int out = mask, lx = 128, ly = 128;
        for (int i = 0; i < 16; i++) {
            if ((mask & (1 << i)) != 0) lastDown[i] = now;
            else if (lastDown[i] != 0 && now - lastDown[i] < HOLD_MS) { out |= 1 << i; removeCallbacks(release); postDelayed(release, HOLD_MS - (now - lastDown[i]) + 5); }
        }
        if (stickId >= 0) {
            float dx = (px - sx) / stickRadius, dy = (py - sy) / stickRadius, len = (float) Math.hypot(dx, dy);
            if (len > 1) { dx /= len; dy /= len; len = 1; }
            if (len > 0.2f) {
                if (dpadMode) {
                    if (dx > 0.4f) out |= RIGHT;
                    if (dx < -0.4f) out |= LEFT;
                    if (dy > 0.4f) out |= DOWN;
                    if (dy < -0.4f) out |= UP;
                } else {
                    lx = Math.round(128 + dx * 127); ly = Math.round(128 + dy * 127);
                }
            }
            px = sx + dx * stickRadius; py = sy + dy * stickRadius;
        }
        heldNow = out;
        send(out, lx, ly);
        invalidate();
    }

    void send(int buttons, int lx, int ly) {
        if (buttons == sentButtons && lx == sentX && ly == sentY) return;
        sentButtons = buttons; sentX = lx; sentY = ly;
        try { nativeSet(buttons, lx, ly); } catch (UnsatisfiedLinkError e) { /* the game library is not loaded yet */ }
    }
}
