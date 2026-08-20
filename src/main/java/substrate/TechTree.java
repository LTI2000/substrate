package substrate;

import javax.swing.JComponent;
import javax.swing.event.MouseInputAdapter;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The research tree, drawn as an actual tree: one icon tile per {@link Tech}, stacked in tiers
 * from the two roots at the top down to the Fusion Reactor at the bottom, with every {@link
 * Tech#requires() prerequisite} drawn as a trace running from the tech that grants it into the
 * tech it gates. Replaces the flat scrolling list of rows this page used to be, where the
 * prerequisites were only ever readable as "needs Scientific Method" text on each row.
 *
 * <p><b>One component, not one per node.</b> Like {@link BoardPanel} and unlike the BUILD tab's
 * grid of tiles, the whole tree is a single {@link JComponent} that paints all 28 nodes and all
 * 26 edges itself and hit-tests the pointer against stored rectangles. Child components would
 * have made the edges awkward — they have to paint underneath every node, which means they'd
 * have to live in the parent anyway — and there is no per-node state worth a component: every
 * node's appearance is derived from {@link Engine} on each repaint.
 *
 * <p><b>The layout is computed once, in the constructor, and never again.</b> The tree's shape
 * comes from the prerequisite DAG, which is static data (see {@link Tech}) — researching
 * something changes how a node is <em>painted</em>, never where it sits. So {@link #layout(int)}
 * runs once and stores a fixed {@link Rectangle} per tech plus a fixed {@link Path2D} per edge;
 * {@link #getPreferredSize()} then reports a constant, which is all the enclosing {@link
 * Ui#scroll} needs to know how far there is to pan. This matches the rest of this UI, where
 * every component's bounds are computed once from fixed constants rather than by a {@link
 * LayoutManager} (see {@link Game}'s class Javadoc), and it is why nothing here ever calls
 * {@code revalidate()}.
 *
 * <p><b>Layout in three passes.</b> {@link #layout(int)} assigns each tech a tier (longest path
 * from a root, so a tech always sits strictly below everything it needs), then orders each tier
 * left to right by the average position of its prerequisites — the standard barycenter heuristic
 * for untangling layered graphs, one pass, which is enough to keep the crossings down at this
 * size — then snaps every node onto a shared column grid. The grid is what makes the edge
 * routing in {@link #trace} safe: because every tier uses the same column pitch, the vertical
 * gutters between columns are guaranteed free of nodes in <em>every</em> tier, so an edge that
 * has to skip past a tier can run down one and never cross a tile it has nothing to do with.
 */
public final class TechTree extends JComponent {

    /** Callbacks for tree interaction, implemented by the owning screen. */
    public interface Handler {
        /** A tech tile was clicked. The tree does not check whether it can actually be bought. */
        void researched(Tech t);
        /** Pointer moved onto a tech tile, or off all of them ({@code null}). */
        void hovered(Tech t);
    }

    // -- Fixed tile/grid geometry. Node width is the one derived number: it shrinks to fit
    // however many columns the widest tier needs, so adding techs can never overflow the page. --
    /** Margin between the page edge and the outermost tiles. */
    private static final int PAD = 12;
    /** Tile height, and the widest a tile is allowed to get. */
    private static final int NODE_H = 82, NODE_W_MAX = 124;
    /** Horizontal gap between tiles in a tier, and vertical gap between tiers. */
    private static final int COL_GAP = 12, TIER_GAP = 30;
    /** Icon square inside a tile, and the caption/cost lines' line height. */
    private static final int ICON = 38, LINE_H = 11;
    /** Corner radius used when rounding an edge's right-angle turns. */
    private static final double CORNER = 6;

    /** The simulation this tree reads its state from; nothing here mutates it directly. */
    private final Engine engine;
    /** Receives click and hover callbacks, translated from pixels into techs. */
    private final Handler handler;

    /** Tier (row) each tech sits in: 0 for the roots, otherwise one past its deepest prerequisite. */
    private final Map<Tech, Integer> tier = new EnumMap<>(Tech.class);
    /** Column slot each tech sits in, on the shared grid every tier is snapped to. */
    private final Map<Tech, Integer> slot = new EnumMap<>(Tech.class);
    /** Where each tech's tile is painted, fixed at construction. */
    private final Map<Tech, Rectangle> box = new EnumMap<>(Tech.class);
    /** The machine each tech unlocks, for tiles that borrow their icon from {@link Art}; absent if none. */
    private final Map<Tech, Machine> unlocks = new EnumMap<>(Tech.class);
    /** Every prerequisite edge, with its route precomputed. */
    private final List<Edge> edges = new ArrayList<>();
    /** Total painted size, reported as the preferred size so the scroll pane can size its extent. */
    private Dimension size = new Dimension(0, 0);

    /** Tech under the pointer, or {@code null}. Drives the hover wash and {@link #chain}. */
    private Tech hover;
    /**
     * {@link #hover} plus everything it transitively needs, lit up so the pointer answers "what
     * does this actually cost me" in one glance rather than one hop at a time. Empty when nothing
     * is hovered.
     */
    private Set<Tech> chain = Set.of();

    /**
     * One prerequisite, from the tech that grants it to the tech it gates.
     *
     * @param from  the prerequisite tech, painted above
     * @param to    the tech it gates, painted below
     * @param path  the precomputed route between them, already rounded at its corners
     * @param ex    x of the point where the trace lands on {@code to}'s top edge
     * @param ey    y of that same point, i.e. {@code to}'s top edge
     */
    private record Edge(Tech from, Tech to, Path2D.Double path, double ex, double ey) {}

    /**
     * Lays the whole tree out and wires up mouse handling.
     *
     * @param engine  the simulation to read research state from
     * @param width   the page width to lay out within; tiles shrink to fit rather than overflow
     * @param handler receives click and hover callbacks
     */
    public TechTree(Engine engine, int width, Handler handler) {
        this.engine = engine;
        this.handler = handler;
        setOpaque(false);
        layout(width);

        var mouse = new MouseInputAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent e) { moved(at(e.getX(), e.getY())); }
            @Override public void mouseDragged(java.awt.event.MouseEvent e) { moved(at(e.getX(), e.getY())); }
            @Override public void mouseExited(java.awt.event.MouseEvent e) { moved(null); }
            // mousePressed, not mouseClicked: MOUSE_CLICKED is only synthesized when the pointer
            // doesn't move at all between press and release, so any tiny jitter drops the click.
            // Same reason BoardPanel acts on press for board taps.
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                Tech t = at(e.getX(), e.getY());
                if (t != null) handler.researched(t);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /* ------------------------------------------------------------------ */
    /* layout                                                              */
    /* ------------------------------------------------------------------ */

    /**
     * Places every tech and precomputes every edge's route. Called once, from the constructor.
     *
     * <p>Three passes. <b>Tiers</b> come from the longest path back to a root, iterated to a
     * fixpoint rather than assumed from declaration order: a tech ends up one row below the
     * deepest thing it needs, so every edge points strictly downward and no tech is ever drawn
     * above something it depends on. <b>Order within a tier</b> is by barycenter — the mean x of
     * a tech's already-placed prerequisites — which pulls each node towards whatever feeds it and
     * is the cheap standard fix for a layered graph's crossings; ties keep declaration order,
     * since {@link Comparator} sorting in Java is stable. <b>Slots</b> then snap each tier onto a
     * shared column grid, centred, which is what lets {@link #trace} route through gutters that
     * are node-free in every tier (see the class Javadoc).
     *
     * @param width the page width to fit within
     */
    private void layout(int width) {
        // Tiers, by longest path from a root. The loop is capped rather than trusted to
        // terminate: a cycle in the prerequisite data would otherwise spin here forever, and a
        // tree that renders slightly wrong beats a UI that hangs at construction.
        for (Tech t : Tech.values()) tier.put(t, 0);
        for (int pass = 0; pass < Tech.values().length; pass++) {
            boolean changed = false;
            for (Tech t : Tech.values()) {
                int depth = 0;
                for (Tech p : t.requires()) depth = Math.max(depth, tier.get(p) + 1);
                if (depth > tier.get(t)) {
                    tier.put(t, depth);
                    changed = true;
                }
            }
            if (!changed) break;
        }

        int tiers = 0;
        for (Tech t : Tech.values()) tiers = Math.max(tiers, tier.get(t) + 1);
        var rows = new ArrayList<List<Tech>>();
        for (int i = 0; i < tiers; i++) rows.add(new ArrayList<>());
        for (Tech t : Tech.values()) rows.get(tier.get(t)).add(t);

        int cols = 0;
        for (List<Tech> row : rows) cols = Math.max(cols, row.size());
        int nodeW = Math.min(NODE_W_MAX, (width - PAD * 2 - (cols - 1) * COL_GAP) / cols);
        int pitch = nodeW + COL_GAP;
        int originX = (width - (cols * pitch - COL_GAP)) / 2;

        for (int r = 0; r < rows.size(); r++) {
            List<Tech> row = rows.get(r);
            // Prerequisites always live in an earlier tier, so by the time a row is ordered every
            // node it hangs from already has a box to take a barycenter from.
            row.sort(Comparator.comparingDouble(this::barycenter));
            int start = (cols - row.size()) / 2;
            for (int i = 0; i < row.size(); i++) {
                Tech t = row.get(i);
                slot.put(t, start + i);
                box.put(t, new Rectangle(originX + (start + i) * pitch,
                        PAD + r * (NODE_H + TIER_GAP), nodeW, NODE_H));
            }
        }

        for (Machine m : Machine.BUILDABLE) {
            Tech t = m.spec().tech();
            // First machine wins: SMELTING unlocks both furnaces, DRAUGHT both hot-blast
            // furnaces, and either one of the pair reads as "this is what that research buys".
            if (t != null) unlocks.putIfAbsent(t, m);
        }

        for (Tech t : Tech.values()) route(t, nodeW, pitch, originX);

        size = new Dimension(width, PAD * 2 + tiers * NODE_H + (tiers - 1) * TIER_GAP);
    }

    /**
     * Mean x-centre of {@code t}'s already-placed prerequisites, used to order a tier. Roots have
     * nothing to hang from and sort to the far left as a group, which keeps them in declaration
     * order relative to each other.
     *
     * @param t the tech being placed
     * @return the barycenter, or 0 for a tech with no prerequisites
     */
    private double barycenter(Tech t) {
        double sum = 0;
        int n = 0;
        for (Tech p : t.requires()) {
            Rectangle r = box.get(p);
            if (r == null) continue;                       // only possible for a cyclic DAG
            sum += r.getCenterX();
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    /**
     * Builds the trace from each of {@code t}'s prerequisites down into {@code t}, and adds them
     * to {@link #edges}.
     *
     * <p>Both ends are fanned out along the tile edge rather than all meeting at its midpoint:
     * a tech's prerequisites land at evenly spaced points across its top edge, and a tech's
     * dependents leave from evenly spaced points across its bottom edge, so a node with six
     * dependents (Scientific Method) reads as a wiring loom instead of a single overdrawn line.
     *
     * <p>An edge between adjacent tiers is a plain elbow through the gap between the two rows.
     * An edge that skips a tier can't do that — the straight run between the rows would cross
     * whatever is in between — so it drops into the gap below its source, runs sideways into the
     * gutter beside its target's column, down that gutter, and into the gap above its target.
     * Every leg of that route is either a gap between two rows or a gutter between two columns,
     * and the shared column grid guarantees both are free of tiles.
     *
     * @param t       the tech whose incoming edges are being routed
     * @param nodeW   tile width
     * @param pitch   distance between adjacent column slots
     * @param originX x of the leftmost column
     */
    private void route(Tech t, int nodeW, int pitch, int originX) {
        var prereqs = new ArrayList<>(t.requires());
        if (prereqs.isEmpty()) return;
        prereqs.sort(Comparator.comparingInt(slot::get));
        Rectangle to = box.get(t);

        for (int j = 0; j < prereqs.size(); j++) {
            Tech p = prereqs.get(j);
            Rectangle from = box.get(p);
            double sx = from.x + nodeW * exitFraction(p, t);
            double sy = from.getMaxY();
            double tx = to.x + nodeW * (j + 1.0) / (prereqs.size() + 1);
            double ty = to.y;

            Path2D.Double path;
            if (tier.get(t) == tier.get(p) + 1) {
                double mid = sy + TIER_GAP / 2.0;
                path = trace(sx, sy, sx, mid, tx, mid, tx, ty);
            } else {
                double first = sy + TIER_GAP / 2.0;
                double last = ty - TIER_GAP / 2.0;
                double lane = slot.get(p) <= slot.get(t)
                        ? originX + slot.get(t) * pitch - COL_GAP / 2.0
                        : originX + slot.get(t) * pitch + nodeW + COL_GAP / 2.0;
                path = trace(sx, sy, sx, first, lane, first, lane, last, tx, last, tx, ty);
            }
            edges.add(new Edge(p, t, path, tx, ty));
        }
    }

    /**
     * Where along {@code from}'s bottom edge the trace to {@code to} should leave, as a fraction
     * of tile width. Dependents are spread across the edge in left-to-right slot order, so their
     * traces leave in the same order they arrive and don't cross each other on the way out.
     *
     * @param from the prerequisite tech
     * @param to   one of the techs it gates
     * @return a fraction strictly between 0 and 1
     */
    private double exitFraction(Tech from, Tech to) {
        var dependents = new ArrayList<Tech>();
        for (Tech t : Tech.values()) if (t.requires().contains(from)) dependents.add(t);
        dependents.sort(Comparator.comparingInt(slot::get));
        return (dependents.indexOf(to) + 1.0) / (dependents.size() + 1);
    }

    /**
     * Builds an orthogonal polyline through the given {@code x, y} pairs with its right-angle
     * corners rounded off, so the traces read as bent wiring rather than as a staircase of hard
     * pixels. A corner whose neighbouring legs are too short to round is left square, which is
     * also what happens when two consecutive points coincide — the case that arises whenever a
     * trace happens to leave a tile exactly in line with the gutter it's heading for.
     *
     * @param pts alternating x and y coordinates, at least two points
     * @return the path, ready to stroke
     */
    private static Path2D.Double trace(double... pts) {
        var path = new Path2D.Double();
        path.moveTo(pts[0], pts[1]);
        int n = pts.length / 2;
        for (int i = 1; i < n - 1; i++) {
            double px = pts[2 * i - 2], py = pts[2 * i - 1];
            double cx = pts[2 * i], cy = pts[2 * i + 1];
            double nx = pts[2 * i + 2], ny = pts[2 * i + 3];
            double in = Math.hypot(cx - px, cy - py), out = Math.hypot(nx - cx, ny - cy);
            double r = Math.min(CORNER, Math.min(in, out) / 2);
            if (in == 0 || out == 0 || r <= 0.5) {
                path.lineTo(cx, cy);
                continue;
            }
            path.lineTo(cx + (px - cx) / in * r, cy + (py - cy) / in * r);
            path.quadTo(cx, cy, cx + (nx - cx) / out * r, cy + (ny - cy) / out * r);
        }
        path.lineTo(pts[pts.length - 2], pts[pts.length - 1]);
        return path;
    }

    /** Fixed content size, computed in {@link #layout(int)}; the scroll pane's only sizing input. */
    @Override public Dimension getPreferredSize() { return new Dimension(size); }

    /** @return the tech whose tile covers this point, or {@code null} if the point is between tiles. */
    private Tech at(int x, int y) {
        for (var e : box.entrySet()) if (e.getValue().contains(x, y)) return e.getKey();
        return null;
    }

    /** Updates the hover state, the lit prerequisite chain and the cursor, and tells the handler. */
    private void moved(Tech t) {
        if (t == hover) return;
        hover = t;
        chain = t == null ? Set.of() : ancestry(t);
        setCursor(Cursor.getPredefinedCursor(t == null ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR));
        repaint();
        if (t != null) handler.hovered(t);
    }

    /**
     * @param t the tech to walk back from
     * @return {@code t} plus everything it transitively requires
     */
    private static Set<Tech> ancestry(Tech t) {
        var out = EnumSet.noneOf(Tech.class);
        var todo = new ArrayDeque<Tech>();
        todo.push(t);
        while (!todo.isEmpty()) {
            Tech cur = todo.pop();
            if (out.add(cur)) todo.addAll(cur.requires());
        }
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* painting                                                            */
    /* ------------------------------------------------------------------ */

    /** Paints every edge first, then every tile on top of them, so a trace tucks under its tiles. */
    @Override protected void paintComponent(Graphics graphics) {
        var g = (Graphics2D) graphics;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        for (Edge e : edges) paintEdge(g, e);
        for (Tech t : Tech.values()) paintNode(g, t);
    }

    /**
     * Draws one prerequisite trace, plus a small joint where it lands. Colour carries the state:
     * lit amber when the pointer is on something downstream of it, olive once the prerequisite is
     * actually researched (so a satisfied path is visible at a glance), otherwise the faint rule
     * colour everything else in this UI uses.
     */
    private void paintEdge(Graphics2D g, Edge e) {
        boolean lit = chain.contains(e.from()) && chain.contains(e.to());
        boolean done = engine.board.has(e.from());
        g.setStroke(new BasicStroke(lit ? 1.8f : 1f));
        g.setColor(lit ? Theme.AMBER : done ? Theme.alpha(Theme.GOOD, 130) : Theme.LINE);
        g.draw(e.path());
        g.fill(new Ellipse2D.Double(e.ex() - 2, e.ey() - 2, 4, 4));
        g.setStroke(new BasicStroke(1f));
    }

    /**
     * Draws one tech tile: wash, icon, name, and price. Four states, all derived live from the
     * engine rather than stored: researched (olive, ticked, no price), affordable right now
     * (amber border — the thing to click), unlocked but too expensive (normal border, the prices
     * it can't cover in red), and still gated by prerequisites (the whole tile at 40% so the
     * reachable frontier of the tree stands out from the part that isn't in play yet).
     */
    private void paintNode(Graphics2D g, Tech t) {
        Rectangle r = box.get(t);
        boolean done = engine.board.has(t);
        boolean open = engine.researchable(t);
        boolean afford = open && engine.affordable(t.cost);
        boolean lit = chain.contains(t);

        Composite old = g.getComposite();
        if (!done && !open) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f));

        g.setColor(done ? Theme.alpha(Theme.GOOD, 26)
                : lit ? Theme.alpha(Theme.AMBER, 30)
                : new Color(52, 42, 28, t == hover ? 150 : 90));
        g.fillRect(r.x, r.y, r.width, r.height);

        var icon = new Rectangle2D.Double(r.x + (r.width - ICON) / 2.0, r.y + 5, ICON, ICON);
        Machine m = unlocks.get(t);
        if (m != null) Art.paint(g, Game.previewGroup(m), icon, 0, false, 0.5);
        else paintGlyph(g, t, icon);

        g.setFont(Theme.mono(9));
        var fm = g.getFontMetrics();
        float y = r.y + ICON + 6 + fm.getAscent();
        g.setColor(done ? Theme.GOOD : Theme.CHALK);
        // Two lines is what every current label needs at this width; a third would collide with
        // the price line, so anything longer is clipped rather than allowed to overflow the tile.
        List<String> lines = Ui.wrap(t.label, fm, r.width - 8);
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            g.drawString(lines.get(i), r.x + (r.width - fm.stringWidth(lines.get(i))) / 2f, y);
            y += LINE_H;
        }

        if (done) paintTick(g, r);
        else paintPrice(g, t, r, r.y + r.height - 5);

        g.setColor(done ? Theme.alpha(Theme.GOOD, 150) : afford ? Theme.AMBER : lit ? Theme.LINE2 : Theme.LINE);
        g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
        g.setComposite(old);
    }

    /**
     * Draws the price along the tile's bottom edge, each resource in its own colour so the cost
     * is scannable without reading it, and in {@link Theme#HOT} instead when the site is short of
     * that resource.
     *
     * <p>Three ways of writing the same price, widest first, and the first one that fits the tile
     * wins: {@code "800 steel · 60 data"}, then {@code "800 ste · 60 dat"}, then a bare {@code
     * "800 60"}. A tile is about 107px of usable width, which the full names clear for only six
     * of the 28 techs and the abbreviations for 25 of them; the last three are the late
     * three-resource prices, where nothing but the numbers will ever fit and the colours are left
     * to say which resource each one belongs to. Measuring and stepping down beats picking one
     * form for everything, which would mean either truncating half the tree or writing every
     * price in the most cryptic form the worst case needs.
     */
    private void paintPrice(Graphics2D g, Tech t, Rectangle r, float y) {
        g.setFont(Theme.mono(9));
        var fm = g.getFontMetrics();
        List<List<Ui.Seg>> forms = List.of(price(t, 2), price(t, 1), price(t, 0));
        var segs = forms.get(forms.size() - 1);
        for (List<Ui.Seg> form : forms) {
            if (width(fm, form) <= r.width - 8) {
                segs = form;
                break;
            }
        }
        Ui.segments(g, segs, r.x + (r.width - width(fm, segs)) / 2f, y);
    }

    /**
     * Builds one written form of {@code t}'s price, coloured per resource: that resource's own
     * colour when the site can cover it, {@link Theme#HOT} when it can't, so a price says both
     * what it wants and what's missing.
     *
     * @param t     the tech being priced
     * @param names 2 for full resource names, 1 for abbreviations, 0 for numbers alone
     * @return the segments, ready to measure or draw
     */
    private List<Ui.Seg> price(Tech t, int names) {
        var out = new ArrayList<Ui.Seg>();
        for (var e : t.cost.entrySet()) {
            if (!out.isEmpty()) out.add(Ui.Seg.of(names == 0 ? " " : " · ", Theme.DIM));
            Color c = engine.board.get(e.getKey()) >= e.getValue() - 1e-9 ? e.getKey().color : Theme.HOT;
            String amount = Fmt.n(e.getValue());
            out.add(Ui.Seg.of(switch (names) {
                case 2 -> amount + " " + e.getKey().lower();
                case 1 -> amount + " " + code(e.getKey());
                default -> amount;
            }, c));
        }
        return out;
    }

    /**
     * Shortest readable name for a resource. Ores keep the survey tag the board already prints on
     * their patches ({@code Fe}, {@code C}, ...) so the tree and the map say the same thing;
     * everything else is cut to three letters, which is enough to tell {@code ste} from {@code
     * cir} from {@code dat} at a glance. Cut here in the view rather than stored on {@link Res}
     * because this is the only place in the UI cramped enough to need it.
     */
    private static String code(Res r) {
        return r.isOre() ? r.tag : r.lower().substring(0, Math.min(3, r.lower().length()));
    }

    /** Total pixel width of a run of segments, for centring it. */
    private static float width(FontMetrics fm, List<Ui.Seg> segs) {
        float w = 0;
        for (Ui.Seg s : segs) w += fm.stringWidth(s.text());
        return w;
    }

    /** Draws the two-stroke check mark that marks a researched tile, where its price would be. */
    private static void paintTick(Graphics2D g, Rectangle r) {
        double cx = r.getCenterX(), cy = r.getMaxY() - 9;
        g.setColor(Theme.GOOD);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        var tick = new Path2D.Double();
        tick.moveTo(cx - 5, cy);
        tick.lineTo(cx - 1.5, cy + 3.5);
        tick.lineTo(cx + 5, cy - 4);
        g.draw(tick);
        g.setStroke(new BasicStroke(1f));
    }

    /**
     * Draws the icon for a tech that doesn't unlock a machine and so has no {@link Art} of its
     * own to borrow — the multipliers and the claim extensions. Each gets a glyph for its family
     * rather than a unique drawing: the four click-yield techs share one, the two mining techs
     * share one, and so on, so a tile's icon says what kind of upgrade it is and its caption says
     * which one. Same hand-drawn Java2D approach as {@link Game}'s two tool glyphs.
     */
    private static void paintGlyph(Graphics2D g, Tech t, Rectangle2D r) {
        switch (t) {
            case TOOLS0, TOOLS1, TOOLS2, TOOLS3 -> paintTapGlyph(g, r);
            case DRILLS1, DRILLS2 -> paintDrillGlyph(g, r);
            case TERR1, TERR2, TERR3, TERR4 -> paintClaimGlyph(g, r);
            case SMELT1 -> paintHeatGlyph(g, r);
            case LABS1 -> paintChipGlyph(g, r);
            case GEO1, GEO2 -> paintFusionGlyph(g, r);
            default -> paintBoostGlyph(g, r);
        }
    }

    /** Click yield: a fist-sized knock coming down on the core, with impact ticks. */
    private static void paintTapGlyph(Graphics2D g, Rectangle2D r) {
        double cx = r.getCenterX(), cy = r.getCenterY();
        double rad = r.getWidth() * 0.24;
        g.setColor(Theme.alpha(Theme.AMBER, 90));
        g.fill(new Ellipse2D.Double(cx - rad, cy + 1, rad * 2, rad * 2));
        g.setColor(Theme.AMBER);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Ellipse2D.Double(cx - rad, cy + 1, rad * 2, rad * 2));
        var arrow = new Path2D.Double();
        arrow.moveTo(cx, r.getY() + 2);
        arrow.lineTo(cx, cy - 3);
        arrow.moveTo(cx - 4, cy - 7);
        arrow.lineTo(cx, cy - 3);
        arrow.lineTo(cx + 4, cy - 7);
        g.setColor(Theme.CHALK);
        g.draw(arrow);
        g.setStroke(new BasicStroke(1f));
    }

    /** Mining rate: a drill bit biting into a hatched seam. */
    private static void paintDrillGlyph(Graphics2D g, Rectangle2D r) {
        double cx = r.getCenterX();
        g.setColor(Theme.STEEL);
        g.fill(new Rectangle2D.Double(cx - 3, r.getY() + 3, 6, r.getHeight() * 0.42));
        var bit = new Path2D.Double();
        bit.moveTo(cx - 7, r.getY() + 3 + r.getHeight() * 0.42);
        bit.lineTo(cx + 7, r.getY() + 3 + r.getHeight() * 0.42);
        bit.lineTo(cx, r.getY() + r.getHeight() * 0.78);
        bit.closePath();
        g.setColor(Theme.CHALK);
        g.fill(bit);
        g.setColor(Theme.alpha(Theme.AMBER, 150));
        for (int i = 0; i < 3; i++) {
            double y = r.getMaxY() - 3 - i * 3.5;
            g.draw(new java.awt.geom.Line2D.Double(cx - 9 + i * 2, y, cx + 9 - i * 2, y));
        }
    }

    /** Claim extension: the amber dashed survey boundary from the board, opening outwards. */
    private static void paintClaimGlyph(Graphics2D g, Rectangle2D r) {
        double inset = r.getWidth() * 0.28;
        g.setColor(Theme.alpha(Theme.AMBER, 80));
        g.fill(new Rectangle2D.Double(r.getX() + inset, r.getY() + inset,
                r.getWidth() - inset * 2, r.getHeight() - inset * 2));
        g.setColor(Theme.AMBER);
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[]{4f, 3f}, 0f));
        g.draw(new Rectangle2D.Double(r.getX() + 2, r.getY() + 2, r.getWidth() - 4, r.getHeight() - 4));
        g.setStroke(new BasicStroke(1f));
    }

    /** Smelter output: heat rising off a crucible lip. */
    private static void paintHeatGlyph(Graphics2D g, Rectangle2D r) {
        g.setColor(Theme.STEEL);
        g.fill(new Rectangle2D.Double(r.getX() + 4, r.getMaxY() - 9, r.getWidth() - 8, 6));
        g.setColor(Theme.HOT);
        g.fill(new Rectangle2D.Double(r.getX() + 6, r.getMaxY() - 11, r.getWidth() - 12, 3));
        g.setColor(Theme.AMBER);
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = -1; i <= 1; i++) {
            double x = r.getCenterX() + i * r.getWidth() * 0.26;
            var flame = new Path2D.Double();
            flame.moveTo(x, r.getMaxY() - 14);
            flame.quadTo(x - 4, r.getCenterY() - 2, x, r.getY() + 4);
            flame.quadTo(x + 4, r.getCenterY() - 2, x, r.getMaxY() - 14);
            g.draw(flame);
        }
        g.setStroke(new BasicStroke(1f));
    }

    /** Lab output: a processor die with its pins out. */
    private static void paintChipGlyph(Graphics2D g, Rectangle2D r) {
        double inset = r.getWidth() * 0.22;
        var die = new Rectangle2D.Double(r.getX() + inset, r.getY() + inset,
                r.getWidth() - inset * 2, r.getHeight() - inset * 2);
        g.setColor(Theme.STEEL_DARK);
        g.fill(die);
        g.setColor(Res.CIRCUIT.color);
        g.draw(die);
        for (int i = 0; i < 3; i++) {
            double f = (i + 1) / 4.0;
            g.draw(new java.awt.geom.Line2D.Double(die.getX() + die.getWidth() * f, r.getY() + 2,
                    die.getX() + die.getWidth() * f, die.getY()));
            g.draw(new java.awt.geom.Line2D.Double(die.getX() + die.getWidth() * f, die.getMaxY(),
                    die.getX() + die.getWidth() * f, r.getMaxY() - 2));
            g.draw(new java.awt.geom.Line2D.Double(r.getX() + 2, die.getY() + die.getHeight() * f,
                    die.getX(), die.getY() + die.getHeight() * f));
            g.draw(new java.awt.geom.Line2D.Double(die.getMaxX(), die.getY() + die.getHeight() * f,
                    r.getMaxX() - 2, die.getY() + die.getHeight() * f));
        }
        g.setColor(Theme.alpha(Res.DATA.color, 190));
        g.fill(new Rectangle2D.Double(die.getCenterX() - 3, die.getCenterY() - 3, 6, 6));
    }

    /** Fusion exponent: four cells merging into the one bigger block the whole game is about. */
    private static void paintFusionGlyph(Graphics2D g, Rectangle2D r) {
        double s = r.getWidth() * 0.3, gap = 2.5;
        double x0 = r.getCenterX() - s - gap / 2, y0 = r.getCenterY() - s - gap / 2;
        g.setColor(Theme.alpha(Theme.AMBER, 70));
        for (int i = 0; i < 4; i++) {
            g.fill(new Rectangle2D.Double(x0 + (i % 2) * (s + gap), y0 + (i / 2) * (s + gap), s, s));
        }
        g.setColor(Theme.AMBER);
        g.setStroke(new BasicStroke(1.6f));
        g.draw(new Rectangle2D.Double(x0, y0, s * 2 + gap, s * 2 + gap));
        g.setStroke(new BasicStroke(1f));
    }

    /** Anything else: a plain "this makes something better" chevron stack. */
    private static void paintBoostGlyph(Graphics2D g, Rectangle2D r) {
        g.setColor(Theme.AMBER);
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 2; i++) {
            double y = r.getCenterY() + 4 + i * 7;
            var chevron = new Path2D.Double();
            chevron.moveTo(r.getCenterX() - 7, y);
            chevron.lineTo(r.getCenterX(), y - 7);
            chevron.lineTo(r.getCenterX() + 7, y);
            g.draw(chevron);
        }
        g.setStroke(new BasicStroke(1f));
    }

    /* ------------------------------------------------------------------ */
    /* test hooks                                                          */
    /* ------------------------------------------------------------------ */

    /** @return where {@code t}'s tile is painted; package-visible so the layout can be asserted on. */
    Rectangle boxOf(Tech t) { return box.get(t); }

    /** @return {@code t}'s row in the tree, counting from 0 at the roots. */
    int tierOf(Tech t) { return tier.get(t); }

    /** @return how many prerequisite traces the tree draws. */
    int edgeCount() { return edges.size(); }
}
