package substrate;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;

/**
 * Procedurally drawn, theme-matched replacements for the plain OS cursors {@link BoardPanel}
 * switches between. Built the same way as every other visual in this project — raw {@link
 * Graphics2D} calls rather than a loaded image (see {@link Art}'s class doc: there is no sprite
 * or asset pipeline anywhere in this codebase, and cursors are no exception).
 *
 * <p>{@link #DEMOLISH} and {@link #TOGGLE} draw the same pictograms, at the same proportions, as
 * the DISM and PWR {@code ToolIcon} glyphs ({@code Game#paintDismantleGlyph}/{@code
 * Game#paintPowerGlyph}) — the cursor is literally that tool's icon, so the button you press and
 * the pointer it arms are the same drawing. Both pictograms are open outlines rather than filled
 * shapes precisely so they work at pointer size without painting a solid block over whatever is
 * underneath; the only thing the cursor adds is the dark halo of {@link #outlineThenColor}, a
 * legibility device the tile doesn't need because its own wash is already dark.
 * {@link #POINT} has no equivalent tool icon; it's a plain crosshair in the same amber the ghost
 * placement preview uses, so the "no tool armed" pointer still belongs to the same palette.
 *
 * <p>Each cursor is rendered once, at class-load time, into a transparent {@link BufferedImage}
 * and handed to {@link Toolkit#createCustomCursor}, which requires a live display and throws
 * {@link HeadlessException} under {@code -Djava.awt.headless=true} — the mode the test suite's
 * offscreen renderer runs in (see {@code RenderTool}). Nothing in the test suite currently
 * touches {@link BoardPanel}, so this class is never loaded headless in practice, but every
 * cursor is still built defensively: a failure just falls back to the closest predefined {@link
 * Cursor} constant instead of taking the whole class (and everything that references it) down.
 */
final class Cursors {
    private Cursors() {}

    /** Edge length of the square cursor image, in pixels; AWT scales this to the platform's actual cursor size. */
    private static final int SIZE = 32;

    /** No tool armed (including while a machine is armed for placement): a thin amber crosshair. */
    static final Cursor POINT = build("point", Cursors::paintPoint, Cursor.DEFAULT_CURSOR);
    /** Demolish tool active: the DISM tool's own glyph, a red targeting reticle, in {@code paintDismantleGlyph}'s danger red. */
    static final Cursor DEMOLISH = build("demolish", Cursors::paintDemolish, Cursor.CROSSHAIR_CURSOR);
    /** Power-switch tool active: the same broken-ring-and-tick glyph as the PWR tool icon. */
    static final Cursor TOGGLE = build("toggle", Cursors::paintToggle, Cursor.HAND_CURSOR);

    @FunctionalInterface private interface Painter { void paint(Graphics2D g, double c); }

    /**
     * Renders {@code painter} into a transparent {@value #SIZE}x{@value #SIZE} image and wraps
     * it as a custom cursor hot-spotted dead center — every glyph here is a symmetric reticle
     * centered on the click point, not a directional pointer with a corner hotspot — or returns
     * the predefined {@code fallback} cursor if this AWT environment can't create custom cursors
     * at all.
     */
    private static Cursor build(String name, Painter painter, int fallback) {
        try {
            var img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            painter.paint(g, SIZE / 2.0);
            g.dispose();
            return Toolkit.getDefaultToolkit().createCustomCursor(img, new Point(SIZE / 2, SIZE / 2), name);
        } catch (RuntimeException e) {
            return Cursor.getPredefinedCursor(fallback);
        }
    }

    /**
     * Every glyph below is stroked twice at the same coordinates: once thick and near-black for
     * contrast, then again thinner in the actual accent color on top. Without the dark halo a
     * bright amber or chalk line vanishes against the survey chart's own pale ore/paper tones.
     */
    private static void outlineThenColor(Graphics2D g, Color color, float thick, float thin, Runnable strokes) {
        g.setColor(new Color(0, 0, 0, 140));
        g.setStroke(new BasicStroke(thick, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        strokes.run();
        g.setColor(color);
        g.setStroke(new BasicStroke(thin, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        strokes.run();
    }

    /** Thin crosshair with a small solid dot at the center, in the same amber as the ghost placement preview. */
    private static void paintPoint(Graphics2D g, double c) {
        double len = c * 0.68, gap = c * 0.2;
        outlineThenColor(g, Theme.alpha(Theme.AMBER, 235), 3.4f, 1.6f, () -> {
            g.draw(new Line2D.Double(c - len, c, c - gap, c));
            g.draw(new Line2D.Double(c + gap, c, c + len, c));
            g.draw(new Line2D.Double(c, c - len, c, c - gap));
            g.draw(new Line2D.Double(c, c + gap, c, c + len));
        });
        g.setColor(Theme.alpha(Theme.AMBER, 235));
        g.fill(new Ellipse2D.Double(c - c * 0.06, c - c * 0.06, c * 0.12, c * 0.12));
    }

    /** The DISM tool's own glyph (a ring with an X through it plus four outward tick marks), in {@link Theme#HOT} red. */
    private static void paintDemolish(Graphics2D g, double c) {
        double rad = c * 0.72, in = rad * 0.55, tickIn = rad * 1.05, tickOut = rad * 1.3;
        outlineThenColor(g, Theme.alpha(Theme.HOT, 235), 3.6f, 1.8f, () -> {
            g.draw(new Ellipse2D.Double(c - rad, c - rad, rad * 2, rad * 2));
            g.draw(new Line2D.Double(c - in, c - in, c + in, c + in));
            g.draw(new Line2D.Double(c + in, c - in, c - in, c + in));
            g.draw(new Line2D.Double(c, c - tickOut, c, c - tickIn));
            g.draw(new Line2D.Double(c, c + tickIn, c, c + tickOut));
            g.draw(new Line2D.Double(c - tickOut, c, c - tickIn, c));
            g.draw(new Line2D.Double(c + tickIn, c, c + tickOut, c));
        });
    }

    /** The PWR tool's own glyph (a broken ring plus a vertical tick through the gap), in {@link Theme#CHALK}. */
    private static void paintToggle(Graphics2D g, double c) {
        double rad = c * 0.62;
        outlineThenColor(g, Theme.alpha(Theme.CHALK, 235), 3.8f, 2.0f, () -> {
            g.draw(new Arc2D.Double(c - rad, c - rad, rad * 2, rad * 2, 145, 250, Arc2D.OPEN));
            g.draw(new Line2D.Double(c, c - rad * 1.3, c, c - rad * 0.1));
        });
    }
}
