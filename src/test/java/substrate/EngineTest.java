package substrate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static substrate.TestSite.build;
import static substrate.TestSite.zero;

/** What the blocks actually do once they are running: throughput, draw, fuel, brownout. */
@DisplayName("engine")
class EngineTest {

    /** Runs the site for the given number of seconds in the usual 100ms steps. */
    private static void run(Engine e, double seconds) {
        for (int i = 0; i < seconds * 10; i++) e.tick(0.1);
    }

    /** A 3x3 mining rig fused into one block over rich iron ore, powered by an adjacent solar collector and pylon. */
    private static Engine minedSite() {
        var e = TestSite.blank();
        var b = e.board;
        b.claim = 15;
        for (Tech t : new Tech[]{Tech.SMELTING, Tech.COMBUSTION, Tech.STORAGE, Tech.OVERCLOCK}) b.tech.add(t);
        for (int y = 4; y <= 6; y++)
            for (int x = 4; x <= 6; x++) {
                b.ore[Board.idx(x, y)] = Res.IRON_ORE;
                b.rich[Board.idx(x, y)] = 2;
            }
        build(e, Machine.PYLON, 7, 6, 1, 1);
        assertEquals(9, build(e, Machine.MINER, 4, 4, 3, 3), "nine rigs should go down on the patch");
        build(e, Machine.SOLAR, 8, 7, 3, 3);
        e.recompute();
        return e;
    }

    /** Checks that the 3x3 mining block reports the expected size, anchor position, and powered state after fusing. */
    @Test
    @DisplayName("a fused block is anchored on the patch and linked through one pylon")
    void fusedBlockIsLive() {
        Group rig = minedSite().layout().at(4, 4);
        assertEquals(3, rig.w);
        assertEquals(3, rig.h);
        assertEquals(4, rig.x);
        assertEquals(4, rig.y);
        assertTrue(rig.powered);
    }

    /**
     * Checks the core scaling rule: a fused 3x3 mining block's ore output equals the base mine
     * rate times the area squared (not the area), times the richness under it and the site's
     * mine multiplier.
     */
    @Test
    @DisplayName("output is base rate times area squared")
    void outputIsAreaSquared() {
        var e = minedSite();
        zero(e.board);
        run(e, 10);
        double got = e.board.get(Res.IRON_ORE) / 10;
        double want = 0.35 * Math.pow(9, 2) * 2 * e.mineMultiplier();
        assertEquals(want, got, want * 0.02, "nine rigs as one block");
    }

    /** Checks that a fused solar collector's power supply scales with area squared while a fused mining rig's power draw stays linear in area. */
    @Test
    @DisplayName("supply squares with area too, but draw stays linear")
    void supplySquaresDrawDoesNot() {
        var e = minedSite();
        run(e, 0.1);                                           // the power snapshot is taken during a tick
        assertEquals(1.5 * 81, e.power().supply(), 1e-6, "a 3x3 collector, one alone is 1.5 pw");
        assertEquals(2 * 9, e.power().demand(), 1e-6, "nine rigs draw nine rigs worth");
    }

    /** Checks that researching GEO1 raises the mining output exponent from 2.00 to 2.15, boosting a 3x3 block's yield accordingly. */
    @Test
    @DisplayName("geometric synergy lifts the exponent from 2.00 to 2.15")
    void synergyLiftsTheExponent() {
        var e = minedSite();
        e.board.tech.add(Tech.GEO1);
        e.recompute();
        zero(e.board);
        run(e, 10);
        double want = 0.35 * Math.pow(9, 2.15) * 2 * e.mineMultiplier();
        assertEquals(want, e.board.get(Res.IRON_ORE) / 10, want * 0.02);
    }

    /** Furnace, burner, capacitor and condenser, all touching, all fed. */
    private static Engine smelterSite() {
        var e = TestSite.blank();
        var b = e.board;
        b.claim = 15;
        for (Tech t : new Tech[]{Tech.SMELTING, Tech.COMBUSTION, Tech.STORAGE, Tech.OVERCLOCK}) b.tech.add(t);
        build(e, Machine.FE, 8, 7, 2, 2);
        build(e, Machine.BURNER, 8, 5, 2, 2);
        build(e, Machine.CAP, 10, 7, 2, 2);
        build(e, Machine.PYLON, 7, 8, 1, 1);
        build(e, Machine.COND, 4, 8, 3, 3);
        e.recompute();
        zero(b);
        b.set(Res.COAL, 1e6);
        b.set(Res.IRON_ORE, 1e6);
        return e;
    }

