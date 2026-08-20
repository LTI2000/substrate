package substrate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static substrate.TestSite.build;
import static substrate.TestSite.integrity;
import static substrate.TestSite.put;
import static substrate.TestSite.signature;

/** The rule the whole game hangs on: which machines fuse, and exactly where the block lands. */
@DisplayName("fusion geometry")
class FusionTest {

    /** Checks that an empty board still yields one group: the core itself. */
    @Test
    @DisplayName("a bare site is nothing but the core")
    void bareSite() {
        var b = new Board();
        assertEquals(1, Fusion.layout(b).groups().size());
    }

    /** Checks that a 2x2 square of mining rigs on iron ore fuses into a single block anchored at its top-left cell. */
    @Test
    @DisplayName("a square of rigs fuses and anchors on its top left cell")
    void squareFuses() {
        var b = new Board();
        put(b, 3, 3, 2, 2, Machine.MINER, Res.IRON_ORE);
        var l = Fusion.layout(b);
        assertEquals("MINER:IRON_ORE@3,3 2x2", signature(l));
        integrity(b, l, "2x2");
    }

    /** Checks that a non-square 2x3 rectangle of solar collectors fuses too, since fusion only requires both sides at least 2. */
    @Test
    @DisplayName("rectangles do not have to be square")
    void obloingFuses() {
        var b = new Board();
        put(b, 1, 1, 2, 3, Machine.SOLAR, null);
        var l = Fusion.layout(b);
        assertEquals("SOLAR@1,1 2x3", signature(l));
        integrity(b, l, "2x3");
    }

    /** Checks that an L-shaped placement of condensers decomposes into a fused 2x2 block plus a separate lone 1x1 leftover cell. */
    @Test
    @DisplayName("an L shape splits into a block plus the leftovers")
    void lShapeSplits() {
        var b = new Board();
        put(b, 5, 5, 2, 2, Machine.COND, null);
        b.cell[Board.idx(7, 5)] = Machine.COND;
        var l = Fusion.layout(b);
        assertEquals("COND@5,5 2x2 | COND@7,5 1x1", signature(l));
        integrity(b, l, "L");
    }

    /** Checks that a single-file line of pylons never fuses, however long, since fusion requires both sides of the rectangle to be at least 2. */
    @Test
    @DisplayName("a single file line never fuses, however long")
    void lineNeverFuses() {
        var b = new Board();
        put(b, 2, 9, 5, 1, Machine.PYLON, null);
        var l = Fusion.layout(b);
        assertEquals(6, l.groups().size());                       // five pylons plus the core
        assertTrue(l.groups().stream().allMatch(g -> g.area == 1));
        integrity(b, l, "line");
    }

    /** Checks that two touching blocks of different machine kinds (solar and condenser) never fuse together. */
    @Test
    @DisplayName("touching blocks of different machines stay apart")
    void differentTypesStayApart() {
        var b = new Board();
        put(b, 0, 0, 2, 2, Machine.SOLAR, null);
        put(b, 2, 0, 2, 2, Machine.COND, null);
        assertEquals("COND@2,0 2x2 | SOLAR@0,0 2x2", signature(Fusion.layout(b)));
    }

    /** Checks that mining rigs sitting on different ore types are treated as distinct machine identities, so touching rigs on different ores never fuse. */
    @Test
    @DisplayName("rigs sitting on different ores are different machines")
    void oreKeepsRigsApart() {
        var b = new Board();
        put(b, 4, 0, 1, 2, Machine.MINER, Res.IRON_ORE);
        put(b, 5, 0, 1, 2, Machine.MINER, Res.COPPER_ORE);
        assertTrue(Fusion.layout(b).groups().stream()
                .filter(g -> g.type == Machine.MINER)
                .allMatch(g -> g.area == 1));
    }

    /** Checks that a solid 4x4 block of steel mills stays as one fused group rather than being decomposed into four separate 2x2 squares. */
    @Test
    @DisplayName("a 4x4 stays whole instead of splitting into four squares")
    void bigSquareStaysWhole() {
        var b = new Board();
        put(b, 10, 10, 4, 4, Machine.STL, null);
        assertEquals("STL@10,10 4x4", signature(Fusion.layout(b)));
    }

