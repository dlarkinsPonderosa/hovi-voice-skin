// SPDX-License-Identifier: Apache-2.0
package ca.ponderosa.hovi;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Draws the robot each frame and keeps its pose: where it looks, when it blinks, how bright each light is. A port
 * of the browser preview (robot-skin/robot_preview.html), so tune the look there first and copy the numbers here.
 * Everything is in the 1024 pixel space of the source render.
 */
final class Drawer {
    static final String[] LIGHTS = {"ear_l", "ear_r", "chest_slot", "chest_ring", "hover", "arm_l", "arm_r", "badge", "fingers_l", "fingers_r", "tail_1", "tail_2", "tail_3"};
    private static final float[] RING_CENTER = {496, 682};
    private static final int MUTED = Color.parseColor("#ff5a5a");
    private static final String[] SEGMENTS = {"abcdef", "bc", "abdeg", "abcdg", "bcfg", "acdfg", "acdefg", "abc", "abcdefg", "abcdfg"};
    /** How deep each half of the cat's "w" curves: a third of a half circle, so it reads calm rather than a grin. */
    private static final float MOUTH_DEPTH = 9;
    /** The mouth opens by 1.5 times the voice level, as the pumpkin's does: real speech rarely reaches full level. */
    private static final float GAIN = 1.5f;

    private final Assets a;
    private final Random random = new Random();
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    /** The robot's own shell, darkened by the Darkness setting. */
    private final Paint metal = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private float darkness;
    private final Paint tint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint screenPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plain = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** A thin outline with no glow. */
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint floor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Integer, PorterDuffColorFilter> filters = new HashMap<>();
    private final Path path = new Path();
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private final float[] lights = new float[LIGHTS.length];
    private final float[] target = new float[LIGHTS.length];

    private float last = -1;
    private float shown;
    /** When the mouth last moved, so the answering smile waits for a real pause and not a gap between words. */
    private float loudAt;
    private float phon = 0.5f;
    private float gazeX, gazeY, gazeTX, gazeTY, nextGaze;
    private float blinkStart = -1, nextBlink = 1.5f, happyUntil, nextHappy = 6;
    private float tilt, eyeScale = 1, armLift, whisk, whiskTip, pupil = 7;
    /** How high the cat's lids sit: how much of each eye above its middle shows, as a share of its half height. */
    private float lid = 0.35f;
    private float sweepAt = -10, flickAt = -10, shimmerAt = 4;
    // Fireworks (New Year's, Independence Day): sparks are added onto what's behind them, like light; the big burst
    // that answers a loud word
    private final Paint spark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flake = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint snow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float bigAt = -100, bigX;
    private int bigSeed;
    private String state = "idle";

    Drawer(Assets assets) {
        this.a = assets;
        for (int i = 0; i < lights.length; i++) lights[i] = 0.25f;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        screenPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        spark.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        spark.setStrokeCap(Paint.Cap.ROUND);
    }

    /**
     * Darker shell, 0 to 1: each channel becomes c * (1 - 0.25d) - 0.18d, so highlights stay bright while the rest
     * drops. The same curve as the preview's Darkness slider.
     */
    void darkness(float d) {
        if (d == darkness) return;
        darkness = d;
        if (d <= 0) {
            metal.setColorFilter(null);
            return;
        }
        float k = 1 - 0.25f * d, off = -0.18f * d * 255;
        metal.setColorFilter(new ColorMatrixColorFilter(new float[] {
            k, 0, 0, 0, off,
            0, k, 0, 0, off,
            0, 0, k, 0, off,
            0, 0, 0, 1, 0,
        }));
    }

