package substrate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rectangle fusion and the power network.
 *
 * <p>Fusion decides which adjacent identical machines on the board merge into a single,
 * more efficient {@link Group}. This is done greedily, not optimally: for each bucket of
 * same-kind machines the single best available rectangle is carved out and turned into a
 * group, repeated until no rectangle of at least 2x2 remains, and every cell left over
 * becomes its own 1x1 group. A globally optimal partition into rectangles is a much harder
 * problem and not worth solving exactly for a board this size and a value that only needs
 * to be "good," not perfect.
 *
 * <p>This class also computes power reachability from the core: a machine only produces
 * if it is connected, cell by cell, to the core through other machines.
 */
public final class Fusion {

    /** The result of dividing the site into machines: every fused/unfused group, and a lookup from cell to group. */
    public record Layout(List<Group> groups, int[] cellGroup) {
        /** The group occupying the cell at (x, y), or {@code null} if the cell is empty. */
        public Group at(int x, int y) {
            int g = cellGroup[Board.idx(x, y)];
            return g < 0 ? null : groups.get(g);
        }
    }

    private Fusion() {}

    /** A candidate rectangle of cells, top-left corner (x, y), width w, height h. */
    private record Rect(int x, int y, int w, int h) {}

    /**
     * Largest rectangle of available cells with both sides at least 2, preferring
     * the squarest and then the topmost/leftmost. Returned x,y is the top-left corner.
     *
     * <p>This is the classic largest-rectangle-in-a-binary-matrix algorithm: for every
     * row it treats the column histogram of consecutive free cells ({@code left}, the
     * running "free run length ending here" per column) and, for every cell scanned as a
     * potential bottom-left corner, walks upward shrinking the available width to the
     * narrowest column in the run. Every (width, height) pair visited during that walk is
     * a valid all-free rectangle and is scored.
     *
     * <p>The score {@code w*h*1000 - abs(w-h)} is not raw area: multiplying area by 1000
     * makes it dominate, so the highest-area rectangle always wins the comparison against
     * anything smaller. The {@code abs(w-h)} term only breaks ties (or near-ties) between
     * rectangles of otherwise-equal footprint by nudging the choice toward squarer shapes,
     * which fuse and render more predictably than long, thin strips.
     *
     * @param free bitmap over the flat board grid; {@code true} means the cell is an
     *             unclaimed instance of the machine kind currently being partitioned
     * @return the best rectangle, or {@code null} if no eligible (both sides &gt;= 2) rectangle exists
     */
    static Rect bestRect(boolean[] free) {
        int[] left = new int[Board.W * Board.H];
        for (int y = 0; y < Board.H; y++) {
            for (int x = 0; x < Board.W; x++) {
                int i = Board.idx(x, y);
                left[i] = free[i] ? (x > 0 ? left[i - 1] + 1 : 1) : 0;
            }
        }
        Rect best = null;
        long bestScore = Long.MIN_VALUE;
        for (int y = 0; y < Board.H; y++) {
            for (int x = 0; x < Board.W; x++) {
                if (!free[Board.idx(x, y)]) continue;
                int minW = Integer.MAX_VALUE;
                for (int h = 1; y - h + 1 >= 0; h++) {
                    int j = Board.idx(x, y - h + 1);
                    if (!free[j]) break;
                    minW = Math.min(minW, left[j]);
                    if (minW < 2 || h < 2) continue;
                    for (int w = 2; w <= minW; w++) {
                        long score = (long) w * h * 1000 - Math.abs(w - h);
                        if (score > bestScore) {
                            bestScore = score;
                            best = new Rect(x - w + 1, y - h + 1, w, h);
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * Partition the site. Identical machines - same type, and for rigs the same ore -
     * forming a solid rectangle 2x2 or larger become one machine. Leftovers, including
     * every 1xN line, stay single.
     *
     * <p>Machines are first bucketed by kind into per-kind bitmaps. The bucket key is a
     * synthetic string built by concatenation ({@code m.name()} plus, for ore-bound rigs,
     * {@code ":" + ore.name()}); it exists purely to serve as a de facto composite map key
     * (machine type, ore) without introducing a dedicated key class or a nested map.
     *
     * <p>For each bucket, {@link #bestRect} is repeatedly applied and the winning
     * rectangle is carved out ({@link #clear}) until nothing 2x2 or larger remains — a
     * greedy decomposition, not a globally optimal one, since re-solving optimally after
     * every carve would be far more expensive for no real gameplay benefit. Whatever
     * single cells are left over each become their own 1x1 group.
     */
    public static Layout layout(Board b) {
        Map<String, boolean[]> byKind = new LinkedHashMap<>();
        Map<String, Machine> kindType = new LinkedHashMap<>();
        for (int i = 0; i < Board.W * Board.H; i++) {
            Machine m = b.cell[i];
            if (m == null || m == Machine.CORE) continue;
            boolean rig = m.spec().role() instanceof Role.Mine;
            String key = rig && b.ore[i] != null ? m.name() + ":" + b.ore[i].name() : m.name();
            byKind.computeIfAbsent(key, k -> new boolean[Board.W * Board.H])[i] = true;
            kindType.put(key, m);
        }

        List<Group> groups = new ArrayList<>();
        int[] cellGroup = new int[Board.W * Board.H];
        java.util.Arrays.fill(cellGroup, -1);

        for (var e : byKind.entrySet()) {
            boolean[] free = e.getValue();
            Machine type = kindType.get(e.getKey());
            Rect r;
            while ((r = bestRect(free)) != null) {
                clear(free, r);
                groups.add(make(b, cellGroup, groups.size(), type, r));
            }
            for (int i = 0; i < free.length; i++) {
                if (free[i]) {
                    free[i] = false;
                    groups.add(make(b, cellGroup, groups.size(), type,
                            new Rect(Board.xOf(i), Board.yOf(i), 1, 1)));
                }
            }
        }
        groups.add(make(b, cellGroup, groups.size(), Machine.CORE, new Rect(Board.CX, Board.CY, 1, 1)));
        return new Layout(List.copyOf(groups), cellGroup);
    }

    /** Marks every cell of {@code r} as no longer free, so it will not be picked again by {@link #bestRect}. */
    private static void clear(boolean[] free, Rect r) {
        for (int dy = 0; dy < r.h(); dy++)
            for (int dx = 0; dx < r.w(); dx++)
                free[Board.idx(r.x() + dx, r.y() + dy)] = false;
    }

    /**
     * Builds a {@link Group} for rectangle {@code r}, registers its cells in
     * {@code cellGroup}, averages the ore richness of any ore-bearing cells it covers
     * (defaulting to 1 when the group covers no ore, e.g. non-mining machines), and derives
     * {@link Group#enabled} from {@link Board#off}.
     *
     * <p>{@code enabled} is {@code false} if <em>any</em> cell in the rectangle is manually
     * switched off, not only if every cell is. {@link Engine#toggle} always flips every cell
     * of a group at once, so in the common case the cells agree; the only way they could
     * disagree is a fresh placement fusing a switched-off block together with an adjacent
     * switched-on one before the player re-toggles the merged result, and erring toward "off"
     * there is the safer default — it never silently reactivates something the player turned
     * off.
     */
    private static Group make(Board b, int[] cellGroup, int id, Machine type, Rect r) {
        int[] cells = new int[r.w() * r.h()];
        int n = 0, richSum = 0;
        Res ore = null;
        boolean enabled = true;
        for (int dy = 0; dy < r.h(); dy++) {
            for (int dx = 0; dx < r.w(); dx++) {
                int i = Board.idx(r.x() + dx, r.y() + dy);
                cells[n++] = i;
                cellGroup[i] = id;
                if (b.ore[i] != null) { ore = b.ore[i]; richSum += b.rich[i]; }
                if (b.off[i]) enabled = false;
            }
        }
        double rich = n > 0 ? (double) richSum / n : 1;
        if (rich <= 0) rich = 1;
        return new Group(id, type, r.x(), r.y(), r.w(), r.h(), cells, ore, rich, enabled);
    }

    /**
     * A machine only works if it touches the mass that reaches the core.
     *
     * <p>Reachability is a flood fill (depth-first, via an explicit {@link ArrayDeque}
     * used as a stack rather than recursion, to avoid stack-depth concerns on a 15x15
     * grid) starting from the core cell. It walks the flat 1-D cell-index grid directly:
     * neighbor steps are index arithmetic ({@code i - 1}, {@code i + 1}, {@code i - Board.W},
     * {@code i + Board.W}) guarded by explicit edge checks, rather than converting to and
     * from 2-D (x, y) coordinates for each step, since the board is already stored flat.
     *
     * @return a bitmap over the flat grid marking every cell connected to the core through machines
     */
    public static boolean[] energise(Board b, Layout layout) {
        boolean[] linked = new boolean[Board.W * Board.H];
        var stack = new ArrayDeque<Integer>();
        int start = Board.idx(Board.CX, Board.CY);
        linked[start] = true;
        stack.push(start);
        while (!stack.isEmpty()) {
            int i = stack.pop();
            int x = Board.xOf(i), y = Board.yOf(i);
            if (x > 0)           step(b, linked, stack, i - 1);
            if (x < Board.W - 1) step(b, linked, stack, i + 1);
            if (y > 0)           step(b, linked, stack, i - Board.W);
            if (y < Board.H - 1) step(b, linked, stack, i + Board.W);
        }
        // Reachability alone does not need a group to be switched on: a manually disabled
        // machine still occupies its cells, so it still conducts the flood fill through to
        // whatever fuses or wires past it, exactly as an unpowered-but-linked machine already
        // did before Group#enabled existed. Only g.powered — what tick() and the renderer act
        // on — folds enabled in, so a disabled group reads as unpowered everywhere else in the
        // codebase without every caller needing to check both flags separately.
        for (Group g : layout.groups()) {
            boolean on = false;
            for (int i : g.cells) on |= linked[i];
            g.powered = on && g.enabled;
        }
        return linked;
    }

    /** Marks cell {@code j} reached and pushes it for expansion, unless it is already linked or empty. */
    private static void step(Board b, boolean[] linked, ArrayDeque<Integer> stack, int j) {
        if (!linked[j] && b.cell[j] != null) { linked[j] = true; stack.push(j); }
    }
}
