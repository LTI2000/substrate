package substrate;

import java.util.Arrays;
import java.util.EnumSet;

/**
 * Helpers for building a site by hand. Deliberately free of any test framework so the
 * render tools in this source root can use them too: {@link RenderTool} reuses the same
 * fixture-building code as the JUnit test suite, an unusual cross-cutting concern for a
 * "test helper" class.
 */
final class TestSite {

    /** Not instantiated; every member is static. */
    private TestSite() {
    }

    /** A fresh engine over an empty board (just the core). */
    static Engine blank() {
        return new Engine(new Board());
    }

    /** Stamps a w x h block of one machine straight onto the board, bypassing costs. */
    static void put(Board b, int x, int y, int w, int h, Machine m, Res ore) {
        for (int j = 0; j < h; j++)
            for (int i = 0; i < w; i++) {
                int k = Board.idx(x + i, y + j);
                b.cell[k] = m;
                if (ore != null) {
                    b.ore[k] = ore;
                    b.rich[k] = 1;
                }
                b.built.merge(m, 1, Integer::sum);
            }
    }

    /** Tops every resource up to an effectively unlimited amount, so any purchase can be made. */
    static void stock(Board b) {
        for (Res r : Res.values()) b.set(r, 1e12);
    }

    /** Zeroes every resource, typically used right before timing production over a fresh window. */
    static void zero(Board b) {
        for (Res r : Res.values()) b.set(r, 0.0);
    }

    /** Buys a block through the engine, topping up the stock before every purchase. */
    static int build(Engine e, Machine m, int x, int y, int w, int h) {
        int n = 0;
        for (int j = 0; j < h; j++)
            for (int i = 0; i < w; i++) {
                stock(e.board);
                if (e.place(m, x + i, y + j)) n++;
            }
        return n;
    }

    /** A stable, sorted description of a layout, so expectations can be written as one string. */
    static String signature(Fusion.Layout l) {
        return l.groups().stream()
                .filter(g -> g.type != Machine.CORE)
                .map(g -> g.type + (g.ore != null ? ":" + g.ore : "") + "@" + g.x + "," + g.y + " " + g.w + "x" + g.h)
                .sorted()
                .reduce((a, b) -> a + " | " + b)
                .orElse("");
    }

    /**
     * Every machine belongs to exactly one group, every group is a solid rectangle of its own
     * kind, and the cell to group index agrees with both. Throws if any of that is violated.
     */
    static void integrity(Board b, Fusion.Layout l, String label) {
        int[] seen = new int[Board.W * Board.H];
        Arrays.fill(seen, -1);
        for (Group g : l.groups()) {
            if (g.cells.length != g.area) throw new AssertionError(label + ": cell count != area");
            if (g.x < 0 || g.y < 0 || g.x + g.w > Board.W || g.y + g.h > Board.H)
                throw new AssertionError(label + ": group out of bounds");
            for (int dy = 0; dy < g.h; dy++)
                for (int dx = 0; dx < g.w; dx++) {
                    int i = Board.idx(g.x + dx, g.y + dy);
                    if (seen[i] != -1) throw new AssertionError(label + ": groups overlap at cell " + i);
                    seen[i] = g.id;
                    if (b.cell[i] != g.type) throw new AssertionError(label + ": foreign machine inside a rectangle");
                    if (l.cellGroup()[i] != g.id) throw new AssertionError(label + ": cell to group map disagrees");
                }
        }
        for (int i = 0; i < seen.length; i++)
            if ((b.cell[i] != null) != (seen[i] != -1))
                throw new AssertionError(label + ": machine at " + i + " belongs to no group");
    }

