package substrate;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The research tree: every purchasable upgrade, what it costs, and (via {@link #blurb}) what it
 * does in plain English.
 *
 * <p><b>Prerequisites live in a separate table.</b> An enum constant's constructor runs before
 * its sibling constants exist, so a tech cannot name another {@code Tech} while being
 * constructed — {@code TOOLS1} cannot simply list {@code TOOLS0} as a dependency inline. Instead
 * the whole prerequisite DAG lives in the static {@link #REQ} map, built in a static initializer
 * after all constants are already live, and looked up separately via {@link #requires()}.
 */
public enum Tech {
    TOOLS0     ("Percussive Drills",      Cost.of(Res.MATTER, 60),                                        "Click yield x5."),
    SMELTING   ("Smelting",               Cost.of(Res.MATTER, 250, Res.IRON_ORE, 60),                     "Unlocks Iron Furnace, Copper Furnace."),
    TOOLS1     ("Precision Manipulators", Cost.of(Res.MATTER, 1200, Res.IRON, 60),                        "Click yield x5 again."),
    COMBUSTION ("Combustion",             Cost.of(Res.IRON, 50, Res.COAL, 50),                            "Unlocks Coal Burner."),
    AUTOMATION ("Automation",             Cost.of(Res.MATTER, 1200, Res.IRON, 80),                        "Unlocks Manipulator Arm."),
    ELECTRONICS("Electronics",            Cost.of(Res.IRON, 180, Res.COPPER, 120),                        "Unlocks Circuit Assembler."),
    METALLURGY ("Metallurgy",             Cost.of(Res.IRON, 250, Res.COAL, 150),                          "Unlocks Steel Foundry."),
    DRILLS1    ("Hardened Bits",          Cost.of(Res.IRON, 400, Res.CIRCUIT, 50),                        "All mining x1.6."),
    SCIENCE    ("Scientific Method",      Cost.of(Res.STEEL, 200, Res.CIRCUIT, 100),                      "Unlocks Research Lab, which makes data."),
    STORAGE    ("Energy Storage",         Cost.of(Res.STEEL, 150, Res.CIRCUIT, 60),                       "Unlocks Capacitor Bank."),
    TOOLS2     ("Servo Actuators",        Cost.of(Res.STEEL, 300, Res.CIRCUIT, 150),                      "Click yield x8."),
    TERR1      ("Claim Extension I",      Cost.of(Res.DATA, 20),                                          "Survey grid 9x9."),
    DEEPDRILL  ("Deep Drilling",          Cost.of(Res.DATA, 60, Res.STEEL, 800),                          "Unlocks Deep Drill."),
    SMELT1     ("Thermal Regulators",     Cost.of(Res.DATA, 150, Res.STEEL, 1200),                        "All smelter output x2."),
    OVERCLOCK  ("Overclocking",           Cost.of(Res.DATA, 120, Res.CIRCUIT, 400),                       "Unlocks Overclock Node."),
    DRAUGHT    ("Forced Draught",         Cost.of(Res.DATA, 200, Res.STEEL, 1500),                        "Unlocks Blast + Induction Furnace."),
    TERR2      ("Claim Extension II",     Cost.of(Res.DATA, 350),                                         "Survey grid 11x11."),
    LABS1      ("Neural Coprocessors",    Cost.of(Res.DATA, 400, Res.CIRCUIT, 2000),                      "Research Lab output x3."),
    GEO1       ("Geometric Synergy I",    Cost.of(Res.DATA, 500, Res.CIRCUIT, 800),                       "Fusion exponent 2.00 to 2.15."),
    DRILLS2    ("Resonant Cutters",       Cost.of(Res.DATA, 700, Res.STEEL, 5000),                        "All mining x2.5."),
    ALLOYS     ("Titanium Alloys",        Cost.of(Res.DATA, 800, Res.STEEL, 4000, Res.CIRCUIT, 1200),     "Unlocks Titanium Refinery."),
    TERR3      ("Claim Extension III",    Cost.of(Res.DATA, 1500, Res.TITANIUM, 200),                     "Survey grid 13x13."),
    FISSION    ("Fission",                Cost.of(Res.DATA, 2500, Res.TITANIUM, 600, Res.CIRCUIT, 2000),  "Unlocks Fission Reactor."),
    TOOLS3     ("Graviton Manipulator",   Cost.of(Res.DATA, 3000, Res.TITANIUM, 500),                     "Click yield x20."),
    GEO2       ("Geometric Synergy II",   Cost.of(Res.DATA, 4000, Res.TITANIUM, 1000),                    "Fusion exponent 2.15 to 2.30."),
    TERR4      ("Claim Extension IV",     Cost.of(Res.DATA, 6000, Res.TITANIUM, 2500),                    "Survey grid 15x15 - the whole site."),
    REPLICATION("Mass Replication",       Cost.of(Res.DATA, 12000, Res.TITANIUM, 5000),                   "Unlocks Quantum Replicator."),
    FUSION     ("Fusion",                 Cost.of(Res.DATA, 15000, Res.TITANIUM, 6000, Res.CIRCUIT, 8000),"Unlocks Fusion Reactor.");

    /** Human-readable name shown in the research UI. */
    public final String label;
    /** Resources spent to unlock this tech. */
    public final Map<Res, Double> cost;
    /** One-line description of the effect, shown alongside the cost. */
    public final String blurb;

    /**
     * @param label human-readable name
     * @param cost  resources spent to unlock
     * @param blurb one-line effect description
     */
    Tech(String label, Map<Res, Double> cost, String blurb) {
        this.label = label;
        this.cost = cost;
        this.blurb = blurb;
    }

    /**
     * Prerequisite DAG, keyed by the tech it gates. Absent from this map means no prerequisite.
     * Populated in the static initializer below rather than per-constant, since a constant's
     * constructor cannot reference sibling constants (see the class Javadoc).
     */
    private static final Map<Tech, Set<Tech>> REQ = new EnumMap<>(Tech.class);
    static {
        REQ.put(TOOLS1,      EnumSet.of(TOOLS0, SMELTING));
        REQ.put(COMBUSTION,  EnumSet.of(SMELTING));
        REQ.put(AUTOMATION,  EnumSet.of(COMBUSTION));
        REQ.put(ELECTRONICS, EnumSet.of(SMELTING));
        REQ.put(METALLURGY,  EnumSet.of(COMBUSTION));
        REQ.put(DRILLS1,     EnumSet.of(ELECTRONICS));
        REQ.put(SCIENCE,     EnumSet.of(METALLURGY, ELECTRONICS));
        REQ.put(STORAGE,     EnumSet.of(SCIENCE));
        REQ.put(TOOLS2,      EnumSet.of(TOOLS1, SCIENCE));
        REQ.put(TERR1,       EnumSet.of(SCIENCE));
        REQ.put(DEEPDRILL,   EnumSet.of(SCIENCE));
        REQ.put(SMELT1,      EnumSet.of(SCIENCE));
        REQ.put(OVERCLOCK,   EnumSet.of(SCIENCE));
        REQ.put(DRAUGHT,     EnumSet.of(SMELT1));
        REQ.put(TERR2,       EnumSet.of(TERR1));
        REQ.put(LABS1,       EnumSet.of(OVERCLOCK));
        REQ.put(GEO1,        EnumSet.of(OVERCLOCK));
        REQ.put(DRILLS2,     EnumSet.of(DEEPDRILL));
        REQ.put(ALLOYS,      EnumSet.of(DRAUGHT));
        REQ.put(TERR3,       EnumSet.of(TERR2, ALLOYS));
        REQ.put(FISSION,     EnumSet.of(ALLOYS));
        REQ.put(TOOLS3,      EnumSet.of(TOOLS2, ALLOYS));
        REQ.put(GEO2,        EnumSet.of(GEO1));
        REQ.put(TERR4,       EnumSet.of(TERR3));
        REQ.put(REPLICATION, EnumSet.of(FISSION));
        REQ.put(FUSION,      EnumSet.of(FISSION, GEO2));
    }

    /** @return the set of techs that must already be researched before this one, possibly empty. */
    public Set<Tech> requires() { return REQ.getOrDefault(this, Set.of()); }

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
