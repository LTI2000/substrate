package substrate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A whole session without a screen: survey, tap, research, build, save, come back later.
 * These tests hot-swap the JVM-wide {@code user.home} system property to a JUnit
 * {@link TempDir} before each test and restore it afterwards, because {@link Save} hardcodes
 * the path {@code ~/.substrate/site.txt} with no injectable override. That makes {@code
 * user.home} global mutable state for the whole JVM: this class is not safe to run in parallel
 * with anything else — in this suite or otherwise — that reads or writes {@code user.home}.
 */
@DisplayName("session")
class SessionTest {

    /** JUnit-managed temporary directory substituted in for the real home directory. */
    @TempDir
    Path home;
    /** The real {@code user.home}, captured so it can be restored after each test. */
    private String realHome;

    /** Points {@code user.home} at the temp directory so {@link Save} cannot touch the real home directory. */
    @BeforeEach
    void redirectHome() {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());       // keep saves out of the real home directory
    }

    /** Restores the real {@code user.home}, undoing {@link #redirectHome()}. */
    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", realHome);
    }

    /** An engine over a freshly surveyed board, using a fixed random seed so the fixture is repeatable. */
    private static Engine surveyed() {
        var b = new Board();
        OreGen.survey(b, new Random(42));                       // fixed seed, so the test is repeatable
        return new Engine(b);
    }

    /** Checks that a survey deposits a reasonable amount of ore and places the core at its fixed board position. */
    @Test
    @DisplayName("the survey lays down ore and the core sits at H8")
    void surveyIsSane() {
        var b = surveyed().board;
        assertTrue(Arrays.stream(b.ore).filter(Objects::nonNull).count() > 20);
        assertEquals(Machine.CORE, b.cell[Board.idx(Board.CX, Board.CY)]);
    }

    /** Checks that hand-tapping the core accumulates matter one-for-one, and that TOOLS0 research raises the click yield fivefold. */
    @Test
    @DisplayName("hand taps pay for the first research, which then pays back fivefold")
    void tapAndResearch() {
        var e = surveyed();
        for (int i = 0; i < 100; i++) e.tapCore();
        assertEquals(100, e.board.get(Res.MATTER), 1e-9);
        assertTrue(e.research(Tech.TOOLS0));
        assertEquals(5.0, e.clickYield(), 1e-9);
    }

    /** Checks that placement succeeds inside the current claim, fails outside it, and that mining rigs require ore underneath. */
    @Test
    @DisplayName("placement respects the claim and the ore underneath")
    void placementRules() {
        var e = surveyed();
        e.board.set(Res.MATTER, 100_000);
        assertTrue(e.place(Machine.SOLAR, Board.CX + 1, Board.CY), "beside the core is fine");
        assertFalse(e.place(Machine.SOLAR, 0, 0), "the far corner is outside the opening claim");
        int left = Board.idx(Board.CX - 1, Board.CY);
        assertTrue(e.board.ore[left] != null || !e.place(Machine.MINER, Board.CX - 1, Board.CY),
                "rigs only go on ore");
    }

    /**
     * Checks that mining rigs placed with no path back to the core stay unpowered and idle,
     * and that once a chain of pylons and extra solar collectors links them in, they power up
     * and ore begins to flow — though under-provisioned power still leaves the site browned out.
     */
    @Test
    @DisplayName("rigs are dead until they are linked, then they produce")
    void linkingBringsTheSiteToLife() {
        var e = surveyed();
        var b = e.board;
        b.set(Res.MATTER, 100_000);
        e.place(Machine.SOLAR, Board.CX + 1, Board.CY);         // one collector, next to the core
        int placed = 0;
        for (int y = 4; y < 11 && placed < 4; y++)
            for (int x = 4; x < 11 && placed < 4; x++)
                if (b.ore[Board.idx(x, y)] != null && e.place(Machine.MINER, x, y)) placed++;
        e.recompute();
        for (int i = 0; i < 100; i++) e.tick(0.1);
        assertTrue(e.layout().groups().stream().anyMatch(g -> g.type == Machine.MINER && !g.powered),
                "with no path back to the core they are stranded");

        for (int y = 4; y < 11; y++)
            for (int x = 4; x < 11; x++) {
                b.set(Res.MATTER, 100_000);
                e.place(Machine.PYLON, x, y);
            }
        for (int[] p : new int[][]{{4, 3}, {5, 3}, {4, 2}, {5, 2}}) {
            b.set(Res.MATTER, 100_000);
            e.place(Machine.SOLAR, p[0], p[1]);
        }
        e.recompute();
        for (int i = 0; i < 100; i++) e.tick(0.1);
        assertTrue(Arrays.stream(Res.values()).anyMatch(r -> r.isOre() && b.get(r) > 0), "ore starts flowing");
        assertTrue(e.power().satisfaction() < 1, "and collectors alone leave the site browned out");
    }

    /**
     * Checks that saving a board with {@link Save#write} and reading it back with
     * {@link Save#read} reproduces the claim, tech, resources, cell layout and ore richness
     * exactly, and that {@link Save#wipe} clears the save file so a subsequent read returns
     * {@code null}.
     */
    @Test
    @DisplayName("a site survives the round trip through the save file")
    void saveRoundTrip() {
        var e = surveyed();
        var b = e.board;
        b.set(Res.MATTER, 100_000);
        e.research(Tech.TOOLS0);
        e.place(Machine.SOLAR, Board.CX + 1, Board.CY);
        e.recompute();

        Save.write(b);
        Board back = Save.read();
        assertNotNull(back);
        assertEquals(b.claim, back.claim);
        assertEquals(b.tech, back.tech);
        assertEquals(b.get(Res.MATTER), back.get(Res.MATTER), 1e-6);
        assertArrayEqualsCells(b, back);
        assertTrue(Arrays.equals(b.rich, back.rich), "ore richness survives");

        Save.wipe();
        assertNull(Save.read(), "abandoning the site clears the file");
    }

    /** Asserts that every cell of two boards matches, one at a time, so a mismatch names the offending cell. */
    private static void assertArrayEqualsCells(Board a, Board b) {
        for (int i = 0; i < a.cell.length; i++)
            assertEquals(a.cell[i], b.cell[i], "cell " + i);
    }

    /**
     * Checks that a long absence (thirty minutes) is caught up in a single {@code runUnattended}
     * call worth exactly that many ticks' production, that matter never runs backwards while
     * away, and that a short absence (one second) is too brief to register any catch-up at all.
     */
    @Test
    @DisplayName("time away is caught up in one go, and short absences are ignored")
    void idleCatchUp() {
        var e = surveyed();
        var b = e.board;
        b.set(Res.MATTER, 100_000);
        e.place(Machine.SOLAR, Board.CX + 1, Board.CY);
        e.recompute();
        double before = b.get(Res.MATTER);
        assertEquals(1800.0, e.runUnattended(30 * 60 * 1000), 1e-9);
        assertTrue(b.get(Res.MATTER) >= before, "matter never goes backwards while away");
        assertEquals(0.0, e.runUnattended(1000), 1e-9);
    }
}
