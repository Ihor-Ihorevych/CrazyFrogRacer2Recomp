// Layout after RatatouilleReDroid's SettingsPanel (https://github.com/Ihor-Ihorevych/RatatouilleReDroid), MIT License,
// Copyright (c) 2026 Paulo Henrique.
package org.ps2recomp.spike;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The settings: a dark card over the dimmed game, two columns so that it fits a phone held sideways. On the left the
 * emblem and the Telegram group, on the right the settings in sections: graphics (resolution, FPS counter), screen
 * (picture shape) and the on-screen controls (opacity, left control, shown). Everything is saved as it is changed.
 * A view over the game surface (not a dialog), so the game keeps its immersive screen; closes with the cross, a tap beside it or Back.
 */
public class SettingsPanel extends FrameLayout {
    interface Host {
        void settingsChanged(boolean live);   // an applied (live) setting changed
        void restartGame();                    // resolution changed: the native side reads it only at start
        void editControls();                   // close the panel and let the player drag the on-screen controls (TouchOverlay edit mode)
    }

    static final String TELEGRAM = "https://t.me/flipendodroid";
    static final String SOURCE = "https://github.com/Ihor-Ihorevych/CrazyFrogRacer2Recomp";

    private static final int ACCENT = 0xFFE58A2E, CARD = 0xFF1C1814, SECTION = 0xFF262019, FIELD = 0xFF2E2822,
        TEXT = 0xFFF2EDE6, DIM = 0xFF9A8F84;
    private static final int MIN_PERCENT = 25, STEP = 5, MAX_PERCENT = 100;

    private final Context ctx;
    private final Settings st;
    private final Host host;
    private final float density;
    private final int startPercent;
    private final List<TextView> chips = new ArrayList<>();
    private TextView resolutionLabel;
    private FancySlider resolution;
    private TextView restartNote;

