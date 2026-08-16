package substrate;

import javax.swing.JComponent;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * The stock ledger across the top: power first, then everything discovered. Lays itself out in a
 * fixed grid of fixed-size cells — a constant column count, not one computed from the panel's
 * live width — wrapping to as many rows as the (also fixed) column count needs. There is no
 * {@link LayoutManager} involved anywhere in this UI: {@code Game} gives this panel
 * one explicit {@code setBounds} rect and never touches it again, so the grid math below only
 * ever needs to answer "where does cell N go," not "how much room do I have this time."
 */
public final class LedgerPanel extends JComponent {

    /**
     * One rendered ledger cell: a label, its current value, a status note, and the three colors
     * for them. Built fresh from engine state on every {@link #cells()} call rather than cached,
     * since the ledger is cheap to recompute and this keeps it always in sync.
     */
    private record Cellule(String key, String value, String note, Color keyColor, Color valueColor, Color noteColor) {}

    private final Engine engine;
    /** Fixed cell dimensions the grid layout is built from. */
    private static final int CELL_W = 128, CELL_H = 44;
    /**
     * Fixed column count: {@link Res} has 12 constants plus the Power cell always shown first,
     * so 6 columns comfortably covers every resource ever discovered in 3 rows without the grid
     * needing to know how wide its container is.
     */
    private static final int COLS = 6;

    /** @param engine simulation to read power and resource state from */
    public LedgerPanel(Engine engine) {
        this.engine = engine;
        setOpaque(false);
    }

    /**
     * Builds the current list of cells: power always first, then one cell per discovered
     * resource in {@link Res} declaration order.
     */
    private List<Cellule> cells() {
        var out = new ArrayList<Cellule>();
        var p = engine.power();
        String note = p.demand() > 0 ? Fmt.pct(p.satisfaction()) + " supplied" : "idle";
        if (p.capacity() > 0) note += "  buffer " + Fmt.n(p.stored()) + "/" + Fmt.n(p.capacity());
        out.add(new Cellule("Power", Fmt.n(p.supply()) + " / " + Fmt.n(p.demand()), note,
                Theme.DIM, p.satisfaction() < 0.999 ? Theme.HOT : Theme.AMBER, Theme.DIM));
        for (Res r : Res.values()) {
            if (!engine.board.discovered(r)) continue;
            double flow = engine.flowOf(r);
            Color noteColor = flow > 0.001 ? Theme.GOOD : flow < -0.001 ? Theme.HOT : Theme.DIM;
            out.add(new Cellule(r.label, Fmt.n(engine.board.get(r)),
                    Math.abs(flow) > 0.001 ? Fmt.rate(flow) : "", r.color, Theme.CHALK, noteColor));
        }
        return out;
    }

    /**
     * Reports a size tall enough for every cell at the fixed {@link #COLS} column count. Nothing
     * in this UI consults a component's preferred size to lay it out — {@code Game} sets this
     * panel's bounds once via {@code setBounds} — but the method stays for the same reason
     * {@link Group#fusionFactor} keeps its own doc even though its caller could inline the
     * formula: it's the one place the "how tall does the ledger need to be" answer lives.
     */
    @Override public Dimension getPreferredSize() {
        int rows = (int) Math.ceil(cells().size() / (double) COLS);
        return new Dimension(COLS * CELL_W, Math.max(CELL_H, rows * CELL_H));
    }

    /** Paints the paper background and every cell's border, key, value, and note text. */
    @Override protected void paintComponent(Graphics graphics) {
        var g = (Graphics2D) graphics;
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        var list = cells();
        int cols = COLS;
        double cw = getWidth() / (double) cols;
        int rows = (int) Math.ceil(list.size() / (double) cols);
        g.setColor(Theme.PAPER);
        g.fillRect(0, 0, getWidth(), rows * CELL_H);
        for (int i = 0; i < list.size(); i++) {
            var c = list.get(i);
            double x = (i % cols) * cw, y = (i / cols) * CELL_H;
            g.setColor(Theme.LINE);
            g.draw(new Rectangle2D.Double(x, y, cw, CELL_H));
            g.setFont(Theme.mono(9));
            g.setColor(c.keyColor());
            g.drawString(c.key().toUpperCase(), (float) x + 7, (float) y + 13);
            g.setFont(Theme.mono(16));
            g.setColor(c.valueColor());
            g.drawString(c.value(), (float) x + 7, (float) y + 30);
            g.setFont(Theme.mono(9));
            g.setColor(c.noteColor());
            g.drawString(c.note(), (float) x + 7, (float) y + 40);
        }
    }
}