    /** Checks that all five non-core blocks of the smelter site (furnace, burner, capacitor, pylon, condenser) are powered and linked. */
    @Test
    @DisplayName("everything touching the core is linked")
    void siteIsLinked() {
        var e = smelterSite();
        assertEquals(5, e.layout().groups().stream().filter(g -> g.powered && g.type != Machine.CORE).count());
    }

    /** Checks that the fused 2x2 furnace consumes ore and produces iron at the same 16x multiple over a single furnace, and is fully powered by the burner. */
    @Test
    @DisplayName("a converter scales its inputs as well as its outputs")
    void converterScalesBothWays() {
        var e = smelterSite();
        var b = e.board;
        run(e, 10);
        assertEquals(0.5 * 16, b.get(Res.IRON) / 10, 0.5, "2x2 furnace, 16 times one furnace");
        assertEquals(16.0, (1e6 - b.get(Res.IRON_ORE)) / 10, 0.5, "and it eats ore at the same multiple");
        assertEquals(1.0, e.power().satisfaction(), 1e-9, "the burner covers the whole site");
    }

    /**
     * Checks that a burner's coal consumption tracks actual site power demand rather than
     * running flat out, and that any generation surplus beyond that demand accumulates in the
     * capacitor. Lets the capacitor fill for 60 simulated seconds first so the measured burn
     * rate over the following window reflects steady-state demand, not the initial fill-up.
     */
    @Test
    @DisplayName("a burner only burns what the site is drawing")
    void fuelFollowsDemand() {
        var e = smelterSite();
        var b = e.board;
        run(e, 60);                                            // let the capacitor fill first
        double coal0 = b.get(Res.COAL);
        run(e, 10);
        double burn = (coal0 - b.get(Res.COAL)) / 10;
        double want = 0.3 * 16 * (e.power().demand() / (12 * 16));
        assertEquals(want, burn, 0.15, "flat out it would be 4.80 coal/s");
        assertTrue(b.energy > e.power().capacity() * 0.99, "and the surplus is sitting in the capacitor");
    }

    /** Checks that a fused 2x2 overclock node raises the throughput multiplier of a touching block but leaves its own multiplier at 1.0. */
    @Test
    @DisplayName("an overclock node boosts its neighbours but not itself")
    void overclockNode() {
        var e = smelterSite();
        build(e, Machine.AMP, 7, 9, 2, 2);
        e.recompute();
        assertEquals(1 + 0.06 * 16, e.layout().at(4, 8).mult, 1e-9, "2x2 node on a touching block");
        assertEquals(1.0, e.layout().at(7, 9).mult, 1e-9);
    }

    /**
     * Checks the whole power-switch lifecycle on a fused block: switching it off drops it to
     * zero output and shrinks site demand, it reads as unpowered (but still {@code enabled}
     * stays observably {@code false}) even though it's still linked, and switching it back on
     * resumes production without needing to demolish and rebuild it.
     */
    @Test
    @DisplayName("a manually switched-off group draws and produces nothing until switched back on")
    void powerSwitchStopsAndResumesAGroup() {
        var e = smelterSite();
        var b = e.board;
        run(e, 0.1);                                           // one tick, so power() has a real demand baseline
        double demandBefore = e.power().demand();

        e.toggle(e.layout().at(8, 7), false);                  // the 2x2 furnace block
        e.recompute();
        Group off = e.layout().at(8, 7);
        assertFalse(off.enabled, "manually switched off");
        assertFalse(off.powered, "an off group reads as unpowered even though still linked");

        zero(b);
        run(e, 5);
        assertEquals(0.0, b.get(Res.IRON), 1e-9, "a switched-off furnace makes nothing");
        assertTrue(e.power().demand() < demandBefore, "and stops drawing its share of power");

        e.toggle(off, true);
        e.recompute();
        assertTrue(e.layout().at(8, 7).enabled);
        assertTrue(e.layout().at(8, 7).powered, "switched back on and still linked");
        b.set(Res.IRON_ORE, 1e6);                              // zero(b) above also drained the furnace's feedstock
        b.set(Res.COAL, 1e6);
        run(e, 10);
        assertTrue(b.get(Res.IRON) > 0, "resumes producing once back on");
    }

