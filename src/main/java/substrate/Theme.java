package substrate;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.List;

/**
 * Sun-scorched survey chart for the chrome, dirty industrial greys for the machines. Central
 * palette, font lookup, and small color-arithmetic helpers shared by every painted component so
 * nobody hand-mixes an RGB triple outside this file.
 *
 * <p>The whole chrome layer is warm and desaturated on purpose — dust and rust rather than the
 * clean cyanotype-blueprint look an earlier pass had. {@link #GRID}/{@link #LINE}/{@link
 * #LINE2}/{@link #DIM} are all the same dusty tan family at different weights, {@link #CHALK} is
 * bone/parchment rather than cool white, and the three status accents ({@link #AMBER}, {@link
 * #HOT}, {@link #GOOD}) are each deliberately harsher or grimier than a "clean" idle game would
 * use: rust-orange, blood-rust red, and a sickly irradiated olive rather than a pleasant teal.
 * {@link #ICE} is the one surviving cool accent, kept exactly so it still reads as a lone
 * flickering readout against the warm backdrop rather than because it fits the palette.
 */
public final class Theme {
    private Theme() {}

    // -- Chrome palette: UI backgrounds, grid lines, and status colors. --
    /** Darkest background, used behind everything else — scorched, near-black umber. */
    public static final Color INK      = new Color(0x15, 0x10, 0x0A);
    /** Chart/paper background: dark, dusty brown instead of dark blue. */
    public static final Color PAPER    = new Color(0x2A, 0x21, 0x15);
    /** Panel background, one step lighter than {@link #PAPER}. */
    public static final Color PANEL    = new Color(0x30, 0x26, 0x18);
    /** Faint grid lines: dusty tan, not cyan. */
    public static final Color GRID     = new Color(196, 168, 122, 24);
    /** Standard rule/border line. */
    public static final Color LINE     = new Color(196, 168, 122, 42);
    /** Heavier rule/border line. */
    public static final Color LINE2    = new Color(196, 168, 122, 88);
    /** Primary text color: bone/parchment, not cool white-blue. */
    public static final Color CHALK    = new Color(0xE9, 0xDC, 0xC2);
    /** Secondary/muted text color: muted khaki/taupe, not blue-grey. */
    public static final Color DIM      = new Color(0x8F, 0x7E, 0x62);
    /** Warning/attention accent: a deeper, grittier rust-amber than a clean idle-game amber. */
    public static final Color AMBER    = new Color(0xE0, 0x8A, 0x2E);
    /** Alert/negative accent: a harsher, more blood-rust red than a bright alarm orange-red. */
    public static final Color HOT      = new Color(0xBE, 0x3A, 0x28);
    /** Positive/success accent: a sickly, irradiated olive-green rather than a pleasant teal. */
    public static final Color GOOD     = new Color(0x93, 0xA3, 0x3C);
    /** Cool accent for machine status readouts — the one deliberately-cool color left, so it still pops against the warm chrome. */
    public static final Color ICE      = new Color(0x86, 0xB0, 0xAE);

    // -- Machine material palette: used by Art to paint machine bodies. --
    /** Warmer, dirtier steel than a clean blue-grey. */
    public static final Color STEEL      = new Color(0x8C, 0x82, 0x70);
    /** Warm dark steel/rust shadow. */
    public static final Color STEEL_DARK = new Color(0x4A, 0x42, 0x36);
    /** Dirtier, warmer concrete. */
    public static final Color CONCRETE   = new Color(0x6B, 0x62, 0x51);
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
