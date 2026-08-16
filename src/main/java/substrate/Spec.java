package substrate;

import java.util.Map;

/**
 * Static description of a machine.
 *
 * <p>Besides its plain fields, {@code Spec} exposes fluent "wither" methods —
 * {@link #onOre()}, {@link #smelts()}, and {@link #research()} — that each return a new
 * {@code Spec} with exactly one boolean flag flipped, leaving the rest untouched. Records don't
 * get built-in {@code with}-copy syntax, so these are a small hand-rolled stand-in for it,
 * written purely so machine-definition call sites can read as a fluent chain (e.g.
 * {@code Spec.of(...).onOre().smelts()}) instead of repeating every field back through the
 * canonical constructor for a one-flag change.
 *
 * @param label    display name
 * @param abbr     short abbreviation used in compact UI (e.g. board tiles)
 * @param cost     resources spent to build one of this machine
 * @param draw     power draw per machine (scales linearly with a fused group's area)
 * @param role     behaviour this machine performs each tick
 * @param tech     tech that must be researched to unlock this machine, or {@code null} if available from the start
 * @param oreOnly  whether this machine may only be placed on a matching ore tile
 * @param smelter  whether this machine counts as a smelter (for smelter-wide research bonuses)
 * @param lab      whether this machine counts as a research lab (for lab-wide research bonuses)
 * @param blurb    one-line description shown in the build panel
 */
public record Spec(String label, String abbr, Map<Res, Double> cost, double draw,
                   Role role, Tech tech, boolean oreOnly, boolean smelter, boolean lab,
                   String blurb) {

    /** Canonical constructor for a plain machine: none of the {@code oreOnly}/{@code smelter}/{@code lab} flags set. */
    public static Spec of(String label, String abbr, Map<Res, Double> cost, double draw,
                          Role role, Tech tech, String blurb) {
        return new Spec(label, abbr, cost, draw, role, tech, false, false, false, blurb);
    }

    /** @return a copy of this spec with {@code oreOnly} set, restricting placement to matching ore tiles. */
    public Spec onOre()   { return new Spec(label, abbr, cost, draw, role, tech, true, smelter, lab, blurb); }
    /** @return a copy of this spec with {@code smelter} set, marking it for smelter-wide bonuses. */
    public Spec smelts()  { return new Spec(label, abbr, cost, draw, role, tech, oreOnly, true, lab, blurb); }
    /** @return a copy of this spec with {@code lab} set, marking it for lab-wide bonuses. */
    public Spec research(){ return new Spec(label, abbr, cost, draw, role, tech, oreOnly, smelter, true, blurb); }
}
