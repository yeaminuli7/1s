package com.yeaminul.muktocut;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.LruCache;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** SAF-backed metadata and a bounded, asynchronous thumbnail cache. */
final class MediaLibrary {
    interface ThumbnailCallback {
        void onThumbnail();
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<String> loading = new HashSet<>();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    MediaLibrary(Context context) {
        this.context = context.getApplicationContext();
    }

    static EditProject.MediaSource probe(Context context, Uri uri, boolean persisted) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String name = displayName(resolver, uri);
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(context, uri);
            long durationUs = parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), 0) * 1000L;
            int width = (int) parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 0);
            int height = (int) parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 0);
            int rotation = (int) parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), 0);
            boolean hasAudio = "yes".equals(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
            if (durationUs < EditProject.MIN_CLIP_US || width <= 0 || height <= 0) {
                throw new IOException("এই ফাইলটি পড়া যাচ্ছে না বা এটি কোনো ভিডিও নয়");
            }
            return new EditProject.MediaSource(UUID.randomUUID().toString(), uri.toString(),
                    name, durationUs, width, height, rotation, hasAudio, persisted);
        } catch (IllegalArgumentException | SecurityException e) {
            throw new IOException("ভিডিওটি খোলা যায়নি: " + e.getMessage(), e);
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
    }

    static EditProject.AudioTrack probeAudio(Context context, Uri uri, long startUs, boolean persisted) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String name = displayName(resolver, uri);
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(context, uri);
            long durationUs = parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), 0) * 1000L;
            boolean hasAudio = "yes".equals(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
            if (!hasAudio || durationUs < EditProject.MIN_CLIP_US) throw new IOException("এই ফাইলে ব্যবহারযোগ্য অডিও নেই");
            return new EditProject.AudioTrack(UUID.randomUUID().toString(), uri.toString(), name,
                    Math.max(0, startUs), 0, durationUs, 0.8f);
        } catch (IllegalArgumentException | SecurityException e) {
            throw new IOException("অডিও ফাইলটি খোলা যায়নি: " + e.getMessage(), e);
        } finally { try { mmr.release(); } catch (Exception ignored) {} }
    }

    static boolean isReadable(Context context, Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            return in != null;
        } catch (Exception e) {
            return false;
        }
    }

    Bitmap thumbnail(EditProject.MediaSource source, long sourceUs, ThumbnailCallback callback) {
        long bucket = Math.max(0, sourceUs / 2_000_000L) * 2_000_000L;
        bucket = Math.min(bucket, Math.max(0, source.durationUs - 1));
        String key = source.id + ':' + bucket;
        Bitmap cached = cache.get(key);
        if (cached != null) return cached;
        synchronized (loading) {
            if (loading.contains(key)) return null;
            loading.add(key);
        }
        final long time = bucket;
        try {
            executor.execute(() -> {
                Bitmap bitmap = null;
                MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                try {
                    mmr.setDataSource(context, Uri.parse(source.uri));
                    if (Build.VERSION.SDK_INT >= 27) {
                        bitmap = mmr.getScaledFrameAtTime(time,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 96);
                    } else {
                        Bitmap full = mmr.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                        if (full != null) {
                            bitmap = Bitmap.createScaledBitmap(full, 160, 96, true);
                            if (bitmap != full) full.recycle();
                        }
                    }
                } catch (Exception ignored) {
                    // A missing/unreadable source keeps the neutral timeline placeholder.
                } finally {
                    try { mmr.release(); } catch (Exception ignored) {}
                }
                if (bitmap != null) cache.put(key, bitmap);
                synchronized (loading) { loading.remove(key); }
                main.post(callback::onThumbnail);
            });
        } catch (RuntimeException rejected) {
            synchronized (loading) { loading.remove(key); }
        }
        return null;
    }

    void shutdown() {
        executor.shutdownNow();
        cache.evictAll();
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        String name = null;
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) name = cursor.getString(column);
            }
        } catch (Exception ignored) {}
        if (name == null || name.trim().isEmpty()) {
            name = uri.getLastPathSegment();
        }
        return name == null ? "ভিডিও" : name;
    }

    private static long parseLong(String value, long fallback) {
        if (value == null) return fallback;
        try { return Long.parseLong(value); } catch (NumberFormatException e) { return fallback; }
    }
}
