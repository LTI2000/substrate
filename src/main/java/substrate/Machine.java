package substrate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Everything that can stand on a cell. Declaration order is build-menu order.
 *
 * <p>Rather than loading machine definitions from an external data file, this enum doubles
 * as the game's content/config table: every constant bakes its label, ore/tag code, build
 * cost, power draw, {@link Role} (what it actually does each tick), tech gate, and flavour
 * text directly into its constructor call, via {@link Spec#of}. Adding, removing, or
 * reordering a machine is a one-line change here.
 *
 * <p>Declaration order is not incidental: {@link #BUILDABLE} preserves {@link #values()}
 * order, and the UI walks that list to build the build menu, so the order machines are
 * declared in above is exactly the order they appear to the player, cheapest/earliest
 * first.
 */
public enum Machine {
    PYLON  (Spec.of("Conduit Pylon", "PYL", Cost.of(Res.MATTER, 12), 0,
            new Role.Conduit(), null,
            "Carries power. Nothing else. Reaches distant patches.")),

    SOLAR  (Spec.of("Photon Collector", "SOL", Cost.of(Res.MATTER, 30), 0,
            new Role.Generator(1.5, Map.of()), null,
            "Trickle of power, no fuel.")),

    MINER  (Spec.of("Mining Rig", "MIN", Cost.of(Res.MATTER, 60), 2,
            new Role.Mine(0.35), null,
            "Sits on an ore patch and chews.").onOre()),

    COND   (Spec.of("Matter Condenser", "CND", Cost.of(Res.MATTER, 150), 3,
            new Role.Producer(Cost.of(Res.MATTER, 0.5)), null,
            "Condenses matter out of the vacuum.")),

    FE     (Spec.of("Iron Furnace", "FE", Cost.of(Res.MATTER, 300, Res.IRON_ORE, 40), 3,
            new Role.Converter(Cost.of(Res.IRON_ORE, 1), Cost.of(Res.IRON, 0.5)), Tech.SMELTING,
            "Iron ore to iron.").smelts()),

    CU     (Spec.of("Copper Furnace", "CU", Cost.of(Res.MATTER, 300, Res.COPPER_ORE, 40), 3,
            new Role.Converter(Cost.of(Res.COPPER_ORE, 1), Cost.of(Res.COPPER, 0.5)), Tech.SMELTING,
            "Copper ore to copper.").smelts()),

    BURNER (Spec.of("Coal Burner", "BRN", Cost.of(Res.MATTER, 500, Res.IRON, 30), 0,
            new Role.Generator(12, Cost.of(Res.COAL, 0.3)), Tech.COMBUSTION,
            "Burns coal for real power.")),

    ARM    (Spec.of("Manipulator Arm", "ARM", Cost.of(Res.MATTER, 800, Res.IRON, 50), 2,
            new Role.AutoTap(0.5), Tech.AUTOMATION,
            "Taps the core for you. Scales with click yield.")),

    ASM    (Spec.of("Circuit Assembler", "ASM", Cost.of(Res.MATTER, 1600, Res.IRON, 120, Res.COPPER, 80), 6,
            new Role.Converter(Cost.of(Res.IRON, 0.5, Res.COPPER, 0.5), Cost.of(Res.CIRCUIT, 0.25)), Tech.ELECTRONICS,
            "Iron and copper to circuits.")),

    STL    (Spec.of("Steel Foundry", "STL", Cost.of(Res.MATTER, 2200, Res.IRON, 200, Res.COAL, 120), 8,
            new Role.Converter(Cost.of(Res.IRON, 1, Res.COAL, 0.5), Cost.of(Res.STEEL, 0.5)), Tech.METALLURGY,
            "Iron and coal to steel.").smelts()),

    CAP    (Spec.of("Capacitor Bank", "CAP", Cost.of(Res.MATTER, 2500, Res.STEEL, 100, Res.CIRCUIT, 30), 0,
            new Role.Buffer(200), Tech.STORAGE,
            "Buffers surplus power for brownouts.")),

    LAB    (Spec.of("Research Lab", "LAB", Cost.of(Res.MATTER, 5000, Res.STEEL, 150, Res.CIRCUIT, 80), 10,
            new Role.Converter(Cost.of(Res.CIRCUIT, 0.15), Cost.of(Res.DATA, 0.05)), Tech.SCIENCE,
            "Burns circuits into data.").research()),

    AMP    (Spec.of("Overclock Node", "AMP", Cost.of(Res.MATTER, 9000, Res.CIRCUIT, 200, Res.STEEL, 400), 5,
            new Role.Amplifier(0.06), Tech.OVERCLOCK,
            "Boosts every group it touches. Its boost scales with its own fusion.")),

    DRILL  (Spec.of("Deep Drill", "DRL", Cost.of(Res.MATTER, 25000, Res.STEEL, 500, Res.CIRCUIT, 250), 9,
            new Role.Mine(2.2), Tech.DEEPDRILL,
            "A mining rig, seven times over.").onOre()),

    BLAST  (Spec.of("Blast Furnace", "BLF", Cost.of(Res.MATTER, 30000, Res.STEEL, 600, Res.CIRCUIT, 200), 14,
            new Role.Converter(Cost.of(Res.IRON_ORE, 6), Cost.of(Res.IRON, 4)), Tech.DRAUGHT,
            "High-throughput iron.").smelts()),

    INDUCT (Spec.of("Induction Furnace", "IND", Cost.of(Res.MATTER, 30000, Res.STEEL, 600, Res.CIRCUIT, 200), 14,
            new Role.Converter(Cost.of(Res.COPPER_ORE, 6), Cost.of(Res.COPPER, 4)), Tech.DRAUGHT,
            "High-throughput copper.").smelts()),

    REFINE (Spec.of("Titanium Refinery", "TIR", Cost.of(Res.MATTER, 120000, Res.STEEL, 2000, Res.CIRCUIT, 800), 30,
            new Role.Converter(Cost.of(Res.TITANIUM_ORE, 4, Res.COAL, 2), Cost.of(Res.TITANIUM, 1.5)), Tech.ALLOYS,
            "Titanium ore and coal to titanium.").smelts()),

    REACTOR(Spec.of("Fission Reactor", "FIS", Cost.of(Res.MATTER, 250000, Res.STEEL, 3000, Res.TITANIUM, 400, Res.CIRCUIT, 1500), 0,
            new Role.Generator(180, Cost.of(Res.URANIUM_ORE, 0.06)), Tech.FISSION,
            "Uranium ore straight into the pile.")),

    REP    (Spec.of("Quantum Replicator", "REP", Cost.of(Res.MATTER, 1_500_000, Res.TITANIUM, 3000, Res.CIRCUIT, 5000), 80,
            new Role.Producer(Cost.of(Res.MATTER, 400)), Tech.REPLICATION,
            "Power into matter, absurdly.")),

    TOKAMAK(Spec.of("Fusion Reactor", "FUS", Cost.of(Res.MATTER, 2_000_000, Res.TITANIUM, 4000, Res.CIRCUIT, 6000, Res.STEEL, 8000), 0,
            new Role.Generator(900, Map.of()), Tech.FUSION,
            "No fuel. Enormous output.")),

    /** Not buildable: the one machine you operate by hand. */
    CORE   (Spec.of("Core", "CORE", Cost.of(), 0, new Role.Conduit(), null,
            "The only thing here that works by hand."));

    /** This machine's baked-in content: label, cost, power, role, tech gate, flavour text. */
    private final Spec spec;

    Machine(Spec spec) { this.spec = spec; }

    /** The content/config record backing this machine; see the class doc. */
    public Spec spec() { return spec; }

    /** Every machine except {@link #CORE}, in declaration (i.e. build-menu) order. */
    public static final List<Machine> BUILDABLE =
            Arrays.stream(values()).filter(m -> m != CORE).toList();
}
