// SPDX-License-Identifier: Apache-2.0
package ca.ponderosa.hovi;

import java.util.Map;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/** A voice skin: Hovi, a hovering robot that listens, thinks and talks, dressed for the holiday. */
public final class HoviPlugin implements KioskPlugin {
    private final SkinController skin = new SkinController();

    @Override
    public synchronized void start(PluginHost host, Map<String, Object> settings) {
        skin.start(host);
        skin.configure(settings);
    }

    @Override
    public synchronized void configure(Map<String, Object> settings) {
        skin.configure(settings);
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {
        throw new IllegalArgumentException("Unknown command: " + command);
    }

    @Override
    public synchronized void onEvent(String event, Map<String, Object> payload) {
        skin.onEvent(event, payload);
    }

    @Override
    public synchronized void stop() {
        skin.stop();
    }
}
