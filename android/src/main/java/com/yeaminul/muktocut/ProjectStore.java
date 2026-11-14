package com.yeaminul.muktocut;

import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Local, atomic project persistence. No account, cloud upload, or analytics. */
public final class ProjectStore {
    private static final int VERSION = 2;
    private static final Object LOCK = new Object();

    private ProjectStore() {}

    public static EditProject load(File file) throws IOException {
        if (!file.exists()) return new EditProject();
        synchronized (LOCK) {
            byte[] bytes = Files.readAllBytes(file.toPath());
            return fromJson(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    public static void save(File file, EditProject project) throws IOException {
        String json = toJson(project).toString();
        synchronized (LOCK) {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("প্রজেক্ট ফোল্ডার তৈরি করা যায়নি");
            }
            AtomicFile atomic = new AtomicFile(file);
            FileOutputStream out = atomic.startWrite();
            try {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                atomic.finishWrite(out);
            } catch (IOException e) {
                atomic.failWrite(out);
                throw e;
            }
        }
    }

    public static JSONObject toJson(EditProject p) {
        try {
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            root.put("name", p.name);
            root.put("aspect", p.aspect);
            root.put("fitCanvas", p.fitCanvas);
            JSONArray sources = new JSONArray();
            for (EditProject.MediaSource s : p.sources.values()) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("uri", s.uri);
                o.put("name", s.name);
                o.put("durationUs", s.durationUs);
                o.put("width", s.width);
                o.put("height", s.height);
                o.put("rotation", s.rotation);
                o.put("hasAudio", s.hasAudio);
                o.put("persisted", s.persisted);
                sources.put(o);
            }
            JSONArray clips = new JSONArray();
            for (EditProject.EditClip c : clips(p)) {
                JSONObject o = new JSONObject();
                o.put("id", c.id);
                o.put("sourceId", c.sourceId);
                o.put("inUs", c.inUs);
                o.put("outUs", c.outUs);
                o.put("volume", c.volume);
                o.put("speed", c.speed);
                o.put("voiceMode", c.voiceMode);
                o.put("voiceStrength", c.voiceStrength);
                o.put("rotation", c.rotation);
                o.put("flipH", c.flipH);
                o.put("flipV", c.flipV);
                o.put("fadeInUs", c.fadeInUs);
                o.put("fadeOutUs", c.fadeOutUs);
                o.put("colorPreset", c.colorPreset);
                clips.put(o);
            }
            JSONArray texts = new JSONArray();
            for (EditProject.TextOverlay t : p.texts) {
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                o.put("text", t.text);
                o.put("startUs", t.startUs);
                o.put("durationUs", t.durationUs);
                o.put("x", t.x);
                o.put("y", t.y);
                o.put("size", t.size);
                o.put("color", t.color);
                o.put("box", t.box);
                texts.put(o);
            }
            JSONArray audio = new JSONArray();
            for (EditProject.AudioTrack a : p.audioTracks) {
                JSONObject o = new JSONObject();
                o.put("id", a.id);
                o.put("uri", a.uri);
                o.put("name", a.name);
                o.put("startUs", a.startUs);
                o.put("inUs", a.inUs);
                o.put("durationUs", a.durationUs);
                o.put("volume", a.volume);
                audio.put(o);
            }
            root.put("sources", sources);
            root.put("clips", clips);
            root.put("texts", texts);
            root.put("audioTracks", audio);
            return root;
        } catch (JSONException e) {
            throw new IllegalStateException("প্রজেক্ট সংরক্ষণ করা যায়নি", e);
        }
    }

    private static Iterable<EditProject.EditClip> clips(EditProject p) { return p.clips; }

    public static EditProject fromJson(String json) throws IOException {
        try {
            JSONObject root = new JSONObject(json);
            int version = root.optInt("version", 0);
            if (version != 1 && version != VERSION) throw new IOException("অজানা প্রজেক্ট ফরম্যাট");
            EditProject p = new EditProject();
            p.name = root.optString("name", p.name);
            p.aspect = root.optString("aspect", "original");
            p.fitCanvas = root.optBoolean("fitCanvas", true);
            JSONArray sources = root.getJSONArray("sources");
            for (int i = 0; i < sources.length(); i++) {
                JSONObject o = sources.getJSONObject(i);
                EditProject.MediaSource s = new EditProject.MediaSource(
                        o.getString("id"), o.getString("uri"), o.optString("name", "ভিডিও"),
                        o.getLong("durationUs"), o.getInt("width"), o.getInt("height"),
                        o.optInt("rotation", 0), o.optBoolean("hasAudio", false),
                        o.optBoolean("persisted", false));
                p.sources.put(s.id, s);
            }
            JSONArray clips = root.getJSONArray("clips");
            for (int i = 0; i < clips.length(); i++) {
                JSONObject o = clips.getJSONObject(i);
                String sourceId = o.getString("sourceId");
                if (!p.sources.containsKey(sourceId)) throw new IOException("প্রজেক্টে অজানা সোর্স আছে");
                EditProject.EditClip c = new EditProject.EditClip(o.getString("id"), sourceId,
                        o.getLong("inUs"), o.getLong("outUs"), (float) o.optDouble("volume", 1.0));
                c.speed = EditProject.clampSpeed((float) o.optDouble("speed", 1.0));
                c.voiceMode = o.optInt("voiceMode", 0);
                c.voiceStrength = EditProject.clampStrength((float) o.optDouble("voiceStrength", 0.85));
                c.rotation = ((o.optInt("rotation", 0) % 4) + 4) % 4;
                c.flipH = o.optBoolean("flipH", false);
                c.flipV = o.optBoolean("flipV", false);
                c.fadeInUs = o.optLong("fadeInUs", 0);
                c.fadeOutUs = o.optLong("fadeOutUs", 0);
                c.colorPreset = o.optInt("colorPreset", 0);
                EditProject.clampFades(c);
                p.clips.add(c);
            }
            JSONArray texts = root.optJSONArray("texts");
            if (texts != null) for (int i = 0; i < texts.length(); i++) {
                JSONObject o = texts.getJSONObject(i);
                EditProject.TextOverlay t = new EditProject.TextOverlay(o.getString("id"),
                        o.optString("text", ""), o.optLong("startUs", 0),
                        Math.max(EditProject.MIN_CLIP_US, o.optLong("durationUs", 3_000_000L)));
                t.x = (float) o.optDouble("x", 0.5);
                t.y = (float) o.optDouble("y", 0.82);
                t.size = (float) o.optDouble("size", 0.055);
                t.color = o.optInt("color", 0xFFFFFFFF);
                t.box = o.optBoolean("box", true);
                p.texts.add(t);
            }
            JSONArray audio = root.optJSONArray("audioTracks");
            if (audio != null) for (int i = 0; i < audio.length(); i++) {
                JSONObject o = audio.getJSONObject(i);
                p.audioTracks.add(new EditProject.AudioTrack(o.getString("id"), o.getString("uri"),
                        o.optString("name", "অডিও"), o.optLong("startUs", 0),
                        o.optLong("inUs", 0), Math.max(EditProject.MIN_CLIP_US, o.optLong("durationUs", 0)),
                        (float) o.optDouble("volume", 0.8)));
            }
            return p;
        } catch (JSONException e) {
            throw new IOException("প্রজেক্ট ফাইলটি পড়া যায়নি", e);
        }
    }
}