    /** Checks that {@link Engine#toggle} silently ignores the core, which can never be switched off. */
    @Test
    @DisplayName("the core can't be switched off")
    void coreCannotBeToggled() {
        var e = smelterSite();
        e.toggle(e.layout().at(Board.CX, Board.CY), false);
        e.recompute();
        Group core = e.layout().at(Board.CX, Board.CY);
        assertTrue(core.enabled);
        assertTrue(core.powered);
    }

    /**
     * Checks that a manual switch-off is tracked per cell on the board, not on the block that
     * happened to occupy it: demolishing a switched-off block and rebuilding on the same cells
     * starts fresh, powered on, rather than inheriting the old off state.
     */
    @Test
    @DisplayName("demolishing and rebuilding on the same cell clears any manual switch-off")
    void demolishAndRebuildClearsManualOff() {
        var e = TestSite.blank();
        e.board.claim = 15;
        build(e, Machine.PYLON, 7, 6, 1, 1);
        e.toggle(e.layout().at(7, 6), false);
        e.recompute();
        e.demolish(e.layout().at(7, 6));
        build(e, Machine.PYLON, 7, 6, 1, 1);
        e.recompute();
        assertTrue(e.layout().at(7, 6).enabled, "a fresh build on a previously-switched-off cell starts enabled");
    }

    /**
     * Checks that taking a block apart one cell at a time (the shift-click gesture) pays back
     * exactly what scrapping it in one click pays: both walk the same {@code 1.14} price ramp
     * downward from the current unit count, so the choice of gesture is never a refund exploit
     * in either direction. See {@code Engine#scrap}.
     */
    @Test
    @DisplayName("dismantling cell by cell refunds the same as dismantling the block")
    void piecemealRefundMatchesWholeBlock() {
        double whole = refundFor(false), piecemeal = refundFor(true);
        assertEquals(whole, piecemeal, whole * 1e-9, "same four units removed, same money back");
        assertTrue(whole > 0, "scrapping pays something back at all");
    }

    /** Builds an identical 2x2 of pylons on a fresh site, scraps it either way, and reports the refund. */
    private static double refundFor(boolean cellByCell) {
        var e = TestSite.blank();
        var b = e.board;
        b.claim = 15;
        build(e, Machine.PYLON, 5, 5, 2, 2);
        e.recompute();
        zero(b);
        if (cellByCell) {
            for (int y = 5; y <= 6; y++)
                for (int x = 5; x <= 6; x++) e.demolishCell(x, y);
        } else {
            e.demolish(e.layout().at(5, 5));
        }
        assertEquals(0, b.count(Machine.PYLON), "either way the whole block is gone");
        return b.get(Res.MATTER);
    }

    /**
     * Checks the victory condition: placing the first Fusion Reactor latches {@link Board#won}
     * permanently and logs the moment, and that demolishing the reactor afterward leaves the
     * flag set — it's a record of having once reached the top of the tech tree, not a live
     * count of how many reactors currently stand. {@link Engine#place} doesn't itself check
     * {@link Engine#unlocked}, so this reaches the same effect a real Fission + Geometric
     * Synergy II research chain would gate, without having to research the whole tree first.
     */
    @Test
    @DisplayName("building the first Fusion Reactor wins, permanently")
    void firstFusionReactorWins() {
        var e = TestSite.blank();
        var b = e.board;
        b.claim = 15;
        assertFalse(b.won, "not won at the start of a fresh site");

        build(e, Machine.TOKAMAK, 7, 5, 1, 1);
        assertTrue(b.won, "the first reactor latches victory");
        assertEquals("Fusion Reactor online. The site has reached self-sustaining output.", b.log.get(0));

        e.demolish(e.layout().at(7, 5));
        assertTrue(b.won, "demolishing the reactor afterward doesn't undo the achievement");
    }

