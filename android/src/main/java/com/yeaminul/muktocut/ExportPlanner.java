package com.yeaminul.muktocut;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds an argument array (never a shell string) for an accurate, re-encoding
 * FFmpeg export. Each unique source is opened once; split/asplit feed multiple
 * timeline ranges from the same source without opening the file repeatedly.
 */
public final class ExportPlanner {
    public static final int FPS = 30;
    public static final int SAMPLE_RATE = 48_000;
    public static final int CRF = 23;

    private ExportPlanner() {}

    public static final class Plan {
        public final String[] arguments;
        public final int width;
        public final int height;
        public final long durationUs;

        Plan(String[] arguments, int width, int height, long durationUs) {
            this.arguments = arguments;
            this.width = width;
            this.height = height;
            this.durationUs = durationUs;
        }
    }

    public static Plan build(EditProject project, Map<String, String> inputPaths,
                             int maxShortEdge, String outputPath) {
        return build(project, inputPaths, new LinkedHashMap<>(), new LinkedHashMap<>(),
                maxShortEdge, outputPath, null);
    }

    /**
     * @param audioPaths  audio-track id -> readable FFmpeg input path
     * @param textImages  text-overlay id -> full-canvas transparent PNG path
     * @param voiceModel  readable RNNoise .rnnn model path, or null to disable
     */
    public static Plan build(EditProject project, Map<String, String> inputPaths,
                             Map<String, String> audioPaths, Map<String, String> textImages,
                             int maxShortEdge, String outputPath, String voiceModel) {
        List<String> errors = project.validate();
        if (!errors.isEmpty()) throw new IllegalArgumentException(errors.get(0));
        if (outputPath == null || outputPath.isEmpty()) {
            throw new IllegalArgumentException("আউটপুট ফাইল ঠিক করা হয়নি");
        }
        boolean needsVoice = false;
        for (EditProject.EditClip c : project.clips) if (c.voiceMode == 1) needsVoice = true;
        if (needsVoice && (voiceModel == null || voiceModel.isEmpty())) {
            throw new IllegalArgumentException("ভয়েস আইসোলেশন মডেল পাওয়া যায়নি");
        }

        LinkedHashMap<String, String> sourceInputs = new LinkedHashMap<>();
        for (EditProject.EditClip c : project.clips) {
            if (!sourceInputs.containsKey(c.sourceId)) {
                String path = inputPaths.get(c.sourceId);
                if (path == null || path.isEmpty()) {
                    throw new IllegalArgumentException("সোর্স ভিডিওটি পড়া যাচ্ছে না: " + c.sourceId);
                }
                sourceInputs.put(c.sourceId, path);
            }
        }

        int[] size = outputSize(project, maxShortEdge);
        int w = size[0], h = size[1];
        LinkedHashMap<String, Integer> sourceIndex = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> sourceCount = new LinkedHashMap<>();
        int index = 0;
        for (String sourceId : sourceInputs.keySet()) {
            sourceIndex.put(sourceId, index++);
            int count = 0;
            for (EditProject.EditClip c : project.clips) if (c.sourceId.equals(sourceId)) count++;
            sourceCount.put(sourceId, count);
        }

        StringBuilder f = new StringBuilder();
        for (Map.Entry<String, Integer> e : sourceCount.entrySet()) {
            String sid = e.getKey();
            int si = sourceIndex.get(sid);
            int count = e.getValue();
            if (count > 1) {
                f.append('[').append(si).append(":v]split=").append(count);
                for (int k = 0; k < count; k++) f.append("[s").append(si).append('v').append(k).append(']');
                f.append(';');
            }
            EditProject.MediaSource source = project.sources.get(sid);
            if (source.hasAudio && count > 1) {
                f.append('[').append(si).append(":a]asplit=").append(count);
                for (int k = 0; k < count; k++) f.append("[s").append(si).append('a').append(k).append(']');
                f.append(';');
            }
        }

        LinkedHashMap<String, Integer> occurrence = new LinkedHashMap<>();
        ArrayList<String> concatInputs = new ArrayList<>();
        for (int i = 0; i < project.clips.size(); i++) {
            EditProject.EditClip c = project.clips.get(i);
            EditProject.MediaSource s = project.sources.get(c.sourceId);
            int si = sourceIndex.get(c.sourceId);
            int count = sourceCount.get(c.sourceId);
            int k = occurrence.containsKey(c.sourceId) ? occurrence.get(c.sourceId) : 0;
            occurrence.put(c.sourceId, k + 1);
            String vin = count == 1 ? si + ":v" : "s" + si + "v" + k;
            String vout = "v" + i;
            String aout = "a" + i;
            double start = c.inUs / 1_000_000.0;
            double end = c.outUs / 1_000_000.0;
            double duration = c.durationUs() / 1_000_000.0;
            float speed = EditProject.clampSpeed(c.speed);

            f.append('[').append(vin).append(']')
                    .append("trim=start=").append(sec(start)).append(":end=").append(sec(end))
                    .append(",setpts=(PTS-STARTPTS)/").append(sec(speed));
            appendTransform(f, c);
            appendColor(f, c);
            if (project.fitCanvas) {
                f.append(",scale=").append(w).append(':').append(h)
                        .append(":force_original_aspect_ratio=decrease")
                        .append(",pad=").append(w).append(':').append(h)
                        .append(":(ow-iw)/2:(oh-ih)/2:color=black");
            } else {
                f.append(",scale=").append(w).append(':').append(h)
                        .append(":force_original_aspect_ratio=increase")
                        .append(",crop=").append(w).append(':').append(h);
            }
            f.append(",setsar=1,fps=").append(FPS);
            if (c.fadeInUs > 0) f.append(",fade=t=in:st=0:d=").append(sec(c.fadeInUs / 1_000_000.0));
            if (c.fadeOutUs > 0) f.append(",fade=t=out:st=").append(sec(Math.max(0, duration - c.fadeOutUs / 1_000_000.0)))
                    .append(":d=").append(sec(c.fadeOutUs / 1_000_000.0));
            f.append(",format=yuv420p[").append(vout).append("];");

            if (s.hasAudio) {
                String ain = count == 1 ? si + ":a" : "s" + si + "a" + k;
                f.append('[').append(ain).append(']')
                        .append("atrim=start=").append(sec(start)).append(":end=").append(sec(end))
                        .append(",asetpts=PTS-STARTPTS");
                appendTempo(f, speed);
                if (c.voiceMode == 1) {
                    f.append(",aresample=").append(SAMPLE_RATE)
                            .append(",aformat=sample_fmts=flt:channel_layouts=mono")
                            .append(",highpass=f=80,arnndn=m='").append(escapeFilterPath(voiceModel))
                            .append("':mix=").append(sec(EditProject.clampStrength(c.voiceStrength)))
                            .append(",lowpass=f=12000")
                            .append(",aformat=sample_fmts=fltp:channel_layouts=stereo");
                }
                f.append(",volume=").append(sec(c.volume));
                if (c.fadeInUs > 0) f.append(",afade=t=in:st=0:d=").append(sec(c.fadeInUs / 1_000_000.0));
                if (c.fadeOutUs > 0) f.append(",afade=t=out:st=").append(sec(Math.max(0, duration - c.fadeOutUs / 1_000_000.0)))
                        .append(":d=").append(sec(c.fadeOutUs / 1_000_000.0));
                f.append(",aresample=").append(SAMPLE_RATE).append(":async=1:first_pts=0")
                        .append(",aformat=sample_fmts=fltp:channel_layouts=stereo[")
                        .append(aout).append("];");
            } else {
                f.append("anullsrc=r=").append(SAMPLE_RATE).append(":cl=stereo")
                        .append(",atrim=duration=").append(sec(duration))
                        .append(",asetpts=PTS-STARTPTS")
                        .append(",aformat=sample_fmts=fltp:channel_layouts=stereo[")
                        .append(aout).append("];");
            }
            concatInputs.add("[" + vout + "][" + aout + "]");
        }

        for (String pair : concatInputs) f.append(pair);
        f.append("concat=n=").append(project.clips.size()).append(":v=1:a=1[basev][basea];");

        String currentV = "basev";
        int nextInput = sourceInputs.size();
        for (EditProject.TextOverlay t : project.texts) {
            String png = textImages.get(t.id);
            if (png == null || png.isEmpty()) throw new IllegalArgumentException("টেক্সট ওভারলে রেন্ডার হয়নি: " + t.text);
            int ti = nextInput++;
            String label = "vt" + ti;
            double ts = t.startUs / 1_000_000.0;
            double te = (t.startUs + t.durationUs) / 1_000_000.0;
            f.append('[').append(currentV).append("][").append(ti).append(":v]overlay=0:0:eof_action=repeat:enable='between(t,")
                    .append(sec(ts)).append(',').append(sec(te)).append(")'[").append(label).append("];");
            currentV = label;
        }

        String currentA = "basea";
        ArrayList<String> mixInputs = new ArrayList<>();
        mixInputs.add("[basea]");
        for (EditProject.AudioTrack a : project.audioTracks) {
            String path = audioPaths.get(a.id);
            if (path == null || path.isEmpty()) throw new IllegalArgumentException("অডিও ট্র্যাক পড়া যাচ্ছে না: " + a.name);
            int ai = nextInput++;
            String label = "ta" + ai;
            long delayMs = Math.max(0, a.startUs / 1000L);
            f.append('[').append(ai).append(":a]atrim=start=").append(sec(a.inUs / 1_000_000.0))
                    .append(":duration=").append(sec(a.durationUs / 1_000_000.0))
                    .append(",asetpts=PTS-STARTPTS,volume=").append(sec(a.volume))
                    .append(",adelay=").append(delayMs).append('|').append(delayMs)
                    .append(",aformat=sample_fmts=fltp:sample_rates=").append(SAMPLE_RATE)
                    .append(":channel_layouts=stereo[").append(label).append("];");
            mixInputs.add("[" + label + "]");
        }
        if (mixInputs.size() > 1) {
            for (String label : mixInputs) f.append(label);
            f.append("amix=inputs=").append(mixInputs.size())
                    .append(":duration=first:dropout_transition=0:normalize=0,alimiter=limit=0.95[mixa];");
            currentA = "mixa";
        }

        ArrayList<String> args = new ArrayList<>();
        args.add("-nostdin");
        args.add("-hide_banner");
        args.add("-y");
        for (String path : sourceInputs.values()) { args.add("-i"); args.add(path); }
        for (EditProject.TextOverlay t : project.texts) { args.add("-i"); args.add(textImages.get(t.id)); }
        for (EditProject.AudioTrack a : project.audioTracks) { args.add("-i"); args.add(audioPaths.get(a.id)); }
        args.add("-filter_complex");
        args.add(f.toString());
        args.add("-map");
        args.add("[" + currentV + "]");
        args.add("-map");
        args.add("[" + currentA + "]");
        args.add("-c:v"); args.add("libx264");
        args.add("-preset"); args.add("veryfast");
        args.add("-crf"); args.add(Integer.toString(CRF));
        args.add("-profile:v"); args.add("high");
        args.add("-pix_fmt"); args.add("yuv420p");
        args.add("-threads"); args.add("2");
        args.add("-c:a"); args.add("aac");
        args.add("-b:a"); args.add("160k");
        args.add("-ar"); args.add(Integer.toString(SAMPLE_RATE));
        args.add("-ac"); args.add("2");
        args.add("-movflags"); args.add("+faststart");
        args.add("-map_metadata"); args.add("-1");
        args.add("-max_muxing_queue_size"); args.add("1024");
        args.add("-t"); args.add(sec(project.totalUs() / 1_000_000.0));
        args.add(outputPath);
        return new Plan(args.toArray(new String[0]), w, h, project.totalUs());
    }

