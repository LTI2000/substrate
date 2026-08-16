package substrate;

import javax.swing.JComponent;
import javax.swing.event.MouseInputAdapter;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.geom.*;

/**
 * The site itself, drawn as a survey chart with machines standing on it.
 *
 * <p>This is the interactive board view: it owns rendering ({@link #paintComponent}), mouse
 * input and hover/drag tracking, and a small set of bespoke, hand-timed effects (the
 * core-tap "+N" floater and its ripple) with no animation framework behind them.
 *
 * <p><b>Ad hoc tween system.</b> Both the floater and the core ripple are animated by
 * comparing a wall-clock timestamp ({@link #clock()}, derived from {@link System#nanoTime()}
 * at construction) against a recorded start time, and turning the elapsed age into a linear
 * progress fraction such as {@code p = (t - startedAt) / 0.9} that is then used to interpolate
 * alpha and position by hand (see {@link #flashCore(String)}, {@link #coreRipple}, and the
 * floater block in {@link #paintComponent}). There is no shared animation/tween abstraction —
 * every effect's state (start time, text, whether it is active) is just loose fields on this
 * class, and every effect re-derives its own progress fraction inline.
 *
 * <p><b>Column letters are spreadsheet-style arithmetic.</b> The ruler draws column headers
 * with {@code (char) ('A' + i)} (see {@link #rulers}) — the same trick used by
 * {@link Group#where()} for machine coordinates. Both are silently limited to 26 columns;
 * that's fine only because the board is currently {@link Board#W} = 15 wide, and would need
 * revisiting (e.g. AA, AB, ...) if the board ever grew past a single letter's worth of columns.
 *
 * <p><b>Ore richness is drawn as dot pips, not a number.</b> {@link #drawPips} lays out a small
 * hand-centered grid of dots (three per row) rather than printing a numeral, purely as visual
 * flavor for the survey-chart look — it is a tiny bespoke layout algorithm with no reuse
 * elsewhere.
 */
public final class BoardPanel extends JComponent {

    /** Callbacks for board interaction, implemented by the owning screen. */
    public interface Handler {
        /** Left press on a cell, including drag-through. */
        void pressed(int x, int y, Group group);
        /** Pointer moved onto a new cell. */
        void hovered(int x, int y, Group group);
    }

    /** Panel padding, and the width/height reserved for the column/row ruler, in pixels. */
    private static final int PAD = 8, RULER_L = 15, RULER_T = 12;

    /** The simulation being visualised; read-only from this panel's point of view. */
    private final Engine engine;
    /** Receives press/hover callbacks translated from pixel coordinates into board cells. */
    private final Handler handler;
    /** Reference instant for {@link #clock()}; captured once so animations have a stable zero point. */
    private final long start = System.nanoTime();

    /** Machine currently armed for placement, previewed under the cursor; {@code null} when not placing. */
    private Machine ghost;
    /** Whether the demolish tool is active, previewing the fused block under the cursor for removal. */
    private boolean demolishing;
    /** Whether the power-switch tool is active, previewing the fused block under the cursor for toggling. */
    private boolean toggling;
    /** Board cell currently under the mouse, or {@code -1, -1} when the pointer is outside the grid. */
    private int hoverX = -1, hoverY = -1;
    /** {@link #clock()} time the last core ripple started; far in the past so no ripple shows initially. */
    private double coreFlashAt = -10;
    /** Text of the currently rising "+N" floater, or {@code null} when none is showing. */
    private String floater;
    /** {@link #clock()} time the current floater started; far in the past so none shows initially. */
    private double floaterAt = -10;

