package com.yeaminul.muktocut;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sequential native preview. Export is a separate, frame-accurate FFmpeg pass;
 * preview clip changes may have a short prepare gap on slower phones.
 */
final class PreviewController implements TextureView.SurfaceTextureListener {
    interface Listener {
        void onPreviewTime(long timelineUs, boolean playing);
        void onPreviewError(String message);
    }

    private final Context context;
    private final TextureView texture;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private EditProject project = new EditProject();
    private MediaPlayer player;
    private Surface surface;
    private long currentUs;
    private boolean playing;
    private boolean prepared;
    private boolean seeking;
    private long pendingSeekUs = -1;
    private int currentIndex = -1;
    private int generation;
    private boolean released;
    private final LinkedHashMap<String, MediaPlayer> audioPlayers = new LinkedHashMap<>();

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (released) return;
            tick();
            main.postDelayed(this, 33);
        }
    };

    PreviewController(Context context, TextureView texture, Listener listener) {
        this.context = context.getApplicationContext();
        this.texture = texture;
        this.listener = listener;
        texture.setSurfaceTextureListener(this);
        // TextureView does not support background drawables on Android. The
        // containing FrameLayout in MainActivity supplies the black background.
        main.post(ticker);
    }

    void setProject(EditProject next, long timeUs, boolean play) {
        project = next.deepCopy();
        currentUs = clamp(timeUs);
        playing = play && !project.clips.isEmpty() && currentUs < project.totalUs();
        releaseAudioTracks();
        releasePlayer();
        prepareCurrent();
        prepareAudioTracks();
        emit();
    }

    void togglePlay() {
        if (project.clips.isEmpty()) return;
        if (currentUs >= project.totalUs() - 1000L) currentUs = 0;
        playing = !playing;
        int index = project.indexAt(currentUs);
        if (prepared && index == currentIndex && player != null) {
            try {
                if (playing) {
                    long local = currentUs - project.startUs(project.clips.get(index).id);
                    if (local >= project.clips.get(index).durationUs() - 20_000L) {
                        doSeek(0);
                    } else {
                        player.start();
                    }
                    syncAudio(true);
                } else {
                    player.pause();
                    syncAudio(false);
                }
            } catch (IllegalStateException e) {
                prepareCurrent();
            }
        } else {
            prepareCurrent();
        }
        emit();
    }

    void pause() {
        playing = false;
        if (prepared && player != null) {
            try { player.pause(); } catch (IllegalStateException ignored) {}
        }
        syncAudio(false);
        emit();
    }

    void scrubTo(long timeUs) {
        playing = false;
        currentUs = clamp(timeUs);
        int index = project.indexAt(currentUs);
        if (prepared && index == currentIndex && player != null) {
            long local = currentUs - project.startUs(project.clips.get(index).id);
            doSeek(local);
            syncAudio(false);
        } else {
            prepareCurrent();
        }
        emit();
    }

    void updateVolume(String clipId, float volume) {
        EditProject.EditClip c = project.find(clipId);
        if (c != null) c.volume = EditProject.clampVolume(volume);
        applyVolume();
    }

    long currentTimeUs() {
        return currentUs;
    }

    boolean isPlaying() {
        return playing;
    }

    private void tick() {
        if (!playing || !prepared || seeking || player == null || project.clips.isEmpty()) return;
        EditProject.EditClip clip = project.clips.get(currentIndex);
        try {
            long sourceUs = player.getCurrentPosition() * 1000L;
            long localUs = Math.max(0, Math.round((sourceUs - clip.inUs) / EditProject.clampSpeed(clip.speed)));
            currentUs = clamp(project.startUs(clip.id) + localUs);
            syncAudio(playing);
            if (sourceUs >= clip.outUs - 15_000L || currentUs >= project.totalUs()) {
                advance();
            } else {
                emit();
            }
        } catch (IllegalStateException e) {
            playing = false;
            listener.onPreviewError("প্রিভিউ চালানো যায়নি। আবার চেষ্টা করুন।");
            emit();
        }
    }

    private void advance() {
        if (currentIndex + 1 >= project.clips.size()) {
            currentUs = project.totalUs();
            playing = false;
            try { player.pause(); } catch (Exception ignored) {}
            emit();
            return;
        }
        EditProject.EditClip next = project.clips.get(currentIndex + 1);
        currentUs = project.startUs(next.id);
        prepareCurrent();
        emit();
    }

    private void prepareCurrent() {
        if (released || surface == null || project.clips.isEmpty()) return;
        releasePlayer();
        currentIndex = project.indexAt(currentUs);
        EditProject.EditClip clip = project.clips.get(currentIndex);
        EditProject.MediaSource source = project.sourceFor(clip);
        if (source == null || source.missing) {
            playing = false;
            listener.onPreviewError("সোর্স ভিডিওটি খোলা যাচ্ছে না");
            emit();
            return;
        }
        final int gen = generation;
        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            mp.setDataSource(context, Uri.parse(source.uri));
            mp.setSurface(surface);
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
            mp.setOnPreparedListener(p -> {
                if (gen != generation || released) return;
                prepared = true;
                try { p.setPlaybackParams(new PlaybackParams().setSpeed(EditProject.clampSpeed(clip.speed))); }
                catch (Exception ignored) {}
                applyVolume();
                updateTransform();
                long local = currentUs - project.startUs(clip.id);
                doSeek(Math.max(0, Math.min(local, clip.durationUs())));
            });
            mp.setOnSeekCompleteListener(p -> {
                if (gen != generation || released) return;
                seeking = false;
                if (pendingSeekUs >= 0) {
                    long next = pendingSeekUs;
                    pendingSeekUs = -1;
                    doSeek(next);
                    return;
                }
                try {
                    if (playing && currentUs < project.totalUs()) p.start();
                    else p.pause();
                    syncAudio(playing && currentUs < project.totalUs());
                } catch (IllegalStateException ignored) {}
                emit();
            });
            mp.setOnCompletionListener(p -> {
                if (gen != generation || released) return;
                if (playing) advance();
            });
            mp.setOnVideoSizeChangedListener((p, w, h) -> {
                if (gen == generation) updateTransform();
            });
            mp.setOnErrorListener((p, what, extra) -> {
                if (gen != generation || released) return true;
                playing = false;
                prepared = false;
                listener.onPreviewError("এই ভিডিওটির প্রিভিউ চালানো যায়নি (কোডেক সাপোর্ট সীমাবদ্ধ হতে পারে)");
                emit();
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            playing = false;
            listener.onPreviewError("ভিডিওটি খোলা যায়নি: " + e.getClass().getSimpleName());
            emit();
        }
    }

    private void doSeek(long localUs) {
        if (!prepared || player == null) return;
        if (seeking) {
            pendingSeekUs = localUs;
            return;
        }
        seeking = true;
        pendingSeekUs = -1;
        try {
            EditProject.EditClip clip = project.clips.get(currentIndex);
            long sourceUs = clip.inUs + clip.sourceOffsetForTimeline(localUs);
            player.seekTo(Math.max(0, sourceUs / 1000L), MediaPlayer.SEEK_CLOSEST);
        } catch (IllegalStateException e) {
            seeking = false;
        }
    }

    private void applyVolume() {
        if (!prepared || player == null || currentIndex < 0 || currentIndex >= project.clips.size()) return;
        float v = project.clips.get(currentIndex).volume;
        // MediaPlayer accepts 0..1; 100..200% is applied by the export engine.
        float capped = Math.min(1f, v);
        try { player.setVolume(capped, capped); } catch (IllegalStateException ignored) {}
    }

    private void updateTransform() {
        if (currentIndex < 0 || currentIndex >= project.clips.size()) return;
        EditProject.MediaSource s = project.sourceFor(project.clips.get(currentIndex));
        if (s == null) return;
        int vw = texture.getWidth(), vh = texture.getHeight();
        int sw = Math.max(1, s.displayWidth()), sh = Math.max(1, s.displayHeight());
        if (vw <= 0 || vh <= 0) return;
        EditProject.EditClip c = project.clips.get(currentIndex);
        int rotation = ((c.rotation % 4) + 4) % 4;
        boolean quarter = rotation == 1 || rotation == 3;
        int rw = quarter ? sh : sw, rh = quarter ? sw : sh;
        float scale = Math.min(vw / (float) rw, vh / (float) rh);
        float dw = rw * scale, dh = rh * scale;
        Matrix matrix = new Matrix();
        matrix.setScale(dw / vw, dh / vh);
        matrix.postTranslate((vw - dw) / 2f, (vh - dh) / 2f);
        Matrix extra = new Matrix();
        extra.postScale(c.flipH ? -1 : 1, c.flipV ? -1 : 1, vw / 2f, vh / 2f);
        extra.postRotate(rotation * 90f, vw / 2f, vh / 2f);
        matrix.postConcat(extra);
        texture.setTransform(matrix);
    }

    private void prepareAudioTracks() {
        releaseAudioTracks();
        for (EditProject.AudioTrack track : project.audioTracks) {
            if (track.missing) continue;
            try {
                MediaPlayer mp = new MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
                mp.setDataSource(context, Uri.parse(track.uri));
                mp.setVolume(Math.min(1f, track.volume), Math.min(1f, track.volume));
                mp.setOnPreparedListener(p -> { if (!released) syncAudio(playing); });
                mp.setOnErrorListener((p, a, b) -> { releaseAudioPlayer(track.id); return true; });
                audioPlayers.put(track.id, mp);
                mp.prepareAsync();
            } catch (Exception ignored) { releaseAudioPlayer(track.id); }
        }
    }

    private void syncAudio(boolean play) {
        if (released) return;
        for (EditProject.AudioTrack track : project.audioTracks) {
            MediaPlayer mp = audioPlayers.get(track.id);
            if (mp == null) continue;
            long local = timeUsForAudio(track);
            try {
                if (local < 0 || local >= track.durationUs) { if (mp.isPlaying()) mp.pause(); continue; }
                long want = track.inUs + local;
                if (Math.abs(mp.getCurrentPosition() * 1000L - want) > 300_000L)
                    mp.seekTo(want / 1000L, MediaPlayer.SEEK_CLOSEST);
                if (play && !mp.isPlaying()) mp.start();
                if (!play && mp.isPlaying()) mp.pause();
            } catch (IllegalStateException ignored) {}
        }
    }

    private long timeUsForAudio(EditProject.AudioTrack track) { return currentUs - track.startUs; }

    private void releaseAudioPlayer(String id) {
        MediaPlayer mp = audioPlayers.remove(id);
        if (mp != null) { try { mp.release(); } catch (Exception ignored) {} }
    }

    private void releaseAudioTracks() {
        for (String id : new java.util.ArrayList<>(audioPlayers.keySet())) releaseAudioPlayer(id);
    }

    private long clamp(long us) {
        return Math.max(0, Math.min(us, project.totalUs()));
    }

    private void emit() {
        listener.onPreviewTime(currentUs, playing);
    }

    private void releasePlayer() {
        generation++;
        prepared = false;
        seeking = false;
        pendingSeekUs = -1;
        currentIndex = -1;
        if (player != null) {
            try {
                player.setOnPreparedListener(null);
                player.setOnSeekCompleteListener(null);
                player.setOnCompletionListener(null);
                player.setOnErrorListener(null);
                player.setOnVideoSizeChangedListener(null);
                player.release();
            } catch (Exception ignored) {}
            player = null;
        }
    }

    void release() {
        released = true;
        main.removeCallbacks(ticker);
        releaseAudioTracks();
        releasePlayer();
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        if (surface != null) surface.release();
        surface = new Surface(surfaceTexture);
        prepareCurrent();
        updateTransform();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
        updateTransform();
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        releasePlayer();
        if (surface != null) {
            surface.release();
            surface = null;
        }
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {}
}