    /**
     * One of everything, several blocks fused and two left dark, used by the artwork check
     * and by the offscreen render tool. The hardcoded {@code spots}/{@code kinds} arrays below
     * exist purely to place one instance of every machine kind — including deliberately fused
     * blocks and two deliberately depowered ones, chosen by literal coordinate comparison — so
     * as to exercise the renderer for visual test coverage. None of this is meant to reflect a
     * plausible or gameplay-valid layout.
     */
    static Engine sampler() {
        var e = blank();
        var b = e.board;
        b.claim = 15;
        b.tech.addAll(EnumSet.allOf(Tech.class));
        for (Res r : Res.values()) b.set(r, 1e15);

        // Grid positions (x, y, w, h) for each machine kind below; several are wider than 1x1
        // so they fuse into a block, purely to give the renderer something fused to draw.
        int[][] spots = {
                {1, 1, 1, 1}, {3, 1, 2, 2}, {6, 1, 3, 2}, {10, 1, 2, 3}, {13, 1, 1, 1},
                {1, 4, 2, 2}, {4, 4, 2, 2}, {7, 4, 2, 2}, {10, 4, 3, 3}, {13, 4, 1, 2},
                {1, 7, 2, 2}, {4, 7, 2, 3}, {7, 7, 1, 1}, {9, 8, 2, 2}, {12, 7, 2, 2},
                {1, 10, 3, 2}, {5, 11, 2, 2}, {8, 11, 3, 3}, {12, 10, 2, 2}, {12, 13, 2, 2}
        };
        // One machine kind per spot above, in the same order; CORE is skipped below since the
        // core is already on the board.
        Machine[] kinds = {
                Machine.PYLON, Machine.SOLAR, Machine.MINER, Machine.COND, Machine.PYLON,
                Machine.FE, Machine.CU, Machine.BURNER, Machine.ARM, Machine.PYLON,
                Machine.ASM, Machine.STL, Machine.CORE, Machine.CAP, Machine.LAB,
                Machine.AMP, Machine.DRILL, Machine.BLAST, Machine.REFINE, Machine.TOKAMAK
        };
        for (int k = 0; k < spots.length; k++) {
            int[] s = spots[k];
            Machine m = kinds[k];
            if (m == Machine.CORE) continue;                 // the core is already on the board
            for (int dy = 0; dy < s[3]; dy++)
                for (int dx = 0; dx < s[2]; dx++) {
                    int i = Board.idx(s[0] + dx, s[1] + dy);
                    if (b.cell[i] != null) continue;
                    if (m.spec().oreOnly()) {
                        b.ore[i] = m == Machine.DRILL ? Res.TITANIUM_ORE : Res.IRON_ORE;
                        b.rich[i] = 3;
                    }
                    b.cell[i] = m;
                    b.built.merge(m, 1, Integer::sum);
                }
        }
        b.ore[Board.idx(6, 10)] = Res.COAL;
        b.rich[Board.idx(6, 10)] = 2;
        b.ore[Board.idx(0, 13)] = Res.URANIUM_ORE;
        b.rich[Board.idx(0, 13)] = 4;
        b.ore[Board.idx(14, 9)] = Res.COPPER_ORE;
        b.rich[Board.idx(14, 9)] = 1;
        put(b, 0, 8, 1, 1, Machine.REACTOR, null);
        put(b, 0, 9, 1, 1, Machine.REP, null);
        put(b, 0, 10, 1, 1, Machine.INDUCT, null);

        for (int x = 1; x < 14; x++) if (b.cell[Board.idx(x, 6)] == null) b.cell[Board.idx(x, 6)] = Machine.PYLON;
        for (int y = 1; y < 14; y++) if (b.cell[Board.idx(6, y)] == null) b.cell[Board.idx(6, y)] = Machine.PYLON;
        e.recompute();
        for (Group g : e.layout().groups())                  // keep two blocks dark to show the hazard state
            g.powered = !(g.x == 12 && g.y == 13) && !(g.x == 3 && g.y == 1);
        b.energy = e.power().capacity() * 0.62;
        for (int i = 0; i < 40; i++) e.tick(0.1);
        return e;
    }

    /** A plausible mid game site, for painting the whole window. */
    static Engine midGame() {
        var engine = Engine.fresh();
        var b = engine.board;
        b.tech.add(Tech.TOOLS0);
        b.tech.add(Tech.SMELTING);
        b.tech.add(Tech.COMBUSTION);
        b.tech.add(Tech.AUTOMATION);
        b.tech.add(Tech.ELECTRONICS);
        b.set(Res.MATTER, 4200);
        b.set(Res.IRON_ORE, 320);
        b.set(Res.IRON, 180);
        b.set(Res.COPPER_ORE, 90);
        b.set(Res.COAL, 260);
        b.set(Res.COPPER, 40);
        for (Res r : new Res[]{Res.IRON_ORE, Res.IRON, Res.COAL, Res.COPPER_ORE, Res.COPPER}) b.seen.put(r, true);
        engine.place(Machine.SOLAR, 8, 7);
        engine.place(Machine.SOLAR, 8, 8);
        engine.place(Machine.SOLAR, 9, 7);
        engine.place(Machine.SOLAR, 9, 8);
        engine.place(Machine.PYLON, 7, 8);
        for (int y = 4; y < 11; y++)
            for (int x = 4; x < 11; x++)
                if (b.ore[Board.idx(x, y)] != null && b.cell[Board.idx(x, y)] == null) engine.place(Machine.MINER, x, y);
        engine.place(Machine.FE, 6, 6);
        engine.place(Machine.FE, 6, 5);
        engine.recompute();
        for (int i = 0; i < 60; i++) engine.tick(0.1);
        return engine;
    }
}
