package com.yeaminul.muktocut;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure editing model. Source ranges are microseconds; timeline durations account
 * for playback speed. Android APIs are deliberately kept out so the cut/split/
 * trim/speed rules can be tested on the JVM.
 */
public final class EditProject {
    public static final long MIN_CLIP_US = 100_000L; // 0.1 s of source media
    public static final float MIN_SPEED = 0.25f;
    public static final float MAX_SPEED = 4f;

    public String name = "আমার প্রজেক্ট";
    public final LinkedHashMap<String, MediaSource> sources = new LinkedHashMap<>();
    public final ArrayList<EditClip> clips = new ArrayList<>();
    public final ArrayList<TextOverlay> texts = new ArrayList<>();
    public final ArrayList<AudioTrack> audioTracks = new ArrayList<>();

    /** original, 9:16, 16:9, 1:1, 4:5 */
    public String aspect = "original";
    /** true = fit/letterbox, false = fill/crop */
    public boolean fitCanvas = true;

    public static final class MediaSource {
        public final String id;
        public final String uri;
        public String name;
        public long durationUs;
        public int width;
        public int height;
        public int rotation;
        public boolean hasAudio;
        public boolean persisted;
        public transient boolean missing;

        public MediaSource(String id, String uri, String name, long durationUs,
                           int width, int height, int rotation, boolean hasAudio,
                           boolean persisted) {
            this.id = id;
            this.uri = uri;
            this.name = name;
            this.durationUs = durationUs;
            this.width = width;
            this.height = height;
            this.rotation = rotation;
            this.hasAudio = hasAudio;
            this.persisted = persisted;
        }

        public int displayWidth() { return quarterTurn() ? height : width; }
        public int displayHeight() { return quarterTurn() ? width : height; }
        private boolean quarterTurn() {
            int r = ((rotation % 360) + 360) % 360;
            return r == 90 || r == 270;
        }
        public MediaSource copy() {
            MediaSource s = new MediaSource(id, uri, name, durationUs, width, height,
                    rotation, hasAudio, persisted);
            s.missing = missing;
            return s;
        }
    }

    public static final class EditClip {
        public final String id;
        public final String sourceId;
        /** Source-media range, not timeline duration. */
        public long inUs;
        public long outUs;
        public float volume = 1f;
        public float speed = 1f;
        /** 0 = original, 1 = offline RNNoise voice isolation. */
        public int voiceMode;
        public float voiceStrength = 0.85f;
        /** Extra user rotation, quarter turns clockwise. */
        public int rotation;
        public boolean flipH;
        public boolean flipV;
        public long fadeInUs;
        public long fadeOutUs;
        /** 0 none, 1 warm, 2 cool, 3 mono, 4 vivid */
        public int colorPreset;

        public EditClip(String id, String sourceId, long inUs, long outUs, float volume) {
            this.id = id;
            this.sourceId = sourceId;
            this.inUs = inUs;
            this.outUs = outUs;
            this.volume = clampVolume(volume);
        }

        public static EditClip fromSource(MediaSource source) {
            return new EditClip(UUID.randomUUID().toString(), source.id, 0,
                    source.durationUs, 1f);
        }

        public long sourceDurationUs() { return Math.max(0, outUs - inUs); }
        public long durationUs() {
            return Math.max(0, Math.round(sourceDurationUs() / clampSpeed(speed)));
        }
        public long sourceOffsetForTimeline(long timelineLocalUs) {
            return Math.round(Math.max(0, timelineLocalUs) * clampSpeed(speed));
        }
        public EditClip copy() {
            EditClip c = new EditClip(id, sourceId, inUs, outUs, volume);
            c.speed = speed;
            c.voiceMode = voiceMode;
            c.voiceStrength = voiceStrength;
            c.rotation = rotation;
            c.flipH = flipH;
            c.flipV = flipV;
            c.fadeInUs = fadeInUs;
            c.fadeOutUs = fadeOutUs;
            c.colorPreset = colorPreset;
            return c;
        }
    }

    public static final class TextOverlay {
        public final String id;
        public String text;
        public long startUs;
        public long durationUs = 3_000_000L;
        /** Normalized center position inside the export canvas. */
        public float x = 0.5f;
        public float y = 0.82f;
        /** Text size as a fraction of canvas height. */
        public float size = 0.055f;
        public int color = 0xFFFFFFFF;
        public boolean box = true;

        public TextOverlay(String id, String text, long startUs, long durationUs) {
            this.id = id;
            this.text = text;
            this.startUs = startUs;
            this.durationUs = durationUs;
        }
        public TextOverlay copy() {
            TextOverlay t = new TextOverlay(id, text, startUs, durationUs);
            t.x = x; t.y = y; t.size = size; t.color = color; t.box = box;
            return t;
        }
    }

    public static final class AudioTrack {
        public final String id;
        public final String uri;
        public String name;
        public long startUs;
        public long inUs;
        public long durationUs;
        public float volume = 0.8f;
        public transient boolean missing;

