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
    private static final int VERSION = 1;
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
            for (EditProject.EditClip c : p.clips) {
                JSONObject o = new JSONObject();
                o.put("id", c.id);
                o.put("sourceId", c.sourceId);
                o.put("inUs", c.inUs);
                o.put("outUs", c.outUs);
                o.put("volume", c.volume);
                clips.put(o);
            }
            root.put("sources", sources);
            root.put("clips", clips);
            return root;
        } catch (JSONException e) {
            throw new IllegalStateException("প্রজেক্ট সংরক্ষণ করা যায়নি", e);
        }
    }

    public static EditProject fromJson(String json) throws IOException {
        try {
            JSONObject root = new JSONObject(json);
            if (root.optInt("version", 0) != VERSION) {
                throw new IOException("অজানা প্রজেক্ট ফরম্যাট");
            }
            EditProject p = new EditProject();
            p.name = root.optString("name", p.name);
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
                if (!p.sources.containsKey(sourceId)) {
                    throw new IOException("প্রজেক্টে অজানা সোর্স আছে");
                }
                p.clips.add(new EditProject.EditClip(o.getString("id"), sourceId,
                        o.getLong("inUs"), o.getLong("outUs"),
                        (float) o.optDouble("volume", 1.0)));
            }
            return p;
        } catch (JSONException e) {
            throw new IOException("প্রজেক্ট ফাইলটি পড়া যায়নি", e);
        }
    }
}
