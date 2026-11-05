// SPDX-License-Identifier: Apache-2.0
package ca.ponderosa.hovi;

/** What makes this build Hovi: the overlay key, the everyday colours and how the Appearance setting maps to the art. */
final class Brand {
    private Brand() {}

    /** The overlay this skin shows. */
    static final String KEY = "hovi";
    /** The name over the turn on a full screen. */
    static final String NAME = "Hovi";
    /** The everyday face and light colours: cyan. */
    static final String FACE = "#b8f3ff", LIGHT = "#2fd8f5";

    /** The art for an Appearance choice: Robot (the default) or Robot Cat. */
    static String base(Object choice) {
        return "Robot Cat".equals(choice) ? Assets.WHITE : Assets.ROBOT;
    }
}