    /**
     * One frame into a w by h area. phase is resting, listening, thinking or answering, from the Voice Satellite
     * state. level is the voice level, 0 to 1.
     */
    void draw(Canvas c, int w, int h, float level, String phase, boolean muted, Variant v, long nowMs) {
        float now = nowMs / 1000f;
        float dt = last < 0 ? 0 : Math.min(0.05f, now - last);
        last = now;
        update(now, dt, level, phase, muted, v);

        float s = Math.min(w, h) / 1024f;
        c.save();
        c.translate((w - 1024 * s) / 2, (h - 1024 * s) / 2);
        c.scale(s, s);
        // Just enough margin that bobbing hats, props and the floor glow stay inside, centred top to bottom
        c.translate(512, 512);
        c.scale(0.95f, 0.95f);
        c.translate(-512, -457);
        float px = s * 0.95f;
        float bob = bobAt(now);

        // Falling leaves drift down behind the robot and start again at the top
        for (int i = 0; i < v.falling.size(); i++) {
            Assets.Part p = a.props.get(v.falling.get(i));
            float speed = 16 + (i % 4) * 7;
            float y = ((p.y + 120 + now * speed) % 1200) - 120;
            c.save();
            around(c, p.x + p.w / 2, p.y + p.h / 2, (float) Math.sin(now * 0.7 + i * 1.7) * 0.6f, (float) Math.sin(now * 0.8 + i) * 24, y - p.y);
            c.drawBitmap(p.bitmap, p.x, p.y, image);
            c.restore();
        }
        if (v.snow) drawSnow(c, now);
        if (v.sparks != null) drawFireworks(c, now, v.sparks);
        if (v.confetti != null) drawConfetti(c, now, v.confetti);

        // Glow cast below the hover ring
        int hoverColor = lightColor("hover", v, now);
        float glowAlpha = Math.max(0, Math.min(1, 0.18f * lights[index("hover")] * (1 - bob / 40)));
        floor.setShader(new RadialGradient(500, 965, 190, withAlpha(hoverColor, glowAlpha), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        c.save();
        c.scale(1, 0.18f, 500, 965);
        c.drawCircle(500, 965, 190, floor);
        c.restore();

        // Holiday props float beside the robot, each on its own slower bob
        for (int i = 0; i < v.floats.length; i++) {
            String name = v.floats[i];
            Assets.Part p = a.props.get(name);
            if (p == null) continue;
            c.save();
            around(c, p.x + p.w / 2, p.y + p.h / 2, (float) Math.sin(now * 0.9 + i * 2.4) * 0.08f, 0, (float) Math.sin(now * 1.1 + 2 + i * 1.7) * 16);
            c.drawBitmap(p.bitmap, p.x, p.y, image);
            if (p.screen != null) drawYear(c, p.screen, lightColor("chest_ring", v, now), px);
            if (name.startsWith("pumpkin")) {
                // Candle flicker in the carved face
                screenPaint.setAlpha(Math.round(255 * (0.12f + 0.12f * random.nextFloat())));
                c.drawBitmap(p.bitmap, p.x, p.y, screenPaint);
            }
            c.restore();
        }

        // The tail floats on its own, a beat behind the body (the plain robot has none)
        if (a.hasTail()) {
            float[] tp = a.pivots.get("tail");
            // The shake when listening starts, 2.4 s: full wags for 0.8 s, then 1.6 s of smaller and quicker ones
            // (18 up to 50 a second) until it ends in a vibrate
            float ft = now - flickAt, fu = Math.max(0, ft - 0.8f);
            float shake = ft < 0 || ft > 2.4f ? 0 : ft < 0.4f ? (float) Math.sin(Math.PI / 2 * ft / 0.4f) : ft < 0.8f ? 1 : (float) Math.pow(1 - fu / 1.6f, 1.5);
            float flick = shake * (float) Math.sin(18 * ft + 10 * fu * fu) * 0.12f;
            float curl = "thinking".equals(state) ? -0.1f : 0;
            c.save();
            around(c, tp[0], tp[1], (float) Math.sin(now * 1.3) * 0.07f + flick + curl, 0, bobAt(now - 0.35f));
            drawLayer(c, "tail", v, now, px);
            c.restore();
        }

        c.save();
        c.translate(0, bob);
        drawLayer(c, "body", v, now, px);
        float sway = (float) Math.sin(now * 1.6 + 1) * 0.025f;
        float[] al = a.pivots.get("arm_l");
        c.save();
        around(c, al[0], al[1], sway + armLift, 0, 0);
        drawLayer(c, "arm_l", v, now, px);
        c.restore();
        float[] ar = a.pivots.get("arm_r");
        c.save();
        around(c, ar[0], ar[1], -sway - armLift, 0, 0);
        drawLayer(c, "arm_r", v, now, px);
        c.restore();

        // Head, with a slight sideways shift toward where it looks
        float[] hp = a.pivots.get("head");
        c.save();
        around(c, hp[0], hp[1], tilt, gazeX * 6, gazeY * 3);
        drawLayer(c, "head", v, now, px);
        drawFace(c, v, now, px);
        if (v.hat != null) drawHat(c, v.hat, now);
        c.restore();
        c.restore();

        c.restore();
    }

    // ---- Pose ----

    private void update(float now, float dt, float level, String phase, boolean muted, Variant v) {
        String next;
        if ("listening".equals(phase)) next = "listening";
        else if ("thinking".equals(phase)) next = "thinking";
        else if ("answering".equals(phase)) next = "answering";
        else next = muted ? "muted" : "idle";
        if ("listening".equals(next) && !"listening".equals(state)) {
            sweepAt = now;
            flickAt = now;
        }
        state = next;

        float before = shown;
        shown += (level - shown) * Math.min(1, dt * 18);
        if (shown >= 0.05f) loudAt = now;
        // A new mouth shape at the start of each syllable
        if (before * GAIN < 0.12f && shown * GAIN >= 0.12f) phon = random.nextFloat();

        if (now > nextGaze) {
            if ("idle".equals(state)) {
                if (random.nextFloat() < 0.35f) { gazeTX = 0; gazeTY = 0; }
                else { gazeTX = random.nextFloat() * 2 - 1; gazeTY = random.nextFloat() * 1.2f - 0.6f; }
            } else if ("thinking".equals(state)) {
                gazeTX = 0.75f + random.nextFloat() * 0.25f;
                gazeTY = -0.85f;
            } else if ("muted".equals(state)) {
                gazeTX = (random.nextFloat() - 0.5f) * 0.3f;
                gazeTY = 0.75f;
            } else {
                gazeTX = (random.nextFloat() - 0.5f) * 0.4f;
                gazeTY = (random.nextFloat() - 0.5f) * 0.2f;
            }
            nextGaze = now + ("idle".equals(state) ? 1.5f + random.nextFloat() * 2.5f : 0.8f + random.nextFloat());
        }
        float gk = Math.min(1, dt * 8);
        gazeX += (gazeTX - gazeX) * gk;
        gazeY += (gazeTY - gazeY) * gk;

        if (now > nextBlink) { blinkStart = now; nextBlink = now + 2.5f + random.nextFloat() * 3.5f; }
        if ("idle".equals(state) && now > nextHappy) { happyUntil = now + 1.6f; nextHappy = now + 7 + random.nextFloat() * 6; }
        if (now > shimmerAt) shimmerAt = now + 6 + random.nextFloat() * 5;

        float tTilt = 0, tEye = 1, tLift = 0;
        switch (state) {
            case "listening": tTilt = -0.06f; tEye = 1.12f; tLift = 0.04f; break;
            case "thinking": tTilt = 0.08f; tEye = 0.92f; break;
            case "answering": tTilt = (float) Math.sin(now * 1.3) * 0.03f; tLift = shown * 0.06f; break;
            case "muted": tTilt = 0.03f; tLift = -0.02f; break;
            default: break;
        }
        float k = Math.min(1, dt * 6);
        tilt += (tTilt - tilt) * k;
        eyeScale += (tEye - eyeScale) * k;
        armLift += (tLift - armLift) * k;
        // Whiskers follow the mouth: roots quickly, tips a beat behind, so they flex instead of moving as a block
        float whiskTarget = "answering".equals(state) ? Math.min(1, shown * GAIN) :"listening".equals(state) ? 0.18f : 0;
        whisk += (whiskTarget - whisk) * Math.min(1, dt * 12);
        whiskTip += (whisk - whiskTip) * Math.min(1, dt * 7);
        // Pupils: wide and interested while listening, narrow while thinking, a little wider with the voice
        float pupilTarget;
        switch (state) {
            case "listening": pupilTarget = 13; break;
            case "thinking": pupilTarget = 4; break;
            case "answering": pupilTarget = 7.5f + 3 * Math.min(1, shown * GAIN); break;
            case "muted": pupilTarget = 5; break;
            default: pupilTarget = 7; break;
        }
        pupil += (pupilTarget - pupil) * Math.min(1, dt * 5);
        // The cat's lids: highest listening, lifting a little on loud words, lowest muted
        float lidTarget;
        switch (state) {
            case "listening": lidTarget = 0.5f; break;
            case "thinking": lidTarget = 0.15f; break;
            case "muted": lidTarget = 0; break;
            case "answering": lidTarget = 0.35f + 0.2f * Math.min(1, shown * GAIN); break;
            default: lidTarget = 0.35f; break;
        }
        lid += (lidTarget - lid) * Math.min(1, dt * 6);

        // Lights
        float breathe = 0.5f + 0.5f * (float) Math.sin(now * 1.6);
        switch (state) {
            case "listening": {
                // The ears pulse from dim to full; the wake sweep's flash down the arms keeps repeating, once a
                // second, running on into the finger bands
                float p = 0.35f + 0.65f * (float) Math.pow(0.5 + 0.5 * Math.sin(now * 6), 2);
                set("ear_l", p); set("ear_r", p);
                set("chest_slot", 0.4f); set("chest_ring", 0.4f); set("hover", 0.45f);
                float ph = ((now - sweepAt - 0.3f) % 1 + 1) % 1;
                float arm = 0.3f + 0.7f * bump(ph, 0, 0.3f), hand = 0.3f + 0.7f * bump(ph, 0.12f, 0.42f);
                set("arm_l", arm); set("arm_r", arm);
                set("fingers_l", hand); set("fingers_r", hand);
                set("tail_1", 0.35f); set("tail_2", 0.35f); set("tail_3", 0.35f);
                break;
            }
            case "thinking": {
                set("ear_l", 0.45f); set("ear_r", 0.45f); set("chest_slot", 0.35f);
                // The ring spins; an emblem in its place pulses instead
                set("chest_ring", v.chest != null && a.props.containsKey("chestlight_" + v.chest) ? 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(now * 5)) : 1);
                set("arm_l", 0.25f + 0.6f * Math.max(0, (float) Math.sin(now * 4)));
                set("arm_r", 0.25f + 0.6f * Math.max(0, -(float) Math.sin(now * 4)));
                // A pulse that starts at the hover ring and runs up the tail
                String[] run = {"hover", "tail_3", "tail_2", "tail_1"};
                for (int i = 0; i < run.length; i++) set(run[i], 0.15f + 0.85f * (float) Math.pow(Math.max(0, Math.sin(now * 5 - i)), 3));
                break;
            }
            case "answering": {
                // Near dark between syllables, full on at the peaks, so the speech reads clearly
                float sv = Math.min(1, shown * 1.35f);
                set("ear_l", 0.5f); set("ear_r", 0.5f);
                set("chest_slot", 0.06f + 0.94f * sv); set("chest_ring", 0.06f + 0.94f * sv); set("hover", 0.12f + 0.88f * sv);
                set("arm_l", 0.3f); set("arm_r", 0.3f);
                set("tail_1", 0.35f); set("tail_2", 0.35f); set("tail_3", 0.35f);
                break;
            }
            case "muted":
                for (int i = 0; i < target.length; i++) target[i] = 0.12f;
                set("ear_l", 0.5f); set("ear_r", 0.5f);
                break;
            default: {
                set("ear_l", 0.25f); set("ear_r", 0.25f); set("chest_slot", 0.25f); set("chest_ring", 0.25f);
                set("hover", 0.3f + 0.25f * breathe); set("arm_l", 0.2f); set("arm_r", 0.2f);
                float sh = now - (shimmerAt - 6);
                String[] up = {"tail_3", "tail_2", "tail_1"};
                for (int i = 0; i < up.length; i++) set(up[i], 0.2f + 0.7f * bump(sh, i * 0.12f, i * 0.12f + 0.35f));
                break;
            }
        }
        // Wake sweep up the body: hover, chest, arms, ears
        float sw = now - sweepAt;
        if (sw < 0.9f) {
            add("hover", bump(sw, 0, 0.3f)); add("chest_ring", bump(sw, 0.12f, 0.42f)); add("chest_slot", bump(sw, 0.2f, 0.5f));
            add("arm_l", bump(sw, 0.3f, 0.6f)); add("arm_r", bump(sw, 0.3f, 0.6f));
            add("ear_l", bump(sw, 0.45f, 0.85f)); add("ear_r", bump(sw, 0.45f, 0.85f));
        }
        // The pine badge on the left forearm keeps time with that arm's light, and the robot's finger bands with
        // their arm's
        set("badge", target[index("arm_l")]);
        if (!"listening".equals(state)) {
            set("fingers_l", target[index("arm_l")]);
            set("fingers_r", target[index("arm_r")]);
        }
        if (v.calm) for (int i = 0; i < target.length; i++) target[i] *= 0.6f;
        float lk = Math.min(1, dt * 14);
        for (int i = 0; i < lights.length; i++) lights[i] += (target[i] - lights[i]) * lk;
    }

    private void set(String name, float value) { target[index(name)] = value; }

    private void add(String name, float value) { int i = index(name); target[i] = Math.min(1, target[i] + value); }

    private static int index(String name) {
        for (int i = 0; i < LIGHTS.length; i++) if (LIGHTS[i].equals(name)) return i;
        throw new IllegalArgumentException(name);
    }

    // ---- Layers and lights ----

    private void drawLayer(Canvas c, String name, Variant v, float now, float px) {
        Assets.Part layer = a.layers.get(name);
        c.drawBitmap(layer.bitmap, layer.x, layer.y, metal);
        if ("body".equals(name) && v.chest != null) {
            Assets.Part glass = a.props.get("chest_" + v.chest);
            if (glass != null) c.drawBitmap(glass.bitmap, glass.x, glass.y, metal);
        }
        // A string of lights wrapped round the tail, over the base's own tail
        if ("tail".equals(name) && v.tail != null) {
            Assets.Part wrap = a.props.get(v.tail);
            if (wrap != null) c.drawBitmap(wrap.bitmap, wrap.x, wrap.y, image);
        }
        drawLights(c, name, v, now);
        if ("tail".equals(name)) {
            // String lights twinkle in three groups, out of step
            for (int i = 0; i < v.bulbs.length; i++) {
                Assets.Part b = a.props.get(v.bulbs[i]);
                if (b == null) continue;
                screenPaint.setAlpha(Math.round(255 * 0.7f * (float) Math.pow(Math.max(0, Math.sin(now * 2.3 + i * 2.1)), 2)));
                c.drawBitmap(b.bitmap, b.x, b.y, screenPaint);
            }
        }
        // Things pinned to this part, drawn over its lights so they move with it
        String[] pinned = v.wear.get(name);
        if (pinned != null) {
            for (String w : pinned) {
                Assets.Part p = a.props.get(w);
                if (p != null) c.drawBitmap(p.bitmap, p.x, p.y, image);
            }
        }
    }

    private void drawLights(Canvas c, String parent, Variant v, float now) {
        for (int i = 0; i < LIGHTS.length; i++) {
            String name = LIGHTS[i];
            Assets.Part p = a.lights.get(name);
            if (p == null || !parent.equals(p.parent)) continue;
            float b = lights[i];
            if (b < 0.02f) continue;
            boolean emblem = "chest_ring".equals(name) && v.chest != null && a.props.containsKey("chestlight_" + v.chest);
            if (emblem) p = a.props.get("chestlight_" + v.chest);
            tint.setColorFilter(filter(lightColor(name, v, now)));
            // Listening, the ears' glow is twice as strong, so their pulse reads from across the room
            float boost = "listening".equals(state) && name.startsWith("ear") ? 2 : 1;
            if ("chest_ring".equals(name) && !emblem && "thinking".equals(state)) {
                // A bright segment chases round the ring over a dim one
                drawLight(c, p, 0.12f, 1);
                float start = (float) Math.toDegrees(now * 5);
                c.save();
                wedge(start, 110);
                c.clipPath(clip);
                drawLight(c, p, b, 1);
                c.restore();
            } else {
                drawLight(c, p, b, boost);
            }
        }
    }

    /** First the strip itself in its true colour over the dark glass, then its soft glow. */
    private void drawLight(Canvas c, Assets.Part p, float b, float glowBoost) {
        tint.setAlpha(Math.round(255 * Math.min(1, b * 1.15f)));
        c.drawBitmap(p.bitmap, p.x, p.y, tint);
        if (p.glow != null) {
            tint.setAlpha(Math.round(255 * Math.min(1, b * 0.6f * glowBoost)));
            c.drawBitmap(p.glow, p.x - Assets.GLOW_PAD, p.y - Assets.GLOW_PAD, tint);
        }
    }

    private void wedge(float startDegrees, float sweep) {
        clip.reset();
        clip.moveTo(RING_CENTER[0], RING_CENTER[1]);
        rect.set(RING_CENTER[0] - 90, RING_CENTER[1] - 90, RING_CENTER[0] + 90, RING_CENTER[1] + 90);
        clip.arcTo(rect, startDegrees, sweep);
        clip.close();
    }

    private int lightColor(String name, Variant v, float now) {
        if ("muted".equals(state) && ("ear_l".equals(name) || "ear_r".equals(name))) return MUTED;
        return v.light("badge".equals(name) || "fingers_l".equals(name) ? "arm_l" : "fingers_r".equals(name) ? "arm_r" : name, now);
    }

    private PorterDuffColorFilter filter(int color) {
        PorterDuffColorFilter f = filters.get(color);
        if (f == null) {
            f = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
            filters.put(color, f);
        }
        return f;
    }

    private void drawHat(Canvas c, String name, float now) {
        Assets.Part h = a.props.get(name);
        if (h == null) return;
        c.save();
        if ("bat".equals(name)) {
            // Every few seconds the bat gives its wings a quick double flutter
            float t = now % 4.5f;
            float flap = t < 0.5f ? Math.abs((float) Math.sin(t * Math.PI * 4)) * 0.14f : 0;
            c.scale(1 - flap, 1 + flap * 0.3f, h.x + h.w / 2, h.y + h.h);
        }
        c.drawBitmap(h.bitmap, h.x, h.y, image);
        c.restore();
    }

    // ---- The face on the screen ----

    private void drawFace(Canvas c, Variant v, float now, float px) {
        float[] sc = a.screen;
        float cx = sc[0] + sc[2] / 2, cy = sc[1] + sc[3] / 2;
        int color = v.face;
        c.save();
        clip.reset();
        rect.set(sc[0] + 8, sc[1] + 8, sc[0] + sc[2] - 8, sc[1] + sc[3] - 8);
        clip.addRoundRect(rect, 130, 130, Path.Direction.CW);
        c.clipPath(clip);
        fill.setColor(color);
        stroke.setColor(color);
        float glow = 24 * px;
        fill.setShadowLayer(glow, 0, 0, color);
        stroke.setShadowLayer(glow, 0, 0, color);

        float gx = gazeX * 26, gy = gazeY * 14;
        float bf = blinkFactor(now);
        boolean happy = !v.calm && (("idle".equals(state) && now < happyUntil)
            || ("answering".equals(state) && now - loudAt > 0.5f && Math.sin(now * 0.9) > 0.55));

        // Eyes, in the holiday's outline: the pumpkin's triangles at Halloween (round and wide while listening) and
        // hearts at Valentine's (round while muted)
        int kind = v.pumpkinFace && !"listening".equals(state) ? TRI : v.heartEyes && !"muted".equals(state) ? HEART : OVAL;
        for (int side = -1; side <= 1; side += 2) {
            float x = cx + side * 112 + gx, y = cy - 50 + gy;
            if (a.catFace) {
                catEye(c, x, y, color, bf, now, side, kind, px);
            } else if (happy && kind == OVAL) {
                // The robot's contented closed-eye smile; the cat's lidded eyes have none, since the skin only
                // shows after a wake word and so is seldom idle
                stroke.setStrokeWidth(13);
                rect.set(x - 32, y + 14 - 32, x + 32, y + 14 + 32);
                c.drawArc(rect, 201.6f, 136.8f, false, stroke);
            } else {
                robotEye(c, x, y, color, bf, now, kind);
            }
        }

        if (v.rosyCheeks) {
            plain.setColor(Color.argb(89, 255, 110, 140));
            for (int side = -1; side <= 1; side += 2) {
                float x = cx + side * 160 + gx, y = cy + 2 + gy;
                rect.set(x - 28, y - 16, x + 28, y + 16);
                c.drawOval(rect, plain);
            }
        }

        // Nose
        // The nose, mouth and whiskers hang from ny; 40 below the centre leaves the eyes some room
        float nx = cx + gx * 0.6f, ny = cy + 40 + gy * 0.6f;
        if (a.catFace) {
            // A lit nose: rounded, brighter at the top, with a glint
            path.reset();
            path.moveTo(nx - 15, ny - 9);
            path.quadTo(nx, ny - 13, nx + 15, ny - 9);
            path.quadTo(nx + 22, ny - 7, nx + 15, ny - 1);
            path.quadTo(nx + 4, ny + 10, nx, ny + 10);
            path.quadTo(nx - 4, ny + 10, nx - 15, ny - 1);
            path.quadTo(nx - 22, ny - 7, nx - 15, ny - 9);
            path.close();
            fill.setShader(new LinearGradient(0, ny - 12, 0, ny + 10, whiter(color, 0.6f), color, Shader.TileMode.CLAMP));
            c.drawPath(path, fill);
            fill.setShader(null);
            plain.setColor(Color.argb(204, 255, 255, 255));
            c.save();
            c.rotate(-11.5f, nx - 6, ny - 6);
            rect.set(nx - 11, ny - 8.5f, nx - 1, ny - 3.5f);
            c.drawOval(rect, plain);
            c.restore();
        }

        // Mouth
        float my = ny + 16;
        stroke.setStrokeWidth(9);
        if (v.pumpkinFace && !("answering".equals(state) && shown > 0.08f)) {
            path.reset();
            for (int i = 0; i <= 8; i++) {
                float x = nx - 64 + i * 16, y = my + 12 + (i % 2 == 1 ? 12 : -2);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            c.drawPath(path, stroke);
        } else if ("thinking".equals(state)) {
            // A sideways "hmm"
            c.drawLine(nx - 8, my + 16, nx + 30, my + 9, stroke);
        } else if (!a.catFace) {
            robotMouth(c, nx, my + 14, color, now, happy);
        } else {
            catMouth(c, nx, my, color, now);
        }

        // Whiskers, kept below the eyes and fanning out slightly. As the mouth opens the cheeks push them out and
        // down a little and they spread apart, the tips most
        if (a.catFace) {
            for (int side = -1; side <= 1; side += 2) {
                for (int i = -1; i <= 1; i++) {
                    fibreWhisker(c, nx + side * (92 + 6 * whisk), ny + 14 + 5 * whisk + i * (13 + 6 * whisk),
                            nx + side * (200 + 12 * whiskTip), ny + 12 + 9 * whiskTip + i * (24 + 27 * whiskTip),
                            color, now, (side + 1) * 1.5f + i + 1, px);
                }
            }
        }
        fill.clearShadowLayer();
        stroke.clearShadowLayer();
        c.restore();
    }

    // ---- Eye shapes ----
    // The lens eyes come in three outlines, each drawn the same way: oval (every day), heart (Valentine's) and the
    // pumpkin's triangle (Halloween).

    private static final int OVAL = 0, HEART = 1, TRI = 2;
    /** How far each outline is shifted down so its middle is at its centre, as a share of its height. */
    private static final float[] EYE_MID = {0, 0.055f, 0.125f};
    /** How big each outline is drawn, against the oval's width and height. */
    private static final float[][] EYE_SIZE = {{1, 1}, {1.2f, 1.18f}, {1, 1.15f}};
    private final Path shape = new Path();

    /** Traces one outline into shape, sx and sy wide and tall, centred on ox, oy. */
    private void eyePath(int kind, float sx, float sy, float ox, float oy) {
        oy += EYE_MID[kind] * sy;
        shape.reset();
        if (kind == HEART) {
            shape.moveTo(ox, oy + 0.75f * sy);
            shape.cubicTo(ox - 1.15f * sx, oy - 0.1f * sy, ox - 0.75f * sx, oy - sy, ox, oy - 0.45f * sy);
            shape.cubicTo(ox + 0.75f * sx, oy - sy, ox + 1.15f * sx, oy - 0.1f * sy, ox, oy + 0.75f * sy);
            shape.close();
        } else if (kind == TRI) {
            shape.moveTo(ox - 1.05f * sx, oy + 0.7f * sy);
            shape.lineTo(ox, oy - 0.95f * sy);
            shape.lineTo(ox + 1.05f * sx, oy + 0.7f * sy);
            shape.close();
        } else {
            rect.set(ox - sx, oy - sy, ox + sx, oy + sy);
            shape.addOval(rect, Path.Direction.CW);
        }
    }

    /** Half the outline's width at height y from its middle, for the cat's lid line. */
    private static float eyeHalfWidth(int kind, float sx, float sy, float y) {
        float t = y / sy - EYE_MID[kind];
        if (kind == TRI) return 1.05f * sx * Math.min(1, Math.max(0, (t + 0.95f) / 1.65f));
        if (kind == HEART) return 0.9f * sx * (float) Math.sqrt(Math.max(0, 1 - (t + 0.25f) * (t + 0.25f)));
        return sx * (float) Math.sqrt(Math.max(0, 1 - t * t));
    }

    /** Roughly how far round the outline is, so a dash can chase round it once. */
    private static float eyePerimeter(int kind, float sx, float sy) {
        if (kind == TRI) return 2 * (float) Math.hypot(1.05f * sx, 1.65f * sy) + 2.1f * sx;
        if (kind == HEART) return 3.9f * sx;
        return (float) (Math.PI * (3 * (sx + sy) - Math.sqrt((3 * sx + sy) * (sx + 3 * sy))));
    }

    /** The ring: dim all round, brighter over the top left like light on a bevel. */
    private void eyeRing(Canvas c, int kind, float sx, float sy, int color) {
        stroke.setStrokeWidth(5);
        stroke.setColor(withAlpha(color, 0.45f));
        eyePath(kind, sx, sy, 0, 0);
        c.drawPath(shape, stroke);
        c.save();
        c.clipRect(-sx * 1.4f, -sy * 1.4f, sx * 0.31f, sy * 0.16f);
        stroke.setColor(color);
        c.drawPath(shape, stroke);
        c.restore();
    }

    /** Thinking on a heart or triangle: two bright dashes chasing round the outline. */
    private void eyeChase(Canvas c, int kind, float sx, float sy, float now) {
        float p = eyePerimeter(kind, sx, sy), dash = p * 0.12f;
        stroke.setStrokeWidth(7);
        stroke.setPathEffect(new DashPathEffect(new float[] {dash, p / 2 - dash}, p - (now * p * 0.6f) % p));
        eyePath(kind, sx, sy, 0, 0);
        c.drawPath(shape, stroke);
        stroke.setPathEffect(null);
    }

    /**
     * The plain robot's eye, a glowing lens: an outer ring brighter over the top left like light on a bevel, a
     * gradient iris that looks further than the eye, a darker ring round a hot core, and two glints fixed to the
     * glass, all in the eye's outline (kind). Blinks squash it top to bottom; muted shuts the top half under a lid;
     * thinking spins two arcs round the ring (dashes chase round a heart or triangle). Uses the face paints, so the
     * ring glows.
     */
    private void robotEye(Canvas c, float x, float y, int color, float bf, float now, int kind) {
        float big = 56 * eyeScale;
        float ir;
        switch (state) {
            case "listening": ir = 42; break;
            case "thinking": ir = 32; break;
            case "muted": ir = 34; break;
            case "answering": ir = 37 + 4 * Math.min(1, shown * GAIN); break;
            default: ir = 37; break;
        }
        float r = ir * eyeScale, k = r / big;
        float sx = big * EYE_SIZE[kind][0], sy = big * EYE_SIZE[kind][1];
        boolean muted = "muted".equals(state);
        c.save();
        c.translate(x, y);
        c.scale(1, Math.max(0.06f, bf));
        if (muted) c.clipRect(-sx - 12, -sy * 0.15f, sx + 12, sy * 2);
        eyeRing(c, kind, sx, sy, color);
        if ("thinking".equals(state)) {
            stroke.setColor(color);
            if (kind == OVAL) {
                stroke.setStrokeWidth(7);
                rect.set(-big, -big, big, big);
                float spin = (float) Math.toDegrees(now * 4);
                c.drawArc(rect, spin, 52, false, stroke);
                c.drawArc(rect, spin + 180, 52, false, stroke);
            } else {
                eyeChase(c, kind, sx, sy, now);
            }
        }
        // Iris, its gradient a little further toward the gaze than the eye
        float ix = gazeX * 7, iy = gazeY * 5;
        fill.setShader(new RadialGradient(ix - r * 0.15f, iy - r * 0.15f, r * 1.1f * EYE_SIZE[kind][1],
                new int[] {Color.WHITE, whiter(color, 0.55f), color, withAlpha(color, 0.55f)},
                new float[] {0, 0.3f, 0.75f, 1}, Shader.TileMode.CLAMP));
        eyePath(kind, sx * k, sy * k, ix, iy);
        c.drawPath(shape, fill);
        fill.setShader(null);
        // A darker ring partway in, then the hot core, both in the eye's outline
        line.setStrokeWidth(3);
        line.setColor(Color.argb(71, 0, 30, 20));
        eyePath(kind, sx * k * 0.58f, sy * k * 0.58f, ix, iy);
        c.drawPath(shape, line);
        plain.setColor(Color.argb(230, 255, 255, 255));
        eyePath(kind, sx * k * 0.26f, sy * k * 0.26f, ix, iy);
        c.drawPath(shape, plain);
        // Glints on the glass
        plain.setColor(Color.argb(217, 255, 255, 255));
        float gx0 = -r * 0.42f, gy0 = -r * 0.5f;
        c.save();
        c.rotate(-34, gx0, gy0);
        rect.set(gx0 - 9, gy0 - 6, gx0 + 9, gy0 + 6);
        c.drawOval(rect, plain);
        c.restore();
        plain.setColor(Color.argb(115, 255, 255, 255));
        c.drawCircle(r * 0.45f, r * 0.4f, 4, plain);
        c.restore();
        if (muted) {
            // The lid's edge
            stroke.setStrokeWidth(6);
            stroke.setColor(color);
            float half = eyeHalfWidth(kind, sx, sy, -sy * 0.15f) + 4, ly = y - sy * 0.15f * bf;
            c.drawLine(x - half, ly, x + half, ly, stroke);
        }
    }

    /**
     * The cat's eye, a glass lens under a lid, in the eye's outline (kind). The ring has a brighter arc over the top
     * left like light on a bevel; the iris is white-hot toward the middle; the dark slit pupil widens listening and
     * narrows thinking; glints sit on the glass. Every state is the lid at a different height (lid): high and alert
     * listening, low and heavy muted; a heart's lid sits higher, just grazing the lobes that make it read as a heart,
     * and isn't drawn until it crosses them. A blink brings the lid down to a little below the middle, where the eye
     * is still nearly full width, and fades the iris behind it, so a closed eye is the same lid line over the ring's
     * lower curve. Thinking runs two arcs along the bottom curve (dashes round a heart or triangle). A bright dot
     * sits at the lid's inner end, toward the nose, like the corner of a real eye.
     */
    private void catEye(Canvas c, float x, float y, int color, float bf, float now, int side, int kind, float px) {
        float sx = 42 * eyeScale * EYE_SIZE[kind][0], sy = 49 * eyeScale * EYE_SIZE[kind][1], k = 32f / 42;
        float open = -sy * (lid + (kind == HEART ? 0.5f : 0)), shut = 1 - bf;
        float lidY = open + shut * (sy * 0.3f - open);
        float iris = Math.min(1, Math.max(0, (0.9f - shut) / 0.6f));
        c.save();
        c.translate(x, y);
        c.clipRect(-sx * 1.3f - 12, lidY, sx * 1.3f + 12, sy * 2);
        eyeRing(c, kind, sx, sy, color);
        if ("thinking".equals(state)) {
            stroke.setColor(color);
            if (kind == OVAL) {
                // Two arcs chasing round the bottom curve, from the right side under to the left, and again
                stroke.setStrokeWidth(7);
                rect.set(-sx, -sy, sx, sy);
                for (float off = 0; off < 1; off += 0.5f) {
                    double at = -0.4 + (Math.PI + 0.8) * ((now * 0.55f + off) % 1);
                    c.drawArc(rect, deg(at), 43, false, stroke);
                }
            } else {
                eyeChase(c, kind, sx, sy, now);
            }
        }
        // Iris, a little further toward the gaze than the eye
        float ix = gazeX * 6, iy = gazeY * 4, rx = sx * k, ry = sy * k;
        eyePath(kind, rx, ry, ix, iy);
        // A round gradient about the origin, stretched to the iris's height and moved onto it
        Shader gradient = new RadialGradient(-rx * 0.1f, -rx * 0.15f, rx * 1.05f,
                new int[] {Color.WHITE, whiter(color, 0.5f), color, withAlpha(color, 0.6f)},
                new float[] {0, 0.35f, 0.8f, 1}, Shader.TileMode.CLAMP);
        gradient.setLocalMatrix(irisMatrix(ix, iy, ry / rx));
        fill.setShader(gradient);
        fill.setAlpha(Math.round(255 * iris));
        c.drawPath(shape, fill);
        fill.setShader(null);
        fill.setColor(color);
        // Slit pupil, further toward the gaze, dark with a lit edge
        float pcx = ix + gazeX * 4, pcy = iy + EYE_MID[kind] * ry, ph = ry * (kind == OVAL ? 0.88f : 0.62f);
        path.reset();
        path.moveTo(pcx, pcy - ph);
        path.quadTo(pcx + pupil * 2, pcy, pcx, pcy + ph);
        path.quadTo(pcx - pupil * 2, pcy, pcx, pcy - ph);
        path.close();
        plain.setColor(Color.argb(Math.round(199 * iris), 4, 22, 14));
        c.drawPath(path, plain);
        line.setStrokeWidth(1.5f);
        line.setColor(Color.argb(Math.round(128 * iris), 255, 255, 255));
        c.drawPath(path, line);
        // Glints on the glass
        plain.setColor(Color.argb(Math.round(217 * iris), 255, 255, 255));
        float gx0 = -rx * 0.45f, gy0 = -ry * 0.3f;
        c.save();
        c.rotate(-34, gx0, gy0);
        rect.set(gx0 - 8, gy0 - 5, gx0 + 8, gy0 + 5);
        c.drawOval(rect, plain);
        c.restore();
        plain.setColor(Color.argb(Math.round(115 * iris), 255, 255, 255));
        c.drawCircle(rx * 0.4f, ry * 0.4f, 3.5f, plain);
        c.restore();
        // The lid's edge, as wide as the eye where it crosses, plus a little, and the dot at its inner end; none
        // while it's above a heart's lobes
        if (kind == HEART && lidY < -0.72f * sy) return;
        float half = eyeHalfWidth(kind, sx, sy, lidY) + 6;
        stroke.setStrokeWidth(6);
        stroke.setColor(color);
        c.drawLine(x - half, y + lidY, x + half, y + lidY, stroke);
        plain.setShadowLayer(8 * px, 0, 0, Color.WHITE);
        plain.setColor(Color.argb(242, 255, 255, 255));
        c.drawCircle(x - side * half, y + lidY, 4.2f, plain);
        plain.clearShadowLayer();
    }

    private final Matrix irisLocal = new Matrix();

    /** The iris gradient is a circle; this stretches it to the iris's height and moves it onto the iris. */
    private Matrix irisMatrix(float ix, float iy, float stretch) {
        irisLocal.reset();
        irisLocal.setScale(1, stretch);
        irisLocal.postTranslate(ix, iy);
        return irisLocal;
    }

    /**
     * The cat's mouth as one shape: the whole "w" is its top edge, and a curve hangs from the w's outer tips, flat
     * on the w when closed and dropping into a round smile as it opens; the tips are the mouth's pointed corners.
     * Inside, like the robot's, a faint glow and level bars that jump with the voice, centred so they read as a
     * meter rather than teeth. Drawn as an LED tube: a glowing line with a bright core.
     */
    private void catMouth(Canvas c, float nx, float my, int color, float now) {
        float md = MOUTH_DEPTH;
        float open = "answering".equals(state) ? Math.min(1, shown * GAIN) : "listening".equals(state) ? 0.18f : 0;
        double tip = 0.05 * Math.PI;
        float tipX = 27 * (float) Math.cos(tip) + 27, tipY = my + md * (float) Math.sin(tip);
        float by = my + md + 4 + open * 46;
        stroke.setStrokeWidth(7);
        line.setStrokeWidth(2.5f);
        line.setColor(whiter(color, 0.7f));
        if (open > 0.03f) {
            path.reset();
            path.moveTo(nx + tipX, tipY);
            // Down from the right corner, level across the bottom, up to the left corner, then along the w
            path.cubicTo(nx + tipX, tipY + (by - tipY) * 0.75f, nx + tipX * 0.55f, by, nx, by);
            path.cubicTo(nx - tipX * 0.55f, by, nx - tipX, tipY + (by - tipY) * 0.75f, nx - tipX, tipY);
            rect.set(nx - 54, my - md, nx, my + md);
            path.arcTo(rect, 171, -162, false);
            rect.set(nx, my - md, nx + 54, my + md);
            path.arcTo(rect, 171, -162, false);
            path.close();
            fill.setColor(withAlpha(color, 0.14f));
            c.drawPath(path, fill);
            fill.setColor(color);
            c.drawPath(path, stroke);
            c.save();
            c.clipPath(path);
            stroke.setStrokeWidth(6);
            stroke.setColor(whiter(color, 0.35f));
            int n = 7;
            for (int i = 0; i < n; i++) {
                float u = (float) i / (n - 1) * 2 - 1, bx = nx + u * (tipX - 16);
                float off = (Math.abs(bx - nx) - 27) / 27;
                float top = my + md * (float) Math.sqrt(Math.max(0, 1 - off * off));
                float bottom = tipY + (by - tipY) * (float) Math.sqrt(Math.max(0, 1 - u * u * 0.9f));
                float room = Math.max(0, bottom - top - 8);
                float k = "answering".equals(state)
                        ? (0.55f + 0.45f * (float) Math.sin(i * 1.7 + phon * 9)) * Math.min(1, open * 1.4f)
                        : 0.5f + 0.5f * (float) Math.sin(now * 6 - i * 0.8);
                float bh = Math.max(2, room * k), bm = (top + bottom) / 2;
                c.drawLine(bx, bm - bh / 2, bx, bm + bh / 2, stroke);
            }
            c.restore();
            stroke.setColor(color);
            c.drawPath(path, line);
        } else {
            for (int pass = 0; pass < 2; pass++) {
                Paint p = pass == 0 ? stroke : line;
                rect.set(nx - 54, my - md, nx, my + md);
                c.drawArc(rect, 9, 162, false, p);
                rect.set(nx, my - md, nx + 54, my + md);
                c.drawArc(rect, 9, 162, false, p);
            }
        }
    }

    /**
     * A whisker as a lit fibre: a bar tapering from root to tip, a bright core, a lit dot where the fibre ends, and
     * now and then a gleam running out from root to tip, more often while listening. seed staggers them.
     */
    private void fibreWhisker(Canvas c, float x0, float y0, float x1, float y1, int color, float now, float seed, float px) {
        float dx = x1 - x0, dy = y1 - y0, len = (float) Math.hypot(dx, dy), ux = dx / len, uy = dy / len, nxv = -uy, nyv = ux;
        path.reset();
        path.moveTo(x0 + nxv * 3.5f, y0 + nyv * 3.5f);
        path.lineTo(x1 + nxv * 1.2f, y1 + nyv * 1.2f);
        path.lineTo(x1 - nxv * 1.2f, y1 - nyv * 1.2f);
        path.lineTo(x0 - nxv * 3.5f, y0 - nyv * 3.5f);
        path.close();
        fill.setColor(withAlpha(color, 0.85f));
        c.drawPath(path, fill);
        c.drawCircle(x0, y0, 3.5f, fill);
        fill.setColor(color);
        line.setStrokeWidth(1.4f);
        line.setColor(whiter(color, 0.75f));
        c.drawLine(x0, y0, x0 + dx * 0.9f, y0 + dy * 0.9f, line);
        // The gleam
        float period = "listening".equals(state) ? 1.1f : 3.2f;
        float t = ((now + seed * 0.37f) % period) / 0.7f;
        if (t < 1) {
            float g = (float) Math.sin(Math.PI * t), gx = x0 + dx * t, gy = y0 + dy * t;
            line.setShadowLayer(10 * px, 0, 0, Color.WHITE);
            line.setStrokeWidth(3.5f);
            line.setColor(Color.argb(Math.round(230 * g), 255, 255, 255));
            c.drawLine(gx - ux * 9, gy - uy * 9, gx + ux * 9, gy + uy * 9, line);
            line.clearShadowLayer();
        }
        // The fibre's lit end
        plain.setShadowLayer(8 * px, 0, 0, color);
        plain.setColor(Color.argb(217, 255, 255, 255));
        c.drawCircle(x1, y1, 2.6f, plain);
        plain.clearShadowLayer();
    }

    /** How far the middle of the mouth's top edge sits below its ends, and the lower lip's when closed. */
    private static final float LIP_TOP = 2, LIP_LOW = 7;
    private static final int BARS = 9;

    /**
     * The mouth's outline into p: rounded ends, and each edge an arc of a big circle that touches the ends
     * exactly (the ends' circles sit inside the lower one and outside the upper one), so there is no kink. top
     * and low are how far each edge's middle sits below its ends; inset shrinks it evenly. Returns {dt, Rt, db, Rb}:
     * each edge circle's centre offset from my and its radius, for the bars.
     */
    private float[] mouthPath(Path p, float mx, float my, float w, float h, float low, float inset) {
        float r = h / 2 - inset, ex = w / 2 - h / 2;
        float db = (low * low - ex * ex) / (2 * low), rb = r + low - db, ab = (float) Math.atan2(-db, ex);
        float dt = (LIP_TOP * LIP_TOP - ex * ex) / (2 * LIP_TOP), rt = LIP_TOP - dt - r, at = (float) Math.atan2(-dt, ex);
        float rightTop = (float) Math.atan2(dt, -ex), leftTop = (float) (Math.atan2(dt, ex) + Math.PI * 2);
        p.reset();
        rect.set(mx - rt, my + dt - rt, mx + rt, my + dt + rt);
        p.arcTo(rect, deg(Math.PI - at), -deg(Math.PI - 2 * at), true);
        rect.set(mx + ex - r, my - r, mx + ex + r, my + r);
        p.arcTo(rect, deg(rightTop), deg(ab - rightTop), false);
        rect.set(mx - rb, my + db - rb, mx + rb, my + db + rb);
        p.arcTo(rect, deg(ab), deg(Math.PI - 2 * ab), false);
        rect.set(mx - ex - r, my - r, mx - ex + r, my + r);
        p.arcTo(rect, deg(Math.PI - ab), deg(leftTop - (Math.PI - ab)), false);
        p.close();
        return new float[] {dt, rt, db, rb};
    }

    /**
     * The plain robot's mouth, a small screen: a smiling capsule, outlined and faintly lit inside, with a row of
     * LED bars. Speaking, it opens taller, the lower lip curves more and the bars jump with the voice, tallest in the
     * middle and shaped differently each syllable; listening, a slow wave runs along them; thinking, one lit bar
     * scans back and forth; resting, they are dots along the smile; muted, a flat dim row.
     */
    private void robotMouth(Canvas c, float mx, float my, int color, float now, boolean happy) {
        float w = 150;
        float open = "answering".equals(state) ? Math.min(1, shown * GAIN) : 0;
        float h = 24 + open * 36;
        float low = LIP_LOW + open * 12 + (happy ? 4 : 0);
        stroke.setStrokeWidth(4.5f);
        stroke.setColor(color);
        fill.setColor(withAlpha(color, 0.12f));
        mouthPath(path, mx, my, w, h, low, 0);
        c.drawPath(path, fill);
        c.drawPath(path, stroke);
        fill.setColor(color);
        c.save();
        float[] e = mouthPath(clip, mx, my, w, h, low, 6);
        c.clipPath(clip);
        stroke.setStrokeWidth(6.5f);
        float scan = (float) Math.sin(now * 2.6) * (BARS - 1) / 2f + (BARS - 1) / 2f;
        for (int i = 0; i < BARS; i++) {
            float u = (i - (BARS - 1) / 2f) / ((BARS - 1) / 2f);
            float dx = (i - (BARS - 1) / 2f) * 14, bx = mx + dx;
            float top = my + e[0] + (float) Math.sqrt(e[1] * e[1] - dx * dx);
            float bottom = my + e[2] + (float) Math.sqrt(e[3] * e[3] - dx * dx);
            float room = bottom - top - 8, mid = (top + bottom) / 2;
            float bh = 0, alpha = 1;
            switch (state) {
                case "answering": {
                    float shape = 0.55f + 0.45f * (float) Math.sin(i * 1.7 + phon * 9);
                    bh = room * Math.min(1, open * 1.4f) * (1 - 0.3f * u * u) * shape;
                    break;
                }
                case "listening": bh = 2 + 7 * (0.5f + 0.5f * (float) Math.sin(now * 6 - i * 0.8)); break;
                case "thinking": alpha = 0.25f + 0.75f * Math.max(0, 1 - Math.abs(i - scan) / 1.5f); break;
                case "muted": alpha = 0.35f; break;
                default: break;
            }
            stroke.setColor(alpha < 1 ? withAlpha(color, alpha) : whiter(color, 0.35f));
            c.drawLine(bx, mid - bh / 2, bx, mid + bh / 2 + 0.01f, stroke);
        }
        c.restore();
        stroke.setColor(color);
    }

    private static float deg(double radians) { return (float) Math.toDegrees(radians); }

    private static int whiter(int color, float k) {
        return Color.rgb(Math.round(Color.red(color) + (255 - Color.red(color)) * k),
                Math.round(Color.green(color) + (255 - Color.green(color)) * k),
                Math.round(Color.blue(color) + (255 - Color.blue(color)) * k));
    }

    private float blinkFactor(float now) {
        if (blinkStart < 0) return 1;
        float p = (now - blinkStart) / 0.16f;
        return p >= 1 ? 1 : Math.max(0.08f, 1 - (float) Math.sin(p * Math.PI));
    }

    /**
     * The year on the New Year's display, in seven-segment digits. In December it shows the year about to start;
     * on December 31 it counts down to midnight instead: hours and minutes left, then minutes and seconds in the
     * last hour, with a blinking colon.
     */
    private void drawYear(Canvas c, float[] box, int color, float px) {
        Calendar now = Calendar.getInstance();
        String year = String.valueOf(now.get(Calendar.YEAR) + (now.get(Calendar.MONTH) == Calendar.DECEMBER ? 1 : 0));
        boolean colon = false;
        if (now.get(Calendar.MONTH) == Calendar.DECEMBER && now.get(Calendar.DAY_OF_MONTH) == 31) {
            Calendar midnight = (Calendar) now.clone();
            midnight.set(now.get(Calendar.YEAR) + 1, Calendar.JANUARY, 1, 0, 0, 0);
            midnight.set(Calendar.MILLISECOND, 0);
            long left = Math.max(0, (midnight.getTimeInMillis() - now.getTimeInMillis() + 999) / 1000);
            long hours = left / 3600, minutes = left % 3600 / 60;
            year = hours > 0 ? two(hours) + two(minutes) : two(minutes) + two(left % 60);
            colon = now.get(Calendar.MILLISECOND) < 500;
        }
        float x = box[0], y = box[1], w = box[2], h = box[3];
        float padX = w * 0.09f, padY = h * 0.2f;
        float cw = (w - padX * 2) / year.length(), ch = h - padY * 2;
        float gap = cw * 0.16f;
        stroke.setStrokeWidth(cw * 0.14f);
        if (colon) {
            fill.setColor(color);
            fill.setShadowLayer(10 * px, 0, 0, color);
            c.drawCircle(x + padX + 2 * cw, y + padY + ch * 0.3f, cw * 0.077f, fill);
            c.drawCircle(x + padX + 2 * cw, y + padY + ch * 0.7f, cw * 0.077f, fill);
            fill.clearShadowLayer();
        }
        for (int i = 0; i < year.length(); i++) {
            float x0 = x + padX + i * cw + gap / 2, x1 = x0 + cw - gap, y0 = y + padY, y1 = y0 + ch, ym = (y0 + y1) / 2;
            float[][] seg = {{x0, y0, x1, y0}, {x1, y0, x1, ym}, {x1, ym, x1, y1}, {x0, y1, x1, y1}, {x0, ym, x0, y1}, {x0, y0, x0, ym}, {x0, ym, x1, ym}};
            String on = SEGMENTS[year.charAt(i) - '0'];
            for (int k = 0; k < 7; k++) {
                boolean lit = on.indexOf((char) ('a' + k)) >= 0;
                float[] sg = seg[k];
                // Shorten each segment a little so neighbours don't touch, like a real display
                float sx = (sg[2] - sg[0]) * 0.12f, sy = (sg[3] - sg[1]) * 0.12f;
                stroke.setColor(lit ? color : Color.argb(15, 255, 255, 255));
                if (lit) stroke.setShadowLayer(10 * px, 0, 0, color); else stroke.clearShadowLayer();
                c.drawLine(sg[0] + sx, sg[1] + sy, sg[2] - sx, sg[3] - sy, stroke);
            }
        }
        stroke.clearShadowLayer();
    }

    /** Three small bursts taking turns on either side and above, and a big one each time the answer gets loud. */
    private void drawFireworks(Canvas c, float now, int[] colours) {
        float period = 2.2f, life = 1.5f;
        for (int j = 0; j < 3; j++) {
            float t = now + j * 0.73f;
            int cycle = (int) Math.floor(t / period), seed = cycle * 3 + j;
            float x = (j + cycle) % 2 != 0 ? 60 + rnd(seed) * 230 : 734 + rnd(seed) * 230;
            burst(c, x, 70 + rnd(seed + 0.5) * 330, (t % period) / life, 110 + rnd(seed + 0.7) * 70, 18, seed, colours);
        }
        if ("answering".equals(state) && shown > 0.55f && now - bigAt > 2.2f) {
            bigAt = now;
            bigSeed = (int) (now * 7);
            bigX = rnd(bigSeed) < 0.5f ? 210 : 814;
        }
        burst(c, bigX, 250, (now - bigAt) / 1.8f, 230, 28, bigSeed, colours);
    }

    /** One burst of sparks with trails, age 0 to 1 over its life. */
    private void burst(Canvas c, float cx, float cy, float age, float size, int count, int seed, int[] colours) {
        if (age < 0 || age >= 1) return;
        float r = size * (1 - (float) Math.pow(1 - Math.min(1, age * 1.6f), 3));
        float fade = age < 0.15f ? 1 : 1 - (age - 0.15f) / 0.85f;
        float droop = age * age * size * 0.35f;
        int color = colours[Math.min(colours.length - 1, (int) (rnd(seed) * colours.length))];
        spark.setColor(color);
        spark.setStrokeWidth(5);
        for (int k = 0; k < count; k++) {
            double a = (double) k / count * Math.PI * 2 + rnd(seed + k) * 0.25;
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            float x = cx + cos * r, y = cy + sin * r + droop;
            spark.setStyle(Paint.Style.STROKE);
            spark.setAlpha(Math.round(255 * fade * 0.5f));
            c.drawLine(cx + cos * r * 0.7f, cy + sin * r * 0.7f + droop * 0.7f, x, y, spark);
            spark.setStyle(Paint.Style.FILL);
            spark.setAlpha(Math.round(255 * fade));
            c.drawCircle(x, y, 6, spark);
        }
        if (age < 0.12f) {
            spark.setAlpha(Math.round(255 * (1 - age / 0.12f) * 0.5f));
            c.drawCircle(cx, cy, size * 0.18f, spark);
        }
    }

    /** Flakes that flutter as they fall: thin edge-on, brightest face-on like foil. */
    private void drawConfetti(Canvas c, float now, int[] colours) {
        for (int i = 0; i < 46; i++) {
            float y = ((rnd(i + 50) * 1200 + now * (38 + rnd(i) * 40)) % 1200) - 100;
            float x = rnd(i + 100) * 1024 + (float) Math.sin(now * (0.6 + rnd(i + 150) * 0.8) + i) * 30;
            float flip = (float) Math.cos(now * (2 + rnd(i + 200) * 4) + i);
            c.save();
            c.translate(x, y);
            c.rotate((float) Math.toDegrees(rnd(i + 250) * Math.PI + Math.sin(now * 0.9 + i) * 0.6));
            c.scale(1, Math.abs(flip) < 0.02f ? 0.02f : flip);
            flake.setColor(colours[i % colours.length]);
            flake.setAlpha(Math.round(255 * (0.45f + 0.5f * Math.abs(flip))));
            c.drawRect(-7, -4, 7, 4, flake);
            c.restore();
        }
    }

    /** Snow: plain soft dots drifting down and side to side, the small ones slower and fainter, so further away. */
    private void drawSnow(Canvas c, float now) {
        snow.setColor(Color.WHITE);
        for (int i = 0; i < 70; i++) {
            float size = 2 + rnd(i + 300) * 4;
            float y = ((rnd(i + 350) * 1200 + now * (10 + size * 6)) % 1200) - 100;
            float x = rnd(i + 400) * 1024 + (float) Math.sin(now * (0.4 + rnd(i + 450) * 0.5) + i) * 22;
            snow.setAlpha(Math.round(255 * (0.3f + size * 0.09f)));
            c.drawCircle(x, y, size, snow);
        }
    }

    // ---- Helpers ----

    /** A steady pseudo-random number in 0..1 for any n, so effects need no stored particles. */
    private static float rnd(double n) {
        double x = Math.sin(n * 127.1 + 311.7) * 43758.5453;
        return (float) (x - Math.floor(x));
    }

    private static String two(long n) { return n < 10 ? "0" + n : String.valueOf(n); }

    private static float bobAt(float t) { return (float) Math.sin(t * 1.6) * 12; }

    private static float bump(float t, float a, float b) {
        return t < a || t > b ? 0 : (float) Math.sin(Math.PI * (t - a) / (b - a));
    }

    private static void around(Canvas c, float px, float py, float angle, float dx, float dy) {
        c.translate(px + dx, py + dy);
        c.rotate((float) Math.toDegrees(angle));
        c.translate(-px, -py);
    }

    private static int withAlpha(int color, float alpha) {
        return Color.argb(Math.round(255 * alpha), Color.red(color), Color.green(color), Color.blue(color));
    }
}
