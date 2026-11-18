// Panel layout and widgets after RatatouilleReDroid (https://github.com/Ihor-Ihorevych/RatatouilleReDroid), MIT License,
// Copyright (c) 2026 Paulo Henrique. The icons and the emblem here are this app's own.
package org.ps2recomp.spike;

import android.graphics.Path;

/** A 24 x 24 icon from SVG path data: a filled path and/or a stroked one. */
final class Glyph {
    final Path fill, stroke;
    final float strokeWidth;

    Glyph(String fillData, String strokeData, float strokeWidth) {
        this.fill = fillData == null ? null : SvgPath.parse(fillData);
        this.stroke = strokeData == null ? null : SvgPath.parse(strokeData);
        this.strokeWidth = strokeWidth;
    }
}

/** The settings panel's icons, as SVG paths. */
final class Icons {
    private Icons() {}

    // a rounded cross
    static final Glyph CLOSE = new Glyph(null, "M7 7 L17 17 M17 7 L7 17", 2.4f);
    // a picture: frame, hills and a sun
    static final Glyph GRAPHICS = new Glyph(SvgPath.circle(16, 8.8f, 1.7f), "M3 5 H21 V19 H3 Z M3 16 L8.5 11 L12.5 14.5 L15.5 12 L21 16.5", 1.8f);
    // a screen with arrows to both sides: the picture's width
    static final Glyph SCREEN = new Glyph(null, "M3 6 H21 V18 H3 Z M7.5 12 H16.5 M9.5 10 L7.5 12 L9.5 14 M14.5 10 L16.5 12 L14.5 14", 1.8f);
    // a thumbstick and two buttons
    static final Glyph CONTROLS = new Glyph(SvgPath.circle(17, 9.5f, 1.8f) + SvgPath.circle(19.5f, 14, 1.8f) + SvgPath.circle(8, 12, 1.8f),
        SvgPath.circle(8, 12, 5), 1.7f);
    // a paper plane, outlined
    static final Glyph TELEGRAM = new Glyph(null, "M21 4 L2.8 11.2 L9.3 13.4 L11.8 20 L14.9 15.6 L19.6 19.2 Z M9.3 13.4 L21 4", 1.7f);

    /** The frog emblem's layers on its 108 x 108 tile: path data, colour, stroke width (0 fills). */
    static final Object[][] FROG = {
        {SvgPath.circle(37, 38, 15) + SvgPath.circle(71, 38, 15), 0xFF5DB53E, 0f},
        {"M54,34 C79,34 89,50 87,65 C85,81 71,89 54,89 C37,89 23,81 21,65 C19,50 29,34 54,34 Z", 0xFF6CC24A, 0f},
        {"M54,62 C68,62 77,68 76,76 C75,84 65,88 54,88 C43,88 33,84 32,76 C31,68 40,62 54,62 Z", 0xFFA6DE7E, 0f},
        {SvgPath.circle(37, 37, 10) + SvgPath.circle(71, 37, 10), 0xFFFFFFFF, 0f},
        {SvgPath.circle(39.5f, 38, 4.6f) + SvgPath.circle(68.5f, 38, 4.6f), 0xFF1E2A1A, 0f},
        {SvgPath.circle(40.8f, 36.4f, 1.4f) + SvgPath.circle(69.8f, 36.4f, 1.4f), 0xFFFFFFFF, 0f},
        {SvgPath.circle(49.5f, 55, 1.7f) + SvgPath.circle(58.5f, 55, 1.7f), 0xFF2F5A22, 0f},
        {"M37,67 Q54,81 71,67", 0xFF2F5A22, 3f},
    };
}
