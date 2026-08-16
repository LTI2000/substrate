package substrate;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Scatters ore in blobs, poor stuff near the core and the exotic stuff out at the fence.
 *
 * <p>Generation is rejection sampling, not constructive placement: {@link #survey} lays out the
 * whole board from scratch and only accepts the result if a small area near the core happens to
 * be playable, retrying (up to 400 times) otherwise. This is simpler and more robust than trying
 * to constructively guarantee a good opening, at the cost of occasionally doing the work several
 * times over.
 */
public final class OreGen {

    /**
     * One ore type's placement rules.
     *
     * @param res      the ore resource this vein produces
     * @param patches  number of separate blobs to grow
     * @param minSize  smallest allowed blob size, in tiles
     * @param maxSize  largest allowed blob size, in tiles
     * @param nearBand closest allowed Chebyshev distance from the core for a blob's seed tile
     * @param farBand  farthest allowed Chebyshev distance from the core for a blob's seed tile
     * @param minRich  smallest richness value stamped onto a tile of this vein
     * @param maxRich  largest richness value stamped onto a tile of this vein
     */
    private record Vein(Res res, int patches, int minSize, int maxSize, int nearBand, int farBand, int minRich, int maxRich) {}

    /** The fixed ore layout plan: which ores exist, how many blobs of each, and where they may seed. */
    private static final List<Vein> PLAN = List.of(
            new Vein(Res.IRON_ORE,     4, 3, 7, 1, 4, 1, 3),
            new Vein(Res.COPPER_ORE,   3, 3, 6, 2, 5, 1, 3),
            new Vein(Res.COAL,         3, 3, 7, 2, 6, 1, 3),
            new Vein(Res.TITANIUM_ORE, 2, 3, 5, 5, 7, 2, 4),
            new Vein(Res.URANIUM_ORE,  2, 2, 4, 6, 7, 2, 5));

    private OreGen() {}

    /**
     * Regenerates {@code b}'s entire ore layout in place, retrying from scratch (rejection
     * sampling, up to 400 attempts) until the area near the core satisfies
     * {@link #playableOpening}. Brute-force rather than constructive: it's far simpler to
     * generate-and-check than to place ore in a way that provably guarantees a playable start,
     * and 400 attempts is cheap insurance that this practically always terminates on the first
     * or second try.
     *
     * @param b   board whose {@code ore}/{@code rich} arrays are overwritten
     * @param rnd source of randomness; same seed reproduces the same layout
     */
    public static void survey(Board b, Random rnd) {
        for (int attempt = 0; attempt < 400; attempt++) {
            java.util.Arrays.fill(b.ore, null);
            java.util.Arrays.fill(b.rich, 0);
            for (Vein v : PLAN) sprinkle(b, rnd, v);
            if (playableOpening(b)) return;
        }
    }

    /**
     * Grows {@code v.patches()} organic ore blobs onto {@code b} via randomized flood fill: each
     * blob starts from a seed tile in the vein's allowed distance band, then repeatedly pops a
     * random tile off a frontier list, stamps it with ore, and pushes its four neighbors back
     * onto the same list. Popping at random (instead of in FIFO/LIFO order) is what makes the
     * blobs organic rather than diamond- or line-shaped.
     *
     * <p>The frontier list is never deduplicated, so the same coordinate can be queued multiple
     * times by different neighbors; this is tolerated rather than fixed because every pop already
     * re-checks {@code b.ore[i] != null} before stamping, so a duplicate is just a wasted (cheap)
     * iteration, not a correctness bug.
     *
     * @param b   board to stamp ore onto
     * @param rnd source of randomness
     * @param v   vein plan describing size, count, and placement band
     */
    private static void sprinkle(Board b, Random rnd, Vein v) {
        for (int k = 0; k < v.patches(); k++) {
            int sx = 0, sy = 0;
            for (int tries = 0; tries < 250; tries++) {
                sx = rnd.nextInt(Board.W);
                sy = rnd.nextInt(Board.H);
                int d = chebyshev(sx, sy);
                if (d >= v.nearBand() && d <= v.farBand() && b.ore[Board.idx(sx, sy)] == null && !isCore(sx, sy)) break;
            }
            int want = v.minSize() + rnd.nextInt(v.maxSize() - v.minSize() + 1);
            var front = new ArrayList<int[]>();
            front.add(new int[]{sx, sy});
            int made = 0;
            while (!front.isEmpty() && made < want) {
                int[] p = front.remove(rnd.nextInt(front.size()));
                int x = p[0], y = p[1];
                if (x < 0 || y < 0 || x >= Board.W || y >= Board.H) continue;
                int i = Board.idx(x, y);
                if (b.ore[i] != null || isCore(x, y)) continue;
                b.ore[i] = v.res();
                b.rich[i] = v.minRich() + (int) Math.round(rnd.nextDouble() * (v.maxRich() - v.minRich()));
                made++;
                front.add(new int[]{x + 1, y});
                front.add(new int[]{x - 1, y});
                front.add(new int[]{x, y + 1});
                front.add(new int[]{x, y - 1});
            }
        }
    }

    /**
     * The opening 7x7 has to contain enough iron to start and coal to burn.
     *
     * @return {@code true} if the core's immediate surroundings have at least 3 iron ore,
     *         2 copper ore, and 2 coal tiles
     */
    private static boolean playableOpening(Board b) {
        int fe = 0, cu = 0, coal = 0;
        for (int y = 4; y <= 10; y++) {
            for (int x = 4; x <= 10; x++) {
                Res r = b.ore[Board.idx(x, y)];
                if (r == Res.IRON_ORE) fe++;
                else if (r == Res.COPPER_ORE) cu++;
                else if (r == Res.COAL) coal++;
            }
        }
        return fe >= 3 && cu >= 2 && coal >= 2;
    }

    /** @return {@code true} if {@code (x, y)} is the core tile, which never gets ore. */
    private static boolean isCore(int x, int y) { return x == Board.CX && y == Board.CY; }

    /**
     * Chebyshev (chessboard) distance from the core: {@code max(|dx|, |dy|)} rather than
     * Euclidean {@code sqrt(dx^2 + dy^2)}. This makes the near/far placement bands square rings
     * around the core instead of circular ones, which matches the square grid better and is
     * cheaper to compute.
     */
    private static int chebyshev(int x, int y) {
        return Math.max(Math.abs(x - Board.CX), Math.abs(y - Board.CY));
    }
}
