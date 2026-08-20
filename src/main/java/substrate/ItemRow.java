package substrate;

import javax.swing.JComponent;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.Map;

/** One entry in the build or research list: title, price, what it does, why you care. */
public final class ItemRow extends JComponent {

    /** What a row needs to render itself; implemented per-item by the build/research lists. */
    public interface Model {
        /** Display name of the item. */
        String title();
        /**
         * Status text (e.g. owned count, or the prerequisites still missing), or blank/null if
         * none. Drawn right-aligned beside the title when it fits there, on its own line
         * underneath when it doesn't — see {@link ItemRow#metaOnOwnLine()}.
         */
        String meta();
        /** Resource cost to acquire the item, empty if free or already {@link #done()}. */
        Map<Res, Double> cost();
        /** Short input/output description line. */
        String io();
        /** Flavor/explanation line. */
        String blurb();
        /** Whether the player currently has enough resources to buy this. */
        boolean affordable();
        /** Whether this row is the currently selected item. */
        boolean selected();
        /** Whether the item is already owned/researched (hides the cost line). */
        boolean done();
    }

    private static final Font TITLE = Theme.mono(12);
    private static final Font SMALL = Theme.mono(10);
    /**
     * This row's fixed width, handed in at construction (currently {@code Game.RESEARCH_COL_W},
     * one column of the research page). There is no {@link javax.swing.LayoutManager} anywhere in
     * this UI — every component gets an explicit {@code setBounds} call computed once, rather
     * than a container reflowing children off their preferred sizes — so a row is told its width
     * up front and reports it straight back out through {@link #getPreferredSize()}, rather than
     * reading it live off a dynamically-sized parent. This was a hardcoded 330 while the only
     * caller was the fixed-width side panel; it became a field when the research list moved to
     * the main panel and got laid out in columns whose width derives from the window geometry.
     */
    private final int width;

    private final Model model;
    /** Whether the mouse is currently over the row; drives the hover highlight. */
    private boolean hover;