        public AudioTrack(String id, String uri, String name, long startUs,
                          long inUs, long durationUs, float volume) {
            this.id = id;
            this.uri = uri;
            this.name = name;
            this.startUs = startUs;
            this.inUs = inUs;
            this.durationUs = durationUs;
            this.volume = clampVolume(volume);
        }
        public AudioTrack copy() {
            AudioTrack a = new AudioTrack(id, uri, name, startUs, inUs, durationUs, volume);
            a.missing = missing;
            return a;
        }
    }

    public static float clampVolume(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return 1f;
        return Math.max(0f, Math.min(2f, v));
    }
    public static float clampSpeed(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return 1f;
        return Math.max(MIN_SPEED, Math.min(MAX_SPEED, v));
    }
    public static float clampStrength(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return 0.85f;
        return Math.max(0f, Math.min(1f, v));
    }

    public long totalUs() {
        long total = 0;
        for (EditClip c : clips) total += c.durationUs();
        return total;
    }
    public int indexOf(String clipId) {
        if (clipId == null) return -1;
        for (int i = 0; i < clips.size(); i++) if (clips.get(i).id.equals(clipId)) return i;
        return -1;
    }
    public EditClip find(String clipId) {
        int i = indexOf(clipId);
        return i < 0 ? null : clips.get(i);
    }
    public long startUs(String clipId) {
        long start = 0;
        for (EditClip c : clips) {
            if (c.id.equals(clipId)) return start;
            start += c.durationUs();
        }
        return -1;
    }
    public int indexAt(long timelineUs) {
        if (clips.isEmpty()) return -1;
        long t = Math.max(0, timelineUs), end = 0;
        for (int i = 0; i < clips.size(); i++) {
            end += clips.get(i).durationUs();
            if (t < end) return i;
        }
        return clips.size() - 1;
    }
    public EditClip clipAt(long timelineUs) {
        int i = indexAt(timelineUs);
        return i < 0 ? null : clips.get(i);
    }
    public MediaSource sourceFor(EditClip clip) {
        return clip == null ? null : sources.get(clip.sourceId);
    }

    public EditClip addSource(MediaSource source) {
        MediaSource existing = null;
        for (MediaSource s : sources.values()) {
            if (s.uri.equals(source.uri)) { existing = s; break; }
        }
        if (existing == null) {
            sources.put(source.id, source);
            existing = source;
        } else {
            existing.name = source.name;
            existing.durationUs = source.durationUs;
            existing.width = source.width;
            existing.height = source.height;
            existing.rotation = source.rotation;
            existing.hasAudio = source.hasAudio;
            existing.persisted = existing.persisted || source.persisted;
            existing.missing = false;
        }
        EditClip clip = EditClip.fromSource(existing);
        clips.add(clip);
        return clip;
    }

    /** Splits at an absolute timeline position; speed is inherited. */
    public EditClip splitAt(String clipId, long timelineUs) {
        int index = indexOf(clipId);
        if (index < 0) throw new IllegalArgumentException("ক্লিপটি পাওয়া যায়নি");
        EditClip left = clips.get(index);
        long local = timelineUs - startUs(clipId);
        long sourceLocal = left.sourceOffsetForTimeline(local);
        if (sourceLocal < MIN_CLIP_US || left.sourceDurationUs() - sourceLocal < MIN_CLIP_US) {
            throw new IllegalArgumentException("কাটার জায়গাটি ক্লিপের শুরু বা শেষ থেকে অন্তত ০.১ সেকেন্ড (সোর্স সময়) দূরে রাখুন");
        }
        long cut = left.inUs + sourceLocal;
        EditClip right = left.copy();
        right.inUs = cut;
        // copy() keeps the final id; assign a new one through a fresh instance.
        EditClip fresh = new EditClip(UUID.randomUUID().toString(), right.sourceId,
                right.inUs, right.outUs, right.volume);
        fresh.speed = right.speed;
        fresh.voiceMode = right.voiceMode;
        fresh.voiceStrength = right.voiceStrength;
        fresh.rotation = right.rotation;
        fresh.flipH = right.flipH;
        fresh.flipV = right.flipV;
        fresh.colorPreset = right.colorPreset;
        // Fades belong to the original whole clip; do not duplicate a full fade.
        fresh.fadeInUs = 0;
        fresh.fadeOutUs = right.fadeOutUs;
        left.outUs = cut;
        left.fadeOutUs = 0;
        clips.add(index + 1, fresh);
        return fresh;
    }

