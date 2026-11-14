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
        List<String> errors = project.validate();
        if (!errors.isEmpty()) throw new IllegalArgumentException(errors.get(0));
        if (outputPath == null || outputPath.isEmpty()) {
            throw new IllegalArgumentException("আউটপুট ফাইল ঠিক করা হয়নি");
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
            for (EditProject.EditClip c : project.clips) {
                if (c.sourceId.equals(sourceId)) count++;
            }
            sourceCount.put(sourceId, count);
        }

        StringBuilder f = new StringBuilder();
        for (Map.Entry<String, Integer> e : sourceCount.entrySet()) {
            String sid = e.getKey();
            int si = sourceIndex.get(sid);
            int count = e.getValue();
            if (count > 1) {
                f.append('[').append(si).append(":v]split=").append(count);
                for (int k = 0; k < count; k++) {
                    f.append("[s").append(si).append('v').append(k).append(']');
                }
                f.append(';');
            }
            EditProject.MediaSource source = project.sources.get(sid);
            if (source.hasAudio) {
                if (count == 1) {
                    // The single clip can consume [si:a] directly.
                } else {
                    f.append('[').append(si).append(":a]asplit=").append(count);
                    for (int k = 0; k < count; k++) {
                        f.append("[s").append(si).append('a').append(k).append(']');
                    }
                    f.append(';');
                }
            }
        }

        // The loop above only emits split branches. Build per-clip filters in
        // timeline order, tracking which split branch each clip consumes.
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

            f.append('[').append(vin).append(']')
                    .append("trim=start=").append(sec(start)).append(":end=").append(sec(end))
                    .append(",setpts=PTS-STARTPTS")
                    .append(",scale=").append(w).append(':').append(h)
                    .append(":force_original_aspect_ratio=decrease")
                    .append(",pad=").append(w).append(':').append(h)
                    .append(":(ow-iw)/2:(oh-ih)/2:color=black")
                    .append(",setsar=1,fps=").append(FPS)
                    .append(",format=yuv420p[").append(vout).append("];");

            if (s.hasAudio) {
                String ain = count == 1 ? si + ":a" : "s" + si + "a" + k;
                f.append('[').append(ain).append(']')
                        .append("atrim=start=").append(sec(start)).append(":end=").append(sec(end))
                        .append(",asetpts=PTS-STARTPTS,volume=").append(sec(c.volume))
                        .append(",aresample=").append(SAMPLE_RATE).append(":async=1:first_pts=0")
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
        f.append("concat=n=").append(project.clips.size())
                .append(":v=1:a=1[outv][outa]");

        ArrayList<String> args = new ArrayList<>();
        args.add("-nostdin");
        args.add("-hide_banner");
        args.add("-y");
        for (String path : sourceInputs.values()) {
            args.add("-i");
            args.add(path);
        }
        args.add("-filter_complex");
        args.add(f.toString());
        args.add("-map");
        args.add("[outv]");
        args.add("-map");
        args.add("[outa]");
        args.add("-c:v");
        args.add("libx264");
        args.add("-preset");
        args.add("veryfast");
        args.add("-crf");
        args.add(Integer.toString(CRF));
        args.add("-profile:v");
        args.add("high");
        args.add("-pix_fmt");
        args.add("yuv420p");
        args.add("-threads");
        args.add("2");
        args.add("-c:a");
        args.add("aac");
        args.add("-b:a");
        args.add("160k");
        args.add("-ar");
        args.add(Integer.toString(SAMPLE_RATE));
        args.add("-ac");
        args.add("2");
        args.add("-movflags");
        args.add("+faststart");
        args.add("-map_metadata");
        args.add("-1");
        args.add("-max_muxing_queue_size");
        args.add("1024");
        args.add("-shortest");
        args.add(outputPath);
        return new Plan(args.toArray(new String[0]), w, h, project.totalUs());
    }

    /** even dimensions, preserving the first clip's display aspect ratio. */
    public static int[] outputSize(EditProject project, int maxShortEdge) {
        EditProject.EditClip first = project.clips.get(0);
        EditProject.MediaSource s = project.sources.get(first.sourceId);
        if (s == null) throw new IllegalArgumentException("সোর্স ভিডিওটি পাওয়া যায়নি");
        int w = Math.max(2, s.displayWidth());
        int h = Math.max(2, s.displayHeight());
        int shortEdge = Math.min(w, h);
        int longEdge = Math.max(w, h);
        double scale;
        if (maxShortEdge <= 0) {
            scale = Math.min(1.0, Math.min(1080.0 / shortEdge, 1920.0 / longEdge));
        } else {
            scale = Math.min((double) maxShortEdge / shortEdge,
                    (maxShortEdge * 16.0 / 9.0) / longEdge);
        }
        int ow = Math.max(2, (int) Math.round(w * scale / 2.0) * 2);
        int oh = Math.max(2, (int) Math.round(h * scale / 2.0) * 2);
        return new int[]{ow, oh};
    }

    private static String sec(double value) {
        return String.format(Locale.US, "%.6f", value);
    }
}
