// SPDX-License-Identifier: Apache-2.0
package ca.ponderosa.hovi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Draws the Voice Satellite turn while this plugin's skin is picked. Follows the voice skin contract: draw only
 * while selected, follow voice.overlay for when to show, and honour the Appearance settings (Background, Text size,
 * Reactive activity bar). The holiday comes from the Holiday setting or a Home Assistant entity.
 */
final class SkinController {
    static final String KEY = Brand.KEY;
    /** Where the docked bubble sits above the bottom edge, in dp. */
    static final int DOCK_INSET = 24;
    static final String[] EVENTS = {"voice.skin", "voice.overlay", "voice.level", "voice.text", "voice.appearance"};
    static final String FOLLOW = "Follow Home Assistant";

    private PluginHost host;
    private int generation;
    private boolean selected;
    private boolean skinHeard;
    private boolean appearanceHeard;
    private String overlay = "hidden";
    private String drawn;
    private String holidaySetting = FOLLOW;
    /** The entity the skin is subscribed to, or null. */
    private String entity;
    private String entityHoliday = "";

    // Read by the view on the main thread.
    volatile float level;
    volatile String state = "idle";
    volatile boolean muted;
    volatile String command = "";
    volatile String answer = "";
    volatile String tool = "";
    /** Null for Skin default, which is solid black. */
    volatile Float background;
    volatile float textScale = 1;
    volatile boolean reactive = true;
    /** Milliseconds between frames from the Frame rate setting: 0 for every frame, -1 for Auto. */
    volatile int fixedDelay = -1;
    /** What Auto settled on for this device. It only ever slows down, and lasts until the plugin restarts. */
    volatile int autoDelay;
    /** The Darkness setting, 0 to 1. */
    volatile float darkness;
    /** The Base setting's art: Assets.METAL, WHITE or ROBOT (Brand.base). */
    volatile String base = Brand.base(null);
    /** The base last asked to load (null before start): a change of Base loads that base's pictures. */
    private String loading;
    /** Loaded on a background thread; null until then. */
    volatile Assets assets;
    volatile Variant variant = Variant.of("", Collections.<String>emptyList());
    /** The showing view. Touched on the main thread only. */
    private SkinView view;

    synchronized void start(PluginHost host) {
        this.host = host;
        int current = ++generation;
        try {
            for (String event : EVENTS) host.subscribe(event);
        } catch (IllegalArgumentException | UnsupportedOperationException olderHost) {
            host.status("This Kiosk Satellite version has no voice skins.", true);
            return;
        }
        try {
            host.subscribe("voice.state");
        } catch (IllegalArgumentException | UnsupportedOperationException olderHost) {
            // Without it the face guesses the phase from the words.
        }
        try {
            host.subscribe("wakeword.state");
        } catch (IllegalArgumentException | UnsupportedOperationException olderHost) {
            // Without it the robot never shows the muted face.
        }
        load(current, base);
        read(current);
    }

    /** About 60 small pictures: read them off the plugin's worker thread, which must stay quick. */
    private void load(int current, String which) {
        loading = which;
        Thread loader = new Thread(() -> {
            try {
                Assets loaded = new Assets(host, which);
                synchronized (SkinController.this) {
                    // A newer start, or the Base setting changed again while these loaded
                    if (current != generation || !which.equals(loading)) return;
                    assets = loaded;
                    pickVariant();
                }
            } catch (Exception error) {
                host.log("Hovi's pictures did not load: " + error);
                host.status("The robot's pictures did not load.", true);
            }
        }, "hovi-assets");
        loader.start();
    }

