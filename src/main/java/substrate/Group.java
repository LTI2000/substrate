package substrate;

/**
 * One machine as the simulation sees it: either a single cell, or a rectangle of
 * identical machines that has fused into a bigger one. x,y is always the
 * top-left corner, so the renderer can lay it out directly on the grid.
 */
public final class Group {
    /** Identity for this group, stable for its lifetime; groups are rebuilt (not mutated) when fusion topology changes. */
    public final int id;
    /** Machine kind every cell in this group shares. */
    public final Machine type;
    /** Top-left corner and extent of the bounding rectangle. */
    public final int x, y, w, h, area;
    /** Flat board indices ({@link Board#idx}) of every cell belonging to this group. */
    public final int[] cells;
    /** Ore this group sits on, or {@code null} if it isn't an ore-dependent machine. */
    public final Res ore;
    /** Richness of the underlying ore, meaningless when {@link #ore} is {@code null}. */
    public final double richness;

    /** Whether this group is currently connected to the core and receiving power; recomputed whenever the site changes. */
    public boolean powered;
    /** Output multiplier from external effects (e.g. amplifiers), separate from {@link #fusionFactor}. */
    public double mult = 1;
    /** Fraction of nominal throughput actually achieved last tick. */
    public double rate = 1;

    /**
     * @param id       stable identity for this group
     * @param type     shared machine kind
     * @param x        bounding rectangle left edge
     * @param y        bounding rectangle top edge
     * @param w        bounding rectangle width, in cells
     * @param h        bounding rectangle height, in cells
     * @param cells    flat indices of every cell in the group
     * @param ore      underlying ore, or {@code null}
     * @param richness underlying ore richness
     */
    Group(int id, Machine type, int x, int y, int w, int h, int[] cells, Res ore, double richness) {
        this.id = id; this.type = type;
        this.x = x; this.y = y; this.w = w; this.h = h;
        this.area = w * h; this.cells = cells;
        this.ore = ore; this.richness = richness;
    }

    /** @return {@code true} if this group is more than a single cell — i.e. has actually fused. */
    public boolean fused() { return area > 1; }

    /**
     * Output multiplier from fusion alone: {@code area ^ exponent}. This one call is the entire
     * game's core scaling mechanic — output growing with the square (or research-upgraded higher
     * power) of a fused block's area is the rule the whole game is built around.
     *
     * @param exponent current fusion exponent (starts at 2, raised by Geometric Synergy research)
     */
    public double fusionFactor(double exponent) { return Math.pow(area, exponent); }

    /**
     * Spreadsheet-style grid reference for this group's top-left cell, e.g. {@code "C4"}.
     *
     * <p>Converts the x coordinate to a column letter via raw char arithmetic,
     * {@code (char) ('A' + x)}. This only works because the board is {@link Board#W} = 15 wide,
     * comfortably within {@code A}-{@code O}; it would silently produce garbage past 26 columns.
     * The same trick is duplicated independently in {@code BoardPanel}'s ruler-drawing code
     * rather than shared from here.
     */
    public String where() { return (char) ('A' + x) + String.valueOf(y + 1); }
}