    /**
     * Checks that the decomposition algorithm is greedy — it claims the biggest available
     * rectangle first — rather than searching for a globally optimal partition. A 3x3 furnace
     * block placed beside a touching 2x2 furnace block forms an irregular combined footprint
     * that a 3x3 plus a 2x2 alone would not predict; the greedy algorithm claims the single
     * largest rectangle available in that footprint (a 5x2 strip across the top) first, and
     * this test pins that choice down rather than asserting a full alternative partition.
     */
    @Test
    @DisplayName("the decomposition is greedy: biggest rectangle first")
    void greedyTakesTheBiggest() {
        var b = new Board();
        put(b, 0, 4, 3, 3, Machine.FE, null);
        put(b, 3, 4, 2, 2, Machine.FE, null);
        var l = Fusion.layout(b);
        assertTrue(l.groups().stream().anyMatch(g -> g.x == 0 && g.y == 4 && g.w == 5 && g.h == 2),
                () -> "expected a 5x2 to be claimed first, got " + signature(l));
        integrity(b, l, "greedy");
    }

    /** Checks that a fused mining rig's richness is the arithmetic mean of the per-cell richness values underneath it. */
    @Test
    @DisplayName("a fused rig averages the richness under it")
    void richnessAverages() {
        var b = new Board();
        put(b, 1, 11, 2, 2, Machine.MINER, Res.COAL);
        b.rich[Board.idx(1, 11)] = 3;
        b.rich[Board.idx(2, 11)] = 1;
        b.rich[Board.idx(1, 12)] = 2;
        b.rich[Board.idx(2, 12)] = 2;
        assertEquals(2.0, Fusion.layout(b).groups().get(0).richness, 1e-9);
    }

    /**
     * Fuzzes the decomposition against 200 randomly scattered boards (fixed seed for
     * repeatability) and checks each resulting layout against {@link TestSite#integrity} —
     * every machine in exactly one rectangular group, no overlaps, no gaps — rather than
     * asserting any specific geometry, since the exact partition of a random board is not
     * predictable.
     */
    @Test
    @DisplayName("two hundred random sites keep their geometry")
    void randomSitesHold() {
        var rnd = new Random(7);
        Machine[] kinds = {Machine.SOLAR, Machine.COND, Machine.PYLON, Machine.FE};
        for (int t = 0; t < 200; t++) {
            var b = new Board();
            for (int k = 0; k < 70; k++) {
                int i = rnd.nextInt(Board.W * Board.H);
                if (b.cell[i] == null) b.cell[i] = kinds[rnd.nextInt(kinds.length)];
            }
            integrity(b, Fusion.layout(b), "random " + t);
        }
    }

    /** Checks that demolishing any single cell of a fused block removes the entire block, not just that one cell. */
    @Test
    @DisplayName("dismantling one cell takes the whole fused block with it")
    void demolishClearsTheBlock() {
        var e = TestSite.blank();
        var b = e.board;
        b.claim = 15;
        build(e, Machine.COND, 5, 7, 2, 2);
        build(e, Machine.COND, 5, 9, 2, 2);
        build(e, Machine.PYLON, 7, 8, 1, 1);
        e.recompute();
        Group block = e.layout().at(5, 7);
        assertEquals(8, block.area, "the two 2x2 blocks touch, so they are one 2x4");
        int before = b.count(Machine.COND);
        e.demolish(block);
        assertEquals(before - 8, b.count(Machine.COND));
        assertTrue(b.cell[Board.idx(5, 7)] == null && b.cell[Board.idx(6, 10)] == null);
    }

    /**
     * Checks the shift-click gesture: {@link Engine#demolishCell} takes out exactly the cell
     * asked for, leaves every other cell of the block standing, and lets the survivors re-fuse
     * into whatever rectangles still fit — here a 3x3 clipped at its top-right corner comes back
     * as a 2x3 block plus two loose cells, and the core itself refuses to be picked apart.
     */
    @Test
    @DisplayName("shift-dismantling takes one cell and re-fuses what is left")
    void demolishCellLeavesTheRest() {
        var e = TestSite.blank();
        var b = e.board;
        b.claim = 15;
        build(e, Machine.COND, 3, 3, 3, 3);
        e.recompute();
        assertEquals(9, e.layout().at(3, 3).area);
        int before = b.count(Machine.COND);

        e.demolishCell(5, 3);
        e.recompute();
        assertEquals(before - 1, b.count(Machine.COND), "only the one unit left the books");
        assertTrue(b.cell[Board.idx(5, 3)] == null, "the clicked cell is gone");
        assertEquals("COND@3,3 2x3 | COND@5,4 1x1 | COND@5,5 1x1", signature(e.layout()),
                "the clipped corner re-fuses into a smaller block plus whatever is left over");
        integrity(b, e.layout(), "clipped 3x3");

        e.demolishCell(Board.CX, Board.CY);
        assertEquals(Machine.CORE, b.cell[Board.idx(Board.CX, Board.CY)], "the core can't be picked apart");
    }
}