    /**
     * @param width   the fixed width this row will be given by its caller's {@code setBounds},
     *                used for text wrapping and reported back from {@link #getPreferredSize()}
     * @param onClick invoked when the row is clicked, regardless of affordability
     */
    public ItemRow(int width, Model model, Runnable onClick) {
        this.width = width;
        this.model = model;
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e)  { hover = true; repaint(); }
            @Override public void mouseExited(MouseEvent e)   { hover = false; repaint(); }
            // mousePressed, not mouseClicked: MOUSE_CLICKED is only synthesized when the
            // pointer doesn't move at all between press and release, so any tiny jitter
            // (trackpads, imprecise mice) silently drops the click. Acting on press directly
            // is the same fix BoardPanel already uses for board taps/placement.
            @Override public void mousePressed(MouseEvent e)  { onClick.run(); }
        });
    }

    /** Text column width: {@link #width} minus fixed left/right padding, with a floor so wrapping never collapses to nothing. */
    private int textWidth() { return Math.max(80, width - 16); }

    /**
     * Whether {@link Model#meta()} needs a line of its own instead of sitting right-aligned
     * beside the title: true when the title and the meta text together (plus a 10px gap so they
     * never touch) would be wider than the row. Without this the two simply overprinted each
     * other, which a long title next to a long "needs Claim Extension II, Titanium Alloys" does
     * routinely. Both {@link #getPreferredSize()} and {@link #paintComponent} ask this same
     * question, so the row's height and its painting can't disagree about where the meta went.
     */
    private boolean metaOnOwnLine() {
        String meta = model.meta();
        if (meta == null || meta.isEmpty()) return false;
        return getFontMetrics(TITLE).stringWidth(model.title()) + 10
                + getFontMetrics(SMALL).stringWidth(meta) > width - 12;
    }

    /**
     * Computed from the wrapped line counts of {@link Model#io()} and {@link Model#blurb()},
     * plus a cost line unless {@link Model#done()} and a line for {@link Model#meta()} when it
     * doesn't fit beside the title ({@link #metaOnOwnLine()}). The caller ({@code Game.positionTechRows})
     * calls this once to size the row's {@code setBounds} rect and never again unless the
     * underlying tech's done-state changes and everything gets repositioned from scratch — there
     * is no live resizing in response to this component's own size changing, since it never does.
     */
    @Override public Dimension getPreferredSize() {
        var fmSmall = getFontMetrics(SMALL);
        int lines = 0;
        if (metaOnOwnLine()) lines++;                                // meta, pushed off the title line
        if (!model.done()) lines++;                                  // cost
        lines += Ui.wrap(model.io(), fmSmall, textWidth()).size();
        lines += Ui.wrap(model.blurb(), fmSmall, textWidth()).size();
        int h = 8 + getFontMetrics(TITLE).getHeight() + lines * (fmSmall.getHeight() - 1) + 6;
        return new Dimension(width, h);
    }

    /** Draws the row: background/border, title and meta, the cost line (if not done), then the io and blurb text wrapped to width. */
    @Override protected void paintComponent(Graphics graphics) {
        var g = (Graphics2D) graphics;
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        boolean dim = !model.affordable() && !model.done();
        Composite old = g.getComposite();
        if (dim) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));

        g.setColor(model.selected() ? Theme.alpha(Theme.AMBER, 26)
                : hover ? new Color(52, 42, 28, 200) : new Color(52, 42, 28, 90));
        g.fill(new Rectangle2D.Double(0, 0, getWidth() - 1, getHeight() - 3));
        g.setColor(model.selected() ? Theme.AMBER : Theme.LINE);
        g.draw(new Rectangle2D.Double(0, 0, getWidth() - 1, getHeight() - 3));

        int x = 6;
        g.setFont(TITLE);
        var fmT = g.getFontMetrics();
        int y = 4 + fmT.getAscent();
        g.setColor(Theme.CHALK);
        g.drawString(model.title(), x, y);
        g.setFont(SMALL);
        var fm = g.getFontMetrics();
        String meta = model.meta();
        boolean metaBelow = metaOnOwnLine();
        if (meta != null && !meta.isEmpty() && !metaBelow) {
            g.setColor(model.done() ? Theme.GOOD : Theme.DIM);
            g.drawString(meta, getWidth() - 6 - fm.stringWidth(meta), y);
        }

        y += 2;
        if (metaBelow) {
            y += fm.getHeight() - 1;
            g.setColor(model.done() ? Theme.GOOD : Theme.DIM);
            g.drawString(meta, x, y);
        }
        if (!model.done()) {
            y += fm.getHeight() - 1;
            float cx = x;
            boolean first = true;
            for (var e : model.cost().entrySet()) {
                if (!first) {
                    g.setColor(Theme.DIM);
                    g.drawString(" \u00b7 ", cx, y);
                    cx += fm.stringWidth(" \u00b7 ");
                }
                first = false;
                boolean have = model.done() || engineHas(e.getKey(), e.getValue());
                String s = Fmt.n(e.getValue()) + " " + e.getKey().lower();
                g.setColor(have ? Theme.alpha(Theme.CHALK, 200) : Theme.HOT);
                g.drawString(s, cx, y);
                cx += fm.stringWidth(s);
            }
        }
        for (String line : List.of(model.io(), model.blurb())) {
            boolean blurb = line.equals(model.blurb());
            g.setColor(blurb ? Theme.DIM : Theme.ICE);
            for (String piece : Ui.wrap(line, fm, textWidth())) {
                y += fm.getHeight() - 1;
                g.drawString(piece, x, y);
            }
        }
        g.setComposite(old);
    }

    /**
     * Set once by {@code Game}'s constructor so rows can colour prices by what is in stock.
     *
     * <p>This is an informal singleton / dependency-injection workaround: {@link Model}
     * has no reference to a {@link Board}, and threading one through the interface (and
     * every implementation of it) just for this one cosmetic check would spread a
     * dependency across code that otherwise doesn't need it. A shared static is the
     * cheaper trade-off here since there is only ever one {@link Board} per process.
     */
    static Board stock;

    /** Whether {@code stock} holds at least {@code amount} of {@code r}, tolerant of floating-point drift. */
    private static boolean engineHas(Res r, double amount) {
        return stock == null || stock.get(r) >= amount - 1e-9;
    }
}
