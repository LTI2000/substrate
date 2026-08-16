package substrate;

import javax.imageio.ImageIO;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Paints the site, or the whole window, into a PNG without a display, so the artwork can be
 * eyeballed on a headless machine. This is {@code not} a JUnit test: it is a {@code main()}
 * based CLI utility that happens to live in the test source root, so it gets compiled by
 * {@code mvn test-compile} but is never picked up or run by {@code mvn test}. It is meant to be
 * invoked directly, e.g. under {@code -Djava.awt.headless=true}, so a human (or an AI coding
 * session) can visually inspect the Java2D artwork without a display attached.
 *
 * <pre>
 *   mvn -q test-compile
 *   java -Djava.awt.headless=true -cp target/classes:target/test-classes substrate.RenderTool board 760 board.png
 *   java -Djava.awt.headless=true -cp target/classes:target/test-classes substrate.RenderTool window 1180 860 ui.png
 * </pre>
 */
public final class RenderTool {

    /** Not instantiated; every entry point is static. */
    private RenderTool() {
    }

    /**
     * Entry point. {@code args[0]} selects {@code "board"} (default) or {@code "window"}; the
     * remaining arguments are mode-specific size and output path, each with a sane default so
     * the tool runs with no arguments at all. In board mode, the panel is painted and discarded
     * for 25 frames at 40ms apart before the final capture, to let a second of animation go by
     * so the captured frame shows the artwork mid-motion rather than at its static initial pose.
     */
    public static void main(String[] args) throws Exception {
        String what = args.length > 0 ? args[0] : "board";
        if (what.equals("window")) {
            int w = args.length > 1 ? Integer.parseInt(args[1]) : 1180;
            int h = args.length > 2 ? Integer.parseInt(args[2]) : 860;
            var root = new Game(TestSite.midGame()).root();
            root.setSize(w, h);
            for (int pass = 0; pass < 4; pass++) layout(root);
            write(paint(root, w, h), args.length > 3 ? args[3] : "ui.png");
            System.out.println("painted window " + w + "x" + h);
            return;
        }
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 760;
        var engine = TestSite.sampler();
        var panel = new BoardPanel(engine, new BoardPanel.Handler() {
            @Override
            public void pressed(int x, int y, Group g) {
            }

            @Override
            public void hovered(int x, int y, Group g) {
            }
        });
        panel.setSize(size, size);
        panel.doLayout();
        for (int f = 0; f < 25; f++) {                 // let a second of animation go by first
            paint(panel, size, size);
            Thread.sleep(40);
        }
        write(paint(panel, size, size), args.length > 2 ? args[2] : "board.png");
        System.out.println("painted " + engine.layout().groups().size() + " groups, power "
                + Fmt.n(engine.power().supply()) + "/" + Fmt.n(engine.power().demand()));
    }

    /** Paints a component into a fresh {@link BufferedImage} of the given size, ink-filled first. */
    private static BufferedImage paint(Component c, int w, int h) {
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Theme.INK);
        g.fillRect(0, 0, w, h);
        c.paint(g);
        g.dispose();
        return img;
    }

    /** Writes an image out as a PNG at the given path. */
    private static void write(BufferedImage img, String path) throws Exception {
        ImageIO.write(img, "png", new File(path));
    }

    /** Recursively lays out a component tree, since a freshly sized container needs it before it paints correctly. */
    private static void layout(Component c) {
        if (c instanceof Container ct) {
            ct.doLayout();
            for (Component kid : ct.getComponents()) layout(kid);
        }
    }
}
