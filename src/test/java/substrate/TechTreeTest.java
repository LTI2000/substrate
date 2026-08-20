package substrate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the research tree's layout, which is the part of that view a human eye can't verify at a
 * glance: that no tech is ever drawn level with or above something it requires, that no two tiles
 * land on top of each other, and that every prerequisite in {@link Tech} actually gets a trace
 * drawn for it. The layout is computed once in {@link TechTree}'s constructor from static data, so
 * these assertions hold for the life of the component — there is no "after a repaint" case to
 * cover. The last test paints the whole thing headless, the same smoke check {@code ArtTest}
 * applies to the board.
 */
@DisplayName("research tree")
class TechTreeTest {

    /** The page width {@code Game} gives the tree, so the test lays out at the real size. */
    private static final int WIDTH = 778;

    /** A tree over a mid-game site, with no-op click/hover handlers. */
    private static TechTree tree() {
        return new TechTree(TestSite.midGame(), WIDTH, new TechTree.Handler() {
            @Override public void researched(Tech t) { }
            @Override public void hovered(Tech t) { }
        });
    }

    /** Every edge has to point downwards, which is the whole premise of reading the thing as a tree. */
    @Test
    @DisplayName("every tech sits strictly below everything it requires")
    void tiersRespectPrerequisites() {
        var tree = tree();
        for (Tech t : Tech.values()) {
            for (Tech p : t.requires()) {
                assertTrue(tree.tierOf(p) < tree.tierOf(t),
                        p + " gates " + t + " but is drawn on tier " + tree.tierOf(p)
                                + " against its tier " + tree.tierOf(t));
            }
        }
    }

    /** Tiles are placed by hand from a column grid, so nothing stops two from colliding but the arithmetic. */
    @Test
    @DisplayName("no two tiles overlap and none escapes the page")
    void tilesAreDisjointAndInside() {
        var tree = tree();
        var techs = Tech.values();
        for (Tech t : techs) {
            Rectangle r = tree.boxOf(t);
            assertTrue(r.x >= 0 && r.getMaxX() <= WIDTH, t + " is drawn outside the page: " + r);
            assertTrue(r.y >= 0 && r.getMaxY() <= tree.getPreferredSize().height,
                    t + " is drawn past the bottom of the page: " + r);
        }
        for (int i = 0; i < techs.length; i++) {
            for (int j = i + 1; j < techs.length; j++) {
                assertTrue(!tree.boxOf(techs[i]).intersects(tree.boxOf(techs[j])),
                        techs[i] + " and " + techs[j] + " overlap");
            }
        }
    }

    /** One trace per prerequisite: a dropped edge would silently hide a dependency from the player. */
    @Test
    @DisplayName("one trace is drawn per prerequisite")
    void everyPrerequisiteIsDrawn() {
        int expected = 0;
        for (Tech t : Tech.values()) expected += t.requires().size();
        assertEquals(expected, tree().edgeCount());
    }

    /** The same headless-paint smoke test the board gets: it has to draw something, and not throw. */
    @Test
    @DisplayName("the tree paints without a display")
    void paints() {
        var tree = tree();
        var size = tree.getPreferredSize();
        tree.setSize(size);
        var img = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Theme.INK);
        g.fillRect(0, 0, size.width, size.height);
        tree.paint(g);
        g.dispose();

        int painted = 0;
        for (int y = 0; y < size.height; y += 2) {
            for (int x = 0; x < size.width; x += 2) {
                if (img.getRGB(x, y) != Theme.INK.getRGB()) painted++;
            }
        }
        assertTrue(painted > 20_000, "tiles and traces should have covered a good part of the page");
    }
}
