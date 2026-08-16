package substrate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;

/**
 * Mutable site state: what stands where, what has been dug up, what is known. This is the whole
 * save-worthy state of a game — everything {@link Save} needs to serialize lives here, and
 * everything the engine and renderer read comes from here.
 */
public final class Board {
    /** Grid dimensions and the core's fixed position, always dead centre. */
    public static final int W = 15, H = 15, CX = 7, CY = 7;

    /** Machine occupying each cell, or {@code null} for empty; indexed via {@link #idx}. */
    public final Machine[] cell = new Machine[W * H];
    /** Ore type under each cell, or {@code null} if barren; indexed via {@link #idx}. */
    public final Res[] ore = new Res[W * H];
    /** Richness of the ore under each cell (meaningless where {@link #ore} is {@code null}). */
    public final int[] rich = new int[W * H];

    /** Current stock of every resource. */
    public final EnumMap<Res, Double> res = new EnumMap<>(Res.class);
    /** Whether a resource has ever been produced, independent of its current (possibly zero) stock — see {@link #discovered}. */
    public final EnumMap<Res, Boolean> seen = new EnumMap<>(Res.class);
    /** Count of each machine type currently placed on the board. */
    public final EnumMap<Machine, Integer> built = new EnumMap<>(Machine.class);
    /** Set of researched techs. */
    public final EnumSet<Tech> tech = EnumSet.noneOf(Tech.class);

    /** Current claim width/height, in tiles; grows via {@link Tech#claim()} research. */
    public int claim = 7;
    /** Buffered power available this tick. */
    public double energy;
    /** Total manual clicks on the core, used to scale click yield. */
    public long clicks;
    /** Wall-clock time of the last save, used to compute idle catch-up on load. */
    public long savedAt = System.currentTimeMillis();
    /** Recent event lines, newest first, capped by {@link #logLine}. */
    public final List<String> log = new ArrayList<>();

    /** Fresh board: every resource at zero, only Matter marked discovered, core placed at centre. */
    public Board() {
        for (Res r : Res.values()) res.put(r, 0.0);
        seen.put(Res.MATTER, true);
        cell[idx(CX, CY)] = Machine.CORE;
    }

    /** @return the flat array index for grid coordinates {@code (x, y)}. */
    public static int idx(int x, int y) { return y * W + x; }
    /** @return the x coordinate encoded in flat index {@code i}. */
    public static int xOf(int i)        { return i % W; }
    /** @return the y coordinate encoded in flat index {@code i}. */
    public static int yOf(int i)        { return i / W; }

    /** @return current stock of {@code r}. */
    public double get(Res r)            { return res.get(r); }
    /** Overwrites the stock of {@code r} to exactly {@code v}. */
    public void   set(Res r, double v)  { res.put(r, v); }
    /**
     * Adds {@code v} (may be negative) to the stock of {@code r}.
     *
     * <p>As a side effect, any positive addition also marks {@code r} as {@link #seen} —
     * discovery bookkeeping for the ledger UI is folded directly into this primitive rather than
     * kept as a separate step, so every producer of a resource automatically reveals it without
     * having to remember to call anything else.
     */
    public void   add(Res r, double v)  { res.merge(r, v, Double::sum); if (v > 0) seen.put(r, true); }
    /** @return how many of machine {@code m} are currently built. */
    public int    count(Machine m)      { return built.getOrDefault(m, 0); }

    /** @return {@code true} if {@code t} has been researched. */
    public boolean has(Tech t) { return tech.contains(t); }

    /**
     * The surveyed window, centred on the core.
     *
     * <p>Uses the literal {@code 15} rather than {@link #W}/{@link #H}, so this silently assumes
     * the board stays 15 wide — a magic number duplicating the board-size constants defined just
     * above instead of referencing them.
     */
    public int margin()               { return (15 - claim) / 2; }
    /** @return {@code true} if {@code (x, y)} lies within the currently surveyed/claimed window. */
    public boolean inClaim(int x, int y) {
        int m = margin();
        return x >= m && y >= m && x < W - m && y < H - m;
    }

    /** @return {@code true} if {@code r} has ever been produced or is currently in stock. */
    public boolean discovered(Res r) { return Boolean.TRUE.equals(seen.get(r)) || get(r) > 0; }

    /** Prepends {@code s} to the event log, trimming it to the most recent 40 lines. */
    public void logLine(String s) {
        log.add(0, s);
        while (log.size() > 40) log.remove(log.size() - 1);
    }
}
