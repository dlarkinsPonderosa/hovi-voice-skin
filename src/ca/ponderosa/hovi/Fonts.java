// SPDX-License-Identifier: Apache-2.0
package ca.ponderosa.hovi;

import android.graphics.Typeface;
import android.os.Build;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * The bundled fonts under assets/fonts (Google Fonts, OFL or Apache, unmodified, licences beside them): one
 * accent font per holiday for the short lines (the name, what was said, the actions) and Atkinson Hyperlegible
 * for the answer. Loaded once each, on first use.
 */
final class Fonts {
    static final String MONO = "JetBrainsMono-Variable.ttf";
    static final String HYPERLEGIBLE = "AtkinsonHyperlegible-Bold.ttf";

    private final PluginHost host;
    private final Map<String, Typeface> loaded = new HashMap<>();

    Fonts(PluginHost host) { this.host = host; }

    /** A bundled font, at a weight when it's a variable one (Android 8 and up); null if it won't load. */
    synchronized Typeface get(String file, int weight) {
        String key = file + "@" + weight;
        if (loaded.containsKey(key)) return loaded.get(key);
        Typeface typeface = null;
        try {
            File path = new File(host.assetPath("fonts/" + file));
            if (weight > 0 && Build.VERSION.SDK_INT >= 26) {
                typeface = new Typeface.Builder(path).setFontVariationSettings("'wght' " + weight).build();
            } else {
                typeface = Typeface.createFromFile(path);
            }
        } catch (RuntimeException missing) {
            host.log("Font " + file + " did not load: " + missing);
        }
        loaded.put(key, typeface);
        return typeface;
    }
}
