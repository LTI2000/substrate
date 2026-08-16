package substrate;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level Swing UI wiring for a session: assembles the board, ledger, status bar and the
 * BUILD / RESEARCH / MANUAL side tabs around a single {@link Engine}, and routes board clicks
 * and hovers back into engine calls. This is the only class that knows how the simulation is
 * laid out on screen; everything downstream ({@link BoardPanel}, {@link LedgerPanel}, {@link
 * ItemRow}, ...) is a dumb view driven from here.
 *
 * <p>The build and research rows are not backed by a proper view-model class. Each row's {@link
 * ItemRow.Model} is a fresh anonymous inner class created per machine or tech inside a loop,
 * closing over the loop variable ({@code m} or {@code t}) and over {@code engine} and this
 * class's mutable fields ({@code selected}, {@code demolishing}). This is functional-callback-style
 * UI wiring: each row is just a bundle of closures re-evaluated on every repaint, so there is
 * nothing to keep in sync — the closures always read current engine state.
 */
public final class Game implements BoardPanel.Handler {

    /** The simulation this UI is wired to; owns the board and all game rules. */
    private final Engine engine;
    /** The grid view: renders machines, takes clicks/hover and reports them via {@link BoardPanel.Handler}. */
    private final BoardPanel boardPanel;
    /** Resource totals and rates strip above the board. */
    private final LedgerPanel ledger;
    /** One-line contextual readout at the bottom of the board (hover info, messages). */
    private final StatusBar status;
    /** Rotating gameplay tip shown above the build list; see {@link #hintText()}. */
    private final HintBox hint;
    /** Card-switches between the BUILD, RESEARCH and MANUAL side-panel tabs. */
    private final JPanel cards = new JPanel(new CardLayout());
    /** The three tab buttons (BUILD/RESEARCH/MANUAL), kept so {@link #showTab(String)} can toggle their active state. */
    private final List<TabButton> tabs = new ArrayList<>();
    /** Build-list rows keyed by machine, so {@link #refresh()} can show/hide them as tech unlocks. */
    private final Map<Machine, ItemRow> buildRows = new EnumMap<>(Machine.class);
    /** Research-list rows keyed by tech; currently populated once and never hidden. */
    private final Map<Tech, ItemRow> techRows = new EnumMap<>(Tech.class);
    /** The in-game manual/log panel shown under the MANUAL tab. */
    private final Manual manual;

    /** Machine currently armed for placement, or {@code null} if nothing is selected. */
    private Machine selected;
    /** Whether the board is in dismantle mode (click a machine to remove its block). */
    private boolean demolishing;
    /** Wall-clock timestamp of the last simulation tick, used to compute {@code dt} in {@link #start()}. */
    private long lastTick = System.nanoTime();
    /** Set by {@link #hovered} whenever the pointer is over the board; currently unread elsewhere but kept for future use. */
    private boolean pointerOnBoard;

    /** Builds all side panels and wires them to {@code engine}; does not lay out or start ticking yet. */
    public Game(Engine engine) {
        this.engine = engine;
        ItemRow.stock = engine.board;
        this.boardPanel = new BoardPanel(engine, this);
        this.ledger = new LedgerPanel(engine);
        this.status = new StatusBar();
        this.hint = new HintBox();
        this.manual = new Manual(engine);
    }

    /* ------------------------------------------------------------------ */
    /* layout                                                              */
    /* ------------------------------------------------------------------ */

    /**
     * Builds the full window content: gradient/grid backdrop, masthead and SAVE/ABANDON buttons
     * up top, the board and status bar on the left, the ledger below the header, and the tabbed
     * side panel on the right. Also binds keyboard shortcuts and does the first {@link #refresh()}.
     *
     * @return the assembled root component, ready to drop into a frame
     */
    public JComponent root() {
        var root = new JPanel(new BorderLayout(10, 10)) {
            @Override protected void paintComponent(Graphics g) {
                var g2 = (Graphics2D) g;
                g2.setPaint(new GradientPaint(0, 0, new Color(0x11, 0x24, 0x38), getWidth(), getHeight(), Theme.INK));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(new Color(184, 219, 247, 10));
                for (int x = 0; x < getWidth(); x += 40) g2.drawLine(x, 0, x, getHeight());
                for (int y = 0; y < getHeight(); y += 40) g2.drawLine(0, y, getWidth(), y);
            }
        };
        root.setBorder(BorderFactory.createEmptyBorder(12, 14, 14, 14));

        var head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(new Masthead(), BorderLayout.CENTER);
        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(new Ui.Chip("SAVE", () -> {
            Save.write(engine.board);
            status.set(List.of(Ui.Seg.of("Site saved.", Theme.GOOD)));
        }));
        buttons.add(new Ui.Chip("ABANDON SITE", this::abandon));
        head.add(buttons, BorderLayout.EAST);

        var north = new JPanel(new BorderLayout(0, 10));
        north.setOpaque(false);
        north.add(head, BorderLayout.NORTH);
        north.add(ledger, BorderLayout.SOUTH);

        var left = new JPanel(new BorderLayout(0, 0));
        left.setOpaque(false);
        left.add(boardPanel, BorderLayout.CENTER);
        left.add(status, BorderLayout.SOUTH);

        root.add(north, BorderLayout.NORTH);
        root.add(left, BorderLayout.CENTER);
        root.add(side(), BorderLayout.EAST);

        bindKeys(root);
        refresh();
        status.set(List.of(
                Ui.Seg.of("Click the core", Theme.CHALK),
                Ui.Seg.of(" to condense matter, or press space. Hover anything for readings.", Theme.DIM)));
        return root;
    }

