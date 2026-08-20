package substrate;

import java.util.Collections;
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
 *
 * <p><b>Every map here iterates in the order it was written.</b> That matters because the UI
 * prints costs in iteration order — the tech tree's tiles and the build panel's detail card both
 * walk the map straight onto the screen — so the order a price is written in these tables is the
 * order the player reads it in. It is why each overload fills a {@link LinkedHashMap} and hands
 * back an {@link Collections#unmodifiableMap unmodifiable view} of it, rather than the tidier
 * {@link Map#copyOf}: {@code Map.copyOf} builds one of the {@code Map.of} family, whose iteration
 * order is derived from the keys' hashes and a per-JVM random seed. These maps used to be built
 * that way, and the visible symptom was a price that read "250 matter · 60 iron ore" one launch
 * and "60 iron ore · 250 matter" the next. The view is as immutable in practice as a copy, since
 * the {@code LinkedHashMap} behind it is a local that never escapes the factory that made it.
 */
public final class Cost {
    private Cost() {}

    /** @return an empty cost (something free, or a placeholder). */
    public static Map<Res, Double> of() { return Map.of(); }

    /** @return an immutable single-resource cost map, iterating in the order written. */
    public static Map<Res, Double> of(Res a, double x) {
        var m = new LinkedHashMap<Res, Double>();
        m.put(a, x);
        return Collections.unmodifiableMap(m);
    }

    /** @return an immutable two-resource cost map, iterating in the order written. */
    public static Map<Res, Double> of(Res a, double x, Res b, double y) {
        var m = new LinkedHashMap<Res, Double>();
        m.put(a, x); m.put(b, y);
        return Collections.unmodifiableMap(m);
    }

    /** @return an immutable three-resource cost map, iterating in the order written. */
    public static Map<Res, Double> of(Res a, double x, Res b, double y, Res c, double z) {
        var m = new LinkedHashMap<Res, Double>();
        m.put(a, x); m.put(b, y); m.put(c, z);
        return Collections.unmodifiableMap(m);
    }

    /** @return an immutable four-resource cost map, iterating in the order written. */
    public static Map<Res, Double> of(Res a, double x, Res b, double y, Res c, double z, Res d, double w) {
        var m = new LinkedHashMap<Res, Double>();
        m.put(a, x); m.put(b, y); m.put(c, z); m.put(d, w);
        return Collections.unmodifiableMap(m);
    }
}
