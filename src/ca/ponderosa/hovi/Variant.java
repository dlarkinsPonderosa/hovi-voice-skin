// SPDX-License-Identifier: Apache-2.0
package ca.ponderosa.hovi;

import android.graphics.Color;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One look for the robot: the face and light colours, and the holiday props it wears or that float beside it. */
final class Variant {
    /** Every light, in the order the birthday rainbow ripples up the body. */
    private static final String[] RIPPLE = {"hover", "chest_ring", "chest_slot", "arm_l", "arm_r", "ear_l", "ear_r", "tail_3", "tail_2", "tail_1"};
    int face;
    private int light;
    private final Map<String, Integer> lights = new HashMap<>();
    boolean rainbow;
    /** Worn on the head. */
    String hat;
    /** A tail to draw instead of the plain one. */
    String tail;
    /** In place of the chest ring: chest_NAME is its glass and chestlight_NAME its light. */
    String chest;
    String[] floats = {};
    String[] bulbs = {};
    List<String> falling = Collections.emptyList();
    /** Props pinned to a layer, by layer name. */
    final Map<String, String[]> wear = new HashMap<>();
    /** Dim, steady lights and no playful expressions. */
    boolean calm;
    boolean heartEyes;
    /** Triangle eyes and a jagged grin. */
    boolean pumpkinFace;
    boolean rosyCheeks;
    /** Fireworks behind the robot in these colours, and a big burst when the answer gets loud; null for none. */
    int[] sparks;
    static final int[] GOLD_SPARKS = {0xFFFFD36B, 0xFFFFF4D6, 0xFFFFFFFF, 0xFFFFB933};
    static final int[] FLAG_SPARKS = {0xFFFF3B3B, 0xFFFFFFFF, 0xFF4D7CFF, 0xFFFF6B6B, 0xFFE8EEFF};
    static final int[] CANADA_SPARKS = {0xFFFF2A2A, 0xFFFFFFFF, 0xFFFF6B6B, 0xFFFFE8E8};
    /** Simple snow drifting down behind the robot. */
    boolean snow;
    /** Flakes fluttering down behind the robot in these colours, or null for none. */
    int[] confetti;
    static final int[] GOLD_CONFETTI = {0xFFFFCC4D, 0xFFF5D77A, 0xFFC9D1D9, 0xFFE8EDF2, 0xFFFFFFFF};
    static final int[] RAINBOW_CONFETTI = {0xFFFF4D4D, 0xFFFFB000, 0xFFFFE14D, 0xFF3DDC84, 0xFF3DB8FF, 0xFF9B6BFF, 0xFFFF5FAE};

    private Variant(String face, String light) {
        this.face = Color.parseColor(face);
        this.light = Color.parseColor(light);
    }

    private Variant lights(String... pairs) {
        for (int i = 0; i < pairs.length; i += 2) lights.put(pairs[i], Color.parseColor(pairs[i + 1]));
        return this;
    }

    /** The colour of one light now. */
    int light(String name, float now) {
        if (rainbow) {
            int i = 0;
            while (i < RIPPLE.length && !RIPPLE[i].equals(name)) i++;
            float hue = Math.round(((now * 45 + i * 36) % 360) / 15) * 15;
            return Color.HSVToColor(new float[] {hue, 0.85f, 1f});
        }
        Integer chosen = lights.get(name);
        return chosen != null ? chosen : light;
    }

