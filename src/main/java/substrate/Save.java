package substrate;

import java.io.IOException;
import java.nio.file.*;

/**
 * Plain-text save in the user's home directory. Deliberately forgiving on read.
 *
 * <p>The format is a hand-rolled, line-oriented {@code key=value} text file — no JSON
 * library, no schema, nothing but {@link String} splitting. Each line is one top-level
 * field; fields that hold a map or list (resources, tech, built counts, board cells, ore)
 * are encoded as a single line of {@code ,}-separated entries, with {@code :} separating
 * the key and value inside each entry (e.g. {@code res=MATTER:12.0,IRON:4.0,}). The board
 * itself is serialized as one flat comma-joined list of enum names, one per cell, with
 * {@code -} standing in for an empty cell. This keeps the save human-readable and
 * dependency-free at the cost of any real schema or type safety.
 *
 * <p>Reading is deliberately forgiving rather than strict: every field falls back to a
 * hardcoded default via {@code getOrDefault} if missing, and the whole of {@link #read()}
 * is wrapped in a single catch-all exception handler. A corrupt or unparsable save is
 * discarded outright and the caller gets a fresh board — there is no attempt to surface
 * the error or salvage a partially valid save, since a broken save blocking startup would
 * be worse than silently losing progress.
 *
 * <p>{@code write} always emits a {@code version=1} line, but nothing in {@link #read()}
 * ever looks at it. It is an inert stub for future forward-compatibility (format
 * migrations keyed off version) that isn't wired up yet.
 */
public final class Save {
    private Save() {}

    /** Path to the single save file: {@code ~/.substrate/site.txt}. */
    private static Path file() {
        return Paths.get(System.getProperty("user.home"), ".substrate", "site.txt");
    }

    /** Serializes the full board state to the save file, overwriting any previous save. Failures are logged, not thrown. */
    public static void write(Board b) {
        b.savedAt = System.currentTimeMillis();
        var sb = new StringBuilder();
        sb.append("version=1\n");
        sb.append("claim=").append(b.claim).append('\n');
        sb.append("energy=").append(b.energy).append('\n');
        sb.append("clicks=").append(b.clicks).append('\n');
        sb.append("saved=").append(b.savedAt).append('\n');
        sb.append("res=");
        b.res.forEach((r, v) -> sb.append(r.name()).append(':').append(v).append(','));
        sb.append("\ntech=");
        b.tech.forEach(t -> sb.append(t.name()).append(','));
        sb.append("\nbuilt=");
        b.built.forEach((m, n) -> sb.append(m.name()).append(':').append(n).append(','));
        sb.append("\ncells=");
        for (Machine m : b.cell) sb.append(m == null ? "-" : m.name()).append(',');
        sb.append("\nore=");
        for (int i = 0; i < b.ore.length; i++)
            sb.append(b.ore[i] == null ? "-" : b.ore[i].name() + ":" + b.rich[i]).append(',');
        sb.append('\n');
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), sb.toString());
        } catch (IOException e) {
            System.err.println("could not save: " + e.getMessage());
        }
    }

    /**
     * Parses the save file back into a {@link Board}.
     *
     * <p>See the class doc: every field is read via {@code getOrDefault} against a
     * hardcoded fallback, and any exception (missing file content, malformed number,
     * unknown enum name, etc.) is caught and turned into a {@code null} return rather than
     * propagated, so a bad save can never crash startup — it just starts a new site.
     *
     * @return a restored board, or null if there is nothing usable on disk.
     */
    public static Board read() {
        try {
            if (!Files.exists(file())) return null;
            var fields = new java.util.HashMap<String, String>();
            for (String line : Files.readAllLines(file())) {
                int eq = line.indexOf('=');
                if (eq > 0) fields.put(line.substring(0, eq), line.substring(eq + 1));
            }
            var b = new Board();
            b.claim = Integer.parseInt(fields.getOrDefault("claim", "7"));
            b.energy = Double.parseDouble(fields.getOrDefault("energy", "0"));
            b.clicks = Long.parseLong(fields.getOrDefault("clicks", "0"));
            b.savedAt = Long.parseLong(fields.getOrDefault("saved", String.valueOf(System.currentTimeMillis())));
            for (var pair : split(fields.get("res"))) {
                var kv = pair.split(":");
                b.set(Res.valueOf(kv[0]), Double.parseDouble(kv[1]));
                if (Double.parseDouble(kv[1]) > 0) b.seen.put(Res.valueOf(kv[0]), true);
            }
            for (String t : split(fields.get("tech"))) b.tech.add(Tech.valueOf(t));
            for (var pair : split(fields.get("built"))) {
                var kv = pair.split(":");
                b.built.put(Machine.valueOf(kv[0]), Integer.parseInt(kv[1]));
            }
            var cells = split(fields.get("cells"));
            for (int i = 0; i < Math.min(cells.length, b.cell.length); i++)
                b.cell[i] = cells[i].equals("-") ? null : Machine.valueOf(cells[i]);
            var ore = split(fields.get("ore"));
            for (int i = 0; i < Math.min(ore.length, b.ore.length); i++) {
                if (ore[i].equals("-")) continue;
                var kv = ore[i].split(":");
                b.ore[i] = Res.valueOf(kv[0]);
                b.rich[i] = Integer.parseInt(kv[1]);
            }
            b.cell[Board.idx(Board.CX, Board.CY)] = Machine.CORE;
            return b;
        } catch (Exception e) {
            System.err.println("save unreadable, starting a new site: " + e.getMessage());
            return null;
        }
    }

    /** Deletes the save file, if any. Used to start over cleanly. IO failures are silently ignored. */
    public static void wipe() {
        try { Files.deleteIfExists(file()); } catch (IOException ignored) { }
    }

    /** Splits a {@code ,}-joined field into its entries, discarding blanks; returns an empty array for a null/blank field. */
    private static String[] split(String csv) {
        if (csv == null || csv.isBlank()) return new String[0];
        return java.util.Arrays.stream(csv.split(","))
                .filter(s -> !s.isBlank()).toArray(String[]::new);
    }

}
