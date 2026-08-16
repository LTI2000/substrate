package substrate;

import javax.swing.JComponent;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * The stock ledger across the top: power first, then everything discovered. Lays itself out in a
 * responsive grid of fixed-size cells, wrapping to as many rows as needed for the current width.
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

    /** @return how many {@link #CELL_W}-wide columns fit in the current width, at least 1. */
    private int columns() { return Math.max(1, Math.min(cells().size(), getWidth() / CELL_W)); }

    /**
     * Reports a height tall enough for every cell, wrapped across the current width — Swing uses
     * this to reserve layout space before {@link #paintComponent} runs.
     */
    @Override public Dimension getPreferredSize() {
        int count = cells().size();
        int cols = Math.max(1, Math.min(count, (getWidth() > 0 ? getWidth() : 900) / CELL_W));
        int rows = (int) Math.ceil(count / (double) cols);
        return new Dimension(300, Math.max(CELL_H, rows * CELL_H));
    }

    /** Paints the paper background and every cell's border, key, value, and note text. */
    @Override protected void paintComponent(Graphics graphics) {
        var g = (Graphics2D) graphics;
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        var list = cells();
        int cols = columns();
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