    private static void appendTransform(StringBuilder f, EditProject.EditClip c) {
        int r = ((c.rotation % 4) + 4) % 4;
        if (r == 1) f.append(",transpose=1");
        else if (r == 2) f.append(",transpose=1,transpose=1");
        else if (r == 3) f.append(",transpose=2");
        if (c.flipH) f.append(",hflip");
        if (c.flipV) f.append(",vflip");
    }

    private static void appendColor(StringBuilder f, EditProject.EditClip c) {
        switch (c.colorPreset) {
            case 1: f.append(",colorbalance=rs=0.06:bs=-0.06"); break;
            case 2: f.append(",colorbalance=rs=-0.06:bs=0.06"); break;
            case 3: f.append(",colorchannelmixer=rr=0.299:rg=0.587:rb=0.114:gr=0.299:gg=0.587:gb=0.114:br=0.299:bg=0.587:bb=0.114"); break;
            case 4: f.append(",curves=preset=increase_contrast"); break;
            default: break;
        }
    }

    private static void appendTempo(StringBuilder f, float speed) {
        float remaining = speed;
        if (Math.abs(remaining - 1f) < 0.001f) return;
        while (remaining > 2.0f) { f.append(",atempo=2.0"); remaining /= 2.0f; }
        while (remaining < 0.5f) { f.append(",atempo=0.5"); remaining /= 0.5f; }
        if (Math.abs(remaining - 1f) >= 0.001f) f.append(",atempo=").append(sec(remaining));
    }