    /** Wires up the panel against {@code engine} and installs the single shared mouse listener. */
    public BoardPanel(Engine engine, Handler handler) {
        this.engine = engine;
        this.handler = handler;
        setOpaque(false);
        var mouse = new MouseInputAdapter() {
            @Override public void mousePressed(MouseEvent e)  { at(e, true); }
            @Override public void mouseDragged(MouseEvent e)  { at(e, true); }
            @Override public void mouseMoved(MouseEvent e)    { at(e, false); }
            @Override public void mouseExited(MouseEvent e)   { hoverX = hoverY = -1; repaint(); }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /** Arms placement preview for {@code m}; {@code null} clears it. Triggers a repaint. */
    public void setGhost(Machine m)            { ghost = m; repaint(); }
    /** Toggles the demolish-tool preview. Triggers a repaint. */
    public void setDemolishing(boolean on)     { demolishing = on; repaint(); }
    /** Toggles the power-switch-tool preview. Triggers a repaint. */
    public void setToggling(boolean on)        { toggling = on; repaint(); }
    /** Seconds elapsed since this panel was constructed; the shared clock every animation is timed against. */
    public double clock()                      { return (System.nanoTime() - start) / 1e9; }

    /** Ripple on the core plus a rising number, after a hand tap. */
    public void flashCore(String text) {
        coreFlashAt = clock();
        floater = text;
        floaterAt = coreFlashAt;
    }

    /**
     * Translates a mouse event's pixel position into a board cell and forwards it to the
     * {@link Handler}, either as a press (including drag-through, so dragging paints a run
     * of cells) or, when the hovered cell changed, as a hover. Out-of-bounds positions clear
     * the hover state instead of dispatching. Always repaints, since hover/press can affect
     * the ghost/demolish preview.
     */
    private void at(MouseEvent e, boolean press) {
        double cs = cellSize();
        int x = (int) Math.floor((e.getX() - originX(cs)) / cs);
        int y = (int) Math.floor((e.getY() - originY(cs)) / cs);
        if (x < 0 || y < 0 || x >= Board.W || y >= Board.H) {
            if (!press) { hoverX = hoverY = -1; repaint(); }
            return;
        }
        Group g = engine.layout().at(x, y);
        if (press) {
            handler.pressed(x, y, g);
        } else if (x != hoverX || y != hoverY) {
            hoverX = x; hoverY = y;
            handler.hovered(x, y, g);
        }
        repaint();
    }

    /** Pixel size of one board cell: the panel's available square area divided by {@link Board#W}, floored at 8px. */
    private double cellSize() {
        double w = getWidth() - PAD * 2.0 - RULER_L;
        double h = getHeight() - PAD * 2.0 - RULER_T;
        return Math.max(8, Math.min(w, h) / Board.W);
    }

    /** Pixel x of the board's top-left corner, centering the grid horizontally in the space left of the ruler. */
    private double originX(double cs) { return PAD + RULER_L + (getWidth() - PAD * 2 - RULER_L - cs * Board.W) / 2.0; }
    /** Pixel y of the board's top-left corner, just below the padding and column ruler. */
    private double originY(double cs) { return PAD + RULER_T; }

    @Override public Dimension getPreferredSize() { return new Dimension(620, 620); }

    /**
     * Draws the whole board for one frame: background, per-cell ore/locked-ground shading,
     * the blueprint grid, the claim boundary, every machine group (via {@link Art#paint}
     * plus {@link #label}), the placement/demolish preview, the rulers, and finally the
     * rising floater if one is active. Reads {@link #clock()} once at the top so every
     * animated element within the frame uses the same instant.
     */
    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        double t = clock();
        double cs = cellSize();
        double ox = originX(cs), oy = originY(cs);
        double size = cs * Board.W;

        g.setPaint(new GradientPaint(0, 0, Theme.PANEL, 0, getHeight(), new Color(0x0C, 0x1C, 0x2C)));
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(Theme.LINE2);
        g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);

        g.setColor(new Color(0x0B, 0x1B, 0x2B));
        g.fill(new Rectangle2D.Double(ox, oy, size, size));

        Board b = engine.board;
        var layout = engine.layout();

        // cells: ore, locked ground, hover
        for (int y = 0; y < Board.H; y++) {
            for (int x = 0; x < Board.W; x++) {
                double px = ox + x * cs, py = oy + y * cs;
                boolean claimed = b.inClaim(x, y);
                if (!claimed) {
                    g.setColor(new Color(0x0A, 0x17, 0x25));
                    g.fill(new Rectangle2D.Double(px, py, cs, cs));
                    g.setColor(new Color(184, 219, 247, 9));
                    g.setStroke(new BasicStroke(1f));
                    for (double d = 0; d < cs * 2; d += 5)
                        g.draw(new Line2D.Double(px + d, py + cs, px + d - cs, py));
                }
                Res ore = b.ore[Board.idx(x, y)];
                if (ore != null && claimed) {
                    g.setColor(Theme.alpha(ore.color, 32));
                    g.fill(new Rectangle2D.Double(px, py, cs, cs));
                    drawPips(g, px, py, cs, ore, b.rich[Board.idx(x, y)], 200);
                    if (cs >= 26) {
                        g.setFont(Theme.mono(Math.max(8, (int) (cs * 0.22))));
                        g.setColor(Theme.alpha(ore.color, 190));
                        g.drawString(ore.tag, (float) (px + 2), (float) (py + cs * 0.28));
                    }
                } else if (ore != null) {
                    // unsurveyed: only the faintest trace of what is down there
                    g.setColor(Theme.alpha(ore.color, 9));
                    g.fill(new Rectangle2D.Double(px, py, cs, cs));
                }
            }
        }

        // blueprint grid
        g.setColor(Theme.GRID);
        g.setStroke(new BasicStroke(1f));
        for (int i = 0; i <= Board.W; i++) {
            g.draw(new Line2D.Double(ox + i * cs, oy, ox + i * cs, oy + size));
            g.draw(new Line2D.Double(ox, oy + i * cs, ox + size, oy + i * cs));
        }

        // claim boundary
        int m = b.margin();
        g.setColor(Theme.alpha(Theme.AMBER, 110));
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{5f, 4f}, 0f));
        g.draw(new Rectangle2D.Double(ox + m * cs, oy + m * cs, b.claim * cs, b.claim * cs));

        // machines
        for (Group grp : layout.groups()) {
            double inset = Math.max(1.0, cs * 0.055);
            var r = new Rectangle2D.Double(ox + grp.x * cs + inset, oy + grp.y * cs + inset,
                    grp.w * cs - inset * 2, grp.h * cs - inset * 2);
            double level = engine.power().capacity() > 0 ? b.energy / engine.power().capacity() : 0;
            boolean hot = hoverX >= 0 && layout.at(hoverX, hoverY) == grp;
            Art.paint(g, grp, r, t, hot, level);
            label(g, grp, r, cs);
            if (grp.type == Machine.CORE) coreRipple(g, r, t);
        }

        ghostPreview(g, ox, oy, cs);
        rulers(g, ox, oy, cs, m);

        if (floater != null && t - floaterAt < 0.9) {
            double p = (t - floaterAt) / 0.9;
            Group core = layout.at(Board.CX, Board.CY);
            g.setFont(Theme.monoBold(Math.max(11, (int) (cs * 0.34))));
            g.setColor(Theme.alpha(Theme.AMBER, (int) (255 * (1 - p))));
            var fm = g.getFontMetrics();
            float fx = (float) (ox + (core.x + 0.5) * cs - fm.stringWidth(floater) / 2.0);
            g.drawString(floater, fx, (float) (oy + core.y * cs - p * cs * 0.9));
        }
        g.dispose();
    }

    /**
     * Renders ore richness as a small hand-centered grid of dots (up to 3 per row) instead
     * of a numeral — a bespoke bit of layout math whose only job is to look like a survey
     * marking, not to be a reusable widget.
     */
    private void drawPips(Graphics2D g, double px, double py, double cs, Res ore, int rich, int alpha) {
        double d = Math.max(2, cs * 0.11);
        double gap = d * 1.6;
        double totalW = Math.min(rich, 3) * gap;
        double sx = px + cs / 2 - totalW / 2 + gap / 2 - d / 2;
        g.setColor(Theme.alpha(ore.color, alpha));
        for (int i = 0; i < rich; i++) {
            double rx = sx + (i % 3) * gap;
            double ry = py + cs * 0.62 + (i / 3) * gap;
            g.fill(new Ellipse2D.Double(rx, ry, d, d));
        }
    }

    /**
     * Draws a fused group's name plate: its label (or abbreviation, or nothing, as space
     * allows), its fusion badge (e.g. {@code x81}), and, when powered and there's room, its
     * {@link #statusLine(Group)} readout. Skipped for the core, unfused singles, and cells
     * too small to hold readable text.
     */
    private void label(Graphics2D g, Group grp, Rectangle2D r, double cs) {
        if (grp.type == Machine.CORE || !grp.fused() || cs < 20) return;
        Spec spec = grp.type.spec();
        int fs = Math.max(8, (int) (cs * 0.24));
        g.setFont(Theme.mono(fs));
        var fm = g.getFontMetrics();
        String badge = "x" + Fmt.n(grp.fusionFactor(engine.exponent()));
        int badgeW = fm.stringWidth(badge);
        String name = spec.label().toUpperCase();
        double room = r.getWidth() - badgeW - 12;
        if (fm.stringWidth(name) > room) name = spec.abbr();
        if (fm.stringWidth(name) > room) name = "";

        float ty = (float) (r.getMaxY() - 3);
        g.setColor(new Color(0, 0, 0, 120));
        g.fill(new Rectangle2D.Double(r.getX() + 1, ty - fm.getAscent() - 1, r.getWidth() - 2, fm.getAscent() + 4));
        g.setColor(grp.powered ? Theme.alpha(Theme.CHALK, 205) : Theme.alpha(Theme.HOT, 205));
        if (!name.isEmpty()) g.drawString(name, (float) (r.getX() + 4), ty);
        g.setColor(Theme.AMBER);
        g.drawString(badge, (float) (r.getMaxX() - 4 - badgeW), ty);

        if (grp.powered && cs >= 24) {
            String stat = statusLine(grp);
            if (stat != null && fm.stringWidth(stat) < r.getWidth() - 8) {
                g.setColor(new Color(0, 0, 0, 110));
                g.fill(new Rectangle2D.Double(r.getX() + 1, r.getY() + 1, fm.stringWidth(stat) + 7, fm.getAscent() + 3));
                g.setColor(Theme.alpha(Theme.ICE, 215));
                g.drawString(stat, (float) (r.getX() + 4), (float) (r.getY() + fm.getAscent() + 1));
            }
        }
    }

    /**
     * One number per machine: whatever it is for. Exhaustively switches over every
     * {@link Role} so the readout stays in sync with the simulation without a default
     * fallback to silently paper over a missing case.
     *
     * @return the formatted readout, or {@code null} for roles with nothing worth showing (e.g. conduits)
     */
    String statusLine(Group grp) {
        double scale = grp.fusionFactor(engine.exponent()) * grp.mult;
        Spec spec = grp.type.spec();
        return switch (spec.role()) {
            case Role.Generator gen -> "+" + Fmt.n(gen.power() * scale) + " pw";
            case Role.Mine mine -> Fmt.n(mine.rate() * scale * grp.richness * engine.mineMultiplier() * grp.rate) + "/s";
            case Role.Converter c -> c.out().entrySet().stream().findFirst()
                    .map(e -> Fmt.n(e.getValue() * scale * engine.outputMultiplier(grp.type) * grp.rate) + "/s").orElse(null);
            case Role.Producer p -> p.out().entrySet().stream().findFirst()
                    .map(e -> Fmt.n(e.getValue() * scale * grp.rate) + "/s").orElse(null);
            case Role.AutoTap tap -> Fmt.n(tap.perSecond() * scale * engine.clickYield() * grp.rate) + "/s";
            case Role.Buffer buf -> Fmt.n(buf.capacity() * scale) + " buf";
            case Role.Amplifier amp -> "+" + Fmt.pct(amp.boost() * scale);
            case Role.Conduit c -> null;
        };
    }

    /**
     * Draws the expanding, fading ring around the core after a tap. Hand-timed against
     * {@code t} (the frame's {@link #clock()} reading): age past {@link #coreFlashAt} is
     * turned into a {@code [0, 1]} progress fraction over a fixed 0.55s window, driving both
     * the ring's radius and its fade — the same manual-tween approach as the "+N" floater.
     */
    private void coreRipple(Graphics2D g, Rectangle2D r, double t) {
        double age = t - coreFlashAt;
        if (age < 0 || age > 0.55) return;
        double p = age / 0.55;
        double rad = r.getWidth() * (0.5 + p * 0.9);
        g.setColor(Theme.alpha(Theme.AMBER, (int) (150 * (1 - p))));
        g.setStroke(new BasicStroke(1.6f));
        g.draw(new Ellipse2D.Double(r.getCenterX() - rad, r.getCenterY() - rad, rad * 2, rad * 2));
    }

    /**
     * Draws whatever the cursor is currently doing to the hovered cell: in demolish mode, an
     * outline around the fused block that would be removed; in power-switch mode, an outline
     * around the fused block that would be toggled, colored by which way the click would flip
     * it; otherwise, a tinted square previewing placement of {@link #ghost}, colored amber if
     * legal (empty, claimed, ore-compatible, affordable) or hot/red if not.
     */
    private void ghostPreview(Graphics2D g, double ox, double oy, double cs) {
        if (hoverX < 0 || hoverY < 0) return;
        double px = ox + hoverX * cs, py = oy + hoverY * cs;
        Board b = engine.board;
        int i = Board.idx(hoverX, hoverY);
        if (demolishing) {
            Group grp = engine.layout().at(hoverX, hoverY);
            if (grp != null && grp.type != Machine.CORE) {
                g.setColor(Theme.alpha(Theme.HOT, 150));
                g.setStroke(new BasicStroke(1.6f));
                g.draw(new Rectangle2D.Double(ox + grp.x * cs + 1, oy + grp.y * cs + 1, grp.w * cs - 2, grp.h * cs - 2));
            }
            return;
        }
        if (toggling) {
            Group grp = engine.layout().at(hoverX, hoverY);
            if (grp != null && grp.type != Machine.CORE) {
                // Amber previews "this click switches it off", good/green previews "this click
                // switches it back on" — the same colour convention as Art's dead() overlay.
                g.setColor(Theme.alpha(grp.enabled ? Theme.AMBER : Theme.GOOD, 170));
                g.setStroke(new BasicStroke(1.6f));
                g.draw(new Rectangle2D.Double(ox + grp.x * cs + 1, oy + grp.y * cs + 1, grp.w * cs - 2, grp.h * cs - 2));
            }
            return;
        }
        if (ghost == null) return;
        boolean ok = b.cell[i] == null && b.inClaim(hoverX, hoverY)
                && (!ghost.spec().oreOnly() || b.ore[i] != null)
                && engine.affordable(engine.priceOf(ghost));
        g.setColor(Theme.alpha(ok ? Theme.AMBER : Theme.HOT, 40));
        g.fill(new Rectangle2D.Double(px, py, cs, cs));
        g.setColor(Theme.alpha(ok ? Theme.AMBER : Theme.HOT, 190));
        g.setStroke(new BasicStroke(1.4f));
        g.draw(new Rectangle2D.Double(px + 0.5, py + 0.5, cs - 1, cs - 1));
    }

    /**
     * Draws the column-letter and row-number ruler along the board's top and left edges,
     * dimming the labels outside the current claim margin. Column letters use the same
     * spreadsheet-style {@code 'A' + i} arithmetic as {@link Group#where()}; see the class
     * Javadoc for its 26-column limitation.
     */
    private void rulers(Graphics2D g, double ox, double oy, double cs, int margin) {
        g.setFont(Theme.mono(Math.max(8, (int) (cs * 0.26))));
        var fm = g.getFontMetrics();
        for (int i = 0; i < Board.W; i++) {
            boolean on = i >= margin && i < Board.W - margin;
            g.setColor(Theme.alpha(Theme.DIM, on ? 220 : 70));
            String col = String.valueOf((char) ('A' + i));
            g.drawString(col, (float) (ox + i * cs + cs / 2 - fm.stringWidth(col) / 2.0), (float) (oy - 3));
            String row = String.valueOf(i + 1);
            g.drawString(row, (float) (ox - 4 - fm.stringWidth(row)), (float) (oy + i * cs + cs / 2 + fm.getAscent() / 2.0 - 1));
        }
    }
}
