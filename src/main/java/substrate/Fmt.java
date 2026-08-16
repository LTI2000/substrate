package substrate;

/** Short human-readable magnitudes. */
public final class Fmt {
    /**
     * Magnitude suffixes, thousand to thousand, from unlabelled up through {@code "Dc"}
     * (Decillion, 10^33). This is the standard idle-game abbreviation ladder; it goes this far
     * because the game's numbers reach into the millions already and, being an idle game, are
     * expected to keep climbing well past that over a long session.
     */
    private static final String[] SUF = {"", "K", "M", "B", "T", "Qa", "Qi", "Sx", "Sp", "Oc", "No", "Dc"};

    private Fmt() {}

    /**
     * Formats {@code v} to a short human-readable magnitude, e.g. {@code 1234567 -> "1.23M"}.
     * Precision adapts to magnitude (two decimals under 10, one under 100, whole numbers above)
     * so the string stays roughly constant width regardless of scale.
     *
     * @return {@code "inf"} for NaN/infinite input, otherwise the abbreviated magnitude, suffixed
     *         per {@link #SUF} once the value exceeds 1000 (capped at the largest suffix)
     */
    public static String n(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "inf";
        if (v < 0) return "-" + n(-v);
        if (v == 0) return "0";
        if (v < 1) return String.format("%.2f", v);
        if (v < 1000) return v < 10 ? String.format("%.1f", v) : String.valueOf(Math.round(v));
        int t = 0;
        while (v >= 1000 && t < SUF.length - 1) { v /= 1000; t++; }
        String s = v < 10 ? String.format("%.2f", v) : v < 100 ? String.format("%.1f", v) : String.valueOf(Math.round(v));
        return s + SUF[t];
    }

    /** @return {@code v} formatted via {@link #n} with an explicit sign and a {@code "/s"} suffix, for flow readouts. */
    public static String rate(double v) {
        return (v >= 0 ? "+" : "-") + n(Math.abs(v)) + "/s";
    }

    /** @return {@code f} (a 0..1 fraction) as a rounded whole-number percentage string. */
    public static String pct(double f) { return Math.round(f * 100) + "%"; }
}
