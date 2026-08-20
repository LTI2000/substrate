package substrate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The simulation: fusion, the power network, and the flow of matter.
 *
 * <p>This is the whole game engine in one class: it owns the per-tick economy loop
 * ({@link #tick(double)}), the connectivity/power recompute triggered by board edits
 * ({@link #recompute()}), and the brute-force catch-up used to fast-forward a save
 * that was closed for a while ({@link #runUnattended(long)}).
 *
 * <p><b>The core hook — fusion scaling.</b> A fused group's output multiplier is
 * {@code area^exponent} (see {@link Group#fusionFactor(double)}), where {@code exponent}
 * starts at {@code 2.0} and creeps up in {@code +0.15} steps as {@link Tech#GEO1} and
 * {@link Tech#GEO2} are researched (see {@link #exponent()}). This non-integer,
 * research-upgradable exponent is deliberately the single mechanism the whole game hinges
 * on: bigger rectangles are always disproportionately better, and research makes that
 * disproportion sharper still.
 *
 * <p><b>Power is deliberately asymmetric with fusion.</b> A fused group's power draw scales
 * linearly with its area ({@code spec.draw() * g.area}, see {@link #tick(double)}) while its
 * output scales as {@code area^exponent}. This is not an oversight: fusing machines together
 * is intentionally more power-efficient at scale, which is what makes chasing bigger
 * rectangles worth the trouble even before the yield curve is considered.
 *
 * <p><b>Amplifiers compound their own fusion bonus into what they grant others.</b> In
 * {@link #recompute()}, the multiplier an {@link Role.Amplifier} group grants to every
 * touching group is {@code boost * node.fusionFactor(expo)} — i.e. the amplifier's own
 * fusion-scaled size feeds back into the size of the boost it hands out. A bigger amplifier
 * doesn't just have a bigger nominal boost, its self-scaling is folded straight into that
 * boost, so amplifiers are self-referentially compounding rather than flat-rate buffs.
 *
 * <p><b>UI cosmetics live inside the engine.</b> The resource flow readouts exposed via
 * {@link #flowOf(Res)} are exponentially smoothed ({@code old*0.7 + fresh*0.3} per tick, see
 * the end of {@link #tick(double)}) purely so the on-screen ledger doesn't visibly jitter
 * tick to tick. There is no simulation reason for the smoothing; it exists only to make the
 * numbers pleasant to read.
 *
 * <p><b>Manually switching a group off is a per-cell board flag, not a group flag.</b>
 * {@link #toggle(Group, boolean)} writes to {@link Board#off}, and {@link Group#enabled} —
 * folded into {@link Group#powered} by {@link Fusion#energise} — is derived fresh from it on
 * every {@link #recompute()}, because {@link Group} instances themselves don't survive a
 * recompute to be mutated. See {@link Board#off}'s Javadoc for the full reasoning.
 *
 * <p><b>Victory is a sticky flag, not a live board query.</b> {@link #place} sets {@link
 * Board#won} the moment the player's first {@link Machine#TOKAMAK} goes down and never clears
 * it, even if that reactor is later dismantled — it records that the site once reached the top
 * of the tech tree, not that a reactor currently stands.
 *
 * <p><b>Collapsing the site reuses the fusion rule instead of inventing a new one.</b> {@link
 * #collapse()} fills a rectangle with {@link Machine#MONOLITH} and lets {@link Fusion#layout}
 * fuse it exactly like it would any other same-kind rectangle — the Monolith's payoff scales
 * with {@code area^exponent} for free, no bespoke "how big a reward" formula needed.
 */
public final class Engine {

    /**
     * A snapshot of the power network for one tick: how much was generated
     * ({@code supply}), how much was wanted ({@code demand}), the fraction of demand
     * actually met ({@code satisfaction}), and the buffer's total {@code capacity} and
     * current {@code stored} charge. Exposed as an immutable record so the UI can read a
     * consistent view without racing the next {@link Engine#tick(double)}.
     */
    public record Power(double supply, double demand, double satisfaction, double capacity, double stored) {}

    /** The site being simulated; mutated directly by player actions and by {@link #tick(double)}. */
    public final Board board;
    /** The current rectangle-fusion partition of the board; stale until {@link #recompute()} runs. */
    private Fusion.Layout layout;
    /** Per-cell reachability from the core, as computed by the last {@link #recompute()}. */
    private boolean[] linked = new boolean[Board.W * Board.H];
    /** Set whenever the board changes shape; forces {@link #layout()}/{@link #linked()} to recompute lazily. */
    private boolean dirty = true;

    /** The most recent power-network snapshot; replaced wholesale each {@link #tick(double)}. */
    private Power power = new Power(0, 0, 1, 0, 0);
    /** Raw net resource change this tick, in units/second, keyed by resource; rebuilt every tick. */
    private final EnumMap<Res, Double> flow = new EnumMap<>(Res.class);
    /** Exponentially-smoothed version of {@link #flow}, purely so the displayed rate doesn't jitter. */
    private final EnumMap<Res, Double> smoothed = new EnumMap<>(Res.class);

    /** Wraps an existing board and computes its initial layout/power state. */
    public Engine(Board board) {
        this.board = board;
        recompute();
    }

    /** A brand-new game: an empty board with a freshly rolled ore survey. */
    public static Engine fresh() {
        var b = new Board();
        OreGen.survey(b, new Random());
        return new Engine(b);
    }

    /* ---------------- modifiers ---------------- */

    /**
     * The fusion output exponent used by {@link Group#fusionFactor(double)}: {@code 2.0}
     * base, plus {@code +0.15} for each of {@link Tech#GEO1} and {@link Tech#GEO2}. This is
     * the single number the whole idle-game curve is built on — raising it even slightly
     * makes every existing fused block retroactively more powerful, which is why the
     * research tree treats it as a top-tier prize rather than a routine multiplier.
     */
    public double exponent() {
        return 2 + (board.has(Tech.GEO1) ? 0.15 : 0) + (board.has(Tech.GEO2) ? 0.15 : 0);
    }

    /** Matter gained per manual core tap, stacking the TOOLS tiers multiplicatively. */
    public double clickYield() {
        double v = 1;
        if (board.has(Tech.TOOLS0)) v *= 5;
        if (board.has(Tech.TOOLS1)) v *= 5;
        if (board.has(Tech.TOOLS2)) v *= 8;
        if (board.has(Tech.TOOLS3)) v *= 20;
        return v;
    }

    /** Multiplier applied to raw mining rate, stacking the DRILLS tiers multiplicatively. */
    public double mineMultiplier() {
        double v = 1;
        if (board.has(Tech.DRILLS1)) v *= 1.6;
        if (board.has(Tech.DRILLS2)) v *= 2.5;
        return v;
    }

    /** Output multiplier for a specific machine kind from smelter/lab research tiers. */
    public double outputMultiplier(Machine m) {
        double v = 1;
        if (m.spec().smelter() && board.has(Tech.SMELT1)) v *= 2;
        if (m.spec().lab() && board.has(Tech.LABS1)) v *= 3;
        return v;
    }

    /**
     * Current build cost for one more of {@code m}: base cost scaled by
     * {@code 1.14^(units already built)}. Deliberately recomputed fresh on every call
     * rather than cached — each unit built inflates the price of the next by 14%, so the
     * cost is a pure function of {@link Board#count(Machine)} and there is nothing to
     * invalidate.
     *
     * <p>A {@link LinkedHashMap}, not an {@link EnumMap}, so the scaled price still iterates in
     * the order the machine's cost was written in {@link Machine} — see {@link Cost} for why that
     * order is worth keeping. An {@code EnumMap} silently re-sorted every build price into {@link
     * Res} declaration order, which put the same price on screen in a different order here than
     * in the research tree.
     */
    public Map<Res, Double> priceOf(Machine m) {
        double f = Math.pow(1.14, board.count(m));
        var out = new LinkedHashMap<Res, Double>();
        m.spec().cost().forEach((r, v) -> out.put(r, v * f));
        return out;
    }

    /** Whether the board currently holds enough of every resource in {@code cost} (with epsilon slack). */
    public boolean affordable(Map<Res, Double> cost) {
        return cost.entrySet().stream().allMatch(e -> board.get(e.getKey()) >= e.getValue() - 1e-9);
    }

    /** Deducts {@code cost} from the board's stockpiles. Caller must have checked {@link #affordable}. */
    private void spend(Map<Res, Double> cost) {
        cost.forEach((r, v) -> board.set(r, board.get(r) - v));
    }

    /* ---------------- structure ---------------- */

    /** Flags the layout/power/amplifier state as stale after a board edit (place, demolish, research). */
    public void markDirty()          { dirty = true; }
    /** The current fusion partition, recomputing first if the board has changed since the last read. */
    public Fusion.Layout layout()    { if (dirty) recompute(); return layout; }
    /** Per-cell reachability from the core, recomputing first if the board has changed since the last read. */
    public boolean[] linked()        { if (dirty) recompute(); return linked; }
    /** The most recent power-network snapshot, as of the last {@link #tick(double)}. */
    public Power power()             { return power; }
    /** Smoothed net flow for one resource, in units/second, for display. */
    public double flowOf(Res r)      { return smoothed.getOrDefault(r, 0.0); }

    /**
     * Rebuilds the fusion layout, the core-reachability flood fill, and every group's
     * amplifier multiplier. Called lazily by {@link #layout()}/{@link #linked()} and
     * eagerly at the top of {@link #tick(double)} whenever {@link #dirty} is set.
     *
     * <p>The amplifier pass here is a second, independent graph walk from the
     * connectivity flood fill in {@link Fusion#energise}: for every powered group that
     * isn't itself an {@link Machine#AMP} or {@link Machine#CORE}, it inspects all four
     * orthogonal neighbours of every one of its cells looking for touching powered AMP
     * groups, an O(cells * 4) scan repeated on every recompute. Touching AMP groups are
     * collected into a {@link java.util.LinkedHashSet} keyed by {@link Group} identity so
     * that a multi-cell fused amplifier block — which can be adjacent to the same group's
     * cells from several directions — contributes its boost exactly once rather than once
     * per adjacent cell.
     */
    public void recompute() {
        layout = Fusion.layout(board);
        linked = Fusion.energise(board, layout);
        double expo = exponent();
        var nodes = layout.groups().stream()
                .filter(g -> g.type == Machine.AMP && g.powered)
                .toList();
        for (Group g : layout.groups()) {
            g.mult = 1;
            if (nodes.isEmpty() || g.type == Machine.AMP || g.type == Machine.CORE) continue;
            var touching = new java.util.LinkedHashSet<Group>();
            for (int i : g.cells) {
                int x = Board.xOf(i), y = Board.yOf(i);
                if (x > 0)           consider(touching, x - 1, y);
                if (x < Board.W - 1) consider(touching, x + 1, y);
                if (y > 0)           consider(touching, x, y - 1);
                if (y < Board.H - 1) consider(touching, x, y + 1);
            }
            for (Group node : touching) {
                double boost = ((Role.Amplifier) node.type.spec().role()).boost();
                g.mult += boost * node.fusionFactor(expo);
            }
        }
        dirty = false;
    }

    /** Adds the group at {@code (x, y)} to {@code out} if it is a powered AMP group. Helper for {@link #recompute()}. */
    private void consider(java.util.Set<Group> out, int x, int y) {
        Group n = layout.at(x, y);
        if (n != null && n.type == Machine.AMP && n.powered) out.add(n);
    }

    /* ---------------- the tick ---------------- */

    /**
     * A fuel-burning generator group as seen during one {@link #tick(double)}: its fusion
     * scale and the maximum power it could contribute this tick if fuel were unlimited
     * ({@code cap}). Collected into a list purely so demand can be totalled across every
     * burner before deciding how much each one actually gets to run (see {@link #tick}) —
     * a local, single-loop scratch structure, not a general-purpose model type.
     */
    private record Fuelled(Group group, Role.Generator gen, double scale, double cap) {}
    /** A resource-producing group (mine, converter, producer or auto-tap) queued to run after power settles for the tick. */
    private record Worker(Group group, Role role, double scale) {}

    /**
     * Advances the simulation by {@code dt} seconds: recomputes if stale, tallies power
     * demand and supply (including proportional fuel rationing across generators, see
     * below), settles the energy buffer, then runs every worker at the resulting
     * satisfaction rate and folds the results into the smoothed flow readout.
     *
     * <p><b>Fuel rationing is ad hoc, not a general solver.</b> When total fuel demand
     * exceeds what generators can supply, each burner's share of the deficit is
     * apportioned in proportion to its own capacity ({@code made = take * (cap / capSum)}),
     * and the fuel it actually consumes is back-derived from the ratio between what it
     * made and what it could nominally make. This is a simple proportional split good
     * enough for the handful of generator kinds in play — it is not a market-clearing or
     * priority-based allocator, and does not need to be.
     */
    public void tick(double dt) {
        if (dirty) recompute();
        flow.clear();
        double expo = exponent();
        double demand = 0, freeGen = 0, storeCap = 0;
        List<Fuelled> burners = new ArrayList<>();
        List<Worker> workers = new ArrayList<>();

        for (Group g : layout.groups()) {
            if (!g.powered) continue;
            Spec spec = g.type.spec();
            double scale = g.fusionFactor(expo) * g.mult;
            demand += spec.draw() * g.area;          // draw stays linear in size: fusion is efficient
            switch (spec.role()) {
                case Role.Generator gen -> {
                    if (gen.fuel().isEmpty()) {
                        freeGen += gen.power() * scale;
                    } else {
                        double frac = 1;
                        for (var e : gen.fuel().entrySet()) {
                            double need = e.getValue() * scale * dt;
                            if (need > 0) frac = Math.min(frac, board.get(e.getKey()) / need);
                        }
                        burners.add(new Fuelled(g, gen, scale, gen.power() * scale * clamp(frac)));
                    }
                }
                case Role.Buffer buf -> storeCap += buf.capacity() * scale;
                case Role.Mine m -> workers.add(new Worker(g, m, scale));
                case Role.Converter c -> workers.add(new Worker(g, c, scale));
                case Role.Producer p -> workers.add(new Worker(g, p, scale));
                case Role.AutoTap t -> workers.add(new Worker(g, t, scale));
                case Role.Amplifier a -> { }
                case Role.Conduit c -> { }
            }
        }

        // Burn only what the site draws, plus a little to top up the buffers.
        double topUp = Math.max(0, storeCap - board.energy) / 3;
        double wanted = Math.max(0, demand + topUp - freeGen);
        double capSum = burners.stream().mapToDouble(Fuelled::cap).sum();
        double burned = 0;
        if (capSum > 0 && wanted > 0) {
            double take = Math.min(wanted, capSum);
            for (Fuelled f : burners) {
                double made = take * (f.cap() / capSum);
                burned += made;
                double nominal = f.gen().power() * f.scale();
                double ratio = nominal > 0 ? made / nominal : 0;
                for (var e : f.gen().fuel().entrySet())
                    consume(e.getKey(), e.getValue() * f.scale() * ratio * dt, dt);
            }
        }

        double supply = freeGen + burned;
        double sat = 1;
        if (demand > 1e-9) {
            double fromStore = Math.min(board.energy / dt, Math.max(0, demand - supply));
            sat = Math.min(1, (supply + fromStore) / demand);
        }
        board.energy = Math.max(0, Math.min(storeCap, board.energy + (supply - demand * sat) * dt));
        power = new Power(supply, demand, sat, storeCap, board.energy);

        for (Worker w : workers) {
            Group g = w.group();
            double scale = w.scale();
            double rate = sat;
            switch (w.role()) {
                case Role.Mine m -> {
                    if (g.ore != null)
                        produce(g.ore, m.rate() * scale * g.richness * mineMultiplier() * rate * dt, dt);
                }
                case Role.Converter c -> {
                    double limit = 1;
                    for (var e : c.in().entrySet()) {
                        double want = e.getValue() * scale * sat * dt;
                        if (want > 0) limit = Math.min(limit, board.get(e.getKey()) / want);
                    }
                    rate = sat * clamp(limit);
                    for (var e : c.in().entrySet()) consume(e.getKey(), e.getValue() * scale * rate * dt, dt);
                    double mult = outputMultiplier(g.type);
                    for (var e : c.out().entrySet()) produce(e.getKey(), e.getValue() * scale * mult * rate * dt, dt);
                }
                case Role.Producer p -> {
                    for (var e : p.out().entrySet()) produce(e.getKey(), e.getValue() * scale * rate * dt, dt);
                }
                case Role.AutoTap t -> produce(Res.MATTER, t.perSecond() * scale * clickYield() * rate * dt, dt);
                default -> { }
            }
            g.rate = rate;
        }

        for (Res r : Res.values()) {
            double now = flow.getOrDefault(r, 0.0);
            smoothed.merge(r, now, (old, fresh) -> old * 0.7 + fresh * 0.3);
        }
    }

    /** Adds {@code amount} of {@code r} to the board and records the rate in {@link #flow} for smoothing. */
    private void produce(Res r, double amount, double dt) {
        board.add(r, amount);
        flow.merge(r, amount / dt, Double::sum);
    }

    /** Removes {@code amount} of {@code r} from the board and records the (negative) rate in {@link #flow}. */
    private void consume(Res r, double amount, double dt) {
        board.set(r, board.get(r) - amount);
        flow.merge(r, -amount / dt, Double::sum);
    }

    /** Clamps a fraction/ratio to {@code [0, 1]}. */
    private static double clamp(double v) { return Math.max(0, Math.min(1, v)); }

    /* ---------------- player actions ---------------- */

    /**
     * Places {@code m} at {@code (x, y)} if the cell is empty, inside the claim, ore-compatible
     * when required, and affordable, deducting {@link #priceOf(Machine)} on success.
     *
     * <p>Placing the first {@link Machine#TOKAMAK} (Fusion Reactor) — the top of the tech tree,
     * gated behind both {@link Tech#FISSION} and {@link Tech#GEO2} — is this game's victory
     * condition: it latches {@link Board#won} permanently and logs the moment. The caller
     * ({@link Game#pressed}) diffs {@code board.won} across this call to know whether to
     * celebrate, rather than this method reporting it directly, since {@code place}'s
     * boolean return is already spoken for as "did the placement succeed."
     *
     * @return whether the placement succeeded
     */
    public boolean place(Machine m, int x, int y) {
        if (!board.inClaim(x, y)) return false;
        int i = Board.idx(x, y);
        if (board.cell[i] != null) return false;
        if (m.spec().oreOnly() && board.ore[i] == null) return false;
        var price = priceOf(m);
        if (!affordable(price)) return false;
        spend(price);
        board.cell[i] = m;
        board.off[i] = false;
        board.built.merge(m, 1, Integer::sum);
        if (m == Machine.TOKAMAK && !board.won) {
            board.won = true;
            board.logLine("Fusion Reactor online. The site has reached self-sustaining output.");
        }
        dirty = true;
        return true;
    }

    /**
     * Removes a whole fused block and refunds half of what it cost.
     *
     * <p>See {@link #scrap} for how the refund is worked out. Removing every cell at once is
     * the default gesture; {@link #demolishCell} takes a single cell out of the same block.
     */
    public void demolish(Group g) {
        if (g == null || g.type == Machine.CORE) return;
        scrap(g.type, g.cells);
    }

    /**
     * Removes exactly one cell, leaving the rest of whatever block it belonged to standing.
     * Refunded on the same terms as {@link #demolish}, for the single unit taken out.
     *
     * <p>Nothing else has to be told the block just changed shape: the surviving cells are
     * re-fused from scratch on the next {@link #recompute()}, so a 3x3 clipped at a corner
     * simply comes back as whatever rectangles now fit — often a smaller fused block plus
     * loose cells, and the {@code area}<sup>{@code exponent}</sup> output falls accordingly.
     *
     * @param x cell column
     * @param y cell row
     */
    public void demolishCell(int x, int y) {
        int i = Board.idx(x, y);
        Machine m = board.cell[i];
        if (m == null || m == Machine.CORE) return;
        scrap(m, new int[]{i});
    }

    /**
     * Clears {@code cells} (all of them the same {@code type}) off the board and pays back half
     * of what they cost.
     *
     * <p>Because {@link #priceOf} inflates with every unit built and nothing tracks what a
     * cell actually paid at placement time, the refund instead reconstructs history: it
     * walks back through the {@link Board#count(Machine)} build order to sum what each of
     * the removed units would have cost at its point in that order
     * ({@code 1.14^(units built before it)}), and refunds half of that total. This
     * "undoes the price ramp" for exactly the units being removed rather than requiring a
     * per-cell paid-cost ledger — so scrapping a block one cell at a time pays out exactly
     * the same total as scrapping it in one go.
     */
    private void scrap(Machine type, int[] cells) {
        int owned = board.count(type);
        double factor = 0;
        for (int k = 0; k < cells.length; k++) factor += Math.pow(1.14, Math.max(0, owned - 1 - k));
        for (var e : type.spec().cost().entrySet())
            board.add(e.getKey(), e.getValue() * factor * 0.5);
        for (int i : cells) { board.cell[i] = null; board.off[i] = false; }
        board.built.put(type, Math.max(0, owned - cells.length));
        dirty = true;
    }

    /**
     * Manually switches every cell of {@code g} on or off. A disabled group stops drawing
     * power and stops working on the next {@link #tick(double)} (see the guard at the top of
     * its main loop), but keeps conducting power through to whatever is fused or wired past it
     * (see {@link Fusion#energise}) — only its own draw and output stop. The core can't be
     * switched off. The change is recorded on {@link Board#off}, not on the transient {@link
     * Group} passed in, so it survives the {@link #recompute()} that {@link #markDirty()}
     * forces on the next read.
     *
     * @param g  the group to switch, ignored if it is the core or {@code null}
     * @param on {@code true} to switch on, {@code false} to switch off
     */
    public void toggle(Group g, boolean on) {
        if (g == null || g.type == Machine.CORE) return;
        for (int i : g.cells) board.off[i] = !on;
        dirty = true;
    }

    /**
     * The site's final act of fusion, available once {@link Board#won}: every standing machine
     * is cleared and the claim's northern half is filled solid with {@link Machine#MONOLITH},
     * which — being one machine kind filling one rectangle — {@link Fusion#layout} fuses into a
     * single {@link Group} the same way any other rectangle of identical machines would, with no
     * special-casing anywhere else in the renderer or the tick loop. Its area is {@code claim} by
     * however many rows sit strictly above the core's fixed row, so it's always at least 7x3 (the
     * smallest possible claim) and always touches the core directly, powering up immediately.
     *
     * <p>The Monolith's own {@link Role.Producer} rate is a small, fixed-per-cell constant (see
     * {@link Machine#MONOLITH}) — deliberately unremarkable on its own. What makes the payoff
     * feel earned is the exact same {@code area}<sup>{@code exponent}</sup> rule every other
     * fused block already obeys: a bigger claim (i.e. more Claim Extension research completed
     * before collapsing) yields a bigger Monolith, and the fusion exponent research
     * ({@link Tech#GEO1}/{@link Tech#GEO2}) that made every earlier block hit harder makes this
     * one hit harder too. No new scaling mechanic was needed; the existing one already rewards
     * "how far did you get" exactly the way this capstone should.
     *
     * <p>Resources, tech, and the claim itself are untouched — only what stands on the ground
     * changes, and the southern half of the claim is left buildable, so collapsing is a
     * transformation to build forward from, not an ending. It's also repeatable: nothing stops
     * the player researching another Claim Extension, rebuilding in the southern half, and
     * collapsing again to fold a bigger claim (and whatever got rebuilt) into an even larger
     * Monolith. {@link Board#collapsed} just latches once, the same one-way way {@link
     * Board#won} does, purely so the UI has something permanent to show for it.
     *
     * @return whether the collapse happened ({@code false} if the site hasn't won yet)
     */
    public boolean collapse() {
        if (!board.won) return false;
        for (int i = 0; i < board.cell.length; i++) {
            if (board.cell[i] == Machine.CORE) continue;
            board.cell[i] = null;
            board.off[i] = false;
        }
        board.built.clear();
        int margin = board.margin();
        int width = board.claim;
        int height = Board.CY - margin;            // rows strictly above the core's fixed row
        for (int y = margin; y < margin + height; y++)
            for (int x = margin; x < margin + width; x++)
                board.cell[Board.idx(x, y)] = Machine.MONOLITH;
        board.built.put(Machine.MONOLITH, width * height);
        board.collapsed = true;
        board.logLine("The site collapses into a single Monolith.");
        dirty = true;
        return true;
    }

    /** Manually condenses matter from the core: adds {@link #clickYield()} matter and counts the click. */
    public double tapCore() {
        double gain = clickYield();
        board.add(Res.MATTER, gain);
        board.clicks++;
        return gain;
    }

    /** Whether {@code t} is not yet researched and every prerequisite already is. */
    public boolean researchable(Tech t) {
        return !board.has(t) && board.tech.containsAll(t.requires());
    }

    /**
     * Researches {@code t} if eligible and affordable: spends its cost, marks it done,
     * widens the claim if it grants one, and logs the completion.
     *
     * @return whether the research succeeded
     */
    public boolean research(Tech t) {
        if (!researchable(t) || !affordable(t.cost)) return false;
        spend(t.cost);
        board.tech.add(t);
        if (t.claim() > 0) board.claim = t.claim();
        board.logLine("Research complete - " + t.label);
        dirty = true;
        return true;
    }

    /** Whether {@code m} can be built: either it needs no tech, or its required tech is already researched. */
    public boolean unlocked(Machine m) {
        Tech t = m.spec().tech();
        return t == null || board.has(t);
    }

    /**
     * Whether the machine standing on cell {@code i} is parked on an ore patch it cannot mine —
     * a pylon, furnace, burner or collector sitting where a rig could have been. Nothing in the
     * simulation forbids it: {@link #place} only rejects the opposite mistake (a rig on bare
     * rock), so the cost is silent and permanent-looking, every second of ore that cell never
     * yields. This is the query behind the board's patch marks and the BUILD tab's patch check,
     * and the whole rule lives here rather than in the renderer that draws it.
     *
     * <p>Three cases are deliberately not reported, because none of them is a mistake the player
     * could undo: the {@link Machine#CORE}, which is planted dead centre by the survey and can
     * never be moved off whatever is under it; the {@link Machine#MONOLITH}, which {@link
     * #collapse()} stamps across half the claim on purpose; and any cell outside the current
     * claim, where the ore is only a faint unsurveyed trace (see {@link Board#inClaim}) and so
     * isn't a patch anyone knowingly buried.
     *
     * @param i flat cell index, as built by {@link Board#idx}
     */
    public boolean smothered(int i) { return smothers(board.cell[i], i); }

    /**
     * The same question as {@link #smothered(int)}, asked one click early: whether placing
     * {@code m} at {@code (x, y)} would leave it sitting on ore it cannot mine. Drives the
     * board's placement ghost, so the warning arrives while the cell is still empty instead of
     * only once the machine is down and paid for.
     */
    public boolean wouldSmother(Machine m, int x, int y) { return smothers(m, Board.idx(x, y)); }

    /**
     * Every {@link #smothered(int)} cell on the board, in flat-index order — which is reading
     * order, left to right and top to bottom, so a list of grid references built from this comes
     * out sorted the way the board is scanned by eye.
     *
     * @return the flat indices of every smothered cell; empty if the site is clean
     */
    public int[] smotheredCells() {
        int[] found = new int[Board.W * Board.H];
        int n = 0;
        for (int i = 0; i < found.length; i++) if (smothered(i)) found[n++] = i;
        return Arrays.copyOf(found, n);
    }

    /** The one rule behind {@link #smothered(int)} and {@link #wouldSmother}; see the former for why each clause is there. */
    private boolean smothers(Machine m, int i) {
        return m != null && m != Machine.CORE && m != Machine.MONOLITH
                && !m.spec().mines()
                && board.ore[i] != null
                && board.inClaim(Board.xOf(i), Board.yOf(i));
    }

    /**
     * Catch-up simulation for time spent away, capped at two hours.
     *
     * <p>This is brute force, not closed-form fast-forward math: elapsed time is capped at
     * {@code 2 * 3600} seconds and then replayed by calling {@link #tick(double)} with a
     * fixed {@code dt} of 0.5s in a loop — up to 14,400 iterations — synchronously on
     * startup, before the window is shown. There is no shortcut formula for skipping ahead
     * because the economy is path-dependent (fuel and buffer levels evolve tick to tick),
     * so a long absence can visibly stall application startup while this loop runs.
     *
     * @param millis wall-clock time since the save was last active
     * @return the number of seconds actually replayed, or 0 if under a minute passed
     */
    public double runUnattended(long millis) {
        double seconds = Math.min(2 * 3600, millis / 1000.0);
        if (seconds < 60) return 0;
        dirty = true;
        for (double t = 0; t < seconds; t += 0.5) tick(0.5);
        return seconds;
    }
}
