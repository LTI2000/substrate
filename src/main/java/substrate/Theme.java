package substrate;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.List;

/**
 * Cyanotype survey chart for the chrome, honest industrial greys for the machines. Central
 * palette, font lookup, and small color-arithmetic helpers shared by every painted component so
 * nobody hand-mixes an RGB triple outside this file.
 */
public final class Theme {
    private Theme() {}

    // -- Chrome palette: UI backgrounds, grid lines, and status colors. --
    /** Darkest background, used behind everything else. */
    public static final Color INK      = new Color(0x08, 0x13, 0x1F);
    /** Chart/paper background. */
    public static final Color PAPER    = new Color(0x0F, 0x23, 0x37);
    /** Panel background, one step lighter than {@link #PAPER}. */
    public static final Color PANEL    = new Color(0x10, 0x26, 0x39);
    /** Faint grid lines. */
    public static final Color GRID     = new Color(184, 219, 247, 26);
    /** Standard rule/border line. */
    public static final Color LINE     = new Color(184, 219, 247, 45);
    /** Heavier rule/border line. */
    public static final Color LINE2    = new Color(184, 219, 247, 90);
    /** Primary text color. */
    public static final Color CHALK    = new Color(0xDB, 0xE9, 0xF5);
    /** Secondary/muted text color. */
    public static final Color DIM      = new Color(0x7A, 0x9A, 0xB5);
    /** Warning/attention accent. */
    public static final Color AMBER    = new Color(0xFF, 0xB4, 0x3A);
    /** Alert/negative accent. */
    public static final Color HOT      = new Color(0xFF, 0x6B, 0x45);
    /** Positive/success accent. */
    public static final Color GOOD     = new Color(0x63, 0xD6, 0xA8);
    /** Cool accent for icy/frost readouts. */
    public static final Color ICE      = new Color(0xA8, 0xC4, 0xDC);

    // -- Machine material palette: used by Art to paint machine bodies. --
    public static final Color STEEL      = new Color(0x8C, 0x97, 0xA3);
    public static final Color STEEL_DARK = new Color(0x4C, 0x56, 0x62);
    public static final Color CONCRETE   = new Color(0x6B, 0x70, 0x76);
    public static final Color BRICK      = new Color(0x8E, 0x5A, 0x45);
    public static final Color COPPER     = new Color(0xB5, 0x72, 0x43);
    public static final Color GLASS      = new Color(0x18, 0x36, 0x55);
    public static final Color PCB        = new Color(0x2C, 0x5A, 0x3C);
    public static final Color CABINET    = new Color(0xB6, 0xC2, 0xCC);
    public static final Color VIOLET     = new Color(0x5E, 0x4C, 0x8E);
    public static final Color EMBER      = new Color(0xFF, 0x8A, 0x3D);
    public static final Color CHERENKOV  = new Color(0x76, 0xD8, 0xFF);

    /** Preferred monospace font families, most to least wanted, ending in an always-available fallback. */
    private static final List<String> MONO_PREFS = List.of(
            "JetBrains Mono", "IBM Plex Mono", "Roboto Mono", "DejaVu Sans Mono",
            "Menlo", "Consolas", "Liberation Mono", "Monospaced");
    /** Preferred sans-serif font families, most to least wanted, ending in an always-available fallback. */
    private static final List<String> SANS_PREFS = List.of(
            "Inter", "Helvetica Neue", "Segoe UI", "DejaVu Sans", "Liberation Sans", "SansSerif");

    /** Resolved monospace family name, chosen once at class-load time. */
    private static final String MONO = pick(MONO_PREFS);
    /** Resolved sans-serif family name, chosen once at class-load time. */
    private static final String SANS = pick(SANS_PREFS);

    /**
     * Picks the first font family from {@code prefs} that is actually installed on this JVM's
     * graphics environment, falling back to the list's last entry ({@code "Monospaced"} or
     * {@code "SansSerif"}, both logical fonts guaranteed to exist). This is a manual
     * cross-platform best-effort fallback done once at startup rather than bundling a font file
     * with the game, so the query only ever runs the one time these fields are initialized.
     *
     * @param prefs ordered font-family preferences, most wanted first
     * @return the first available family name, or the last (fallback) entry if none matched
     */
    private static String pick(List<String> prefs) {
        var available = Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        return prefs.stream().filter(available::contains).findFirst().orElse(prefs.get(prefs.size() - 1));
    }

    /** @return the resolved monospace font, plain style, at {@code size}. */
    public static Font mono(int size)      { return new Font(MONO, Font.PLAIN, size); }
    /** @return the resolved monospace font, bold style, at {@code size}. */
    public static Font monoBold(int size)  { return new Font(MONO, Font.BOLD, size); }
    /** @return the resolved sans-serif font, plain style, at {@code size}. */
    public static Font sans(int size)      { return new Font(SANS, Font.PLAIN, size); }
    /** @return the resolved sans-serif font, bold style, at {@code size}. */
    public static Font sansBold(int size)  { return new Font(SANS, Font.BOLD, size); }

    /**
     * Returns {@code c} with a new alpha channel, clamped to the valid {@code 0..255} range.
     *
     * @param c the source color (its RGB is kept as-is)
     * @param a desired alpha, clamped into range
     */
    public static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
    }

    /**
     * Linearly interpolates between two colors. Hand-written per-channel lerp rather than
     * {@link Color}'s HSB conversion, since these blends are purely additive-mixing effects
     * (fades, highlights) where RGB interpolation is what looks right and HSB round-tripping
     * would be needless overhead.
     *
     * @param a source color at {@code t == 0}
     * @param b target color at {@code t == 1}
     * @param t mix factor, clamped to {@code [0, 1]}
     */
    public static Color mix(Color a, Color b, double t) {
        t = Math.max(0, Math.min(1, t));
        return new Color(
                (int) (a.getRed()   + (b.getRed()   - a.getRed())   * t),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue()  + (b.getBlue()  - a.getBlue())  * t));
    }

    /**
     * Multiplies each RGB channel of {@code c} by {@code f} and clamps back into range —
     * a cheap brightness/darkness adjustment (hand-written rather than via {@link Color}'s HSB
     * methods) used to shade machine art for lighting and hazard effects.
     *
     * @param c source color
     * @param f multiplier; {@code f < 1} darkens, {@code f > 1} lightens (clamped at 255)
     */
    public static Color shade(Color c, double f) {
        return new Color(
                (int) Math.max(0, Math.min(255, c.getRed() * f)),
                (int) Math.max(0, Math.min(255, c.getGreen() * f)),
                (int) Math.max(0, Math.min(255, c.getBlue() * f)));
    }

    /**
     * Cheap deterministic noise so smoke and sparks do not jitter between frames.
     *
     * <p>This is a hand-rolled integer hash (multiply-xorshift-multiply-xorshift, using
     * Murmur3-style finalizer constants) rather than {@link java.util.Random}. The animation
     * code calls this every frame with a seed derived from machine id and time step; a
     * {@code Random} instance would need to be stored and advanced per-caller to be stable
     * across repaints, whereas this is a pure function of the seed — same input, same output,
     * every frame, with no state to carry around.
     *
     * @param seed input value; typically derived from a machine id and a coarse time bucket
     * @return a pseudo-random value in {@code [0, 1)}, stable for a given {@code seed}
     */
    public static double noise(int seed) {
        int h = seed * 0x27d4eb2d;
        h ^= h >>> 15;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        return (h >>> 8 & 0xFFFF) / 65535.0;
    }
}
