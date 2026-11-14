package com.yeaminul.muktocut;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure editing model. All times are microseconds. Android APIs are deliberately
 * kept out of this class so the cut/split/trim rules can be tested on the JVM.
 */
public final class EditProject {
    public static final long MIN_CLIP_US = 100_000L; // 0.1 s
    public String name = "আমার প্রজেক্ট";
    public final LinkedHashMap<String, MediaSource> sources = new LinkedHashMap<>();
    public final ArrayList<EditClip> clips = new ArrayList<>();

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
        /** Runtime-only: the document provider no longer lets us read this URI. */
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

        public int displayWidth() {
            return quarterTurn() ? height : width;
        }

        public int displayHeight() {
            return quarterTurn() ? width : height;
        }

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
        public long inUs;
        public long outUs;
        public float volume = 1f;

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

        public long durationUs() {
            return Math.max(0, outUs - inUs);
        }

        public EditClip copy() {
            return new EditClip(id, sourceId, inUs, outUs, volume);
        }
    }

    public static float clampVolume(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return 1f;
        return Math.max(0f, Math.min(2f, v));
    }

    public long totalUs() {
        long total = 0;
        for (EditClip c : clips) total += c.durationUs();
        return total;
    }

    public int indexOf(String clipId) {
        if (clipId == null) return -1;
        for (int i = 0; i < clips.size(); i++) {
            if (clips.get(i).id.equals(clipId)) return i;
        }
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

    /** Returns the clip containing the playhead; the exact end maps to the last clip. */
    public int indexAt(long timelineUs) {
        if (clips.isEmpty()) return -1;
        long t = Math.max(0, timelineUs);
        long end = 0;
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
            if (s.uri.equals(source.uri)) {
                existing = s;
                break;
            }
        }
        if (existing == null) {
            sources.put(source.id, source);
            existing = source;
        } else {
            // Re-importing the same document refreshes metadata and clears a
            // stale "missing" flag after the user restores file access.
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

    /** Splits at an absolute timeline position. Returns the new right-hand clip. */
    public EditClip splitAt(String clipId, long timelineUs) {
        int index = indexOf(clipId);
        if (index < 0) throw new IllegalArgumentException("ক্লিপটি পাওয়া যায়নি");
        EditClip left = clips.get(index);
        long local = timelineUs - startUs(clipId);
        if (local < MIN_CLIP_US || left.durationUs() - local < MIN_CLIP_US) {
            throw new IllegalArgumentException("কাটার জায়গাটি ক্লিপের শুরু বা শেষ থেকে অন্তত ০.১ সেকেন্ড দূরে রাখুন");
        }
        long cut = left.inUs + local;
        EditClip right = new EditClip(UUID.randomUUID().toString(), left.sourceId,
                cut, left.outUs, left.volume);
        left.outUs = cut;
        clips.add(index + 1, right);
        return right;
    }

    public void trim(String clipId, long inUs, long outUs) {
        EditClip c = find(clipId);
        if (c == null) throw new IllegalArgumentException("ক্লিপটি পাওয়া যায়নি");
        MediaSource s = sources.get(c.sourceId);
        if (s == null) throw new IllegalArgumentException("সোর্স ভিডিওটি পাওয়া যায়নি");
        long in = Math.max(0, Math.min(inUs, s.durationUs));
        long out = Math.max(0, Math.min(outUs, s.durationUs));
        if (out - in < MIN_CLIP_US) {
            throw new IllegalArgumentException("ক্লিপটি অন্তত ০.১ সেকেন্ড লম্বা হতে হবে");
        }
        c.inUs = in;
        c.outUs = out;
    }

    /** Incremental edge drag, clamped to the source and the minimum clip length. */
    public void nudgeTrim(String clipId, boolean leftEdge, long deltaUs) {
        EditClip c = find(clipId);
        if (c == null) return;
        MediaSource s = sources.get(c.sourceId);
        if (s == null) return;
        if (leftEdge) {
            long in = Math.max(0, Math.min(c.inUs + deltaUs, c.outUs - MIN_CLIP_US));
            c.inUs = in;
        } else {
            long out = Math.min(s.durationUs, Math.max(c.outUs + deltaUs, c.inUs + MIN_CLIP_US));
            c.outUs = out;
        }
    }

    public void setVolume(String clipId, float volume) {
        EditClip c = find(clipId);
        if (c != null) c.volume = clampVolume(volume);
    }

    public EditClip duplicate(String clipId) {
        int i = indexOf(clipId);
        if (i < 0) throw new IllegalArgumentException("ক্লিপটি পাওয়া যায়নি");
        EditClip original = clips.get(i);
        EditClip copy = new EditClip(UUID.randomUUID().toString(), original.sourceId,
                original.inUs, original.outUs, original.volume);
        clips.add(i + 1, copy);
        return copy;
    }

    public boolean move(String clipId, int delta) {
        int i = indexOf(clipId);
        if (i < 0) return false;
        int j = Math.max(0, Math.min(clips.size() - 1, i + delta));
        if (i == j) return false;
        EditClip c = clips.remove(i);
        clips.add(j, c);
        return true;
    }

    public boolean remove(String clipId) {
        int i = indexOf(clipId);
        if (i < 0) return false;
        clips.remove(i);
        // Sources are retained so undo can restore clips without re-importing.
        return true;
    }

    public boolean hasMissingSources() {
        for (EditClip c : clips) {
            MediaSource s = sources.get(c.sourceId);
            if (s == null || s.missing) return true;
        }
        return false;
    }

    public EditProject deepCopy() {
        EditProject p = new EditProject();
        p.name = name;
        for (Map.Entry<String, MediaSource> e : sources.entrySet()) {
            p.sources.put(e.getKey(), e.getValue().copy());
        }
        for (EditClip c : clips) p.clips.add(c.copy());
        return p;
    }

    public List<String> validate() {
        ArrayList<String> errors = new ArrayList<>();
        if (clips.isEmpty()) errors.add("আগে একটি ভিডিও যোগ করুন");
        for (int i = 0; i < clips.size(); i++) {
            EditClip c = clips.get(i);
            MediaSource s = sources.get(c.sourceId);
            if (s == null || s.missing) {
                errors.add("ক্লিপ " + (i + 1) + ": সোর্স ভিডিওটি খোলা যাচ্ছে না");
            } else if (c.inUs < 0 || c.outUs > s.durationUs || c.durationUs() < MIN_CLIP_US) {
                errors.add("ক্লিপ " + (i + 1) + ": ট্রিম সীমা ঠিক নেই");
            }
        }
        return errors;
    }
}