    public void trim(String clipId, long inUs, long outUs) {
        EditClip c = find(clipId);
        if (c == null) throw new IllegalArgumentException("ক্লিপটি পাওয়া যায়নি");
        MediaSource s = sources.get(c.sourceId);
        if (s == null) throw new IllegalArgumentException("সোর্স ভিডিওটি পাওয়া যায়নি");
        long in = Math.max(0, Math.min(inUs, s.durationUs));
        long out = Math.max(0, Math.min(outUs, s.durationUs));
        if (out - in < MIN_CLIP_US) throw new IllegalArgumentException("ক্লিপটি অন্তত ০.১ সেকেন্ড লম্বা হতে হবে");
        c.inUs = in;
        c.outUs = out;
        clampFades(c);
    }
    public void nudgeTrim(String clipId, boolean leftEdge, long deltaUs) {
        EditClip c = find(clipId);
        if (c == null) return;
        MediaSource s = sources.get(c.sourceId);
        if (s == null) return;
        if (leftEdge) c.inUs = Math.max(0, Math.min(c.inUs + deltaUs, c.outUs - MIN_CLIP_US));
        else c.outUs = Math.min(s.durationUs, Math.max(c.outUs + deltaUs, c.inUs + MIN_CLIP_US));
        clampFades(c);
    }
    public void setVolume(String clipId, float volume) {
        EditClip c = find(clipId);
        if (c != null) c.volume = clampVolume(volume);
    }
    public void setSpeed(String clipId, float speed) {
        EditClip c = find(clipId);
        if (c == null) return;
        c.speed = clampSpeed(speed);
        clampFades(c);
    }
    public static void clampFades(EditClip c) {
        long d = c.durationUs();
        c.fadeInUs = Math.max(0, Math.min(c.fadeInUs, d));
        c.fadeOutUs = Math.max(0, Math.min(c.fadeOutUs, d - c.fadeInUs));
    }

    public EditClip duplicate(String clipId) {
        int i = indexOf(clipId);
        if (i < 0) throw new IllegalArgumentException("ক্লিপটি পাওয়া যায়নি");
        EditClip original = clips.get(i);
        EditClip copy = original.copy();
        EditClip fresh = new EditClip(UUID.randomUUID().toString(), copy.sourceId,
                copy.inUs, copy.outUs, copy.volume);
        fresh.speed = copy.speed;
        fresh.voiceMode = copy.voiceMode;
        fresh.voiceStrength = copy.voiceStrength;
        fresh.rotation = copy.rotation;
        fresh.flipH = copy.flipH;
        fresh.flipV = copy.flipV;
        fresh.fadeInUs = copy.fadeInUs;
        fresh.fadeOutUs = copy.fadeOutUs;
        fresh.colorPreset = copy.colorPreset;
        clips.add(i + 1, fresh);
        return fresh;
    }
    public boolean move(String clipId, int delta) {
        int i = indexOf(clipId);
        if (i < 0) return false;
        int j = Math.max(0, Math.min(clips.size() - 1, i + delta));
        if (i == j) return false;
        clips.add(j, clips.remove(i));
        return true;
    }
    public boolean remove(String clipId) {
        int i = indexOf(clipId);
        if (i < 0) return false;
        clips.remove(i);
        return true;
    }
    public boolean hasMissingSources() {
        for (EditClip c : clips) {
            MediaSource s = sources.get(c.sourceId);
            if (s == null || s.missing) return true;
        }
        for (AudioTrack a : audioTracks) if (a.missing) return true;
        return false;
    }
    public EditProject deepCopy() {
        EditProject p = new EditProject();
        p.name = name;
        p.aspect = aspect;
        p.fitCanvas = fitCanvas;
        for (Map.Entry<String, MediaSource> e : sources.entrySet()) p.sources.put(e.getKey(), e.getValue().copy());
        for (EditClip c : clips) p.clips.add(c.copy());
        for (TextOverlay t : texts) p.texts.add(t.copy());
        for (AudioTrack a : audioTracks) p.audioTracks.add(a.copy());
        return p;
    }
    public List<String> validate() {
        ArrayList<String> errors = new ArrayList<>();
        if (clips.isEmpty()) errors.add("আগে একটি ভিডিও যোগ করুন");
        for (int i = 0; i < clips.size(); i++) {
            EditClip c = clips.get(i);
            MediaSource s = sources.get(c.sourceId);
            if (s == null || s.missing) errors.add("ক্লিপ " + (i + 1) + ": সোর্স ভিডিওটি খোলা যাচ্ছে না");
            else if (c.inUs < 0 || c.outUs > s.durationUs || c.sourceDurationUs() < MIN_CLIP_US)
                errors.add("ক্লিপ " + (i + 1) + ": ট্রিম সীমা ঠিক নেই");
            if (c.voiceMode == 1 && (s == null || !s.hasAudio))
                errors.add("ক্লিপ " + (i + 1) + ": ভয়েস আইসোলেশনের জন্য অডিও নেই");
        }
        for (AudioTrack a : audioTracks) {
            if (a.missing) errors.add("অডিও ট্র্যাক খোলা যাচ্ছে না: " + a.name);
            if (a.durationUs < MIN_CLIP_US) errors.add("অডিও ট্র্যাক খুব ছোট: " + a.name);
        }
        return errors;
    }
}
