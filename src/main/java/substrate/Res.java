package substrate;

import java.awt.Color;

/**
 * Everything the site can hold: raw ores, their refined forms, and manufactured goods, in rough
 * production order. Each constant carries its own display label, ledger/UI color, and (for ores
 * only) a short survey tag, so there is no separate lookup table to keep in sync with the enum.
 */
public enum Res {
    MATTER      ("Matter",       new Color(0xDB, 0xE9, 0xF5), null),
    IRON_ORE    ("Iron ore",     new Color(0x9F, 0xB4, 0xC9), "Fe"),
    COPPER_ORE  ("Copper ore",   new Color(0xE0, 0x8A, 0x4F), "Cu"),
    COAL        ("Coal",         new Color(0x8D, 0x95, 0xA3), "C"),
    TITANIUM_ORE("Titanium ore", new Color(0xB3, 0x9F, 0xF0), "Ti"),
    URANIUM_ORE ("Uranium ore",  new Color(0x7F, 0xE0, 0x8A), "U"),
    IRON        ("Iron",         new Color(0x9F, 0xB4, 0xC9), null),
    COPPER      ("Copper",       new Color(0xE0, 0x8A, 0x4F), null),
    STEEL       ("Steel",        new Color(0xC3, 0xD2, 0xE0), null),
    TITANIUM    ("Titanium",     new Color(0xB3, 0x9F, 0xF0), null),
    CIRCUIT     ("Circuits",     new Color(0x63, 0xD6, 0xA8), null),
    DATA        ("Data",         new Color(0x7C, 0xC4, 0xFF), null);

    /** Human-readable display name. */
    public final String label;
    /** Color used to represent this resource across the UI (ledger, ore tiles, flow readouts). */
    public final Color color;
    /** Short survey tag, only for the five ores. */
    public final String tag;

    /**
     * @param label display name
     * @param color UI color
     * @param tag   survey tag for ores, or {@code null} for everything else — doubles as the
     *              flag {@link #isOre()} tests
     */
    Res(String label, Color color, String tag) {
        this.label = label;
        this.color = color;
        this.tag = tag;
    }

    /** @return {@code true} if this is one of the five diggable ores (i.e. has a survey tag). */
    public boolean isOre() { return tag != null; }

    /** @return {@link #label} lowercased, for mid-sentence use. */
    public String lower() { return label.toLowerCase(); }
}