    /** even dimensions for the selected canvas aspect. */
    public static int[] outputSize(EditProject project, int maxShortEdge) {
        if (project.clips.isEmpty()) throw new IllegalArgumentException("আগে একটি ভিডিও যোগ করুন");
        EditProject.EditClip first = project.clips.get(0);
        EditProject.MediaSource s = project.sources.get(first.sourceId);
        if (s == null) throw new IllegalArgumentException("সোর্স ভিডিওটি পাওয়া যায়নি");
        double ratio;
        switch (project.aspect == null ? "original" : project.aspect) {
            case "9:16": ratio = 9.0 / 16.0; break;
            case "16:9": ratio = 16.0 / 9.0; break;
            case "1:1": ratio = 1.0; break;
            case "4:5": ratio = 4.0 / 5.0; break;
            default: ratio = s.displayWidth() / (double) Math.max(1, s.displayHeight()); break;
        }
        int baseW = s.displayWidth(), baseH = s.displayHeight();
        int w, h;
        if (ratio >= 1) { w = Math.max(baseW, baseH); h = (int) Math.round(w / ratio); }
        else { h = Math.max(baseW, baseH); w = (int) Math.round(h * ratio); }
        w = Math.max(2, w); h = Math.max(2, h);
        int shortEdge = Math.min(w, h), longEdge = Math.max(w, h);
        double scale;
        if (maxShortEdge <= 0) scale = Math.min(1.0, Math.min(1080.0 / shortEdge, 1920.0 / longEdge));
        else scale = Math.min((double) maxShortEdge / shortEdge, (maxShortEdge * 16.0 / 9.0) / longEdge);
        int ow = Math.max(2, (int) Math.round(w * scale / 2.0) * 2);
        int oh = Math.max(2, (int) Math.round(h * scale / 2.0) * 2);
        return new int[]{ow, oh};
    }

    private static String escapeFilterPath(String path) {
        return path.replace("\\", "\\\\").replace("'", "'\\''").replace(":", "\\:");
    }

    private static String sec(double value) {
        return String.format(Locale.US, "%.6f", value);
    }
}
