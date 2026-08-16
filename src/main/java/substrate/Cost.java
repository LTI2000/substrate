package substrate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tiny typed builders so the content tables stay readable.
 *
 * <p>{@code of(...)} is overloaded by hand for 0 through 4 resource/amount pairs instead of
 * taking varargs or building through a fluent builder. This trades a little repetition here for
 * call sites in the {@code Machine} and {@link Tech} tables that read as plain
 * {@code Cost.of(Res.IRON, 50, Res.COAL, 50)} — type-checked pairs with no array/varargs boxing
 * and no builder ceremony. The overload count is capped at four because that is the largest cost
 * table any machine or tech actually uses; a fifth pair would mean adding one more overload, not
 * redesigning the API.
 */
public final class Cost {
    private Cost() {}

    /** @return an empty cost (something free, or a placeholder). */
    public static Map<Res, Double> of() { return Map.of(); }

    /** @return an immutable single-resource cost map. */
    public static Map<Res, Double> of(Res a, double x) {
        var m = new LinkedHashMap<Res, Double>();
        m.put(a, x);
        return Map.copyOf(m);
    }

    /** @return an immutable two-resource cost map, insertion order preserved. */
    public static Map<Res, Double> of(Res a, double x, Res b, double y) {
        var m = new LinkedHashMap<Res, Double>();
        m.put(a, x); m.put(b, y);
        return Map.copyOf(m);
    }

    /** @return an immutable three-resource cost map, insertion order preserved. */
    public static Map<Res, Double> of(Res a, double x, Res b, double y, Res c, double z) {
        var m = new LinkedHashMap<Res, Double>();
        m.put(a, x); m.put(b, y); m.put(c, z);
        return Map.copyOf(m);
    }

    /** @return an immutable four-resource cost map, insertion order preserved. */
    public static Map<Res, Double> of(Res a, double x, Res b, double y, Res c, double z, Res d, double w) {
        var m = new LinkedHashMap<Res, Double>();
        m.put(a, x); m.put(b, y); m.put(c, z); m.put(d, w);
        return Map.copyOf(m);
    }
}