    /**
     * Builds the right-hand tabbed panel: the BUILD/RESEARCH/MANUAL tab row on top of a
     * {@link CardLayout} deck holding the three tab bodies. Starts on the BUILD tab.
     *
     * @return the side panel component
     */
    private JComponent side() {
        var panel = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                var g2 = (Graphics2D) g;
                g2.setPaint(new GradientPaint(0, 0, Theme.PANEL, 0, getHeight(), new Color(0x0C, 0x1C, 0x2C)));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(Theme.LINE2);
                g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            }
        };
        panel.setPreferredSize(new Dimension(354, 100));

        var tabRow = new JPanel(new GridLayout(1, 3));
        tabRow.setOpaque(false);
        for (String name : List.of("BUILD", "RESEARCH", "MANUAL")) {
            var tab = new TabButton(name, () -> showTab(name));
            tabs.add(tab);
            tabRow.add(tab);
        }
        tabs.get(0).active = true;

        cards.setOpaque(false);
        cards.add(buildTab(), "BUILD");
        cards.add(techTab(), "RESEARCH");
        cards.add(Ui.scroll(manual), "MANUAL");

        panel.add(tabRow, BorderLayout.NORTH);
        panel.add(cards, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Builds the BUILD tab: the hint box, a dedicated "Dismantle" row, and one {@link ItemRow}
     * per buildable {@link Machine}.
     *
     * <p>Each row's {@link ItemRow.Model} and click handler are anonymous inner classes created
     * fresh inside the loop, closing over the loop variable {@code m} and over this class's
     * mutable state ({@code engine}, {@code selected}, {@code demolishing}). There is no
     * intermediate view-model object; the closures are read live by {@link ItemRow} on every
     * repaint, so a row always reflects current engine state without any explicit refresh wiring
     * beyond calling {@link #refresh()} after a state change.
     *
     * @return the scrollable BUILD tab body
     */
    private JComponent buildTab() {
        var list = new JPanel();
        list.setOpaque(false);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        list.add(hint);
        list.add(Box.createVerticalStrut(6));

        // Model/handler for the dismantle toggle row; a one-off anonymous Model since it isn't
        // keyed to any Machine.
        var demolish = new ItemRow(new ItemRow.Model() {
            public String title()  { return "Dismantle"; }
            public String meta()   { return demolishing ? "ON" : "half back"; }
            public Map<Res, Double> cost() { return Map.of(); }
            public String io()     { return "Click a machine to remove the whole block."; }
            public String blurb()  { return ""; }
            public boolean affordable() { return true; }
            public boolean selected()   { return demolishing; }
            public boolean done()       { return true; }
        }, () -> {
            demolishing = !demolishing;
            if (demolishing) selected = null;
            boardPanel.setDemolishing(demolishing);
            boardPanel.setGhost(null);
            refresh();
        });
        list.add(demolish);
        list.add(Box.createVerticalStrut(10));
        list.add(new SectionLabel("Machines"));

        // Fresh anonymous Model + click handler per machine, closing over the loop variable m;
        // see class-level Javadoc for why this replaces a proper view-model type.
        for (Machine m : Machine.BUILDABLE) {
            var row = new ItemRow(new ItemRow.Model() {
                public String title() { return m.spec().label(); }
                public String meta()  {
                    if (!engine.unlocked(m)) return "needs " + m.spec().tech().label;
                    return m.spec().abbr() + " x" + engine.board.count(m);
                }
                public Map<Res, Double> cost() { return engine.priceOf(m); }
                public String io()    { return describe(m); }
                public String blurb() { return m.spec().blurb(); }
                public boolean affordable() { return engine.unlocked(m) && engine.affordable(engine.priceOf(m)); }
                public boolean selected()   { return selected == m; }
                public boolean done()       { return false; }
            }, () -> {
                if (!engine.unlocked(m)) return;
                selected = selected == m ? null : m;
                demolishing = false;
                boardPanel.setDemolishing(false);
                boardPanel.setGhost(selected);
                refresh();
            });
            buildRows.put(m, row);
            list.add(row);
            list.add(Box.createVerticalStrut(5));
        }
        list.add(Box.createVerticalGlue());
        return Ui.scroll(list);
    }

    /**
     * Builds the RESEARCH tab: one {@link ItemRow} per {@link Tech}, in declaration order.
     *
     * <p>Same functional-callback-style wiring as {@link #buildTab()}: each row gets a fresh
     * anonymous {@link ItemRow.Model} closing over the loop variable {@code t} and {@code engine}.
     *
     * @return the scrollable RESEARCH tab body
     */
    private JComponent techTab() {
        var list = new JPanel();
        list.setOpaque(false);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        list.add(new SectionLabel("Research"));
        for (Tech t : Tech.values()) {
            var row = new ItemRow(new ItemRow.Model() {
                public String title() { return t.label; }
                public String meta()  {
                    if (engine.board.has(t)) return "done";
                    var missing = t.requires().stream().filter(r -> !engine.board.has(r)).map(r -> r.label).toList();
                    return missing.isEmpty() ? "" : "needs " + String.join(", ", missing);
                }
                public Map<Res, Double> cost() { return t.cost; }
                public String io()    { return t.blurb; }
                public String blurb() { return ""; }
                public boolean affordable() { return engine.researchable(t) && engine.affordable(t.cost); }
                public boolean selected()   { return false; }
                public boolean done()       { return engine.board.has(t); }
            }, () -> {
                if (engine.research(t)) {
                    status.set(List.of(Ui.Seg.of("Research complete - ", Theme.DIM), Ui.Seg.of(t.label, Theme.GOOD)));
                    refresh();
                }
            });
            techRows.put(t, row);
            list.add(row);
            list.add(Box.createVerticalStrut(5));
        }
        list.add(Box.createVerticalGlue());
        return Ui.scroll(list);
    }

    /**
     * Switches the card deck to the named tab and updates each {@link TabButton}'s active
     * (highlighted) state to match.
     *
     * @param name one of "BUILD", "RESEARCH", "MANUAL" — must match a card key added in {@link #side()}
     */
    private void showTab(String name) {
        ((CardLayout) cards.getLayout()).show(cards, name);
        for (TabButton t : tabs) {
            t.active = t.label.equals(name);
            t.repaint();
        }
    }

    /* ------------------------------------------------------------------ */
    /* running                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Starts the four Swing timers that drive the running game: a 50ms simulation tick (dt
     * clamped to 0.5s so a paused/backgrounded window can't cause a huge catch-up step), a 33ms
     * board repaint, a 280ms UI {@link #refresh()}, and a 20s autosave. Call once after {@link
     * #root()}.
     */
    public void start() {
        lastTick = System.nanoTime();
        new Timer(50, e -> {
            long now = System.nanoTime();
            double dt = Math.min(0.5, (now - lastTick) / 1e9);
            lastTick = now;
            engine.tick(dt);
        }).start();
        new Timer(33, e -> boardPanel.repaint()).start();
        new Timer(280, e -> refresh()).start();
        new Timer(20_000, e -> Save.write(engine.board)).start();
    }

    /**
     * Re-syncs all UI surfaces with current engine state: updates the hint text, shows/hides
     * build rows whose unlock state changed, and repaints the ledger, tab cards and manual.
     * Called after every player action and on a timer from {@link #start()}; cheap enough to
     * call liberally since it does no layout work unless visibility actually changed.
     */
    public void refresh() {
        String next = hintText();
        if (!next.equals(hint.text)) {
            hint.text = next;
            hint.revalidate();
            hint.getParent().revalidate();
        }
        for (Machine m : Machine.BUILDABLE) {
            var row = buildRows.get(m);
            if (row == null) continue;
            boolean show = engine.unlocked(m) || (m.spec().tech() != null && engine.researchable(m.spec().tech()));
            if (row.isVisible() != show) {
                row.setVisible(show);
                row.getParent().revalidate();
            }
        }
        ledger.revalidate();
        ledger.repaint();
        cards.repaint();
        manual.repaint();
    }

    /**
     * Resets the session to a brand-new site after confirmation, wiping the save file.
     *
     * <p>This does not construct a new {@link Game}/{@link Engine} and swap it into the UI.
     * Instead it builds a throwaway {@link Engine#fresh()}, copies its board arrays ({@code
     * cell}, {@code ore}, {@code rich}) byte-for-byte onto the <em>live</em> board's arrays via
     * {@link System#arraycopy}, and then manually resets every other mutable board field one by
     * one (resources, seen-set, built/tech sets, claim size, energy, click count, log). This is a
     * manual "reset in place": {@code engine}, {@code boardPanel}, {@code ledger} and every row's
     * closures are all bound to the original {@link Engine} instance, so replacing that instance
     * would mean re-wiring every listener and closure built during {@link #root()}. Mutating the
     * existing engine's board arrays in place avoids that entirely.
     */
    private void abandon() {
        int answer = JOptionPane.showConfirmDialog(boardPanel,
                "Abandon the site? Everything is lost and a new grid is surveyed.",
                "Abandon site", JOptionPane.OK_CANCEL_OPTION);
        if (answer != JOptionPane.OK_OPTION) return;
        Save.wipe();
        var fresh = Engine.fresh();
        System.arraycopy(fresh.board.cell, 0, engine.board.cell, 0, fresh.board.cell.length);
        System.arraycopy(fresh.board.ore, 0, engine.board.ore, 0, fresh.board.ore.length);
        System.arraycopy(fresh.board.rich, 0, engine.board.rich, 0, fresh.board.rich.length);
        engine.board.res.replaceAll((r, v) -> 0.0);
        engine.board.seen.clear();
        engine.board.seen.put(Res.MATTER, true);
        engine.board.built.clear();
        engine.board.tech.clear();
        engine.board.claim = 7;
        engine.board.energy = 0;
        engine.board.clicks = 0;
        engine.board.log.clear();
        selected = null;
        demolishing = false;
        boardPanel.setGhost(null);
        boardPanel.setDemolishing(false);
        engine.markDirty();
        engine.recompute();
        refresh();
    }

    /**
     * Binds window-wide keyboard shortcuts (SPACE = tap core, ESCAPE = clear selection, D =
     * toggle dismantle) onto {@code root}'s input/action maps, active whenever the containing
     * window has focus, regardless of which child component has it.
     *
     * @param root the component whose input map the shortcuts are registered on
     */
    private void bindKeys(JComponent root) {
        var im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        var am = root.getActionMap();
        im.put(KeyStroke.getKeyStroke("SPACE"), "tap");
        am.put("tap", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { tapCore(); }
        });
        im.put(KeyStroke.getKeyStroke("ESCAPE"), "clear");
        am.put("clear", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                selected = null;
                demolishing = false;
                boardPanel.setGhost(null);
                boardPanel.setDemolishing(false);
                refresh();
            }
        });
        im.put(KeyStroke.getKeyStroke("D"), "demolish");
        am.put("demolish", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                demolishing = !demolishing;
                selected = null;
                boardPanel.setGhost(null);
                boardPanel.setDemolishing(demolishing);
                refresh();
            }
        });
    }

    /** Taps the core for matter and flashes the yield over the board; shared by click and space-bar paths. */
    private void tapCore() {
        double gain = engine.tapCore();
        boardPanel.flashCore("+" + Fmt.n(gain));
        ledger.repaint();
    }

    /* ------------------------------------------------------------------ */
    /* board callbacks                                                     */
    /* ------------------------------------------------------------------ */

    /**
     * {@link BoardPanel.Handler} callback for a left click/drag-through on a cell. Dispatches by
     * current mode: dismantle mode demolishes the clicked block (except the core), otherwise
     * clicking the core taps it, and clicking with a machine selected attempts placement.
     *
     * @param x     cell column
     * @param y     cell row
     * @param group the fused block occupying the cell, or {@code null} if empty
     */
    @Override public void pressed(int x, int y, Group group) {
        if (demolishing) {
            if (group != null && group.type != Machine.CORE) {
                engine.demolish(group);
                refresh();
            }
            return;
        }
        if (group != null && group.type == Machine.CORE) {
            tapCore();
            return;
        }
        if (selected != null && engine.place(selected, x, y)) refresh();
    }

    /**
     * {@link BoardPanel.Handler} callback for pointer movement onto a new cell. Updates the
     * status bar with a description of whatever is under the pointer.
     *
     * @param x     cell column
     * @param y     cell row
     * @param group the fused block occupying the cell, or {@code null} if empty
     */
    @Override public void hovered(int x, int y, Group group) {
        pointerOnBoard = true;
        status.set(group != null ? inspect(group) : describeGround(x, y));
    }

    /**
     * Builds the status-bar readout for a hovered machine block: label, size and location,
     * fusion multiplier if fused, overclock bonus if any, ore richness if built on ore, and
     * power state (unpowered / throttled / draw), plus any machine-specific status line.
     *
     * @param g the hovered block
     * @return styled segments for {@link StatusBar#set}
     */
    private List<Ui.Seg> inspect(Group g) {
        var out = new ArrayList<Ui.Seg>();
        if (g.type == Machine.CORE) {
            out.add(Ui.Seg.of("Core", Theme.CHALK));
            out.add(Ui.Seg.of(" - worked by hand. Each tap yields ", Theme.DIM));
            out.add(Ui.Seg.of(Fmt.n(engine.clickYield()) + " matter", Theme.AMBER));
            out.add(Ui.Seg.of(". " + engine.board.clicks + " taps so far.", Theme.DIM));
            return out;
        }
        Spec spec = g.type.spec();
        out.add(Ui.Seg.of(spec.label(), Theme.CHALK));
        out.add(Ui.Seg.of("  " + g.w + "x" + g.h + " at " + g.where(), Theme.DIM));
        if (g.fused()) {
            out.add(Ui.Seg.of("  fused x" + Fmt.n(g.fusionFactor(engine.exponent())), Theme.AMBER));
            out.add(Ui.Seg.of(" from " + g.area + " machines", Theme.DIM));
        }
        if (g.mult > 1.0001) out.add(Ui.Seg.of("  overclock +" + Fmt.pct(g.mult - 1), Theme.AMBER));
        if (g.ore != null) out.add(Ui.Seg.of("  " + g.ore.lower() + " richness " + String.format("%.2f", g.richness), Theme.DIM));
        if (!g.powered) {
            out.add(Ui.Seg.of("  not linked to the core - no power", Theme.HOT));
        } else if (g.rate < 0.999) {
            out.add(Ui.Seg.of("  running at " + Fmt.pct(g.rate), Theme.HOT));
        } else if (spec.draw() > 0) {
            out.add(Ui.Seg.of("  draw " + Fmt.n(spec.draw() * g.area) + " pw", Theme.DIM));
        }
        String line = boardPanel.statusLine(g);
        if (line != null) out.add(Ui.Seg.of("  " + line, Theme.ICE));
        return out;
    }

    /**
     * Builds the status-bar readout for hovered empty ground: outside-claim notice, bare rock,
     * or an ore patch with its richness.
     *
     * @param x cell column
     * @param y cell row
     * @return styled segments for {@link StatusBar#set}
     */
    private List<Ui.Seg> describeGround(int x, int y) {
        Board b = engine.board;
        int i = Board.idx(x, y);
        if (!b.inClaim(x, y))
            return List.of(Ui.Seg.of("Outside the claim", Theme.CHALK),
                    Ui.Seg.of(" - research a Claim Extension to survey this ground.", Theme.DIM));
        Res ore = b.ore[i];
        if (ore == null)
            return List.of(Ui.Seg.of("Bare rock", Theme.CHALK), Ui.Seg.of("  anything can stand here.", Theme.DIM));
        return List.of(Ui.Seg.of(ore.label + " patch", ore.color),
                Ui.Seg.of("  richness " + b.rich[i] + " - a rig here yields x" + b.rich[i], Theme.DIM));
    }

    /* ------------------------------------------------------------------ */
    /* copy                                                                */
    /* ------------------------------------------------------------------ */

    /**
     * Builds the one-line "what does this do" summary shown in a build row's info text: power
     * draw, then a role-specific line (generator output/fuel, mine rate, converter in/out,
     * producer output, buffer capacity, auto-tap rate, amplifier boost, or conduit), then an
     * ore-only note if applicable. Uses an exhaustive {@code switch} over the sealed {@link Role}
     * hierarchy, so adding a new role kind is a compile error here until handled.
     *
     * @param m the machine to describe
     * @return a single line, segments joined with " &middot; "
     */
    private String describe(Machine m) {
        Spec spec = m.spec();
        var parts = new ArrayList<String>();
        if (spec.draw() > 0) parts.add("power -" + Fmt.n(spec.draw()));
        switch (spec.role()) {
            case Role.Generator gen -> {
                parts.add("power +" + Fmt.n(gen.power()));
                gen.fuel().forEach((r, v) -> parts.add("burns " + Fmt.n(v) + " " + r.lower() + "/s"));
            }
            case Role.Mine mine -> parts.add("mines " + Fmt.n(mine.rate() * engine.mineMultiplier()) + "/s x richness");
            case Role.Converter c -> {
                c.in().forEach((r, v) -> parts.add("in " + Fmt.n(v) + " " + r.lower() + "/s"));
                c.out().forEach((r, v) -> parts.add("out " + Fmt.n(v * engine.outputMultiplier(m)) + " " + r.lower() + "/s"));
            }
            case Role.Producer p -> p.out().forEach((r, v) -> parts.add("out " + Fmt.n(v) + " " + r.lower() + "/s"));
            case Role.Buffer buf -> parts.add("stores " + Fmt.n(buf.capacity()) + " power");
            case Role.AutoTap tap -> parts.add("taps the core " + Fmt.n(tap.perSecond() * engine.clickYield()) + " matter/s");
            case Role.Amplifier amp -> parts.add("boosts touching blocks +" + Fmt.pct(amp.boost()) + " each");
            case Role.Conduit c -> parts.add("carries power");
        }
        if (spec.oreOnly()) parts.add("ore patch only");
        return String.join("  \u00b7  ", parts);
    }

    /**
     * Picks the single most relevant tutorial tip for the player's current progress, checked in
     * a fixed priority order from "haven't tapped yet" through late-game fusion advice. Each
     * check gates on board state (counts, researched tech), so the hint advances automatically
     * as the player progresses and never needs to be dismissed.
     *
     * @return the current hint text
     */
    private String hintText() {
        Board b = engine.board;
        int solars = b.count(Machine.SOLAR), rigs = b.count(Machine.MINER);
        if (!b.has(Tech.TOOLS0) && solars == 0)
            return "Click the core to condense matter. The first 60 buys Percussive Drills in Research, which makes every tap worth five times as much.";
        if (solars == 0)
            return "Build a Photon Collector touching the core. Power only flows through machines that touch.";
        if (rigs == 0)
            return "Put a Mining Rig on an ore patch - the coloured cells. Chain pylons or machines out to reach one.";
        if (rigs < 4)
            return "Fusion: place four identical machines as a 2x2 block. They merge into one machine with 4 squared = 16 times the output, on the same four cells and only four times the power draw.";
        if (!b.has(Tech.SMELTING)) return "Research Smelting to turn ore into metal.";
        if (b.count(Machine.FE) + b.count(Machine.CU) == 0)
            return "Build furnaces. They feed from your ore stock, so they need to touch the network but not the rigs.";
        if (!b.has(Tech.COMBUSTION)) return "Coal is fuel. Research Combustion, mine coal, then build Coal Burners for real power.";
        if (!b.has(Tech.SCIENCE)) return "Steel and circuits lead to the Research Lab, which makes data. Data unlocks everything after that.";
        if (!b.has(Tech.TERR1)) return "Data buys territory. A bigger claim means room for bigger rectangles.";
        if (!b.has(Tech.OVERCLOCK)) return "Overclock Nodes multiply every block they touch, and their boost scales with their own fusion.";
        return "Bigger rectangles beat more rectangles: one 4x4 block out-produces four separate 2x2 blocks by four times.";
    }

    /* ------------------------------------------------------------------ */
    /* small components                                                    */
    /* ------------------------------------------------------------------ */

    /**
     * The "SUBSTRATE / SURVEY GRID 04 ..." title header. A bespoke {@link JComponent} rather than
     * a {@link JLabel} because it needs per-glyph control over spacing and color that Swing's
     * label/font kerning doesn't expose: it draws "SUBSTRATE" one character at a time via {@link
     * Graphics#drawString}, adding a fixed 7px gap after each glyph and coloring the first three
     * letters differently from the rest, then continues with the subtitle at the resulting x
     * offset.
     */
    private static final class Masthead extends JComponent {
        /** Fixed size; the masthead's content never changes so there is no live measurement to do. */
        @Override public Dimension getPreferredSize() { return new Dimension(300, 34); }

        /**
         * Draws "SUBSTRATE" glyph-by-glyph with a manual 7px advance per character (the first
         * three letters in chalk, the rest in amber), then the subtitle starting where the title
         * left off, then the underline rule.
         */
        @Override protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics;
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(Theme.monoBold(23));
            float x = 0;
            String word = "SUBSTRATE";
            for (int i = 0; i < word.length(); i++) {
                g.setColor(i < 3 ? Theme.CHALK : Theme.AMBER);
                String c = String.valueOf(word.charAt(i));
                g.drawString(c, x, 24);
                x += g.getFontMetrics().stringWidth(c) + 7;
            }
            g.setFont(Theme.mono(10));
            g.setColor(Theme.DIM);
            g.drawString("SURVEY GRID 04   AUTONOMOUS FOUNDRY", x + 10, 22);
            g.setColor(Theme.LINE2);
            g.drawLine(0, 32, getWidth(), 32);
        }
    }

    /**
     * A tab button (BUILD/RESEARCH/MANUAL). A bespoke {@link JComponent} rather than a {@link
     * JToggleButton} so the label's letter-spacing is under manual control: the constructor's
     * {@code onClick} is invoked straight from a raw mouse listener, and painting measures each
     * character's width, centers the whole run, then draws it one glyph at a time with a fixed
     * 2px gap between letters.
     */
    private static final class TabButton extends JComponent {
        /** Tab name, also used as the {@link CardLayout} key in {@link Game#showTab}. */
        final String label;
        /** Whether this is the currently shown tab; toggled externally by {@link Game#showTab}. */
        boolean active;

        /**
         * @param label   tab name, shown centered and uppercase-styled via the theme font
         * @param onClick invoked on mouse click, regardless of button or modifiers
         */
        TabButton(String label, Runnable onClick) {
            this.label = label;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent e) { onClick.run(); }
            });
        }

        /** Fixed size; the tab row uses a {@link GridLayout} so all three buttons are equal width anyway. */
        @Override public Dimension getPreferredSize() { return new Dimension(80, 30); }

        /**
         * Paints the active-state fill and border, then the label: computes total advance width
         * across all characters first to center the run, then draws each character individually
         * with a fixed 2px gap to get consistent letter-spacing regardless of font kerning.
         */
        @Override protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics;
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            if (active) {
                g.setColor(Theme.CHALK);
                g.fillRect(0, 0, getWidth(), getHeight());
            }
            g.setColor(Theme.LINE);
            g.drawRect(-1, -1, getWidth(), getHeight());
            g.setFont(Theme.mono(10));
            g.setColor(active ? Theme.INK : Theme.DIM);
            var fm = g.getFontMetrics();
            float x = 0;
            float total = 0;
            for (char c : label.toCharArray()) total += fm.charWidth(c) + 2;
            x = (getWidth() - total) / 2f;
            for (char c : label.toCharArray()) {
                g.drawString(String.valueOf(c), x, (getHeight() + fm.getAscent()) / 2f - 1);
                x += fm.charWidth(c) + 2;
            }
        }
    }

    /**
     * A small uppercase section heading (e.g. "MACHINES", "RESEARCH") with a rule underneath. A
     * bespoke {@link JComponent} instead of a {@link JLabel} for the same reason as {@link
     * TabButton}: manual per-glyph {@code drawString} gives wider, deliberate letter-spacing
     * (2.5px) than default font kerning provides at this small size.
     */
    private static final class SectionLabel extends JComponent {
        private final String text;

        SectionLabel(String text) { this.text = text; }

        /** Fixed height; the label sits in a {@link BoxLayout} so width is stretched by the container. */
        @Override public Dimension getPreferredSize() { return new Dimension(100, 20); }
        /** Caps height at 20px so {@link BoxLayout} doesn't stretch it vertically. */
        @Override public Dimension getMaximumSize()   { return new Dimension(Integer.MAX_VALUE, 20); }

        /** Draws the uppercased text glyph-by-glyph with a fixed 2.5px advance, then the underline rule. */
        @Override protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics;
            g.setFont(Theme.mono(9));
            g.setColor(Theme.DIM);
            float x = 0;
            for (char c : text.toUpperCase().toCharArray()) {
                g.drawString(String.valueOf(c), x, 12);
                x += g.getFontMetrics().charWidth(c) + 2.5f;
            }
            g.setColor(Theme.LINE);
            g.drawLine(0, 16, getWidth(), 16);
        }
    }

    /** The amber tip box shown above the build list, wrapping {@link Game#hintText()}'s current tip. */
    private static final class HintBox extends JComponent {
        /** Current hint text; mutated directly by {@link Game#refresh()} rather than via a setter. */
        String text = "";

        /**
         * Computes the box's height by re-running the text-wrapping algorithm against {@link
         * #wrapWidth()} to count how many lines the current text needs. This redoes the same
         * wrapping work that {@link #paintComponent} does, independently and on every layout
         * pass, rather than caching the wrapped lines — simple at the cost of wrapping twice per
         * frame, and correct as long as {@link Ui#wrap} is cheap and deterministic.
         */
        @Override public Dimension getPreferredSize() {
            int w = wrapWidth();
            var fm = getFontMetrics(Theme.mono(11));
            int lines = Math.max(1, Ui.wrap(text, fm, w).size());
            return new Dimension(w + 16, lines * (fm.getHeight() + 1) + 12);
        }

        /**
         * Width to wrap text at: prefers the live parent width (so the box re-wraps as the
         * window resizes), falling back to this component's own width, and finally to the magic
         * literal 300 when neither is known yet. The {@code > 60} checks guard against reading a
         * width from a parent/self that Swing hasn't laid out yet (which reports 0 or a stale
         * small value) and would otherwise wrap text down to one character per line.
         */
        private int wrapWidth() {
            int parent = getParent() != null ? getParent().getWidth() - 20 : 0;
            int w = parent > 60 ? parent : (getWidth() > 60 ? getWidth() : 300);
            return Math.max(120, w - 16);
        }

        /** Height tracks {@link #getPreferredSize()} exactly; width is free to stretch in a {@link BoxLayout}. */
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }

        /** Paints the amber background, left accent bar, and the hint text re-wrapped at {@link #wrapWidth()}. */
        @Override protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics;
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Theme.alpha(Theme.AMBER, 18));
            g.fill(new Rectangle2D.Double(0, 0, getWidth(), getHeight() - 2));
            g.setColor(Theme.AMBER);
            g.fill(new Rectangle2D.Double(0, 0, 2, getHeight() - 2));
            g.setFont(Theme.mono(11));
            var fm = g.getFontMetrics();
            g.setColor(new Color(0xFF, 0xDD, 0xA6));
            int y = 6 + fm.getAscent();
            for (String line : Ui.wrap(text, fm, wrapWidth())) {
                g.drawString(line, 10, y);
                y += fm.getHeight() + 1;
            }
        }
    }

    /** The rules, plus a log of what has happened. */
    private static final class Manual extends JComponent {
        private final Engine engine;

        Manual(Engine engine) {
            this.engine = engine;
            setOpaque(false);
        }

        /**
         * Builds the manual's section headings and body text. The "Fusion" section embeds a
         * live-computed number, {@code engine.exponent()} (the fusion area exponent, which
         * increases as geometry research unlocks), via {@link String#format} directly into the
         * returned text — so the manual's wording changes as the player unlocks tech, with no
         * separate update path required.
         *
         * <p>This list is rebuilt from scratch on every call rather than cached, and it is called
         * from both {@link #getPreferredSize()} and {@link #paintComponent}, so the same
         * string-building and {@code String.format} work runs twice per repaint. That's accepted
         * here for simplicity: the section text is small and {@code engine.exponent()} is cheap,
         * so re-deriving it beats keeping a cached copy in sync with tech changes.
         *
         * @return {@code {heading, body}} pairs, in display order
         */
        private List<String[]> sections() {
            return List.of(
                new String[]{"The site",
                    "The core sits at H8. Everything you build has to form one connected mass with it: machines pass power to whatever they touch, orthogonally. A machine that loses contact stops dead. Conduit Pylons are the cheap way to reach a distant patch."},
                new String[]{"Fusion",
                    "Any solid rectangle of identical machines at least 2x2 merges into a single machine. Its output is multiplied by area to the power of "
                    + String.format("%.2f", engine.exponent())
                    + ", while its power draw only grows with area. A 3x3 block of nine rigs produces 81 times one rig and draws nine times the power. Straight 1xN lines do not fuse. Rigs only fuse with rigs standing on the same kind of ore."},
                new String[]{"Power",
                    "Supply has to meet draw. If it does not, everything slows to the fraction that can be covered. Capacitor Banks store surplus and cover spikes. Fuel burners only burn what the site actually needs."},
                new String[]{"Throughput",
                    "Furnaces and assemblers scale their inputs with their outputs, so a fused smelter is a throughput monster that will strip your ore stock in seconds. Feed it more rigs."},
                new String[]{"Controls",
                    "Pick a machine, then click or drag across empty cells. Space taps the core. D toggles dismantle, which returns half. Escape clears the selection. The site saves itself every twenty seconds."});
        }

        /**
         * Computes total height by rebuilding {@link #sections()} and re-wrapping every section's
         * body plus the log at {@link #wrapWidth()}, summing line counts. As with {@link
         * #sections()}, this duplicates the wrapping work {@link #paintComponent} does on every
         * repaint rather than caching it, so layout and painting independently agree on line
         * counts by construction (both call the same wrap logic) instead of by kept-in-sync state.
         */
        @Override public Dimension getPreferredSize() {
            int w = wrapWidth();
            var fm = getFontMetrics(Theme.mono(11));
            int h = 12;
            for (String[] s : sections()) {
                h += 22;
                h += Ui.wrap(s[1], fm, w).size() * (fm.getHeight() + 1);
                h += 8;
            }
            h += 22 + Math.max(1, engine.board.log.size()) * (fm.getHeight() + 1) + 20;
            return new Dimension(w, h);
        }

        /**
         * Width to wrap section text at: prefers the live parent width, falling back to this
         * component's own width, and finally to the magic literal 320 before either has been laid
         * out. Same {@code > 60} defensive guard as {@link HintBox#wrapWidth()}, against reading a
         * width of 0 (or another not-yet-laid-out stale value) from a parent/self Swing hasn't
         * sized yet.
         */
        private int wrapWidth() {
            int parent = getParent() != null ? getParent().getWidth() - 4 : 0;
            int w = parent > 60 ? parent : (getWidth() > 60 ? getWidth() : 320);
            return Math.max(140, w - 16);
        }

        /** Paints every section's heading (glyph-spaced like {@link SectionLabel}) and wrapped body, then the log. */
        @Override protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics;
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            var fm = g.getFontMetrics(Theme.mono(11));
            int y = 16;
            for (String[] s : sections()) {
                g.setFont(Theme.mono(9));
                g.setColor(Theme.AMBER);
                float x = 8;
                for (char c : s[0].toUpperCase().toCharArray()) {
                    g.drawString(String.valueOf(c), x, y);
                    x += g.getFontMetrics().charWidth(c) + 2.5f;
                }
                y += 14;
                g.setFont(Theme.mono(11));
                g.setColor(new Color(0xB6, 0xCE, 0xE2));
                for (String line : Ui.wrap(s[1], fm, wrapWidth())) {
                    g.drawString(line, 8, y);
                    y += fm.getHeight() + 1;
                }
                y += 10;
            }
            g.setFont(Theme.mono(9));
            g.setColor(Theme.AMBER);
            g.drawString("LOG", 8, y);
            y += 14;
            g.setFont(Theme.mono(10));
            g.setColor(Theme.DIM);
            if (engine.board.log.isEmpty()) {
                g.drawString("Nothing yet.", 8, y);
            } else {
                for (String line : engine.board.log) {
                    g.drawString("- " + line, 8, y);
                    y += fm.getHeight();
                }
            }
        }
    }
}
