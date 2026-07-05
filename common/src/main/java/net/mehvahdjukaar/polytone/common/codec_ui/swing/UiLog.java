package net.mehvahdjukaar.polytone.common.codec_ui.swing;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Logger indirection so the Swing shell survives a bare-JVM launch (UI preview): loading
 * {@code Polytone} constructs every content manager, which can blow up without a game
 * bootstrap. First use tries the mod logger, falls back to a plain log4j one.
 */
final class UiLog {

    private static volatile Logger logger;

    private UiLog() {}

    static Logger get() {
        Logger l = logger;
        if (l == null) {
            try {
                l = net.mehvahdjukaar.polytone.Polytone.LOGGER;
                if (l == null) l = LogManager.getLogger("PolytoneCodecUi");
            } catch (Throwable t) {
                l = LogManager.getLogger("PolytoneCodecUi");
            }
            logger = l;
        }
        return l;
    }
}