    private void read(int current) {
        // Events win over these reads: they are newer.
        host.executeCommand("getVoiceState", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            synchronized (SkinController.this) {
                if (current != generation || !ok || !(data instanceof Map)) return;
                Map<?, ?> voice = (Map<?, ?>) data;
                if (!skinHeard) selected = Boolean.TRUE.equals(voice.get("skinSelected"));
                if ("hidden".equals(overlay) && voice.get("overlay") instanceof String) overlay = (String) voice.get("overlay");
                if (voice.get("state") instanceof String) state = (String) voice.get("state");
                update();
            }
        });
        host.executeCommand("getVoiceAppearance", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            synchronized (SkinController.this) {
                if (current == generation && ok && data instanceof Map && !appearanceHeard) appearance((Map<?, ?>) data);
            }
        });
        host.executeCommand("getVoiceText", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            synchronized (SkinController.this) {
                if (current == generation && ok && data instanceof Map && command.isEmpty() && answer.isEmpty()) words((Map<?, ?>) data);
            }
        });
    }

    synchronized void configure(Map<String, Object> settings) {
        Object chosen = settings.get("holiday");
        holidaySetting = chosen instanceof String ? (String) chosen : FOLLOW;
        Object rate = settings.get("frameRate");
        fixedDelay = "Full".equals(rate) ? 0 : "30 fps".equals(rate) ? 33 : "20 fps".equals(rate) ? 50 : -1;
        Object dark = settings.get("darkness");
        darkness = dark instanceof Number ? Math.max(0, Math.min(1, ((Number) dark).floatValue() / 100)) : 0;
        base = Brand.base(settings.get("base"));
        if (host != null && loading != null && !loading.equals(base)) load(generation, base);
        Object id = settings.get("holidayEntity");
        String wanted = FOLLOW.equals(holidaySetting) && id instanceof String && !((String) id).trim().isEmpty() ? ((String) id).trim() : null;
        follow(wanted);
        pickVariant();
    }

    /** Watch a Home Assistant entity for the holiday, or stop watching with null. */
    private void follow(String wanted) {
        if (host == null || (wanted == null ? entity == null : wanted.equals(entity))) return;
        try {
            if (entity != null) host.unsubscribe("ha.entity." + entity);
            entity = wanted;
            entityHoliday = "";
            if (wanted == null) return;
            host.subscribe("ha.entity." + wanted);
            int current = generation;
            host.executeCommand("getHaEntityState", Collections.<String, Object>singletonMap("entityId", wanted), (ok, data, error) -> {
                synchronized (SkinController.this) {
                    if (current == generation && ok && data instanceof Map && wanted.equals(entity)) holidayFrom((Map<?, ?>) data);
                }
            });
        } catch (IllegalArgumentException | UnsupportedOperationException unavailable) {
            host.status("Can't read " + wanted + " from Home Assistant.", true);
        }
    }

    /** The holiday from an entity state, whether it arrives as {state} or {newState: {state}}. */
    private void holidayFrom(Map<?, ?> data) {
        Object value = data.get("state");
        if (value instanceof Map) value = ((Map<?, ?>) value).get("state");
        if (!(value instanceof String) && data.get("newState") instanceof Map) value = ((Map<?, ?>) data.get("newState")).get("state");
        if (value instanceof String) {
            entityHoliday = (String) value;
            pickVariant();
        }
    }

    private void pickVariant() {
        Assets loaded = assets;
        String holiday = FOLLOW.equals(holidaySetting) ? entityHoliday : holidaySetting;
        variant = Variant.of(holiday, loaded == null ? Collections.<String>emptyList() : loaded.leaves);
    }

    synchronized void onEvent(String event, Map<String, Object> payload) {
        if (host == null) return;
        if ("ks.voice.skin".equals(event)) {
            selected = Boolean.TRUE.equals(payload.get("selected"));
            skinHeard = true;
        } else if ("ks.voice.overlay".equals(event)) {
            overlay = String.valueOf(payload.get("overlay"));
        } else if ("ks.voice.level".equals(event)) {
            Object value = payload.get("level");
            level = value instanceof Number ? ((Number) value).floatValue() : 0;
            return;
        } else if ("ks.voice.state".equals(event)) {
            Object value = payload.get("state");
            state = value instanceof String ? (String) value : "idle";
            return;
        } else if ("ks.voice.text".equals(event)) {
            words(payload);
            return;
        } else if ("ks.voice.appearance".equals(event)) {
            appearanceHeard = true;
            appearance(payload);
            return;
        } else if ("ks.wakeword.state".equals(event)) {
            muted = Boolean.TRUE.equals(payload.get("muted"));
            return;
        } else if (entity != null && event.equals("ks.ha.entity." + entity)) {
            holidayFrom(payload);
            return;
        } else {
            return;
        }
        update();
    }

    /** Which part of the turn the face is in: listening, thinking, answering or resting. */
    String phase() {
        String now = state;
        if ("listening".equals(now)) return "listening";
        if ("processing".equals(now)) return "thinking";
        if ("responding".equals(now)) return "answering";
        // No voice.state, or the answer stays up after it is spoken: guess from the words.
        if (!command.isEmpty() && answer.isEmpty() && tool.isEmpty()) return "thinking";
        return "resting";
    }

    private void appearance(Map<?, ?> settings) {
        background = settings.get("background") instanceof Number ? ((Number) settings.get("background")).floatValue() : null;
        textScale = settings.get("textScale") instanceof Number ? ((Number) settings.get("textScale")).floatValue() : 1;
        reactive = !Boolean.FALSE.equals(settings.get("reactive"));
        restyleLater();
    }

    private void words(Map<?, ?> text) {
        command = text.get("command") instanceof String ? (String) text.get("command") : "";
        answer = text.get("answer") instanceof String ? (String) text.get("answer") : "";
        Object tools = text.get("tools");
        tool = tools instanceof List && !((List<?>) tools).isEmpty() ? String.valueOf(((List<?>) tools).get(((List<?>) tools).size() - 1)) : "";
        SkinView current = view;
        if (current != null) current.post(current::refresh);
    }

    private void restyleLater() {
        SkinView current = view;
        if (current != null) current.post(current::restyle);
    }

    /** Shows the skin while it is picked and the overlay is up, in the look that matches it. */
    private void update() {
        String want = selected && ("full".equals(overlay) || "docked".equals(overlay)) ? overlay : null;
        if (want == null ? drawn == null : want.equals(drawn)) return;
        drawn = want;
        if (want == null) {
            host.hideOverlay(KEY);
            level = 0;
        } else if ("full".equals(want)) {
            host.showOverlay(KEY, animated(OverlaySpec.fullScreen().onTop(true).touchable(false).closeOnBack(false), OverlaySpec.FADE, 300), new Skin(false));
        } else {
            // A bubble at the bottom. Touches reach the dashboard around and under it.
            host.showOverlay(KEY, animated(OverlaySpec.at(OverlaySpec.BOTTOM).inset(DOCK_INSET).onTop(true).touchable(false).closeOnBack(false), OverlaySpec.SLIDE, 250), new Skin(true));
        }
    }

    /** Transitions, or none on Kiosk Satellite versions without them. */
    private static OverlaySpec animated(OverlaySpec spec, String transition, int ms) {
        try {
            return spec.enter(transition).exit(transition).transitionDuration(ms);
        } catch (LinkageError olderHost) {
            return spec;
        }
    }

    synchronized void stop() {
        host = null;
        generation++;
        selected = false;
        skinHeard = false;
        appearanceHeard = false;
        overlay = "hidden";
        drawn = null;
        entity = null;
        entityHoliday = "";
        level = 0;
        state = "idle";
        muted = false;
        command = answer = tool = "";
        background = null;
        textScale = 1;
        reactive = true;
        autoDelay = 0;
        assets = null;
        loading = null;
    }

    /** Auto frame rate: one step slower when the frames that come back are well behind what was asked for. */
    private synchronized void slowDown(int from, float averageMs) {
        if (autoDelay != from || from >= 50) return;
        autoDelay = from == 0 ? 33 : 50;
        if (host != null) host.log(String.format(java.util.Locale.ROOT, "Frames every %.0f ms; Auto frame rate drops to %d fps.", averageMs, 1000 / autoDelay));
    }

    private final class Skin implements OverlayFactory {
        private final boolean docked;

        Skin(boolean docked) { this.docked = docked; }

        @Override public View create(Context context, KsTheme theme) {
            SkinView skin = new SkinView(context, theme, docked);
            view = skin;
            return skin;
        }

        @Override public void onThemeChanged(View view, KsTheme theme) {
            ((SkinView) view).restyle(theme);
        }

        @Override public void onDestroy(View destroyed) {
            if (view == destroyed) view = null;
        }
    }

    /** Full screen: the robot on black and the conversation under it. Docked: a card with a small robot beside the words. */
    private final class SkinView extends FrameLayout {
        private final boolean docked;
        private final RobotView robot;
        private final TextView said;
        private final TextView answered;
        private final LinearLayout card;
        private KsTheme ksTheme;

        SkinView(Context context, KsTheme theme, boolean docked) {
            super(context);
            this.ksTheme = theme;
            this.docked = docked;
            robot = new RobotView(context);
            said = new TextView(context);
            answered = new TextView(context);
            setClipChildren(false);
            setClipToPadding(false);
            int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
            if (docked) {
                card = new LinearLayout(context);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                int faceSize = theme.px(88);
                card.addView(robot, new LinearLayout.LayoutParams(faceSize, faceSize));
                LinearLayout words = new LinearLayout(context);
                words.setOrientation(LinearLayout.VERTICAL);
                answered.setMaxLines(3);
                answered.setEllipsize(TextUtils.TruncateAt.END);
                words.addView(said);
                words.addView(answered);
                LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
                wordsParams.leftMargin = theme.px(12);
                card.addView(words, wordsParams);
                int width = Math.min(theme.px(560), screenWidth - theme.px(48));
                addView(card, new FrameLayout.LayoutParams(width, LayoutParams.WRAP_CONTENT));
            } else {
                card = null;
                int side = theme.px(48);
                // Wide screens put the words beside the robot so it can take the full height; tall ones stack them.
                boolean wide = screenWidth > screenHeight * 1.2f;
                int size = wide ? Math.round(Math.min(screenHeight * 0.96f, screenWidth * 0.58f))
                        : Math.round(Math.min(screenWidth * 0.96f, screenHeight * 0.6f));
                LinearLayout words = new LinearLayout(context);
                words.setOrientation(LinearLayout.VERTICAL);
                words.setGravity(wide ? Gravity.CENTER_VERTICAL : Gravity.CENTER_HORIZONTAL);
                said.setGravity(wide ? Gravity.START : Gravity.CENTER);
                answered.setGravity(wide ? Gravity.START : Gravity.CENTER);
                words.addView(said, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                LinearLayout.LayoutParams answerLine = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                answerLine.topMargin = theme.px(8);
                words.addView(answered, answerLine);
                LinearLayout layout = new LinearLayout(context);
                layout.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
                layout.setGravity(Gravity.CENTER);
                layout.addView(robot, new LinearLayout.LayoutParams(size, size));
                if (wide) {
                    LinearLayout.LayoutParams beside = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
                    beside.leftMargin = theme.px(16);
                    layout.addView(words, beside);
                    layout.setPadding(0, 0, side, 0);
                } else {
                    LinearLayout.LayoutParams below = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    below.topMargin = theme.px(8);
                    layout.addView(words, below);
                    layout.setPadding(side, 0, side, 0);
                }
                addView(layout, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            }
            restyle();
            refresh();
        }

        void restyle(KsTheme next) {
            ksTheme = next;
            restyle();
        }

        void restyle() {
            // The glowing face and lights need the dark, whatever the theme.
            float scale = textScale;
            if (docked) {
                GradientDrawable bubble = new GradientDrawable();
                bubble.setColor(0xFF000000);
                bubble.setCornerRadius(ksTheme.px(ksTheme.radiusCard()));
                bubble.setStroke(Math.max(1, ksTheme.px(1) / 2), 0x665EF2FF);
                card.setBackground(bubble);
                int pad = ksTheme.px(10);
                card.setPadding(pad, pad, ksTheme.px(20), pad);
                ksTheme.styleText(said, 14 * scale, 400, "onSurfaceVariant");
                ksTheme.styleText(answered, 17 * scale, 500, "onSurface");
            } else {
                Float chosen = background;
                float opacity = chosen != null ? chosen : 1f;
                setBackgroundColor(Color.argb(Math.round(255 * opacity), 0, 0, 0));
                ksTheme.styleText(said, 20 * scale, 400, "onSurfaceVariant");
                ksTheme.styleText(answered, 28 * scale, 500, "onSurface");
            }
            said.setTextColor(0xFFB9C2CC);
            answered.setTextColor(0xFFF2F5F8);
            robot.invalidate();
        }

        void refresh() {
            said.setText(command);
            said.setVisibility(command.isEmpty() ? GONE : VISIBLE);
            answered.setText(answer.isEmpty() ? tool : answer);
            answered.setVisibility(answer.isEmpty() && tool.isEmpty() ? GONE : VISIBLE);
        }

        /** The robot, redrawn every frame, at a set rate, or as fast as Auto finds the device keeps up. */
        private final class RobotView extends View {
            private Drawer drawer;
            private Assets drawnWith;
            // Auto frame rate: gaps between frames, measured after the first few at each rate
            private long lastFrame;
            private int watchedDelay = -1;
            private int seen;
            private int frames;
            private long gaps;

            RobotView(Context context) {
                super(context);
            }

            @Override protected void onDraw(Canvas canvas) {
                Assets loaded = assets;
                if (loaded != null) {
                    if (loaded != drawnWith) {
                        drawer = new Drawer(loaded);
                        drawnWith = loaded;
                    }
                    drawer.darkness(darkness);
                    // With Reactive activity bar off nothing follows the voice. KS sends 0 then too.
                    drawer.draw(canvas, getWidth(), getHeight(), reactive ? level : 0, phase(), muted, variant, SystemClock.uptimeMillis());
                }
                int delay = fixedDelay >= 0 ? fixedDelay : autoDelay;
                if (fixedDelay < 0 && loaded != null) watch(delay);
                if (delay > 0) postInvalidateDelayed(delay);
                else postInvalidateOnAnimation();
            }

            /**
             * Every frame should come about 17 ms apart, and 33 ms asks for 33 to 50 once it waits for the screen.
             * Averaging well past that over 60 frames means the device can't keep up, so ask for fewer.
             */
            private void watch(int delay) {
                long now = SystemClock.uptimeMillis();
                long gap = now - lastFrame;
                lastFrame = now;
                if (delay != watchedDelay) {
                    watchedDelay = delay;
                    seen = frames = 0;
                    gaps = 0;
                }
                // Skip the fade in and any frame after a pause
                if (++seen <= 15 || gap > 500) return;
                gaps += gap;
                if (++frames < 60) return;
                float average = gaps / (float) frames;
                frames = 0;
                gaps = 0;
                if (average > (delay == 0 ? 22 : 70)) slowDown(delay, average);
            }
        }
    }
}
