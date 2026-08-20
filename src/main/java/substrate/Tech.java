package substrate;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The research tree: every purchasable upgrade, what it costs, and (via {@link #blurb}) what it
 * does in plain English.
 *
 * <p><b>Prerequisites are declared on the constant, behind a lambda.</b> An enum constant's
 * arguments are evaluated while the enum class is still initializing, and Java forbids them from
 * naming a sibling constant at all: {@code TOOLS1(..., TOOLS0)} is a compile error ("illegal
 * reference to static field from initializer") even though {@code TOOLS0} is declared on the line
 * above. Wrapping the list in a {@code () -> EnumSet.of(...)} supplier defers that reference until
 * the constants exist, which is what lets each tech carry its own prerequisites here rather than
 * in a separate DAG table keyed by tech, the way this file used to hold them — one place per tech
 * for its name, price, effect and gating instead of two.
 *
 * <p>The suppliers are drained exactly once, by the static initializer below, the moment every
 * constant is live. So the deferral costs nothing at runtime: {@link #requires()} is a plain field
 * read, which matters because the research UI calls it for every row on every repaint.
 */
public enum Tech {
    TOOLS0     ("Percussive Drills",      Cost.of(Res.MATTER, 60),                                        "Click yield x5."),
    SMELTING   ("Smelting",               Cost.of(Res.MATTER, 250, Res.IRON_ORE, 60),                     "Unlocks Iron Furnace, Copper Furnace."),
    TOOLS1     ("Precision Manipulators", Cost.of(Res.MATTER, 1200, Res.IRON, 60),                        "Click yield x5 again.",
                () -> EnumSet.of(TOOLS0, SMELTING)),
    COMBUSTION ("Combustion",             Cost.of(Res.IRON, 50, Res.COAL, 50),                            "Unlocks Coal Burner.",
                () -> EnumSet.of(SMELTING)),
    AUTOMATION ("Automation",             Cost.of(Res.MATTER, 1200, Res.IRON, 80),                        "Unlocks Manipulator Arm.",
                () -> EnumSet.of(COMBUSTION)),
    ELECTRONICS("Electronics",            Cost.of(Res.IRON, 180, Res.COPPER, 120),                        "Unlocks Circuit Assembler.",
                () -> EnumSet.of(SMELTING)),
    METALLURGY ("Metallurgy",             Cost.of(Res.IRON, 250, Res.COAL, 150),                          "Unlocks Steel Foundry.",
                () -> EnumSet.of(COMBUSTION)),
    DRILLS1    ("Hardened Bits",          Cost.of(Res.IRON, 400, Res.CIRCUIT, 50),                        "All mining x1.6.",
                () -> EnumSet.of(ELECTRONICS)),
    SCIENCE    ("Scientific Method",      Cost.of(Res.STEEL, 200, Res.CIRCUIT, 100),                      "Unlocks Research Lab, which makes data.",
                () -> EnumSet.of(METALLURGY, ELECTRONICS)),
    STORAGE    ("Energy Storage",         Cost.of(Res.STEEL, 150, Res.CIRCUIT, 60),                       "Unlocks Capacitor Bank.",
                () -> EnumSet.of(SCIENCE)),
    TOOLS2     ("Servo Actuators",        Cost.of(Res.STEEL, 300, Res.CIRCUIT, 150),                      "Click yield x8.",
                () -> EnumSet.of(TOOLS1, SCIENCE)),
    TERR1      ("Claim Extension I",      Cost.of(Res.DATA, 20),                                          "Survey grid 9x9.",
                () -> EnumSet.of(SCIENCE)),
    DEEPDRILL  ("Deep Drilling",          Cost.of(Res.DATA, 60, Res.STEEL, 800),                          "Unlocks Deep Drill.",
                () -> EnumSet.of(SCIENCE)),
    SMELT1     ("Thermal Regulators",     Cost.of(Res.DATA, 150, Res.STEEL, 1200),                        "All smelter output x2.",
                () -> EnumSet.of(SCIENCE)),
    OVERCLOCK  ("Overclocking",           Cost.of(Res.DATA, 120, Res.CIRCUIT, 400),                       "Unlocks Overclock Node.",
                () -> EnumSet.of(SCIENCE)),
    DRAUGHT    ("Forced Draught",         Cost.of(Res.DATA, 200, Res.STEEL, 1500),                        "Unlocks Blast + Induction Furnace.",
                () -> EnumSet.of(SMELT1)),
    TERR2      ("Claim Extension II",     Cost.of(Res.DATA, 350),                                         "Survey grid 11x11.",
                () -> EnumSet.of(TERR1)),
    LABS1      ("Neural Coprocessors",    Cost.of(Res.DATA, 400, Res.CIRCUIT, 2000),                      "Research Lab output x3.",
                () -> EnumSet.of(OVERCLOCK)),
    GEO1       ("Geometric Synergy I",    Cost.of(Res.DATA, 500, Res.CIRCUIT, 800),                       "Fusion exponent 2.00 to 2.15.",
                () -> EnumSet.of(OVERCLOCK)),
    DRILLS2    ("Resonant Cutters",       Cost.of(Res.DATA, 700, Res.STEEL, 5000),                        "All mining x2.5.",
                () -> EnumSet.of(DEEPDRILL)),
    ALLOYS     ("Titanium Alloys",        Cost.of(Res.DATA, 800, Res.STEEL, 4000, Res.CIRCUIT, 1200),     "Unlocks Titanium Refinery.",
                () -> EnumSet.of(DRAUGHT)),
    TERR3      ("Claim Extension III",    Cost.of(Res.DATA, 1500, Res.TITANIUM, 200),                     "Survey grid 13x13.",
                () -> EnumSet.of(TERR2, ALLOYS)),
    FISSION    ("Fission",                Cost.of(Res.DATA, 2500, Res.TITANIUM, 600, Res.CIRCUIT, 2000),  "Unlocks Fission Reactor.",
                () -> EnumSet.of(ALLOYS)),
    TOOLS3     ("Graviton Manipulator",   Cost.of(Res.DATA, 3000, Res.TITANIUM, 500),                     "Click yield x20.",
                () -> EnumSet.of(TOOLS2, ALLOYS)),
    GEO2       ("Geometric Synergy II",   Cost.of(Res.DATA, 4000, Res.TITANIUM, 1000),                    "Fusion exponent 2.15 to 2.30.",
                () -> EnumSet.of(GEO1)),
    TERR4      ("Claim Extension IV",     Cost.of(Res.DATA, 6000, Res.TITANIUM, 2500),                    "Survey grid 15x15 - the whole site.",
                () -> EnumSet.of(TERR3)),
    REPLICATION("Mass Replication",       Cost.of(Res.DATA, 12000, Res.TITANIUM, 5000),                   "Unlocks Quantum Replicator.",
                () -> EnumSet.of(FISSION)),
    FUSION     ("Fusion",                 Cost.of(Res.DATA, 15000, Res.TITANIUM, 6000, Res.CIRCUIT, 8000),"Unlocks Fusion Reactor.",
                () -> EnumSet.of(FISSION, GEO2));

    /** Human-readable name shown in the research UI. */
    public final String label;
    /** Resources spent to unlock this tech. */
    public final Map<Res, Double> cost;
    /** One-line description of the effect, shown alongside the cost. */
    public final String blurb;

    /**
     * How to build {@link #prereqs}, deferred until every constant exists; see the class Javadoc
     * for why the prerequisite list can't just be passed in directly. Read once, by the static
     * initializer below, and never again.
     */
    private final Supplier<Set<Tech>> prereqSource;
    /**
     * Techs that must already be researched before this one, empty for the two roots of the tree.
     * Blank until the static initializer resolves {@link #prereqSource} into it, which happens
     * before any other code can observe a {@link Tech} — enum constants are fully constructed
     * before the class's static initializers run, and the class isn't usable until those finish.
     */
    private Set<Tech> prereqs = Set.of();

    /**
     * @param label human-readable name
     * @param cost  resources spent to unlock
     * @param blurb one-line effect description
     */
    Tech(String label, Map<Res, Double> cost, String blurb) {
        this(label, cost, blurb, Set::of);
    }

    /**
     * @param label        human-readable name
     * @param cost         resources spent to unlock
     * @param blurb        one-line effect description
     * @param prereqSource supplies the techs that gate this one, called once after every constant
     *                     exists (see the class Javadoc for why it's a supplier and not a set)
     */
    Tech(String label, Map<Res, Double> cost, String blurb, Supplier<Set<Tech>> prereqSource) {
        this.label = label;
        this.cost = cost;
        this.blurb = blurb;
        this.prereqSource = prereqSource;
    }

    // Drains every constant's prerequisite supplier now that all of them are live. Resolving
    // here, rather than lazily inside requires(), keeps that method a field read and means a
    // supplier that somehow can't be evaluated fails loudly at class-init time instead of on
    // whichever repaint happens to touch that row first.
    static {
        for (Tech t : values()) t.prereqs = t.prereqSource.get();
    }

    /** @return the set of techs that must already be researched before this one, possibly empty. */
    public Set<Tech> requires() { return prereqs; }

    /**
     * Claim size this tech grants, or 0 for techs that aren't claim extensions.
     *
     * <p>These sizes are hardcoded here rather than derived from {@link #blurb}, so the number
     * exists in two places (this switch and the human-readable "Survey grid NxN" string above) —
     * keep them in sync by hand when adding a new claim tier.
     */
    public int claim() {
        return switch (this) {
            case TERR1 -> 9;
            case TERR2 -> 11;
            case TERR3 -> 13;
            case TERR4 -> 15;
            default -> 0;
        };
    }
}
