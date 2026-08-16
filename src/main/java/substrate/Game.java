package substrate;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Top-level Swing UI wiring for a session: assembles the board, ledger, status bar and the
 * BUILD / RESEARCH / MANUAL side tabs around a single {@link Engine}, and routes board clicks
 * and hovers back into engine calls. This is the only class that knows how the simulation is
 * laid out on screen; everything downstream ({@link BoardPanel}, {@link LedgerPanel}, {@link
 * ItemRow}, ...) is a dumb view driven from here.
 *
 * <p>Nothing in the BUILD or RESEARCH tabs is backed by a proper view-model class. The RESEARCH
 * tab's rows and the BUILD tab's single detail row are each a fresh anonymous {@link
 * ItemRow.Model} inner class, closing over a loop variable ({@code t}) or over {@code this}
 * directly, plus this class's mutable fields ({@code selected}, {@code demolishing}, {@code
 * toggling}). The BUILD tab's per-machine tiles and its two tool tiles go one step further and
 * skip {@link ItemRow} entirely — see {@link MachineIcon} and {@link ToolIcon} — since a whole
 * grid of them needs to stay small. This is all functional-callback-style UI wiring: every
 * row/tile is just a bundle of closures re-evaluated on every repaint, so there is nothing to
 * keep in sync — they always read current engine state.
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
    /**
     * The BUILD tab's detail card; kept as a field (rather than a local in {@link #buildTab()},
     * like {@link MachineIcon} tiles) so {@link #refresh()} can {@code revalidate()} it — its
     * preferred size depends on {@code selected}, unlike every other row/tile on this tab, whose
     * size is fixed regardless of state.
     */
    private final MachineDetail detail;
    /** Card-switches between the BUILD, RESEARCH and MANUAL side-panel tabs. */
    private final JPanel cards = new JPanel(new CardLayout());
    /** The three tab buttons (BUILD/RESEARCH/MANUAL), kept so {@link #showTab(String)} can toggle their active state. */
    private final List<TabButton> tabs = new ArrayList<>();
    /**
     * Research rows keyed by tech, so {@link #refresh()} can find a just-completed row and move
     * it from {@link #unfinishedTechs} to {@link #finishedTechs}.
     */
    private final Map<Tech, ItemRow> techRows = new EnumMap<>(Tech.class);
    /** Research rows not yet completed, in the RESEARCH tab above the {@link #finishedLabel} divider. */
    private final JPanel unfinishedTechs = new JPanel();
    /**
     * Completed research rows, below the {@link #finishedLabel} divider. Only ever grows —
     * research can't be undone — so {@link #refresh()} just moves a row here once and never
     * moves it back.
     */
    private final JPanel finishedTechs = new JPanel();
    /** Divider between {@link #unfinishedTechs} and {@link #finishedTechs}; hidden until the first tech completes. */
    private final SectionLabel finishedLabel = new SectionLabel("Completed");
    /** The in-game manual/log panel shown under the MANUAL tab. */
    private final Manual manual;

    /** Machine currently armed for placement, or {@code null} if nothing is selected. */
    private Machine selected;
    /** Whether the board is in dismantle mode (click a machine to remove its block). */
    private boolean demolishing;
    /** Whether the board is in power-switch mode (click a machine to toggle its whole block on/off). */
    private boolean toggling;
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
        this.detail = new MachineDetail();
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
                g2.setPaint(new GradientPaint(0, 0, new Color(0x24, 0x1C, 0x10), getWidth(), getHeight(), Theme.INK));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(new Color(196, 168, 122, 12));
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
                g2.setPaint(new GradientPaint(0, 0, Theme.PANEL, 0, getHeight(), new Color(0x1E, 0x17, 0x0E)));
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
     * Builds the BUILD tab: the hint box, a small row of {@link ToolIcon} tiles (Dismantle,
     * Power Switch), a grid of one {@link MachineIcon} per buildable {@link Machine}, and a
     * single {@link MachineDetail} card beneath it showing full detail for whichever machine is
     * currently {@link #selected}.
     *
     * <p>Unlike the old one-{@link ItemRow}-per-machine layout, every icon is always present
     * (never hidden as tech unlocks) — a locked or unaffordable one just paints itself dimmed,
     * since {@link MachineIcon#paintComponent} reads {@code engine} live on every repaint. That
     * sidesteps the reflow problem a variable-length visible list would have: with a fixed
     * {@link GridLayout} there is no gap to leave behind when an icon "hides."
     *
     * <p>Selecting a locked machine still shows its cost and "needs X" requirement in the detail
     * card, even though it can't be armed for placement — see {@link #pickMachine}.
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

        list.add(new SectionLabel("Tools"));
        // FlowLayout, not GridLayout: with only two tiles a GridLayout would stretch each one to
        // half the panel's width, turning them into wide rectangles instead of small square
        // icons. FlowLayout leaves them at their own preferred size and just left-aligns them,
        // with unused width as blank space — a toolbar of small icons, not a stretched bar.
        var tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        tools.setOpaque(false);
        // Dismantle and Power Switch used to be full-width ItemRows with their own title/cost/
        // blurb text; that's a lot of vertical space for two toggles with no per-machine detail
        // to show. As ToolIcon tiles their description moves to a hover tooltip instead — see
        // ToolIcon's Javadoc for why that trade (discoverable on hover, invisible otherwise) was
        // fine here specifically.
        var demolishIcon = new ToolIcon("DISM", Game::paintDismantleGlyph, () -> demolishing, () -> {
            demolishing = !demolishing;
            if (demolishing) selected = null;
            toggling = false;
            boardPanel.setToggling(false);
            boardPanel.setDemolishing(demolishing);
            boardPanel.setGhost(null);
            refresh();
        });
        demolishIcon.setToolTipText("Dismantle: click a machine to remove the whole block, half cost back.");
        tools.add(demolishIcon);

        var powerIcon = new ToolIcon("PWR", Game::paintPowerGlyph, () -> toggling, () -> {
            toggling = !toggling;
            if (toggling) selected = null;
            demolishing = false;
            boardPanel.setDemolishing(false);
            boardPanel.setToggling(toggling);
            boardPanel.setGhost(null);
            refresh();
        });
        powerIcon.setToolTipText("Power Switch: click a machine to switch its whole block on or off. "
                + "Off machines draw no power and make nothing, but stay built.");
        tools.add(powerIcon);
        // Fixes the row's height to the tiles' natural size, same reason the machine grid below does.
        tools.setMaximumSize(new Dimension(Integer.MAX_VALUE, tools.getPreferredSize().height));
        list.add(tools);
        list.add(Box.createVerticalStrut(10));
        list.add(new SectionLabel("Machines"));

        var grid = new JPanel(new GridLayout(0, 4, 6, 6));
        grid.setOpaque(false);
        for (Machine m : Machine.BUILDABLE) grid.add(new MachineIcon(m));
        // Fixes the grid's height to its natural (rows x icon height) size so BoxLayout doesn't
        // stretch it to fill the glue's space below — the same "unbounded width, fixed height"
        // trick ItemRow.getMaximumSize() already uses.
        grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, grid.getPreferredSize().height));
        list.add(grid);
        list.add(Box.createVerticalStrut(10));

        list.add(new SectionLabel("Details"));
        list.add(detail);

        list.add(Box.createVerticalGlue());
        return Ui.scroll(list);
    }

    /**
     * Arms {@code m} for placement, or just shows its detail if it isn't unlocked yet: sets
     * {@link #selected}, clears the other two board tools, and only sets the {@link BoardPanel}
     * ghost preview when {@code m} is actually buildable, so a locked machine's cost/blurb still
     * populates {@link MachineDetail} without letting the player try to place it. Shared by
     * every {@link MachineIcon}'s click handler.
     *
     * @param m the machine icon that was clicked
     */
    private void pickMachine(Machine m) {
        selected = m;
        demolishing = false;
        toggling = false;
        boardPanel.setDemolishing(false);
        boardPanel.setToggling(false);
        boardPanel.setGhost(engine.unlocked(m) ? m : null);
        refresh();
    }

    /**
     * Builds the RESEARCH tab: one {@link ItemRow} per {@link Tech}, in declaration order,
     * split into {@link #unfinishedTechs} above and {@link #finishedTechs} below a {@link
     * #finishedLabel} divider — so completed research reads as a receipt at the bottom of the
     * list rather than staying mixed in with what's still buyable.
     *
     * <p>Same functional-callback-style wiring as {@link #buildTab()}: each row gets a fresh
     * anonymous {@link ItemRow.Model} closing over the loop variable {@code t} and {@code
     * engine}. Each row is wrapped in a small bordered panel purely to carry its own bottom
     * margin — see the wrapping comment below for why that, rather than the usual trailing
     * {@link Box#createVerticalStrut}, is what gets used here.
     *
     * @return the scrollable RESEARCH tab body
     */
    private JComponent techTab() {
        var list = new JPanel();
        list.setOpaque(false);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        list.add(new SectionLabel("Research"));

        unfinishedTechs.setOpaque(false);
        unfinishedTechs.setLayout(new BoxLayout(unfinishedTechs, BoxLayout.Y_AXIS));
        list.add(unfinishedTechs);

        finishedLabel.setVisible(false);
        list.add(finishedLabel);
        finishedTechs.setOpaque(false);
        finishedTechs.setLayout(new BoxLayout(finishedTechs, BoxLayout.Y_AXIS));
        list.add(finishedTechs);

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
            // A row moves whole between unfinishedTechs and finishedTechs in refresh(), so its
            // spacing has to travel with it; a separate trailing Box.createVerticalStrut (as
            // buildTab() uses) would get left behind in the old panel instead. Wrapping the row
            // in its own bordered panel keeps the row and its margin as one movable unit.
            var wrap = new JPanel(new BorderLayout());
            wrap.setOpaque(false);
            wrap.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
            wrap.add(row, BorderLayout.CENTER);
            (engine.board.has(t) ? finishedTechs : unfinishedTechs).add(wrap);
        }
        finishedLabel.setVisible(finishedTechs.getComponentCount() > 0);
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
     * Re-syncs all UI surfaces with current engine state: updates the hint text, revalidates the
     * BUILD tab's {@link #detail} card, moves any newly-completed research row down into {@link
     * #finishedTechs}, and repaints the ledger, tab cards and manual. Called after every player
     * action and on a timer from {@link #start()}; cheap enough to call liberally since it does
     * no layout work beyond that one revalidate.
     *
     * <p>The BUILD tab's {@link MachineIcon} tiles need no explicit show/hide bookkeeping here,
     * unlike the RESEARCH tab's rows — every tile is always present and reads {@code
     * engine}/{@code selected} straight off {@code this} on every repaint (see {@link
     * #buildTab()}), so {@code cards.repaint()} below is enough to bring them up to date. {@link
     * #detail} is the exception: its preferred size depends on {@code selected} too, so it needs
     * an actual {@code revalidate()}, not just a repaint — see its field Javadoc.
     */
    public void refresh() {
        String next = hintText();
        if (!next.equals(hint.text)) {
            hint.text = next;
            hint.revalidate();
            hint.getParent().revalidate();
        }
        // Research only ever finishes, never un-finishes, so a row is moved at most once: the
        // moment engine.board.has(t) first turns true, its wrapper panel (see techTab()) is
        // relocated from unfinishedTechs to the end of finishedTechs, carrying its own spacing
        // with it. Rows already in finishedTechs are skipped by the parent check below.
        boolean movedAny = false;
        for (Tech t : Tech.values()) {
            ItemRow row = techRows.get(t);
            if (row == null || !engine.board.has(t)) continue;
            var wrap = row.getParent();
            if (wrap != null && wrap.getParent() == unfinishedTechs) {
                unfinishedTechs.remove(wrap);
                finishedTechs.add(wrap);
                movedAny = true;
            }
        }
        if (movedAny) {
            finishedLabel.setVisible(true);
            unfinishedTechs.revalidate();
            finishedTechs.revalidate();
            finishedLabel.revalidate();
        }
        // detail's preferred height depends on selected, so a repaint alone isn't enough after
        // a pick changes it — see the field Javadoc for why this is the one BUILD-tab component
        // that needs it. Unconditional, same as ledger.revalidate() below: cheap enough on a
        // single component that tracking "did selected actually change" isn't worth the field.
        detail.revalidate();
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
     * cell}, {@code ore}, {@code rich}, {@code off}) byte-for-byte onto the <em>live</em>
     * board's arrays via {@link System#arraycopy}, and then manually resets every other mutable
     * board field one by one (resources, seen-set, built/tech sets, claim size, energy, click
     * count, victory flag, log). This is a manual "reset in place": {@code engine}, {@code
     * boardPanel}, {@code ledger} and every row's closures are all bound to the original {@link
     * Engine} instance, so replacing that instance would mean re-wiring every listener and
     * closure built during {@link #root()}. Mutating the existing engine's board arrays in place
     * avoids that entirely. Resetting {@link Board#won} means the masthead's badge (see {@link
     * Masthead}) correctly disappears on a fresh site rather than carrying over from the
     * abandoned one.
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
        System.arraycopy(fresh.board.off, 0, engine.board.off, 0, fresh.board.off.length);
        engine.board.res.replaceAll((r, v) -> 0.0);
        engine.board.seen.clear();
        engine.board.seen.put(Res.MATTER, true);
        engine.board.built.clear();
        engine.board.tech.clear();
        engine.board.claim = 7;
        engine.board.energy = 0;
        engine.board.clicks = 0;
        engine.board.won = false;
        engine.board.log.clear();
        selected = null;
        demolishing = false;
        toggling = false;
        boardPanel.setGhost(null);
        boardPanel.setDemolishing(false);
        boardPanel.setToggling(false);
        // Undoes refresh()'s one-way move into finishedTechs: abandoning wipes board.tech, so
        // every tech is unresearched again. refresh() only ever moves a row forward, so this
        // rebuilds both panels from scratch in declaration order rather than moving rows back
        // one at a time — a piecemeal move-back would append each recovered row at the end of
        // unfinishedTechs instead of restoring its original position among the rows that were
        // never touched, silently scrambling the list order.
        unfinishedTechs.removeAll();
        finishedTechs.removeAll();
        for (Tech t : Tech.values()) {
            ItemRow row = techRows.get(t);
            if (row != null) unfinishedTechs.add(row.getParent());
        }
        finishedLabel.setVisible(false);
        unfinishedTechs.revalidate();
        finishedTechs.revalidate();
        engine.markDirty();
        engine.recompute();
        refresh();
    }

    /**
     * Binds window-wide keyboard shortcuts (SPACE = tap core, ESCAPE = clear everything, D =
     * toggle dismantle, P = toggle power switch, Q = deselect the armed machine) onto {@code
     * root}'s input/action maps, active whenever the containing window has focus, regardless of
     * which child component has it.
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
                toggling = false;
                boardPanel.setGhost(null);
                boardPanel.setDemolishing(false);
                boardPanel.setToggling(false);
                refresh();
            }
        });
        im.put(KeyStroke.getKeyStroke("D"), "demolish");
        am.put("demolish", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                demolishing = !demolishing;
                selected = null;
                toggling = false;
                boardPanel.setGhost(null);
                boardPanel.setToggling(false);
                boardPanel.setDemolishing(demolishing);
                refresh();
            }
        });
        im.put(KeyStroke.getKeyStroke("P"), "power");
        am.put("power", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                toggling = !toggling;
                selected = null;
                demolishing = false;
                boardPanel.setGhost(null);
                boardPanel.setDemolishing(false);
                boardPanel.setToggling(toggling);
                refresh();
            }
        });
        im.put(KeyStroke.getKeyStroke("Q"), "deselect");
        am.put("deselect", new AbstractAction() {
            // Clicking a build row now only ever arms it (see buildTab()'s row handler) — it
            // never deselects on a second click — so Q is the one dedicated way to drop the
            // armed machine. Scoped to just the selection/ghost, unlike ESCAPE's clear-everything.
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                selected = null;
                boardPanel.setGhost(null);
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
     * current mode: dismantle mode demolishes the clicked block, power-switch mode flips it on
     * or off (neither applies to the core), otherwise clicking the core taps it, and clicking
     * with a machine selected attempts placement.
     *
     * <p>Placement is the one path that can flip {@link Board#won}, so it's the one place that
     * diffs {@code engine.board.won} across the call and fires {@link #celebrateVictory()} on
     * the false-to-true edge — see {@link Engine#place} for why the flag lives there instead of
     * being reported through {@code place}'s own return value.
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
        if (toggling) {
            if (group != null && group.type != Machine.CORE) {
                engine.toggle(group, !group.enabled);
                refresh();
            }
            return;
        }
        if (group != null && group.type == Machine.CORE) {
            tapCore();
            return;
        }
        if (selected == null) return;
        boolean wasWon = engine.board.won;
        if (engine.place(selected, x, y)) {
            refresh();
            if (!wasWon && engine.board.won) celebrateVictory();
        }
    }

    /**
     * One-time congratulations shown the moment {@link Board#won} first flips true: a modal
     * dialog (the same mechanism {@link #abandon()} already uses for its confirmation prompt)
     * rather than a custom board overlay, since this fires once per game and doesn't need to
     * match the board's animated survey-chart look the way persistent UI does. The permanent
     * record of the achievement is the masthead's {@code ONLINE} badge (see {@link Masthead}),
     * not this dialog — closing it loses nothing.
     */
    private void celebrateVictory() {
        JOptionPane.showMessageDialog(boardPanel,
                "Fusion Reactor online.\n\nThe site produces more than it could ever consume. Keep building, "
                        + "or walk away — the reactor doesn't care either way.",
                "Victory", JOptionPane.INFORMATION_MESSAGE);
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
        // g.powered already folds g.enabled in (see Group#powered), so !g.enabled is checked
        // first to give the player the accurate reason rather than always blaming the link.
        if (!g.enabled) {
            out.add(Ui.Seg.of("  switched off", Theme.AMBER));
        } else if (!g.powered) {
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
    private final class Masthead extends JComponent {
        /** Fixed size; the masthead's content never changes size regardless of {@link Board#won} so there is no live measurement to do. */
        @Override public Dimension getPreferredSize() { return new Dimension(300, 34); }

        /**
         * Draws "SUBSTRATE" glyph-by-glyph with a manual 7px advance per character (the first
         * three letters in chalk, the rest in amber), then the subtitle starting where the title
         * left off, then — once {@link Board#won} — a small amber "FUSION ONLINE" badge after
         * it, then the underline rule. A non-static inner class (unlike most of this file's
         * other bespoke widgets) purely so this one line can read {@code engine.board.won}
         * live; the badge is this achievement's only permanent trace in the UI once the
         * one-time {@link #celebrateVictory()} dialog has been dismissed, so it has to survive
         * a save/reload, not just the moment the reactor went down.
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
            String subtitle = "SURVEY GRID 04   AUTONOMOUS FOUNDRY";
            g.drawString(subtitle, x + 10, 22);
            if (engine.board.won) {
                g.setColor(Theme.AMBER);
                g.drawString("· FUSION ONLINE", x + 10 + g.getFontMetrics().stringWidth(subtitle) + 10, 22);
            }
            g.setColor(Theme.LINE2);
            g.drawLine(0, 32, getWidth(), 32);
        }
    }

    /**
     * A small square board-tool toggle (Dismantle, Power Switch) in the BUILD tab: same size and
     * on/off-border convention as {@link MachineIcon}, but for a tool rather than a buildable
     * machine — no owned-count badge, and the icon is a small hand-drawn glyph (passed in as
     * {@code glyph}) instead of an {@link Art} preview, since there's no machine to render.
     *
     * <p>Dismantle and Power Switch used to be full-width {@link ItemRow}s with their own
     * title/cost/blurb text. As a tile there's no room left for that description, so it moves to
     * a hover tooltip (set by the caller via {@link #setToolTipText}) instead of a permanent
     * on-screen line — a deliberate trade of "always visible" for "small," acceptable here
     * because these two tools are used constantly once learned and the description is exactly
     * the kind of thing a player only needs to re-check once in a while, not every glance.
     */
    private final class ToolIcon extends JComponent {
        /** Short caption drawn along the bottom edge, e.g. {@code "DISM"}. */
        private final String caption;
        /** Draws the tool's glyph into the given icon-area rectangle, in local (0,0-origin) coordinates. */
        private final java.util.function.BiConsumer<Graphics2D, Rectangle2D> glyph;
        /** Whether this tool is the one currently armed; drives the amber wash/border. */
        private final BooleanSupplier active;
        /** Whether the pointer is currently over this tile; drives the hover border/wash. */
        private boolean hover;

        ToolIcon(String caption, java.util.function.BiConsumer<Graphics2D, Rectangle2D> glyph,
                 BooleanSupplier active, Runnable onClick) {
            this.caption = caption;
            this.glyph = glyph;
            this.active = active;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseEntered(java.awt.event.MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(java.awt.event.MouseEvent e)  { hover = false; repaint(); }
                // mousePressed, not mouseClicked: see ItemRow's mousePressed for why.
                @Override public void mousePressed(java.awt.event.MouseEvent e) { onClick.run(); }
            });
        }

        /** Fixed square tile, matching {@link MachineIcon#getPreferredSize()} so the two read as one family of controls. */
        @Override public Dimension getPreferredSize() { return new Dimension(74, 74); }

        /** Draws the background wash and border (colored by hover/active state), the glyph, and the caption. */
        @Override protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            boolean on = active.getAsBoolean();
            int w = getWidth(), h = getHeight();

            g.setColor(on ? Theme.alpha(Theme.AMBER, 30) : new Color(52, 42, 28, hover ? 130 : 70));
            g.fillRect(0, 0, w, h);

            double pad = 15, captionH = 13;
            glyph.accept(g, new Rectangle2D.Double(pad, pad, w - pad * 2, h - pad * 2 - captionH));

            g.setFont(Theme.mono(9));
            g.setColor(on ? Theme.AMBER : Theme.DIM);
            g.drawString(caption, (w - g.getFontMetrics().stringWidth(caption)) / 2f, h - 4);

            g.setColor(on ? Theme.AMBER : hover ? Theme.LINE2 : Theme.LINE);
            g.drawRect(0, 0, w - 1, h - 1);
        }
    }

    /**
     * {@link ToolIcon} glyph for Dismantle: a dark steel block with a bold rust-red "X" through
     * it — a universal "this gets broken" pictogram, drawn in a fixed danger color regardless of
     * whether the tool is currently armed (the tile's wash/border already carries that state, the
     * same separation {@link MachineIcon} keeps between its {@link Art} preview and its own
     * selection styling).
     */
    private static void paintDismantleGlyph(Graphics2D g, Rectangle2D r) {
        g.setColor(Theme.alpha(Theme.STEEL_DARK, 230));
        g.fill(r);
        g.setColor(new Color(0, 0, 0, 90));
        g.draw(r);
        g.setColor(Theme.alpha(Theme.HOT, 220));
        g.setStroke(new BasicStroke((float) (r.getWidth() * 0.13), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double in = r.getWidth() * 0.18;
        g.draw(new Line2D.Double(r.getMinX() + in, r.getMinY() + in, r.getMaxX() - in, r.getMaxY() - in));
        g.draw(new Line2D.Double(r.getMaxX() - in, r.getMinY() + in, r.getMinX() + in, r.getMaxY() - in));
    }

    /**
     * {@link ToolIcon} glyph for Power Switch: the universal power-button pictogram (a broken
     * ring plus a vertical tick through the gap), in a neutral chalk tone for the same reason
     * {@link #paintDismantleGlyph} fixes its color regardless of armed state.
     */
    private static void paintPowerGlyph(Graphics2D g, Rectangle2D r) {
        double cx = r.getCenterX(), cy = r.getCenterY();
        double rad = Math.min(r.getWidth(), r.getHeight()) * 0.4;
        g.setColor(Theme.alpha(Theme.CHALK, 210));
        g.setStroke(new BasicStroke((float) (rad * 0.34), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Arc2D.Double(cx - rad, cy - rad, rad * 2, rad * 2, 55, 250, Arc2D.OPEN));
        g.draw(new Line2D.Double(cx, cy - rad * 1.3, cx, cy - rad * 0.1));
    }

    /**
     * One square catalogue tile in the BUILD tab's machine grid: the machine's own {@link Art}
     * artwork, painted via a synthetic always-on {@link Group} rather than a live board group,
     * dimmed when locked or unaffordable, bordered amber when it's the current {@link
     * #selected}, with a small owned-count badge and an abbreviation caption.
     *
     * <p>A non-static inner class (unlike {@link TabButton}/{@link SectionLabel}, which are
     * static and take everything they need as constructor arguments) because it reads {@code
     * engine} and {@code selected} straight off the enclosing {@link Game} instance on every
     * repaint, the same functional-callback-style wiring the class Javadoc describes for the
     * build/research rows — there's no per-icon view-model, just a closure over live state.
     *
     * <p>The preview {@link Group} is built once per icon and reused for every paint: {@code
     * powered} is forced {@code true} so {@link Art#paint} never applies its "dead machine"
     * hazard overlay (which is about the simulation, not about being locked — lock/afford state
     * gets its own dimming here instead), and ore-only machines get a representative {@link
     * Res#IRON_ORE} so their ore-colored details have something to show.
     */
    private final class MachineIcon extends JComponent {
        /** The machine this tile represents and arms when clicked. */
        private final Machine machine;
        /** Reused across every repaint; only {@code powered} is overridden from its defaults. */
        private final Group preview;
        /** Whether the pointer is currently over this tile; drives the hover border/wash. */
        private boolean hover;

        MachineIcon(Machine machine) {
            this.machine = machine;
            this.preview = previewGroup(machine);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseEntered(java.awt.event.MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(java.awt.event.MouseEvent e)  { hover = false; repaint(); }
                // mousePressed, not mouseClicked: see ItemRow's mousePressed for why.
                @Override public void mousePressed(java.awt.event.MouseEvent e) { pickMachine(machine); }
            });
        }

        /** Fixed square tile: {@link GridLayout} gives every tile the same size regardless anyway. */
        @Override public Dimension getPreferredSize() { return new Dimension(74, 74); }

        /**
         * Draws the background wash and border (colored by hover/selected state), the machine's
         * {@link Art} icon composited at reduced alpha when locked or unaffordable, the owned
         * count in the top-right corner, and the abbreviation caption along the bottom edge.
         */
        @Override protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            boolean unlocked = engine.unlocked(machine);
            boolean afford = unlocked && engine.affordable(engine.priceOf(machine));
            boolean isSelected = selected == machine;
            int w = getWidth(), h = getHeight();

            g.setColor(isSelected ? Theme.alpha(Theme.AMBER, 30) : new Color(52, 42, 28, hover ? 130 : 70));
            g.fillRect(0, 0, w, h);

            Composite old = g.getComposite();
            if (!unlocked) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.28f));
            else if (!afford) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
            double pad = 6, captionH = 13;
            Art.paint(g, preview, new Rectangle2D.Double(pad, pad, w - pad * 2, h - pad * 2 - captionH), 0, false, 0.5);
            g.setComposite(old);

            int owned = engine.board.count(machine);
            if (owned > 0) {
                g.setFont(Theme.mono(9));
                g.setColor(Theme.AMBER);
                String s = "x" + owned;
                g.drawString(s, w - g.getFontMetrics().stringWidth(s) - 3, 10);
            }

            g.setFont(Theme.mono(9));
            g.setColor(unlocked ? Theme.DIM : Theme.alpha(Theme.DIM, 130));
            String abbr = machine.spec().abbr();
            g.drawString(abbr, (w - g.getFontMetrics().stringWidth(abbr)) / 2f, h - 4);

            g.setColor(isSelected ? Theme.AMBER : hover ? Theme.LINE2 : Theme.LINE);
            g.drawRect(0, 0, w - 1, h - 1);
        }
    }

    /**
     * Builds a synthetic, always-on 1x1 {@link Group} purely for catalogue rendering — shared by
     * {@link MachineIcon} and {@link MachineDetail} so a machine's tile icon and its detail-card
     * icon are pixel-identical. {@code powered} is forced {@code true} so {@link Art#paint}
     * never applies its "dead machine" hazard overlay, which is about simulation state, not
     * about whether the machine is locked or affordable (each caller dims those separately).
     * Ore-only machines get a representative {@link Res#IRON_ORE} so their ore-colored details
     * have something to show.
     *
     * @param m the machine to preview
     */
    private static Group previewGroup(Machine m) {
        Res ore = m.spec().oreOnly() ? Res.IRON_ORE : null;
        var g = new Group(0, m, 0, 0, 1, 1, new int[]{0}, ore, 1, true);
        g.powered = true;
        return g;
    }

    /**
     * The BUILD tab's detail card: a large preview icon beside the full title, cost, and
     * description of whichever machine is currently {@link #selected} — replacing the old
     * single {@link ItemRow} used here, which was sized like every other compact list row and
     * read as cramped now that it's the one and only detail slot rather than one of twenty.
     *
     * <p>Reads {@code selected} straight off the enclosing {@link Game} instance on every
     * repaint, the same functional-callback-style wiring as {@link MachineIcon} — there's no
     * explicit refresh path, {@link Game#refresh()}'s {@code cards.repaint()} is enough.
     *
     * <p>{@link #getPreferredSize()} and {@link #paintComponent} independently re-derive the
     * same line count from {@link Ui#wrap} (the same duplication tradeoff {@link Manual} and
     * {@link HintBox} already make, for the same reason: keeping layout and painting in sync by
     * construction is simpler than caching a value neither method fully owns), with a small
     * built-in margin so the card is never one pixel too short for its own wrapped text — the
     * original complaint about this area.
     */
    private final class MachineDetail extends JComponent {
        private static final Font TITLE = Theme.monoBold(15);
        private static final Font BODY  = Theme.mono(11);
        private static final int ICON = 88, PAD = 12, GAP = 14;

        MachineDetail() { setOpaque(false); }

        /** Live width estimate, same trick {@link ItemRow#width()} uses. */
        private int width() {
            int parent = getParent() != null ? getParent().getWidth() - 20 : 0;
            return parent > 60 ? parent : (getWidth() > 60 ? getWidth() : 300);
        }

        /** Width left for text once the icon column and padding are subtracted. */
        private int textWidth() { return Math.max(80, width() - PAD * 2 - ICON - GAP); }

        /**
         * Height is the icon's height when nothing is selected (so the card doesn't shrink to a
         * sliver and jump size the moment something is picked), otherwise the title plus a meta
         * line, one line per cost resource (see {@link #paintComponent} for why cost isn't one
         * joined line), and every wrapped line of {@link #describe} and the blurb — each counted
         * at {@code BODY}'s line height plus 2px, a couple pixels more generous than {@link
         * #paintComponent} actually uses per line, as headroom against clipping.
         */
        @Override public Dimension getPreferredSize() {
            int w = width();
            var fmT = getFontMetrics(TITLE);
            var fm = getFontMetrics(BODY);
            int textH;
            if (selected == null) {
                textH = ICON;
            } else {
                int lines = 1 + engine.priceOf(selected).size();    // meta + one line per cost entry
                lines += Ui.wrap(describe(selected), fm, textWidth()).size();
                lines += Ui.wrap(selected.spec().blurb(), fm, textWidth()).size();
                textH = fmT.getHeight() + 6 + lines * (fm.getHeight() + 2);
            }
            return new Dimension(w, PAD * 2 + Math.max(ICON, textH));
        }

        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }

        /**
         * Draws the card frame, then either the empty-state hint or the icon plus title, meta,
         * cost (color-coded by what's in stock, same convention {@link ItemRow} uses), and the
         * wrapped {@link #describe} and blurb text.
         */
        @Override protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g.setColor(new Color(52, 42, 28, 90));
            g.fill(new Rectangle2D.Double(0, 0, getWidth() - 1, getHeight() - 1));
            g.setColor(Theme.LINE);
            g.draw(new Rectangle2D.Double(0, 0, getWidth() - 1, getHeight() - 1));

            var fm = getFontMetrics(BODY);
            if (selected == null) {
                g.setFont(BODY);
                g.setColor(Theme.DIM);
                int y = getHeight() / 2 - fm.getHeight() / 2 + fm.getAscent();
                for (String line : Ui.wrap("Click a machine above to see what it costs and does.", fm, getWidth() - PAD * 2)) {
                    g.drawString(line, PAD, y);
                    y += fm.getHeight() + 2;
                }
                return;
            }

            Machine m = selected;
            Spec spec = m.spec();
            boolean unlocked = engine.unlocked(m);

            Composite old = g.getComposite();
            if (!unlocked) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
            Art.paint(g, previewGroup(m), new Rectangle2D.Double(PAD, PAD, ICON, ICON), 0, false, 0.5);
            g.setComposite(old);

            int tx = PAD + ICON + GAP;
            int tw = textWidth();
            g.setFont(TITLE);
            var fmT = g.getFontMetrics();
            int y = PAD + fmT.getAscent();
            g.setColor(Theme.CHALK);
            g.drawString(spec.label(), tx, y);

            g.setFont(BODY);
            y += 6 + fm.getHeight();
            String meta = unlocked ? spec.abbr() + "  built x" + engine.board.count(m) : "needs " + spec.tech().label;
            g.setColor(unlocked ? Theme.DIM : Theme.HOT);
            g.drawString(meta, tx, y);

            // One resource per line, not joined inline with " · " like ItemRow's compact list
            // rows do: this card's text column is narrower (the icon takes some of the width
            // ItemRow gets to use), and a 3+ resource cost (e.g. the Quantum Replicator's
            // matter/titanium/circuit) silently overflowed past the component's edge and got
            // clipped when it was joined onto one line.
            for (var e : engine.priceOf(m).entrySet()) {
                y += fm.getHeight() + 2;
                boolean have = engine.board.get(e.getKey()) >= e.getValue() - 1e-9;
                g.setColor(have ? Theme.alpha(Theme.CHALK, 200) : Theme.HOT);
                g.drawString(Fmt.n(e.getValue()) + " " + e.getKey().lower(), tx, y);
            }

            g.setColor(Theme.ICE);
            for (String line : Ui.wrap(describe(m), fm, tw)) { y += fm.getHeight() + 2; g.drawString(line, tx, y); }
            g.setColor(Theme.DIM);
            for (String line : Ui.wrap(spec.blurb(), fm, tw)) { y += fm.getHeight() + 2; g.drawString(line, tx, y); }
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
         * @param onClick invoked on mouse press, regardless of button or modifiers
         */
        TabButton(String label, Runnable onClick) {
            this.label = label;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new java.awt.event.MouseAdapter() {
                // mousePressed, not mouseClicked: see ItemRow's mousePressed for why.
                @Override public void mousePressed(java.awt.event.MouseEvent e) { onClick.run(); }
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
                new String[]{"Victory",
                    "The Fusion Reactor sits at the top of the research tree, gated behind Fission and Geometric Synergy II. Build your first one and the site declares victory - the masthead marks it permanently. Nothing stops afterward; there's no reason not to keep building."},
                new String[]{"Controls",
                    "Pick a machine, then click or drag across empty cells; clicking the same one again keeps it armed, it does not deselect. Space taps the core. D toggles dismantle, which returns half. P toggles the power switch, which pauses a block without demolishing it. Q drops whatever machine is armed. Escape clears everything at once. The site saves itself every twenty seconds."});
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
                g.setColor(new Color(0xD8, 0xC9, 0xA8));
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