    /**
     * The look for a holiday, by the name the Holiday setting or the Home Assistant entity gives it. Anything else
     * is the everyday robot.
     */
    static Variant of(String holiday, List<String> leaves) {
        String key = holiday == null ? "" : holiday.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        Variant v;
        switch (key) {
            case "newyears":
            case "newyear":
            case "newyearsday":
            case "newyearseve":
                v = new Variant("#fff4d6", "#ffffff").lights("ear_l", "#ffcc4d", "ear_r", "#ffcc4d", "chest_ring", "#ffcc4d", "hover", "#ffcc4d", "tail_2", "#ffcc4d");
                v.hat = "ny_hat";
                v.tail = "ny_tail";
                v.bulbs = new String[] {"ny_bulbs_0", "ny_bulbs_1", "ny_bulbs_2"};
                v.floats = new String[] {"ny_display"};
                v.sparks = GOLD_SPARKS;
                v.confetti = GOLD_CONFETTI;
                return v;
            case "independenceday":
            case "independence":
            case "fourthofjuly":
            case "thofjuly":
            case "julyfourth":
            case "julyth":
                // Red, white and blue. The cap and the flag panels are only on the plain robot; the cats get the
                // colours and fireworks
                v = new Variant("#e6eeff", "#ff2a2a").lights("ear_l", "#4d7cff", "ear_r", "#4d7cff", "chest_slot", "#ffffff", "hover", "#4d7cff",
                        "tail_2", "#ffffff", "tail_3", "#4d7cff");
                v.hat = "us_cap";
                v.floats = new String[] {"us_panel_l", "us_panel_r"};
                v.sparks = FLAG_SPARKS;
                return v;
            case "valentines":
            case "valentinesday":
                v = new Variant("#ffc2dd", "#ff7ab8").lights("ear_l", "#ff2e7a", "ear_r", "#ff2e7a", "chest_ring", "#ff1a4a", "hover", "#ff2e7a", "tail_1", "#ff1a4a", "tail_3", "#ff1a4a");
                v.chest = "heart";
                v.floats = new String[] {"heart", "heart_l", "heart_s"};
                // The cupid on the tail tip is only on the white base; a prop that's missing is skipped
                v.wear.put("tail", new String[] {"cupid"});
                v.heartEyes = true;
                return v;
            case "stpatricks":
            case "stpatricksday":
                v = new Variant("#9dffb8", "#19e65a").lights("chest_slot", "#ffd23a");
                v.hat = "leprechaun_hat";
                v.chest = "shamrock";
                v.floats = new String[] {"pot_of_gold"};
                return v;
            case "easter":
                v = new Variant("#ffe3f1", "#25e0d0").lights("ear_l", "#ff5fae", "ear_r", "#ff5fae", "chest_slot", "#ffd23a", "hover", "#ffd23a", "tail_1", "#ff5fae", "tail_3", "#ffd23a");
                v.hat = "bunny_ears";
                v.floats = new String[] {"egg", "egg_r"};
                return v;
            case "canadaday":
            case "canada":
                v = new Variant("#ffffff", "#ff1a1a").lights("chest_slot", "#ffffff", "hover", "#ffffff", "tail_2", "#ffffff");
                v.hat = "canada_cap";
                v.chest = "maple";
                // flag_r, a mirrored flag on the right, is only on the white base
                v.floats = new String[] {"flag", "flag_r"};
                v.sparks = CANADA_SPARKS;
                return v;
            case "thanksgiving":
                v = new Variant("#ffc477", "#ffb000").lights("ear_l", "#ff7a00", "ear_r", "#ff7a00", "hover", "#ff3d00", "tail_1", "#ff3d00", "tail_2", "#ff7a00");
                v.chest = "oak";
                v.falling = leaves;
                // The turkey on the tail tip is only on the white base; a prop that's missing is skipped
                v.wear.put("tail", new String[] {"turkey"});
                return v;
            case "halloween":
                v = new Variant("#ff8a1c", "#ff6200");
                v.hat = "bat";
                // pumpkin_r is only on the white base; a prop that's missing is skipped
                v.floats = new String[] {"pumpkin", "pumpkin_r"};
                v.pumpkinFace = true;
                return v;
            case "remembrance":
            case "remembranceday":
                v = new Variant("#f2ece6", "#ffe0cc");
                v.hat = "beret";
                v.wear.put("body", new String[] {"poppy_pin"});
                v.wear.put("tail", new String[] {"tail_poppy"});
                // The white base's poppy chest light (it has no pin); the metal base has no chest_poppy_w,
                // so its ring stays a ring
                v.chest = "poppy_w";
                v.calm = true;
                return v;
            case "christmas":
                v = new Variant("#fff1d6", "#ff1414").lights("chest_ring", "#14e650", "arm_l", "#14e650", "arm_r", "#14e650", "tail_2", "#14e650");
                v.hat = "santa_hat";
                v.tail = "xmas_tail";
                v.bulbs = new String[] {"bulbs_0", "bulbs_1", "bulbs_2"};
                v.floats = new String[] {"cane_l", "cane_r"};
                v.rosyCheeks = true;
                v.snow = true;
                return v;
            case "birthday":
                v = new Variant("#ffffff", "#ffffff");
                v.hat = "party_hat";
                // gift_l, a second present on the left, is only on the white base
                v.floats = new String[] {"gift", "gift_l"};
                v.confetti = RAINBOW_CONFETTI;
                v.rainbow = true;
                return v;
            default:
                return new Variant(Brand.FACE, Brand.LIGHT);
        }
    }
}