    /**
     * Checks that {@link Engine#collapse()} requires {@link Board#won} first — collapsing a
     * site that hasn't reached victory would just demolish everything for nothing, so it's a
     * no-op (returns {@code false}, {@link Board#collapsed} stays {@code false}) rather than
     * something a stray click could trigger by mistake.
     */
    @Test
    @DisplayName("collapse does nothing before victory")
    void collapseRequiresVictory() {
        var e = TestSite.blank();
        build(e, Machine.PYLON, 7, 6, 1, 1);
        assertFalse(e.collapse(), "no victory yet");
        assertFalse(e.board.collapsed);
        assertEquals(Machine.PYLON, e.board.cell[Board.idx(7, 6)], "nothing was touched");
    }

    /**
     * Checks the payoff of {@link Engine#collapse()}: once won, every standing machine except
     * the core is consumed, and the claim's northern half — every row strictly above the core's
     * fixed row, at the claim's full width — is filled solid with {@link Machine#MONOLITH},
     * which fuses into exactly one {@link Group} the same way any other same-kind rectangle
     * would, touching (and so immediately powered by) the core. Also checks it's repeatable:
     * collapsing again with nothing changed still succeeds and leaves the same block in place.
     */
    @Test
    @DisplayName("collapse fuses the whole site into one Monolith, touching the core")
    void collapseFusesEverythingIntoOneMonolith() {
        var e = TestSite.blank();
        var b = e.board;
        b.claim = 15;
        build(e, Machine.TOKAMAK, 7, 5, 1, 1);          // wins
        build(e, Machine.PYLON, 3, 3, 1, 1);
        build(e, Machine.SOLAR, 4, 4, 1, 1);
        assertTrue(b.won);

        assertTrue(e.collapse());
        assertTrue(b.collapsed);

        int margin = b.margin();                        // 0, since claim is the full 15x15
        int width = b.claim;                             // 15
        int height = Board.CY - margin;                  // 7 rows strictly above the core
        Group monolith = e.layout().at(margin, margin);
        assertEquals(Machine.MONOLITH, monolith.type);
        assertEquals(width, monolith.w);
        assertEquals(height, monolith.h);
        assertTrue(monolith.powered, "touches the core's row directly, so it's linked immediately");
        assertEquals(width * height, b.count(Machine.MONOLITH));
        assertEquals(0, b.count(Machine.PYLON), "every other machine was consumed");
        assertEquals(0, b.count(Machine.SOLAR));
        assertEquals(Machine.CORE, b.cell[Board.idx(Board.CX, Board.CY)], "the core survives its own collapse");

        assertTrue(e.collapse(), "collapsing again (e.g. after a further claim extension) just re-collapses cleanly");
    }

    /**
     * Checks that an underpowered site (demand exceeding supply) scales every machine's
     * throughput by the same satisfaction fraction, that a machine with no path to the core
     * stays unpowered, and that resource values remain finite throughout — no NaN or infinity
     * leaking out of the brownout math.
     */
    @Test
    @DisplayName("a shortfall scales every machine by the same fraction")
    void brownoutScalesEverything() {
        var e = TestSite.blank();
        var b = e.board;
        b.claim = 15;
        build(e, Machine.SOLAR, 7, 6, 1, 1);
        build(e, Machine.COND, 5, 7, 2, 2);
        build(e, Machine.PYLON, 7, 8, 1, 1);
        build(e, Machine.COND, 5, 9, 2, 2);
        e.recompute();
        run(e, 2);
        double sat = e.power().satisfaction();
        assertTrue(sat > 0 && sat < 1, () -> "expected a brownout, satisfaction was " + sat);
        assertEquals(sat, e.layout().at(5, 7).rate, 1e-9, "throughput follows the shortfall");

        build(e, Machine.SOLAR, 0, 14, 1, 1);
        e.recompute();
        assertFalse(e.layout().at(0, 14).powered, "a machine with no path to the core is dead");

        assertTrue(Arrays.stream(Res.values()).allMatch(r -> Double.isFinite(b.get(r))),
                "no NaN or infinity anywhere");
    }
}
