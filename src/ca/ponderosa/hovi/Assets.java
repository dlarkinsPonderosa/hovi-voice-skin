// SPDX-License-Identifier: Apache-2.0
package ca.ponderosa.hovi;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import me.jxl.kiosk.plugins.PluginHost;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The robot's pictures, read once from the package: the body layers, the light masks with their glows and the
 * holiday props. Positions are in the 1024 pixel space of the source render, from manifest.json and props.json,
 * which the robot-skin build scripts write.
 *
 * <p>The pictures are licensed artwork that may ship inside the skin but not be handed out as files, so they are
 * packed into one scrambled file, art.pak (tools/prepare_plugin_assets.py), and unpacked here in memory. Nothing is
 * written to disk.
 */
final class Assets {
    /** How far each glow picture reaches past its light, in source pixels. tools/prepare_plugin_assets.py makes them. */
    static final int GLOW_PAD = 24;

    /** The bases, by the folder in the pack each one's layers come from. */
    static final String METAL = "layers/", WHITE = "layers_white/", ROBOT = "layers_plain/";

    /**
     * Where each base looks for props, later folders first: the white cat has its own props in place of shared
     * ones of the same name, and the plain robot (the white cat without ears or tail) its own in front of those.
     */
    private static List<String> propFolders(String base) {
        if (ROBOT.equals(base)) return Arrays.asList(METAL, WHITE, ROBOT);
        if (WHITE.equals(base)) return Arrays.asList(METAL, WHITE);
        return Collections.singletonList(METAL);
    }

    private static final byte[] MAGIC = "RCPK".getBytes(StandardCharsets.US_ASCII);
    /** Not a secret: the code is public. It keeps the pictures from being plain files in the package. */
    private static final byte[] KEY = "ponderosa robot skin art pack v1".getBytes(StandardCharsets.US_ASCII);

    static final class Part {
        final Bitmap bitmap;
        /** The light's soft glow, GLOW_PAD bigger on each side, or null for a picture that isn't a light. */
        final Bitmap glow;
        final float x, y, w, h;
        /** The layer a light moves with. */
        final String parent;
        /** For the New Year's display, where its year goes: x, y, width, height. */
        final float[] screen;

        Part(Bitmap bitmap, Bitmap glow, JSONObject entry) throws JSONException {
            this.bitmap = bitmap;
            this.glow = glow;
            x = (float) entry.getDouble("x");
            y = (float) entry.getDouble("y");
            w = (float) entry.getDouble("w");
            h = (float) entry.getDouble("h");
            parent = entry.optString("parent", "");
            JSONArray s = entry.optJSONArray("screen");
            screen = s == null ? null : new float[] {(float) s.getDouble(0), (float) s.getDouble(1), (float) s.getDouble(2), (float) s.getDouble(3)};
        }
    }

    final Map<String, Part> layers = new HashMap<>();
    /** By light name, such as ear_l or tail_2. */
    final Map<String, Part> lights = new HashMap<>();
    final Map<String, Part> props = new HashMap<>();
    /** The Thanksgiving leaves, in order. */
    final List<String> leaves = new ArrayList<>();
    /** The face screen: x, y, width, height. */
    final float[] screen;
    /** METAL, WHITE or ROBOT. */
    final String base;
    /** The face drawn on the screen: the cat's, or the plain robot's, with no nose, "w" or whiskers. */
    final boolean catFace;
    final Map<String, float[]> pivots = new HashMap<>();
    /** The unpacked files this base uses, by path in the pack; dropped once the pictures are decoded. */
    private final Map<String, byte[]> files;

