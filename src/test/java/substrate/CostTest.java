package substrate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins down the one property of a cost map that isn't about its contents: the order it iterates
 * in. The UI prints prices straight out of these maps, so the order a cost is written in the
 * {@link Machine} and {@link Tech} tables is the order a player reads it in — and when these maps
 * were built with {@code Map.copyOf}, that order came from the keys' hashes and a per-JVM random
 * seed instead, so the same price rendered "250 matter · 60 iron ore" one launch and "60 iron ore
 * · 250 matter" the next. Nothing about the simulation cared, which is exactly why it needs a test
 * rather than being left to be noticed.
 */
@DisplayName("cost maps")
class CostTest {

    /** Two, three and four pairs: enough to catch a reordering, since only one arrangement of four is right. */
    @Test
    @DisplayName("iterate in the order they were written")
    void preserveWrittenOrder() {
        assertEquals(List.of(Res.MATTER, Res.IRON_ORE),
                List.copyOf(Cost.of(Res.MATTER, 250, Res.IRON_ORE, 60).keySet()));
        assertEquals(List.of(Res.DATA, Res.TITANIUM, Res.CIRCUIT),
                List.copyOf(Cost.of(Res.DATA, 15000, Res.TITANIUM, 6000, Res.CIRCUIT, 8000).keySet()));
        assertEquals(List.of(Res.MATTER, Res.STEEL, Res.TITANIUM, Res.CIRCUIT),
                List.copyOf(Cost.of(Res.MATTER, 250000, Res.STEEL, 3000,
                        Res.TITANIUM, 400, Res.CIRCUIT, 1500).keySet()));
    }

    /** The content tables hand these maps out freely, so they still have to be read-only. */
    @Test
    @DisplayName("are immutable")
    void areImmutable() {
        var cost = Cost.of(Res.IRON, 50, Res.COAL, 50);
        assertThrows(UnsupportedOperationException.class, () -> cost.put(Res.DATA, 1.0));
        assertThrows(UnsupportedOperationException.class, () -> cost.remove(Res.IRON));
    }

    /** A build price is the spec's cost times a scale factor; scaling it must not reshuffle it. */
    @Test
    @DisplayName("survive the build-price markup in the same order")
    void priceKeepsSpecOrder() {
        var engine = Engine.fresh();
        for (Machine m : Machine.BUILDABLE) {
            assertEquals(List.copyOf(m.spec().cost().keySet()),
                    List.copyOf(engine.priceOf(m).keySet()),
                    m + "'s price should list its resources in the order the spec does");
        }
    }
}
