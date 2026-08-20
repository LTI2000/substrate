package substrate;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
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
 * <p><b>Every component is placed with an explicit {@code setBounds} rect, computed once, from
 * fixed constants.</b> There is no {@link LayoutManager} anywhere in this class — no {@link
 * BorderLayout}, {@link BoxLayout}, {@link GridLayout}, {@link FlowLayout}, or {@link
 * CardLayout} — and the window itself is fixed-size and not resizable (see {@link Main}), so
 * there is never a "reflow when the container's size changes" case to handle. {@link #root()}
 * and {@link #side()} lay out their children from hand-picked constants (the block right after
 * this Javadoc); {@link #buildTab()} does the same for a fixed-size, non-scrolling page since
 * its ~22 items are known to fit; {@link #techTab()} positions {@link ItemRow}s with a simple
 * "sum of preceding heights" loop ({@link #positionTechRows()}) since the research list doesn't
 * fit and needs a scrollbar — the one piece of Swing's built-in scrolling machinery kept, since
 * panning a fixed-size, statically-positioned canvas isn't the kind of dynamic layout this is
 * about avoiding. Both mechanisms replace an earlier version built on {@code BoxLayout} +
 * {@code GridLayout} + {@code FlowLayout} + {@code CardLayout}, which chased a run of
 * hard-to-predict bugs around stale cached preferred sizes, {@code revalidate()} timing, and
 * cells stretching to fill a container they weren't supposed to — the entire class of bug this
 * rewrite exists to rule out by construction: nothing here is ever asked "what size do you want
 * to be," so nothing can answer that question incorrectly.
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

    // -- Fixed window/panel geometry. Every number below is load-bearing: it is the only place
    // a given dimension is decided, and every setBounds call in this file traces back to one of
    // these rather than to a live-measured parent size. --
    /** Whole window content size; {@link Main} packs the frame to exactly this and disables resizing. */
    static final int WIN_W = 1180, WIN_H = 860;
    /** Outer margins around the window content. */
    private static final int MARGIN_L = 14, MARGIN_T = 12, MARGIN_R = 14, MARGIN_B = 14;
    /** Gap between stacked/side-by-side blocks. */
    private static final int GAP = 10;
    /** Side (tabbed) panel width, and the height it gets (the full content height). */
    private static final int SIDE_W = 354, SIDE_H = WIN_H - MARGIN_T - MARGIN_B;
    /** Left column width: whatever's left after the side panel and the gap between them. */
    private static final int LEFT_W = WIN_W - MARGIN_L - MARGIN_R - SIDE_W - GAP;
    /** Header (masthead + SAVE/ABANDON) row height. */
    private static final int HEADER_H = 34;
    /** Ledger size: {@link LedgerPanel}'s own fixed column count times its fixed cell size. */
    private static final int LEDGER_W = 768, LEDGER_H = 132;
    /** Status bar height. */
    private static final int STATUS_H = 26;
    /** Board area height; its width is {@link #LEFT_W} and it centers its square grid within that itself. */
    private static final int BOARD_H = SIDE_H - HEADER_H - GAP - LEDGER_H - GAP - GAP - STATUS_H;
    /** Tab button row height, and the gap below it before tab content starts. */
    private static final int TAB_ROW_H = 30, TAB_GAP = 4;
    /** Fixed inner padding and content width shared by every tab page. */
    static final int TAB_PAD = 12, TAB_CONTENT_W = SIDE_W - TAB_PAD * 2;
    /**
     * Fixed height for the rotating hint box. {@link #hintText()}'s longest tip wraps to about
     * five lines at {@link #TAB_CONTENT_W}; this is sized to comfortably fit that, with slack
     * left blank for every shorter tip — the same "generous fixed box" choice {@link
     * #buildTab()}'s detail-card height-probe makes, picked by hand here instead of probed
     * since {@link #hintText()}'s candidate strings aren't exposed as an enumerable list.
     */
    private static final int HINT_H = 84;

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
     * The BUILD tab's detail card. Kept as a field (rather than a local in {@link #buildTab()},
     * like {@link MachineIcon} tiles) so {@link #refresh()} can repaint it after a pick changes
     * which machine it shows. Unlike the version of this card that existed before this class
     * dropped every {@link LayoutManager}, its bounds are fixed once at construction (see {@link
     * #buildTab()}'s height-probing loop) and never recomputed — selecting a different machine
     * only ever changes what gets painted inside the same rectangle.
     */
    private final MachineDetail detail;
    /**
     * The three BUILD/RESEARCH/MANUAL tab bodies, keyed by tab name — the whole replacement for
     * a {@link CardLayout} deck. All three are added to the side panel at the same fixed bounds
     * and given fixed size (see {@link #side()}); {@link #showTab(String)} just flips {@link
     * JComponent#setVisible} on each instead of asking a layout manager to swap cards.
     */
    private final Map<String, JComponent> tabPanels = new LinkedHashMap<>();
    /** The three tab buttons (BUILD/RESEARCH/MANUAL), kept so {@link #showTab(String)} can toggle their active state. */
    private final List<TabButton> tabs = new ArrayList<>();
    /**
     * Every {@link MachineIcon} tile in the BUILD tab's grid, in {@link Machine#BUILDABLE}
     * order, kept so {@link #refresh()} can hide/show them as research completes (see {@link
     * #updateMachineIconVisibility()}). Unlike the rest of this class's fixed-once bounds, a
     * hidden tile still occupies its original grid slot rather than being reflowed away — the
     * grid just gets gaps where locked machines sit, which is simpler and avoids repositioning
     * everything below the grid (the "Details" label and card) every time a tech completes.
     */
    private final List<MachineIcon> machineIcons = new ArrayList<>();
    /** Research rows keyed by tech, read by {@link #positionTechRows()} to look up each row's current height. */
    private final Map<Tech, ItemRow> techRows = new EnumMap<>(Tech.class);
    /**
     * The RESEARCH tab's scrollable content panel (null layout, positioned by {@link
     * #positionTechRows()}), kept as a field so {@link #refresh()} and {@link #abandon()} can
     * call that method again — a full recompute is simpler and more robust than trying to move
     * just the one row that changed (see {@link #positionTechRows()}'s Javadoc).
     */
    private JPanel researchContent;
    /** Divider between not-yet-researched and completed rows in the RESEARCH tab; hidden until the first tech completes. */
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
     * up top, the ledger below that, the board and status bar below that, and the tabbed side
     * panel to the right — every one of them placed with an explicit {@code setBounds} call
     * computed from the constants at the top of this class, not a {@link LayoutManager}. Also
     * binds keyboard shortcuts and does the first {@link #refresh()}.
     *
     * @return the assembled root component, ready to drop into a frame
     */
    public JComponent root() {
        var root = new JPanel(null) {
            @Override protected void paintComponent(Graphics g) {
                var g2 = (Graphics2D) g;
                g2.setPaint(new GradientPaint(0, 0, new Color(0x24, 0x1C, 0x10), getWidth(), getHeight(), Theme.INK));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(new Color(196, 168, 122, 12));
                for (int x = 0; x < getWidth(); x += 40) g2.drawLine(x, 0, x, getHeight());
                for (int y = 0; y < getHeight(); y += 40) g2.drawLine(0, y, getWidth(), y);
            }
        };
        root.setPreferredSize(new Dimension(WIN_W, WIN_H));

        // SAVE/COLLAPSE/ABANDON are measured and placed right-to-left off the header row's
        // right edge first — a one-time getPreferredSize() probe, not a live layout dependency,
        // since none of the three chips' text (and so their width) ever changes after
        // construction — so that the masthead below can be given a width that stops safely
        // short of them. COLLAPSE stays visible before victory rather than appearing/
        // disappearing with it — simpler than reflowing SAVE/ABANDON's positions around a chip
        // whose presence changes, and clicking it early just explains what's missing instead of
        // doing nothing.
        var save = new Ui.Chip("SAVE", () -> {
            Save.write(engine.board);
            status.set(List.of(Ui.Seg.of("Site saved.", Theme.GOOD)));
        });
        var collapseChip = new Ui.Chip("COLLAPSE", this::collapse);
        var abandonChip = new Ui.Chip("ABANDON SITE", this::abandon);
        Dimension saveSize = save.getPreferredSize(), collapseSize = collapseChip.getPreferredSize(),
                abandonSize = abandonChip.getPreferredSize();
        int buttonY = MARGIN_T + (HEADER_H - abandonSize.height) / 2;
        int rightEdge = MARGIN_L + LEFT_W;
        abandonChip.setBounds(rightEdge - abandonSize.width, buttonY, abandonSize.width, abandonSize.height);
        collapseChip.setBounds(abandonChip.getX() - 6 - collapseSize.width, buttonY, collapseSize.width, collapseSize.height);
        save.setBounds(collapseChip.getX() - 6 - saveSize.width, buttonY, saveSize.width, saveSize.height);

        // Masthead's own text can grow (the "· FUSION ONLINE" / "· MONOLITH" badges are
        // appended live), and Ui.Chip paints no background of its own — see its Javadoc — so
        // without this, long-enough masthead text would visibly bleed through behind the
        // buttons instead of being covered by them. Stopping the masthead's own bounds safely
        // short of the leftmost chip lets Swing's ordinary per-component clipping cut off
        // anything that would have overflowed, with no manual clip code needed here.
        var masthead = new Masthead();
        masthead.setBounds(MARGIN_L, MARGIN_T, save.getX() - MARGIN_L - 10, HEADER_H);
        root.add(masthead);
        root.add(save);
        root.add(collapseChip);
        root.add(abandonChip);

        int ledgerY = MARGIN_T + HEADER_H + GAP;
        ledger.setBounds(MARGIN_L, ledgerY, LEDGER_W, LEDGER_H);
        root.add(ledger);

        int boardY = ledgerY + LEDGER_H + GAP;
        boardPanel.setBounds(MARGIN_L, boardY, LEFT_W, BOARD_H);
        root.add(boardPanel);

        status.setBounds(MARGIN_L, boardY + BOARD_H + GAP, LEFT_W, STATUS_H);
        root.add(status);

        var sidePanel = side();
        sidePanel.setBounds(WIN_W - MARGIN_R - SIDE_W, MARGIN_T, SIDE_W, SIDE_H);
        root.add(sidePanel);

        bindKeys(root);
        refresh();
        status.set(List.of(
                Ui.Seg.of("Click the core", Theme.CHALK),
                Ui.Seg.of(" to condense matter, or press space. Hover anything for readings.", Theme.DIM)));
        return root;
    }

    /**
     * Builds the right-hand tabbed panel: three {@link TabButton}s side by side, and the three
     * tab bodies stacked on top of one another underneath at identical fixed bounds — the whole
     * replacement for a {@link CardLayout} deck, with {@link #showTab(String)} toggling {@link
     * JComponent#setVisible} instead of asking a layout manager to swap cards. Starts on BUILD.
     *
     * @return the side panel component
     */
    private JComponent side() {
        var panel = new JPanel(null) {
            @Override protected void paintComponent(Graphics g) {
                var g2 = (Graphics2D) g;
                g2.setPaint(new GradientPaint(0, 0, Theme.PANEL, 0, getHeight(), new Color(0x1E, 0x17, 0x0E)));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(Theme.LINE2);
                g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            }
        };

        List<String> names = List.of("BUILD", "RESEARCH", "MANUAL");
        int tabW = SIDE_W / names.size();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            var tab = new TabButton(name, () -> showTab(name));
            tabs.add(tab);
            // The last tab absorbs the rounding remainder so the row's total width is exactly
            // SIDE_W instead of leaving a sliver of unpainted panel past the third tab.
            int w = (i == names.size() - 1) ? SIDE_W - tabW * i : tabW;
            tab.setBounds(tabW * i, 0, w, TAB_ROW_H);
            panel.add(tab);
        }
        tabs.get(0).active = true;

        int contentY = TAB_ROW_H + TAB_GAP;
        int contentH = SIDE_H - contentY;
        tabPanels.put("BUILD", buildTab());
        tabPanels.put("RESEARCH", techTab());
        var manualPanel = Ui.scroll(manual);
        manualPanel.setOpaque(false);
        tabPanels.put("MANUAL", manualPanel);
        for (JComponent p : tabPanels.values()) {
            p.setBounds(0, contentY, SIDE_W, contentH);
            panel.add(p);
        }
        tabPanels.get("RESEARCH").setVisible(false);
        tabPanels.get("MANUAL").setVisible(false);

        return panel;
    }

    /**
     * Builds the BUILD tab: the hint box, a small row of {@link ToolIcon} tiles (Dismantle,
     * Power Switch), a grid of one {@link MachineIcon} per buildable {@link Machine}, and a
     * single {@link MachineDetail} card beneath it showing full detail for whichever machine is
     * currently {@link #selected} — every one of them placed with an explicit {@code setBounds}
     * call as a running {@code y} cursor walks down the tab, rather than a {@link BoxLayout}
     * stacking them off their preferred sizes. This tab doesn't scroll: unlike RESEARCH's ~26
     * rows, its fixed ~22 items (2 tools + 20 machines + 1 detail card) are known in advance to
     * fit inside {@code SIDE_H}, so there's no scrollable-extent bookkeeping to do at all.
     *
     * <p>A tile whose machine isn't researched yet is hidden entirely rather than shown dimmed
     * (see {@link #updateMachineIconVisibility()}, called from {@link #refresh()}); a tile whose
     * machine is unlocked but currently unaffordable stays visible but paints itself greyed —
     * desaturated and darkened, not just faded — since {@link MachineIcon#paintComponent} reads
     * {@code engine} live on every repaint.
     *
     * @return the BUILD tab body
     */
    private JComponent buildTab() {
        var panel = new JPanel(null);
        panel.setOpaque(false);
        int y = TAB_PAD;

        hint.setBounds(TAB_PAD, y, TAB_CONTENT_W, HINT_H);
        panel.add(hint);
        y += HINT_H + 6;

        var toolsLabel = new SectionLabel("Tools");
        toolsLabel.setBounds(TAB_PAD, y, TAB_CONTENT_W, 20);
        panel.add(toolsLabel);
        y += 20;

        // Dismantle and Power Switch used to be full-width ItemRows with their own title/cost/
        // blurb text; that's a lot of vertical space for two toggles with no per-machine detail
        // to show. As ToolIcon tiles their description moves to a hover tooltip instead — see
        // ToolIcon's Javadoc for why that trade (discoverable on hover, invisible otherwise) was
        // fine here specifically.
        int toolSize = 74, toolGap = 6;
        var demolishIcon = new ToolIcon("DISM", Game::paintDismantleGlyph, () -> demolishing, () -> {
            demolishing = !demolishing;
            if (demolishing) selected = null;
            toggling = false;
            boardPanel.setToggling(false);
            boardPanel.setDemolishing(demolishing);
            boardPanel.setGhost(null);
            refresh();
        });
        demolishIcon.setToolTipText("Dismantle: click a machine to remove the whole block, "
                + "shift-click for a single cell. Half cost back either way.");
        demolishIcon.setBounds(TAB_PAD, y, toolSize, toolSize);
        panel.add(demolishIcon);

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
        powerIcon.setBounds(TAB_PAD + toolSize + toolGap, y, toolSize, toolSize);
        panel.add(powerIcon);
        y += toolSize + 10;

        var machinesLabel = new SectionLabel("Machines");
        machinesLabel.setBounds(TAB_PAD, y, TAB_CONTENT_W, 20);
        panel.add(machinesLabel);
        y += 20;

        int cols = 4, iconSize = 74, iconGap = 6;
        for (int i = 0; i < Machine.BUILDABLE.size(); i++) {
            var icon = new MachineIcon(Machine.BUILDABLE.get(i));
            int col = i % cols, row = i / cols;
            icon.setBounds(TAB_PAD + col * (iconSize + iconGap), y + row * (iconSize + iconGap), iconSize, iconSize);
            panel.add(icon);
            machineIcons.add(icon);
        }
        updateMachineIconVisibility();
        int rows = (Machine.BUILDABLE.size() + cols - 1) / cols;
        y += rows * (iconSize + iconGap) - iconGap + 10;

        var detailsLabel = new SectionLabel("Details");
        detailsLabel.setBounds(TAB_PAD, y, TAB_CONTENT_W, 20);
        panel.add(detailsLabel);
        y += 20;

        // detail's fixed height is the tallest any buildable machine's card would need, probed
        // once here rather than recomputed on every pick — see the field Javadoc for why this
        // replaces the dynamic revalidate()-on-selection-change the card used to need.
        Machine savedSelection = selected;
        int detailH = detail.getPreferredSize().height;
        for (Machine m : Machine.BUILDABLE) {
            selected = m;
            detailH = Math.max(detailH, detail.getPreferredSize().height);
        }
        selected = savedSelection;
        detail.setBounds(TAB_PAD, y, TAB_CONTENT_W, detailH);
        panel.add(detail);

        return panel;
    }

    /**
     * Arms {@code m} for placement: sets {@link #selected}, clears the other two board tools,
     * and hands {@code m} to the {@link BoardPanel} as the placement ghost. Only ever called
     * with an unlocked machine — its {@link MachineIcon} is hidden while locked (see {@link
     * #updateMachineIconVisibility()}), so there is no click path to reach this with a machine
     * still gated on research. An unlocked-but-unaffordable machine still arms fine; {@link
     * BoardPanel}'s ghost preview is what flags it can't actually be placed yet. Shared by every
     * {@link MachineIcon}'s click handler.
     *
     * @param m the machine icon that was clicked
     */
    private void pickMachine(Machine m) {
        selected = m;
        demolishing = false;
        toggling = false;
        boardPanel.setDemolishing(false);
        boardPanel.setToggling(false);
        boardPanel.setGhost(m);
        refresh();
    }

    /**
     * Hides every {@link MachineIcon} whose machine isn't researched yet, and shows every one
     * that is — called once from {@link #buildTab()} at construction and again from every
     * {@link #refresh()} so a tile appears the moment its tech completes (and disappears again
     * after {@link #abandon()} wipes research back to nothing). A hidden tile keeps its original
     * grid slot rather than being reflowed away; see {@link #machineIcons}'s Javadoc for why.
     */
    private void updateMachineIconVisibility() {
        for (MachineIcon icon : machineIcons) icon.setVisible(engine.unlocked(icon.machine));
    }

    /**
     * Builds the RESEARCH tab: a "Research" heading, one {@link ItemRow} per {@link Tech} (in a
     * null-layout content panel positioned by {@link #positionTechRows()}), and the {@link
     * #finishedLabel} divider — all wrapped in a fixed-size {@link Ui#scroll}, since ~26 techs
     * are known in advance not to fit statically the way BUILD's ~22 items do.
     *
     * <p>Same functional-callback-style wiring as {@link #buildTab()}: each row gets a fresh
     * anonymous {@link ItemRow.Model} closing over the loop variable {@code t} and {@code
     * engine}. Row positions themselves are computed by {@link #positionTechRows()}, not here —
     * this method only builds the rows and hands them off.
     *
     * @return the scrollable RESEARCH tab body
     */
    private JComponent techTab() {
        researchContent = new JPanel(null);
        researchContent.setOpaque(false);

        var researchLabel = new SectionLabel("Research");
        researchLabel.setBounds(TAB_PAD, 0, TAB_CONTENT_W, 20);
        researchContent.add(researchLabel);

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
            researchContent.add(row);
        }
        researchContent.add(finishedLabel);
        positionTechRows();

        return Ui.scroll(researchContent);
    }

    /**
     * Positions every research row and the {@link #finishedLabel} divider from scratch: every
     * not-yet-researched tech first, in declaration order, then — if anything is done yet — the
     * divider, then every researched tech, also in declaration order. Each row's height comes
     * from its own {@link ItemRow#getPreferredSize()}; the running {@code y} cursor is a plain
     * loop variable, not a {@link LayoutManager}.
     *
     * <p>Called once from {@link #techTab()}, then again from {@link #refresh()} (liberally —
     * see there for why that's cheap enough) and {@link #abandon()} whenever a tech's done state
     * might have changed. Recomputing every row's position from scratch, rather than moving just
     * the one row that changed the way an earlier {@link BoxLayout}-based version did, is both
     * simpler and immune to that version's ordering bug after {@link #abandon()} wiped every tech
     * back to unresearched at once.
     *
     * <p>Finishes by setting {@link #researchContent}'s preferred size to the true content
     * height and revalidating it — the one piece of bookkeeping a scrollable, statically
     * positioned panel still needs, so the scrollbar knows how far there is to pan. That's
     * scoped to this one panel's scroll extent, not a {@link LayoutManager} repositioning
     * siblings, which is the dynamism this class avoids.
     */
    private void positionTechRows() {
        int y = 24;
        for (Tech t : Tech.values()) {
            if (engine.board.has(t)) continue;
            var row = techRows.get(t);
            int h = row.getPreferredSize().height;
            row.setBounds(TAB_PAD, y, TAB_CONTENT_W, h);
            y += h + 5;
        }
        boolean anyDone = false;
        for (Tech t : Tech.values()) if (engine.board.has(t)) { anyDone = true; break; }
        finishedLabel.setVisible(anyDone);
        if (anyDone) {
            finishedLabel.setBounds(TAB_PAD, y, TAB_CONTENT_W, 20);
            y += 20 + 4;
            for (Tech t : Tech.values()) {
                if (!engine.board.has(t)) continue;
                var row = techRows.get(t);
                int h = row.getPreferredSize().height;
                row.setBounds(TAB_PAD, y, TAB_CONTENT_W, h);
                y += h + 5;
            }
        }
        // SIDE_W minus a little, not SIDE_W itself: Ui.scroll's vertical scrollbar reserves 9px
        // of the panel's width, and a preferred width that claims the full SIDE_W (wider than
        // the viewport that leaves for it) makes JScrollPane decide it ALSO needs a horizontal
        // scrollbar to pan across that extra sliver — a second, unwanted scrollbar for content
        // that was never actually meant to scroll sideways.
        researchContent.setPreferredSize(new Dimension(SIDE_W - 10, y + 10));
        researchContent.revalidate();
    }

    /**
     * Switches which tab body is visible and updates each {@link TabButton}'s active
     * (highlighted) state to match — the entire replacement for asking a {@link CardLayout} to
     * swap cards, now that {@link #tabPanels} just holds all three at once.
     *
     * @param name one of "BUILD", "RESEARCH", "MANUAL" — must match a key put in {@link #side()}
     */
    private void showTab(String name) {
        for (var e : tabPanels.entrySet()) e.getValue().setVisible(e.getKey().equals(name));
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
     * Re-syncs all UI surfaces with current engine state: updates the hint text, repositions the
     * RESEARCH tab's rows in case a tech's done state just changed, shows/hides BUILD tab
     * machine icons per {@link #updateMachineIconVisibility()}, and repaints the ledger, the
     * three tab bodies, and the manual. Called after every player action and on a timer from
     * {@link #start()}.
     *
     * <p>Nothing here calls {@code revalidate()} on anything other than {@link
     * #researchContent} (inside {@link #positionTechRows()}): every other component's bounds
     * were fixed once at construction and never need to change again, so a plain {@code
     * repaint()} is enough to bring it up to date with current engine state — see the class
     * Javadoc. {@link #positionTechRows()} itself is called unconditionally rather than only
     * when a tech's done-state actually flipped: it's cheap (26 {@link ItemRow#getPreferredSize}
     * calls, pure text measurement, no painting) and unconditional is simpler than tracking
     * "did anything change" separately.
     */
    public void refresh() {
        hint.text = hintText();
        positionTechRows();
        updateMachineIconVisibility();
        ledger.repaint();
        for (JComponent p : tabPanels.values()) p.repaint();
        manual.repaint();
    }

    /**
     * Confirms and triggers {@link Engine#collapse()}. Before {@link Board#won}, this just
     * explains what's missing instead of doing nothing silently — see the COLLAPSE chip's
     * placement Javadoc in {@link #root()} for why the chip itself doesn't just hide until then.
     */
    private void collapse() {
        if (!engine.board.won) {
            status.set(List.of(Ui.Seg.of("Collapse is a victory reward", Theme.DIM),
                    Ui.Seg.of(" - build a Fusion Reactor first.", Theme.DIM)));
            return;
        }
        int answer = JOptionPane.showConfirmDialog(boardPanel,
                "Collapse the site? Every machine on the board is consumed and refused into one "
                        + "Monolith across the northern half of your claim, powered the instant it "
                        + "appears. Resources, research, and the claim itself are untouched, and the "
                        + "southern half stays free to build on. This cannot be undone.",
                "Collapse site", JOptionPane.OK_CANCEL_OPTION);
        if (answer != JOptionPane.OK_OPTION) return;
        if (engine.collapse()) {
            status.set(List.of(Ui.Seg.of("The site collapses into a single Monolith.", Theme.AMBER)));
            refresh();
        }
    }

    /**
     * Resets the session to a brand-new site after confirmation, wiping the save file.
     *
     * <p>This does not construct a new {@link Game}/{@link Engine} and swap it into the UI.
     * Instead it builds a throwaway {@link Engine#fresh()}, copies its board arrays ({@code
     * cell}, {@code ore}, {@code rich}, {@code off}) byte-for-byte onto the <em>live</em>
     * board's arrays via {@link System#arraycopy}, and then manually resets every other mutable
     * board field one by one (resources, seen-set, built/tech sets, claim size, energy, click
     * count, victory/collapse flags, log). This is a manual "reset in place": {@code engine},
     * {@code boardPanel}, {@code ledger} and every row's closures are all bound to the original
     * {@link Engine} instance, so replacing that instance would mean re-wiring every listener
     * and closure built during {@link #root()}. Mutating the existing engine's board arrays in
     * place avoids that entirely. Resetting {@link Board#won} and {@link Board#collapsed} means
     * the masthead's badges (see {@link Masthead}) correctly disappear on a fresh site rather
     * than carrying over from the abandoned one.
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
        engine.board.collapsed = false;
        engine.board.log.clear();
        selected = null;
        demolishing = false;
        toggling = false;
        boardPanel.setGhost(null);
        boardPanel.setDemolishing(false);
        boardPanel.setToggling(false);
        engine.markDirty();
        engine.recompute();
        // Every tech is unresearched again now that board.tech is cleared; positionTechRows()
        // (called again inside refresh() below) puts every row back above the divider in
        // declaration order on its own, since it always recomputes from scratch rather than
        // moving whatever changed — see its Javadoc.
        refresh();
    }

    /**
     * Binds window-wide keyboard shortcuts (SPACE = tap core, ESCAPE = clear everything, D =
     * toggle dismantle, P = toggle power switch, Q = deselect the armed machine, SHIFT down/up =
     * keep the board's single-cell dismantle preview in step with the modifier) onto {@code
     * root}'s input/action maps, active whenever the containing window has focus, regardless of
     * which child component has it. Also installs a window-wide right-click listener (see
     * {@link #clearSelection()}) that clears everything exactly like ESCAPE does, from anywhere
     * in the window — a global {@link java.awt.event.AWTEventListener} rather than a listener on
     * {@code root} itself, since AWT delivers mouse events to the deepest component under the
     * cursor, not up through ancestors, so a listener on {@code root} alone would miss clicks on
     * any child component.
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
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { clearSelection(); }
        });
        Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
            if (e instanceof java.awt.event.MouseEvent me
                    && me.getID() == java.awt.event.MouseEvent.MOUSE_PRESSED
                    && SwingUtilities.isRightMouseButton(me)) {
                clearSelection();
            }
        }, AWTEvent.MOUSE_EVENT_MASK);
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
        // SHIFT isn't a command of its own — it modifies the dismantle click into a single-cell
        // one. The board already reads the modifier off each mouse event, so these two bindings
        // exist purely so the preview switches the moment SHIFT goes down or up, even if the
        // pointer never moves. See BoardPanel#shiftHeld.
        im.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_SHIFT,
                java.awt.event.InputEvent.SHIFT_DOWN_MASK, false), "shiftDown");
        am.put("shiftDown", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { boardPanel.setShiftHeld(true); }
        });
        im.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_SHIFT, 0, true), "shiftUp");
        am.put("shiftUp", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { boardPanel.setShiftHeld(false); }
        });
    }

    /**
     * Deselects the armed machine and exits dismantle/power-switch mode, clearing the board's
     * preview overlays. Shared by the ESCAPE shortcut and the right-click-to-cancel listener
     * (see {@link #bindKeys}) so the two stay in lockstep.
     */
    private void clearSelection() {
        selected = null;
        demolishing = false;
        toggling = false;
        boardPanel.setGhost(null);
        boardPanel.setDemolishing(false);
        boardPanel.setToggling(false);
        refresh();
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
     * current mode: dismantle mode demolishes the clicked block — or, with SHIFT held, only the
     * one cell clicked — power-switch mode flips it on or off (neither applies to the core),
     * otherwise clicking the core taps it, and clicking with a machine selected attempts
     * placement.
     *
     * <p>Placement is the one path that can flip {@link Board#won}, so it's the one place that
     * diffs {@code engine.board.won} across the call and fires {@link #celebrateVictory()} on
     * the false-to-true edge — see {@link Engine#place} for why the flag lives there instead of
     * being reported through {@code place}'s own return value.
     *
     * @param x     cell column
     * @param y     cell row
     * @param group the fused block occupying the cell, or {@code null} if empty
     * @param shift whether SHIFT was held, narrowing dismantle to the single clicked cell
     */
    @Override public void pressed(int x, int y, Group group, boolean shift) {
        if (demolishing) {
            if (group != null && group.type != Machine.CORE) {
                if (shift) engine.demolishCell(x, y); else engine.demolish(group);
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
        /**
         * Draws "SUBSTRATE" glyph-by-glyph with a manual 7px advance per character (the first
         * three letters in chalk, the rest in amber), then the subtitle starting where the title
         * left off, then — once {@link Board#won} and/or {@link Board#collapsed} — one or two
         * small amber badges after it, then the underline rule. A non-static inner class (unlike
         * most of this file's other bespoke widgets) purely so this one line can read {@code
         * engine.board}'s state live; the badges are these achievements' only permanent trace in
         * the UI once their one-time confirmation dialogs have been dismissed, so they have to
         * survive a save/reload, not just the moment they happened.
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
            float badgeX = x + 10 + g.getFontMetrics().stringWidth(subtitle) + 10;
            if (engine.board.won) {
                g.setColor(Theme.AMBER);
                String badge = "· FUSION ONLINE";
                g.drawString(badge, badgeX, 22);
                badgeX += g.getFontMetrics().stringWidth(badge) + 10;
            }
            if (engine.board.collapsed) {
                g.setColor(Theme.AMBER);
                g.drawString("· MONOLITH", badgeX, 22);
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
     * {@link ToolIcon} glyph for Dismantle: a targeting reticle — a ring with an "X" through it
     * plus four outward tick marks — in a fixed danger red regardless of whether the tool is
     * currently armed (the tile's wash/border already carries that state, the same separation
     * {@link MachineIcon} keeps between its {@link Art} preview and its own selection styling).
     *
     * <p>Same pictogram, at the same proportions, as {@code Cursors#paintDemolish}, so the tile
     * and the cursor it arms are the same drawing — see {@link Cursors}' class doc.
     */
    private static void paintDismantleGlyph(Graphics2D g, Rectangle2D r) {
        double cx = r.getCenterX(), cy = r.getCenterY();
        double rad = Math.min(r.getWidth(), r.getHeight()) * 0.5 * 0.72;
        double in = rad * 0.55, tickIn = rad * 1.05, tickOut = rad * 1.3;
        g.setColor(Theme.alpha(Theme.HOT, 220));
        g.setStroke(new BasicStroke((float) (rad * 0.16), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Ellipse2D.Double(cx - rad, cy - rad, rad * 2, rad * 2));
        g.draw(new Line2D.Double(cx - in, cy - in, cx + in, cy + in));
        g.draw(new Line2D.Double(cx + in, cy - in, cx - in, cy + in));
        g.draw(new Line2D.Double(cx, cy - tickOut, cx, cy - tickIn));
        g.draw(new Line2D.Double(cx, cy + tickIn, cx, cy + tickOut));
        g.draw(new Line2D.Double(cx - tickOut, cy, cx - tickIn, cy));
        g.draw(new Line2D.Double(cx + tickIn, cy, cx + tickOut, cy));
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
        g.draw(new Arc2D.Double(cx - rad, cy - rad, rad * 2, rad * 2, 145, 250, Arc2D.OPEN));
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
         * {@link Art} icon — greyed out per {@link #paintUnaffordable} when the price can't be
         * paid right now — the owned count in the top-right corner, and the abbreviation caption
         * along the bottom edge. Never called for a locked machine: its tile is hidden instead
         * (see {@link #updateMachineIconVisibility()}).
         */
        @Override protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            boolean afford = engine.affordable(engine.priceOf(machine));
            boolean isSelected = selected == machine;
            int w = getWidth(), h = getHeight();

            g.setColor(isSelected ? Theme.alpha(Theme.AMBER, 30) : new Color(52, 42, 28, hover ? 130 : 70));
            g.fillRect(0, 0, w, h);

            double pad = 6, captionH = 13;
            var iconRect = new Rectangle2D.Double(pad, pad, w - pad * 2, h - pad * 2 - captionH);
            if (afford) Art.paint(g, preview, iconRect, 0, false, 0.5);
            else paintUnaffordable(g, preview, iconRect);

            int owned = engine.board.count(machine);
            if (owned > 0) {
                g.setFont(Theme.mono(9));
                g.setColor(Theme.AMBER);
                String s = "x" + owned;
                g.drawString(s, w - g.getFontMetrics().stringWidth(s) - 3, 10);
            }

            g.setFont(Theme.mono(9));
            g.setColor(Theme.DIM);
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
     * Paints {@code grp}'s catalogue icon into {@code r} the way an unaffordable machine should
     * read: dark and colorless, not just faded, so a resource-starved building is unmistakable
     * at a glance rather than reading as merely dim. {@link Art#paint} has no notion of this —
     * it only ever draws in full color — so this renders it once into an offscreen image at
     * {@code r}'s pixel size, then collapses every pixel to its luminance and scales that down,
     * leaving alpha untouched so the icon's silhouette and antialiased edges are unaffected.
     * {@code t} is pinned to 0 and {@code hover} to false: a greyed-out icon has no reason to
     * animate.
     *
     * @param g    destination graphics context
     * @param grp  the machine group to preview (see {@link #previewGroup})
     * @param r    the rectangle to paint into, in {@code g}'s coordinate space
     */
    private static void paintUnaffordable(Graphics2D g, Group grp, Rectangle2D r) {
        int w = (int) Math.ceil(r.getWidth()), h = (int) Math.ceil(r.getHeight());
        if (w <= 0 || h <= 0) return;
        var img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var ig = img.createGraphics();
        ig.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Art.paint(ig, grp, new Rectangle2D.Double(0, 0, w, h), 0, false, 0.5);
        ig.dispose();

        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int a = p >>> 24, rr = (p >> 16) & 0xFF, gg = (p >> 8) & 0xFF, bb = p & 0xFF;
            int lum = (int) Math.round((0.3 * rr + 0.59 * gg + 0.11 * bb) * 0.32);
            px[i] = (a << 24) | (lum << 16) | (lum << 8) | lum;
        }
        img.setRGB(0, 0, w, h, px, 0, w);
        g.drawImage(img, (int) Math.round(r.getX()), (int) Math.round(r.getY()), null);
    }

    /**
     * The BUILD tab's detail card: a large preview icon beside the full title, cost, and
     * description of whichever machine is currently {@link #selected} — replacing the old
     * single {@link ItemRow} used here, which was sized like every other compact list row and
     * read as cramped now that it's the one and only detail slot rather than one of twenty.
     *
     * <p>Reads {@code selected} straight off the enclosing {@link Game} instance on every
     * repaint, the same functional-callback-style wiring as {@link MachineIcon} — there's no
     * explicit refresh path, {@code Game.refresh()}'s per-tab {@code repaint()} is enough.
     *
     * <p>{@link #getPreferredSize()} is only ever called by {@link #buildTab()}'s height-probing
     * loop, which takes the tallest result across every buildable machine and freezes that as
     * this card's one fixed {@code setBounds} height — selecting a different machine afterward
     * only ever repaints, it never resizes. That replaces an earlier version where this card's
     * actual on-screen size tracked {@code selected} live via {@code revalidate()}, which is
     * exactly the kind of dynamic, content-driven sizing this whole class now avoids.
     */
    private final class MachineDetail extends JComponent {
        private static final Font TITLE = Theme.monoBold(15);
        private static final Font BODY  = Theme.mono(11);
        private static final int ICON = 88, PAD = 12, GAP = 14;

        MachineDetail() { setOpaque(false); }

        /** Width left for text once the icon column and padding are subtracted from the fixed {@link Game#TAB_CONTENT_W}. */
        private int textWidth() { return Math.max(80, TAB_CONTENT_W - PAD * 2 - ICON - GAP); }

        /**
         * Height is the icon's height when nothing is selected (so the probe in {@link
         * #buildTab()} never freezes a height shorter than the icon itself), otherwise the title
         * plus a meta line, one line per cost resource (see {@link #paintComponent} for why cost
         * isn't one joined line), and every wrapped line of {@link #describe} and the blurb —
         * each counted at {@code BODY}'s line height plus 2px, a couple pixels more generous
         * than {@link #paintComponent} actually uses per line, as headroom against clipping.
         */
        @Override public Dimension getPreferredSize() {
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
            return new Dimension(TAB_CONTENT_W, PAD * 2 + Math.max(ICON, textH));
        }

        /**
         * Draws the card frame, then either the empty-state hint or the icon — greyed out via
         * {@link #paintUnaffordable} the same way {@link MachineIcon} is when unaffordable, since
         * {@code selected} is only ever an unlocked machine (its icon is hidden while locked, so
         * there's no click path to select one — see {@link #updateMachineIconVisibility()}) —
         * plus title, meta, cost (color-coded by what's in stock, same convention {@link ItemRow}
         * uses), and the wrapped {@link #describe} and blurb text.
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
            boolean afford = engine.affordable(engine.priceOf(m));

            var iconRect = new Rectangle2D.Double(PAD, PAD, ICON, ICON);
            if (afford) Art.paint(g, previewGroup(m), iconRect, 0, false, 0.5);
            else paintUnaffordable(g, previewGroup(m), iconRect);

            int tx = PAD + ICON + GAP;
            int tw = textWidth();
            g.setFont(TITLE);
            var fmT = g.getFontMetrics();
            int y = PAD + fmT.getAscent();
            g.setColor(Theme.CHALK);
            g.drawString(spec.label(), tx, y);

            g.setFont(BODY);
            y += 6 + fm.getHeight();
            String meta = spec.abbr() + "  built x" + engine.board.count(m);
            g.setColor(Theme.DIM);
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
     * 2px gap between letters. Sized entirely by whatever {@code setBounds} rect {@link
     * #side()} gives it; painting reads that back via {@link #getWidth()}/{@link #getHeight()}
     * rather than declaring its own preferred size.
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
     * (2.5px) than default font kerning provides at this small size. Every caller gives this a
     * fixed 20px-tall {@code setBounds} rect; {@link #paintComponent} just reads back whatever
     * width that rect turned out to be, via {@link #getWidth()}, for the underline rule.
     */
    private static final class SectionLabel extends JComponent {
        private final String text;

        SectionLabel(String text) { this.text = text; }

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
         * Paints the amber background, left accent bar, and the hint text wrapped to this
         * component's own (fixed) width. {@link Game#buildTab()} gives this a single fixed
         * {@code setBounds} rect sized to {@code Game.HINT_H} — generous enough for the longest
         * of {@code Game.hintText()}'s tips — so, unlike the version of this box that predates
         * this class dropping every {@link LayoutManager}, wrapping here never feeds back into
         * a resize: it only ever affects how many of the box's already-fixed rows get used.
         */
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
            for (String line : Ui.wrap(text, fm, getWidth() - 16)) {
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
                new String[]{"Collapse",
                    "Once you've won, COLLAPSE consumes every machine on the board and refuses them into a single Monolith across the northern half of your claim - the same fusion rule as any other block, applied to the whole site at once. Resources, research and the claim survive; only what stands on the ground changes, and the southern half stays free to build on. It's repeatable: extend the claim, rebuild, collapse again for a bigger Monolith."},
                new String[]{"Controls",
                    "Pick a machine, then click or drag across empty cells; clicking the same one again keeps it armed, it does not deselect. Space taps the core. D toggles dismantle, which returns half; a plain click scraps the whole fused block, shift-click takes out just the one cell you clicked, so you can trim a block back into shape. P toggles the power switch, which pauses a block without demolishing it. Q drops whatever machine is armed. Escape clears everything at once. The site saves itself every twenty seconds."});
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
         * Fixed width to wrap section text at, matching {@code Game.TAB_CONTENT_W} (this
         * component's own {@code setBounds} width once {@link Game#side()} wraps it in a
         * fixed-size {@link Ui#scroll}) minus a little for the scrollbar. A constant, not read
         * from a live parent: this class has no {@link LayoutManager} anywhere to feed a dynamic
         * width back into.
         */
        private int wrapWidth() { return 320; }

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