    Assets(PluginHost host, String base) throws IOException, JSONException {
        this.base = base;
        List<String> folders = propFolders(base);
        files = unpack(host, folders);
        JSONObject manifest = new JSONObject(text(base + "manifest.json"));
        catFace = !"robot".equals(manifest.optString("face", "cat"));
        JSONObject s = manifest.getJSONObject("screen");
        screen = new float[] {(float) s.getDouble("x"), (float) s.getDouble("y"), (float) s.getDouble("w"), (float) s.getDouble("h")};
        JSONObject p = manifest.getJSONObject("pivots");
        for (Iterator<String> it = p.keys(); it.hasNext();) {
            String key = it.next();
            JSONArray at = p.getJSONArray(key);
            pivots.put(key, new float[] {(float) at.getDouble(0), (float) at.getDouble(1)});
        }
        JSONObject l = manifest.getJSONObject("layers");
        for (Iterator<String> it = l.keys(); it.hasNext();) {
            String key = it.next();
            layers.put(key, part(base, l.getJSONObject(key), false));
        }
        JSONObject li = manifest.getJSONObject("lights");
        for (Iterator<String> it = li.keys(); it.hasNext();) {
            String key = it.next();
            lights.put(key.replaceFirst("^light_", ""), part(base, li.getJSONObject(key), true));
        }
        // Each folder's props replace earlier ones of the same name; null leaves one out on this base
        for (String dir : folders) {
            JSONObject own = new JSONObject(text(dir + "props.json"));
            for (Iterator<String> it = own.keys(); it.hasNext();) {
                String key = it.next();
                if (own.isNull(key)) props.remove(key);
                else props.put(key, part(dir, own.getJSONObject(key), key.startsWith("chestlight_")));
            }
        }
        // After the base's own props: the white base has its own, different set of leaves
        for (String key : props.keySet()) if (key.startsWith("leaf_")) leaves.add(key);
        Collections.sort(leaves);
        files.clear();
    }

    /** Only a tail-less robot can't wag. */
    boolean hasTail() { return layers.containsKey("tail"); }

    private Part part(String dir, JSONObject entry, boolean glow) throws IOException, JSONException {
        String file = entry.getString("file");
        return new Part(bitmap(dir + file), glow ? bitmap(dir + "glow_" + file) : null, entry);
    }

    private Bitmap bitmap(String name) throws IOException {
        byte[] data = files.get(name);
        if (data == null) throw new IOException("Missing picture " + name);
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bitmap == null) throw new IOException("Unreadable picture " + name);
        return bitmap;
    }

    private String text(String name) throws IOException {
        byte[] data = files.get(name);
        if (data == null) throw new IOException("Missing " + name);
        return new String(data, StandardCharsets.UTF_8);
    }

    /**
     * Reads art.pak: "RCPK", a 16 byte nonce, then a zip XORed with SHA-256(KEY, nonce, block number) taken 32 bytes
     * at a time. Keeps only the files under the given folders.
     */
    private static Map<String, byte[]> unpack(PluginHost host, List<String> folders) throws IOException {
        byte[] pack;
        try (InputStream in = host.openAsset("art.pak")) {
            pack = readAll(in);
        }
        if (pack.length < 20 || !Arrays.equals(Arrays.copyOf(pack, 4), MAGIC)) throw new IOException("Not an art pack");
        byte[] nonce = Arrays.copyOfRange(pack, 4, 20);
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        }
        byte[] zip = new byte[pack.length - 20];
        byte[] counter = new byte[8];
        for (int at = 0; at < zip.length; at += 32) {
            // A long: an int shifted by 32 or more wraps round in Java, which filled the counter's top bytes with
            // the block number again (0.6.0's "invalid entry CRC")
            long block = at / 32;
            for (int i = 0; i < 8; i++) counter[7 - i] = (byte) (block >>> (8 * i));
            sha.update(KEY);
            sha.update(nonce);
            byte[] stream = sha.digest(counter);
            for (int i = 0; i < 32 && at + i < zip.length; i++) zip[at + i] = (byte) (pack[20 + at + i] ^ stream[i]);
        }
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream entries = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = entries.getNextEntry()) != null;) {
                String name = e.getName();
                for (String dir : folders) {
                    if (name.startsWith(dir) && name.indexOf('/', dir.length()) < 0) {
                        files.put(name, readAll(entries));
                        break;
                    }
                }
            }
        }
        return files;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        for (int n; (n = in.read(buffer)) > 0;) out.write(buffer, 0, n);
        return out.toByteArray();
    }
}
