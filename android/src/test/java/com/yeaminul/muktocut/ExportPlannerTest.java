package com.yeaminul.muktocut;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ExportPlannerTest {
    public static void main(String[] unused) {
        EditProject p = new EditProject();
        EditProject.MediaSource a = new EditProject.MediaSource("a", "content://a", "a.mp4",
                10_000_000L, 1920, 1080, 0, true, true);
        EditProject.MediaSource b = new EditProject.MediaSource("b", "content://b", "b.mp4",
                5_000_000L, 1080, 1920, 0, false, true);
        EditProject.EditClip c1 = p.addSource(a);
        p.addSource(b);
        p.splitAt(c1.id, 4_000_000L);
        p.setVolume(c1.id, 0f);
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("a", "saf:1");
        paths.put("b", "saf:2");
        ExportPlanner.Plan plan = ExportPlanner.build(p, paths, 0, "/tmp/out.mp4");
        List<String> args = Arrays.asList(plan.arguments);
        check(plan.width == 1920 && plan.height == 1080, "auto keeps landscape size");
        check(count(args, "-i") == 2, "unique sources opened once");
        check(args.contains("saf:1") && args.contains("saf:2"), "SAF paths passed as arguments");
        check(args.contains("libx264") && args.contains("aac"), "mp4 codecs");
        check(args.contains("+faststart"), "streamable mp4");
        String filter = args.get(args.indexOf("-filter_complex") + 1);
        check(filter.contains("split=2"), "same-source video split");
        check(filter.contains("asplit=2"), "same-source audio split");
        check(filter.contains("anullsrc"), "silent source gets silence");
        check(filter.contains("concat=n=3"), "timeline order concat");
        check(filter.contains("volume=0.000000"), "mute exported");
        check(filter.contains(";"), "filter graph uses semicolons");
        check(filter.contains("[basev][basea]"), "concat outputs mapped");
        check(args.get(args.size() - 1).equals("/tmp/out.mp4"), "output path last");

        int[] portrait = ExportPlanner.outputSize(p, 720);
        check(portrait[0] == 1280 && portrait[1] == 720, "landscape 720p box");
        EditProject q = new EditProject();
        q.addSource(b);
        int[] pq = ExportPlanner.outputSize(q, 720);
        check(pq[0] == 720 && pq[1] == 1280, "portrait 720p box");
        int[] autoPortrait = ExportPlanner.outputSize(q, 0);
        check(autoPortrait[0] == 1080 && autoPortrait[1] == 1920, "auto portrait no upscale");

        EditProject.MediaSource rotated = new EditProject.MediaSource("r", "content://r", "r.mp4",
                2_000_000L, 1920, 1080, 90, true, true);
        EditProject r = new EditProject();
        r.addSource(rotated);
        int[] rs = ExportPlanner.outputSize(r, 0);
        check(rs[0] == 1080 && rs[1] == 1920, "rotation metadata swaps display size");

        EditProject advanced = new EditProject();
        advanced.addSource(a);
        EditProject.EditClip ac = advanced.clips.get(0);
        advanced.setSpeed(ac.id, 2f);
        ac.voiceMode = 1;
        ac.rotation = 1;
        ac.flipH = true;
        ac.colorPreset = 3;
        ac.fadeInUs = 500_000L;
        advanced.aspect = "9:16";
        advanced.fitCanvas = false;
        advanced.texts.add(new EditProject.TextOverlay("t", "বাংলা টেক্সট", 0, 1_000_000L));
        advanced.audioTracks.add(new EditProject.AudioTrack("m", "content://m", "m.mp3", 0, 0, 2_000_000L, 0.5f));
        Map<String, String> advPaths = new LinkedHashMap<>(); advPaths.put("a", "saf:a");
        Map<String, String> advAudio = new LinkedHashMap<>(); advAudio.put("m", "saf:m");
        Map<String, String> advText = new LinkedHashMap<>(); advText.put("t", "/tmp/t.png");
        ExportPlanner.Plan adv = ExportPlanner.build(advanced, advPaths, advAudio, advText, 720, "/tmp/adv.mp4", "/tmp/model.rnnn");
        String af = Arrays.asList(adv.arguments).get(Arrays.asList(adv.arguments).indexOf("-filter_complex") + 1);
        check(adv.width == 720 && adv.height == 1280, "9:16 canvas size");
        check(af.contains("arnndn=m='/tmp/model.rnnn'"), "voice isolation model");
        check(af.contains("atempo=2.000000"), "speed tempo");
        check(af.contains("transpose=1") && af.contains("hflip"), "rotate/flip export");
        check(af.contains("colorchannelmixer"), "mono preset");
        check(af.contains("overlay=0:0") && af.contains("between(t,"), "text overlay timing");
        check(af.contains("amix=inputs=2") && af.contains("adelay="), "audio track mix");
        check(af.contains("crop=720:1280"), "fill crops canvas");
        check(Arrays.asList(adv.arguments).contains("/tmp/t.png"), "text png input");
        check(Arrays.asList(adv.arguments).contains("saf:m"), "audio track input");
        expectIllegal(() -> ExportPlanner.build(advanced, advPaths, advAudio, advText, 720, "/tmp/adv.mp4", null),
                "voice model required");

        expectIllegal(() -> ExportPlanner.build(new EditProject(), paths, 0, "/tmp/out.mp4"),
                "empty project rejected");
        expectIllegal(() -> ExportPlanner.build(p, new LinkedHashMap<>(), 0, "/tmp/out.mp4"),
                "missing input path rejected");
        System.out.println("ExportPlannerTest: OK");
    }

    private static int count(List<String> args, String value) {
        int n = 0;
        for (String s : args) if (s.equals(value)) n++;
        return n;
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
