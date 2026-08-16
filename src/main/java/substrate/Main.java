package substrate;

import javax.swing.*;
import java.awt.*;

/** Entry point: restore the site if there is one, catch up on idle time, run. */
public final class Main {

    /**
     * Restores the saved board (or starts fresh), catches the engine up on any time spent away,
     * builds the window, and starts the game loop.
     *
     * <p>The very first statement disables OpenGL-accelerated Java2D for the whole JVM. This is
     * an environment-specific rendering-consistency workaround: it must run before any AWT/Swing
     * class is touched, since the pipeline is selected once at first use, which is why it is the
     * literal first line of {@code main} rather than being set nearer the code it protects.
     *
     * <p>Save-on-exit is wired twice — once via a JVM shutdown hook and once via a
     * {@code windowClosing} listener — both independently calling {@link Save#write}. This is
     * deliberate belt-and-suspenders: either path alone might not fire on every platform/exit
     * route, so both are registered and it's fine if both end up running (the save is
     * idempotent).
     */
    public static void main(String[] args) {
        System.setProperty("sun.java2d.opengl", "false");
        SwingUtilities.invokeLater(() -> {
            Board restored = Save.read();
            Engine engine;
            if (restored != null) {
                engine = new Engine(restored);
                double seconds = engine.runUnattended(System.currentTimeMillis() - restored.savedAt);
                if (seconds >= 60)
                    restored.logLine("Site ran unattended for " + Math.round(seconds / 60) + " min");
            } else {
                engine = Engine.fresh();
            }

            var game = new Game(engine);
            var frame = new JFrame("SUBSTRATE - survey grid 04");
            frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            frame.setContentPane(game.root());
            frame.getContentPane().setBackground(Theme.INK);
            frame.setMinimumSize(new Dimension(980, 720));
            frame.pack();
            frame.setSize(new Dimension(1180, 860));
            frame.setLocationRelativeTo(null);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> Save.write(engine.board)));
            frame.addWindowListener(new java.awt.event.WindowAdapter() {
                /** Second save-on-exit path, redundant with the shutdown hook above by design. */
                @Override public void windowClosing(java.awt.event.WindowEvent e) { Save.write(engine.board); }
            });
            frame.setVisible(true);
            game.start();
        });
    }
}
