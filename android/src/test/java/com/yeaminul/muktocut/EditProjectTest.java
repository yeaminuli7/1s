package com.yeaminul.muktocut;

import java.util.List;

public final class EditProjectTest {
    public static void main(String[] args) {
        EditProject p = new EditProject();
        EditProject.MediaSource a = source("a", 10_000_000L, true);
        EditProject.MediaSource b = source("b", 5_000_000L, false);
        EditProject.EditClip ca = p.addSource(a);
        EditProject.EditClip cb = p.addSource(b);
        check(p.totalUs() == 15_000_000L, "total duration");
        check(p.indexAt(0) == 0, "start maps to first");
        check(p.indexAt(10_000_000L) == 1, "boundary maps to next");
        check(p.indexAt(99_000_000L) == 1, "past end maps to last");

        EditProject.EditClip right = p.splitAt(ca.id, 4_000_000L);
        check(p.clips.size() == 3, "split adds one clip");
        check(ca.outUs == 4_000_000L && right.inUs == 4_000_000L, "split point");
        check(p.totalUs() == 15_000_000L, "split preserves duration");
        check(p.startUs(right.id) == 4_000_000L, "right clip timeline start");
        expectIllegal(() -> p.splitAt(ca.id, 0), "reject split at edge");
        expectIllegal(() -> p.splitAt(ca.id, 3_950_000L), "reject tiny right fragment");

        p.trim(right.id, 5_000_000L, 8_000_000L);
        check(right.durationUs() == 3_000_000L, "trim duration");
        expectIllegal(() -> p.trim(right.id, 8_000_000L, 5_000_000L), "reject reversed trim");
        expectIllegal(() -> p.trim(right.id, -1_000_000L, 50_000L), "reject tiny trim");
        p.nudgeTrim(right.id, true, -10_000_000L);
        check(right.inUs == 0, "left drag clamps at zero");
        p.nudgeTrim(right.id, false, 100_000_000L);
        check(right.outUs == a.durationUs, "right drag clamps at source end");

        check(p.move(cb.id, -2), "move left");
        check(p.clips.get(0).id.equals(cb.id), "new order");
        check(!p.move(cb.id, -1), "cannot move before start");
        EditProject.EditClip copy = p.duplicate(cb.id);
        check(p.clips.get(1).id.equals(copy.id), "duplicate inserted after original");
        check(copy.id != null && !copy.id.equals(cb.id), "duplicate has new id");
        check(p.remove(copy.id), "remove duplicate");

        p.setVolume(ca.id, 5f);
        check(ca.volume == 2f, "volume clamps high");
        p.setVolume(ca.id, -1f);
        check(ca.volume == 0f, "volume clamps low");
        p.setVolume(ca.id, Float.NaN);
        check(ca.volume == 1f, "NaN volume falls back");

        EditProject clone = p.deepCopy();
        clone.clips.get(0).inUs = 123;
        check(p.clips.get(0).inUs != 123, "deep copy isolates clips");
        clone.sources.get(a.id).missing = true;
        check(!p.sources.get(a.id).missing, "deep copy isolates sources");
        check(p.validate().isEmpty(), "valid project");
        p.sources.get(a.id).missing = true;
        List<String> errors = p.validate();
        check(!errors.isEmpty(), "missing source detected");
        int sourceCount = p.sources.size();
        EditProject.MediaSource reimport = new EditProject.MediaSource("a2", a.uri,
                "a-new.mp4", 10_000_000L, 1920, 1080, 0, true, true);
        p.addSource(reimport);
        check(p.sources.size() == sourceCount, "reimport reuses source by URI");
        check(!p.sources.get(a.id).missing, "reimport clears missing flag");
        System.out.println("EditProjectTest: OK");
    }

    private static EditProject.MediaSource source(String id, long duration, boolean audio) {
        return new EditProject.MediaSource(id, "content://test/" + id, id + ".mp4",
                duration, 1920, 1080, 0, audio, true);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static void expectIllegal(Runnable action, String label) {
        try {
            action.run();
            throw new AssertionError("expected IllegalArgumentException: " + label);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
