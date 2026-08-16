package substrate;

import java.util.Map;

/**
 * What a machine actually does. Sealed so the simulation can switch over every
 * behaviour exhaustively — adding a role without teaching the engine about it
 * becomes a compile error rather than a silent no-op.
 *
 * <p>This is the load-bearing design decision of the whole project. Every machine kind's
 * behaviour is one of the records below, and every place that needs to act on that behaviour
 * (the tick loop, fusion/power wiring, UI readouts) does so via a pattern-matching
 * {@code switch} over {@code Role} with <b>no {@code default} branch</b>. Because the switches
 * are exhaustive over a sealed type, adding a new record here — a new machine behaviour — makes
 * every one of those switches fail to compile until it is given a case for the new variant.
 * There is no way to add a half-wired machine kind that silently does nothing; the compiler
 * finds every place that needs updating.
 */
public sealed interface Role {

    /** Carries the network and nothing else — a pass-through connector like a pylon or belt. */
    record Conduit() implements Role {}

    /**
     * Makes power.
     *
     * @param power nominal power output at full fuel supply
     * @param fuel  resources consumed per second to sustain {@code power}; an empty map means
     *              the generator runs on nothing (e.g. renewable/free generation)
     */
    record Generator(double power, Map<Res, Double> fuel) implements Role {}

    /**
     * Extracts from the ore patch underneath, scaled by richness.
     *
     * @param rate nominal extraction rate at richness 1
     */
    record Mine(double rate) implements Role {}

    /**
     * Turns inputs into outputs, e.g. a furnace or foundry.
     *
     * @param in  resources consumed per cycle
     * @param out resources produced per cycle
     */
    record Converter(Map<Res, Double> in, Map<Res, Double> out) implements Role {}

    /**
     * Makes something from power alone, with no material input, e.g. a research lab.
     *
     * @param out resources produced per second
     */
    record Producer(Map<Res, Double> out) implements Role {}

    /**
     * Stores surplus power for later use.
     *
     * @param capacity maximum power that can be buffered
     */
    record Buffer(double capacity) implements Role {}

    /**
     * Taps the core automatically; scales with click yield, standing in for manual clicking.
     *
     * @param perSecond nominal automatic taps per second at click yield 1
     */
    record AutoTap(double perSecond) implements Role {}

    /**
     * Lifts every group it touches, e.g. an overclock node.
     *
     * @param boost multiplier granted to adjacent groups (further scaled by the amplifier's own
     *              fusion factor — see {@code Engine}'s recompute logic)
     */
    record Amplifier(double boost) implements Role {}
}