    public SettingsPanel(Context c, Settings settings, Host h) {
        super(c);
        ctx = c;
        st = settings;
        host = h;
        density = c.getResources().getDisplayMetrics().density;
        startPercent = st.resPct();
        setBackgroundColor(0xB0000000);
        setClickable(true);
        setOnClickListener(v -> close());  // a tap outside the card closes it
        android.util.DisplayMetrics m = c.getResources().getDisplayMetrics();
        int w = Math.max(m.widthPixels, m.heightPixels), hgt = Math.min(m.widthPixels, m.heightPixels);
        final int cardWidth = Math.min(Math.round(w * 0.9f), dp(900));
        final int cardHeight = Math.min(dp(430), Math.round(hgt * 0.94f));
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackground(round(CARD, 22));
        card.setClickable(true);
        card.addView(leftColumn(), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f));
        card.addView(rightColumn(), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 7f));
        addView(card, new FrameLayout.LayoutParams(cardWidth, cardHeight, Gravity.CENTER));
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    // ---- building -----------------------------------------------------------------------------------------------

    private View leftColumn() {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(18), dp(18), dp(12), dp(18));
        col.addView(new IconView(ctx), new LinearLayout.LayoutParams(dp(84), dp(84)));
        TextView title = text("Settings", 22, TEXT, true);
        title.setGravity(Gravity.CENTER);
        col.addView(title, margin(wrap(), 0, 12, 0, 0));
        TextView subtitle = text("Crazy Frog Racer 2 for Android", 13, DIM, false);
        subtitle.setGravity(Gravity.CENTER);
        col.addView(subtitle, margin(wrap(), 0, 2, 0, 0));
        String version = "";
        try {
            version = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;  // AndroidManifest.xml versionName
        } catch (Exception e) { /* none shown */ }
        if (version != null && !version.isEmpty()) {
            TextView ver = text("Version " + version, 12, DIM, false);
            ver.setGravity(Gravity.CENTER);
            col.addView(ver, margin(wrap(), 0, 2, 0, 0));
        }
        View spacer = new View(ctx);
        col.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1f));
        col.addView(link(Icons.TELEGRAM, "Telegram group", TELEGRAM), match());
        col.addView(link(Icons.SOURCE, "Source code (GitHub)", SOURCE), margin(match(), 0, 8, 0, 0));
        return col;
    }

    private View rightColumn() {
        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(6), dp(14), dp(18), dp(14));

        // the close button, top right
        LinearLayout head = new LinearLayout(ctx);
        head.setGravity(Gravity.END);
        FrameLayout close = new FrameLayout(ctx);
        close.setBackground(round(FIELD, 18));
        IconView x = new IconView(ctx, Icons.CLOSE, TEXT);
        close.addView(x, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        close.setOnClickListener(v -> close());
        head.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));
        col.addView(head, margin(match(), 0, 0, 0, 6));

        col.addView(graphicsSection(), margin(match(), 0, 0, 0, 10));
        col.addView(screenSection(), margin(match(), 0, 0, 0, 10));
        col.addView(controlsSection(), match());
        scroll.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private LinearLayout section(Glyph icon, String title) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(SECTION, 16));
        box.setPadding(dp(16), dp(12), dp(16), dp(14));
        LinearLayout head = new LinearLayout(ctx);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(new IconView(ctx, icon, ACCENT), new LinearLayout.LayoutParams(dp(20), dp(20)));
        head.addView(text(title, 15, ACCENT, true), margin(wrap(), 8, 0, 0, 0));
        box.addView(head, margin(match(), 0, 0, 0, 6));
        return box;
    }

    // graphics: the resolution and the FPS counter
    private View graphicsSection() {
        LinearLayout box = section(Icons.GRAPHICS, "Graphics");
        int current = st.resPct();
        resolutionLabel = text("", 14, TEXT, false);
        box.addView(resolutionLabel);
        resolution = new FancySlider(ctx, MIN_PERCENT, MAX_PERCENT, STEP);
        resolution.setValue(current);
        resolution.setListener(new FancySlider.Listener() {
            @Override
            public void onChange(int value) {
                updateResolution(value);
            }

            @Override
            public void onStop(int value) {
                commitResolution(value);
            }
        });
        box.addView(resolution, match());

        LinearLayout row = new LinearLayout(ctx);
        for (final int percent : new int[] {50, 75, 100}) {
            final TextView chip = text(percent + "%", 14, TEXT, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(18), dp(8), dp(18), dp(8));
            chip.setOnClickListener(v -> {
                resolution.setValue(percent);
                commitResolution(percent);
            });
            chip.setTag(percent);
            chips.add(chip);
            row.addView(chip, margin(wrap(), 0, 0, 8, 0));
        }
        box.addView(row);
        box.addView(text("A lower resolution is faster and a little softer.", 12, DIM, false), margin(wrap(), 0, 8, 0, 0));
        // the game reads the resolution when it starts: a tap on the note restarts it
        restartNote = text("Restart the game to apply this. Tap here to restart now.", 12, ACCENT, true);
        restartNote.setOnClickListener(v -> host.restartGame());
        box.addView(restartNote, margin(wrap(), 0, 4, 0, 0));
        restartNote.setVisibility(View.GONE);
        updateResolution(current);

        box.addView(text("Graphics API", 14, TEXT, false), margin(wrap(), 0, 10, 0, 0));
        final Segmented api = new Segmented(ctx, "OpenGL ES", "Vulkan");
        final boolean startGles = st.gles();
        api.setSelected(startGles ? 0 : 1);
        final TextView apiAgain = text("Restart the game to apply this. Tap here to restart now.", 12, ACCENT, true);
        apiAgain.setOnClickListener(v -> host.restartGame());
        api.setListener(index -> {
            st.put("gles", index == 0);
            apiAgain.setVisibility((index == 0) == startGles ? View.GONE : View.VISIBLE);
        });
        box.addView(api, margin(wrap(), 0, 6, 0, 0));
        box.addView(text("OpenGL ES works on most phones. Vulkan can be faster on newer phones: try it if the game is slow.", 12, DIM, false),
                    margin(wrap(), 0, 8, 0, 0));
        box.addView(apiAgain, margin(wrap(), 0, 4, 0, 0));
        apiAgain.setVisibility(View.GONE);

        box.addView(text("FPS counter", 14, TEXT, false), margin(wrap(), 0, 10, 0, 0));
        Segmented fps = new Segmented(ctx, "Shown", "Hidden");
        fps.setSelected(st.fps() ? 0 : 1);
        fps.setListener(index -> {
            st.put("fps", index == 0);
            host.settingsChanged(true);
        });
        box.addView(fps, margin(wrap(), 0, 6, 0, 0));
        box.addView(text("Pictures per second, and the game's speed (100% = full speed).", 12, DIM, false), margin(wrap(), 0, 8, 0, 0));
        return box;
    }

    private void updateResolution(int percent) {
        resolutionLabel.setText(String.format(java.util.Locale.US, "Resolution   %d%%   %d x %d", percent, st.pixelsX(percent), st.pixelsY(percent)));
        for (TextView chip : chips) {
            boolean on = (Integer) chip.getTag() == percent;
            chip.setBackground(round(on ? ACCENT : FIELD, 18));
            chip.setTextColor(on ? 0xFF1C1814 : 0xFFD8D0C6);
        }
    }

    private void commitResolution(int percent) {
        updateResolution(percent);
        st.put("res_pct", percent);
        restartNote.setVisibility(percent == startPercent ? View.GONE : View.VISIBLE);
    }

    // screen: the picture's shape
    private View screenSection() {
        LinearLayout box = section(Icons.SCREEN, "Screen");
        box.addView(text("Picture shape", 14, TEXT, false));
        final Segmented mode = new Segmented(ctx, "Original", "Stretch", "Zoom");
        mode.setSelected(st.display());
        mode.setListener(index -> {
            st.put("display", index);
            host.settingsChanged(true);
        });
        box.addView(mode, margin(wrap(), 0, 6, 0, 0));
        box.addView(text("Stretch widens the picture to your screen. Zoom fills it and cuts the top and bottom.", 12, DIM, false),
                    margin(wrap(), 0, 8, 0, 0));
        box.addView(text("Game view", 14, TEXT, false), margin(wrap(), 0, 10, 0, 0));
        final Segmented view = new Segmented(ctx, "Widescreen", "4:3");
        final boolean startWide = st.wide();
        view.setSelected(startWide ? 0 : 1);
        final TextView again = text("Restart the game to apply this. Tap here to restart now.", 12, ACCENT, true);
        again.setOnClickListener(v -> host.restartGame());
        view.setListener(index -> {
            st.put("wide", index == 0);
            again.setVisibility((index == 0) == startWide ? View.GONE : View.VISIBLE);
            updateResolution(resolution.getValue());
        });
        box.addView(view, margin(wrap(), 0, 6, 0, 0));
        box.addView(text("Widescreen: the camera sees as much as your screen is wide (the game's own widescreen patch, PCSX2). "
                         + "4:3: the picture as on a TV.", 12, DIM, false), margin(wrap(), 0, 8, 0, 0));
        box.addView(again, margin(wrap(), 0, 4, 0, 0));
        again.setVisibility(View.GONE);
        return box;
    }

    // controls: opacity, left control and shown
    private View controlsSection() {
        LinearLayout box = section(Icons.CONTROLS, "On-screen controls");
        final TextView label = text("", 14, TEXT, false);
        final int alpha = Math.max(25, st.opacity());
        label.setText("Button opacity   " + alpha + "%");
        box.addView(label);
        FancySlider opacity = new FancySlider(ctx, 25, 100, 5);
        opacity.setValue(alpha);
        opacity.setListener(new FancySlider.Listener() {
            @Override
            public void onChange(int value) {
                label.setText("Button opacity   " + value + "%");
                st.put("opacity", value);
                host.settingsChanged(true);
            }

            @Override
            public void onStop(int value) {}
        });
        box.addView(opacity, match());
        box.addView(text("Left control", 14, TEXT, false), margin(wrap(), 0, 4, 0, 0));
        Segmented left = new Segmented(ctx, "Stick", "D-pad");
        left.setSelected(st.dpad() ? 1 : 0);
        left.setListener(index -> {
            st.put("dpad", index == 1);
            host.settingsChanged(true);
        });
        box.addView(left, margin(wrap(), 0, 6, 0, 8));
        box.addView(text("Show the buttons", 14, TEXT, false), margin(wrap(), 0, 4, 0, 0));
        Segmented shown = new Segmented(ctx, "Auto", "Shown", "Hidden");
        shown.setSelected(st.controls());
        shown.setListener(index -> {
            st.put("controls", index);
            host.settingsChanged(true);
        });
        box.addView(shown, margin(wrap(), 0, 6, 0, 0));
        box.addView(text("Auto hides them while a gamepad is connected. Hidden is for a gamepad the phone does not report. The settings button stays.",
                         12, DIM, false), margin(wrap(), 0, 8, 0, 0));
        TextView move = text("Move controls", 14, 0xFF1C1814, true);
        move.setGravity(Gravity.CENTER);
        move.setBackground(round(ACCENT, 18));
        move.setPadding(dp(18), dp(10), dp(18), dp(10));
        move.setOnClickListener(v -> {
            if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
            host.editControls();
        });
        box.addView(move, margin(wrap(), 0, 12, 0, 0));
        box.addView(text("Drag any button or the stick to a new place; Reset puts them back.", 12, DIM, false), margin(wrap(), 0, 6, 0, 0));
        return box;
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private View link(Glyph icon, String label, final String url) {
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(round(FIELD, 14));
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.addView(new IconView(ctx, icon, ACCENT), new LinearLayout.LayoutParams(dp(22), dp(22)));
        row.addView(text(label, 14, TEXT, true), margin(wrap(), 10, 0, 0, 0));
        row.setOnClickListener(v -> open(url));
        return row;
    }

    private void open(String url) {
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(ctx, "No app can open " + url, Toast.LENGTH_LONG).show();
        }
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.getPaint().setFakeBoldText(bold);
        return t;
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private static LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams margin(LinearLayout.LayoutParams p, float l, float t, float r, float b) {
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    public void close() {
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
        host.settingsChanged(true);
    }
}
