package substrate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Java2D artwork itself, off a real (if invisible) rendering pipeline: it has to
 * paint without a display attached, and its animation has to actually move, and stop moving
 * when a site loses power. These tests paint into an in-memory {@link BufferedImage} rather
 * than asserting on internal timer or animation state, so they exercise the same code path a
 * human eye would judge the artwork by.
 */
@DisplayName("artwork")
class ArtTest {

    /**
     * Paints one frame of the given panel into a fresh square image and returns it. The image
     * is pre-filled with {@link Theme#INK} so any area the panel fails to paint is still a
     * known, distinct colour rather than default black.
     */
    private static BufferedImage frame(BoardPanel panel, int size) {
        var img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Theme.INK);
        g.fillRect(0, 0, size, size);
        panel.paint(g);
        g.dispose();
        return img;
    }

    /** Builds a square {@link BoardPanel} over the given engine, with no-op click/hover handlers. */
    private static BoardPanel panelFor(Engine e, int size) {
        var panel = new BoardPanel(e, new BoardPanel.Handler() {
            @Override
            public void pressed(int x, int y, Group g, boolean shift) {
            }

            @Override
            public void hovered(int x, int y, Group g) {
            }
        });
        panel.setSize(size, size);
        panel.doLayout();
        return panel;
    }

    /**
     * Counts the distinct RGB values in a sparse (every second pixel) sample of the image.
     * Used as a coarse smoke test — a proxy for "did all this custom gradient/shading code
     * actually draw something complex" — not a pixel-exact golden-image comparison.
     */
    private static long distinctColours(BufferedImage img) {
        var seen = new HashSet<Integer>();
        for (int y = 0; y < img.getHeight(); y += 2)
            for (int x = 0; x < img.getWidth(); x += 2) seen.add(img.getRGB(x, y));
        return seen.size();
    }

    /**
     * Checks that a board built from every machine kind paints offscreen and produces a richly
     * coloured image, using the {@link #distinctColours} threshold as a smoke test for coverage
     * rather than an exact match against a reference image.
     */
    @Test
    @DisplayName("every machine paints offscreen, in colour")
    void everyMachinePaints() {
        var e = TestSite.sampler();
        assertTrue(e.layout().groups().size() > 20, "the sampler should cover the whole catalogue");
        var img = frame(panelFor(e, 760), 760);
        assertTrue(distinctColours(img) > 2000, "the board should be far richer than a flat fill");
    }

    /**
     * Confirms animation liveness with a genuine visual pixel-diff heuristic rather than by
     * inspecting internal timer state: paints two frames a real {@link Thread#sleep} apart
     * (necessary because the artwork is driven by {@link System#nanoTime()}, so wall-clock time
     * actually has to pass), counts differing pixels between them, and asserts nonzero churn
     * while the site is powered. It then forces every {@link Group#powered} flag false and
     * repeats the measurement, asserting the churn drops sharply once the site goes dark.
     */
    @Test
    @DisplayName("the machines are animated, and freeze when the power goes out")
    void animationMovesAndStops() throws Exception {
        var e = TestSite.sampler();
        var panel = panelFor(e, 760);
        var a = frame(panel, 760);
        Thread.sleep(320);                                       // the artwork is driven by wall clock time
        var b = frame(panel, 760);
        assertNotEquals(0, difference(a, b), "nothing moved between frames");

        for (Group g : e.layout().groups()) g.powered = false;    // a dead site should be still
        var c = frame(panel, 760);
        Thread.sleep(320);
        var d = frame(panel, 760);
        assertTrue(difference(c, d) < difference(a, b) / 4,
                "an unpowered site should be all but frozen");
    }

    /** Counts pixels that differ between two same-sized images, sampling every second pixel. */
    private static int difference(BufferedImage a, BufferedImage b) {
        int n = 0;
        for (int y = 0; y < a.getHeight(); y += 2)
            for (int x = 0; x < a.getWidth(); x += 2) if (a.getRGB(x, y) != b.getRGB(x, y)) n++;
        return n;
    }

    /**
     * Checks that the whole Swing window — board, side panel and ledger together — lays out
     * over several passes and paints without throwing, using the {@link #distinctColours}
     * threshold as a coarse smoke test that every part of the UI actually drew something.
     */
    @Test
    @DisplayName("the whole window lays out and paints")
    void windowPaints() {
        var root = new Game(TestSite.midGame()).root();
        root.setSize(1180, 860);
        for (int pass = 0; pass < 4; pass++) layout(root);
        var img = new BufferedImage(1180, 860, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Theme.INK);
        g.fillRect(0, 0, 1180, 860);
        root.paint(g);
        g.dispose();
        assertTrue(distinctColours(img) > 1500, "panels, ledger and board should all have drawn");
    }

    /** Recursively lays out a component tree, since a freshly sized container needs it before it paints correctly. */
    private static void layout(java.awt.Component c) {
        if (c instanceof java.awt.Container ct) {
            ct.doLayout();
            for (java.awt.Component kid : ct.getComponents()) layout(kid);
        }
    }
}
