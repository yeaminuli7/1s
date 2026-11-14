package com.yeaminul.muktocut;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.DocumentsContract;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps a long export alive while the editor is in the background. */
public final class ExportService extends Service {
    private static final String CHANNEL = "export";
    private static final int NOTIFICATION_ID = 42;
    static final String ACTION_CANCEL = "com.yeaminul.muktocut.CANCEL_EXPORT";
    private static final String EXTRA_OUTPUT = "output";
    private static final String EXTRA_NAME = "name";
    private static final String EXTRA_MAX_SHORT = "maxShort";

    private ExecutorService worker;
    private volatile FFmpegSession session;
    private volatile boolean cancelRequested;
    private volatile Uri outputUri;
    private volatile String fileName = "MuktoCut.mp4";
    private volatile File tempFile;
    private volatile File textDir;
    private final StringBuilder logTail = new StringBuilder();
    private long lastNotificationAt;

    static void start(Context context, Uri output, String name, EditProject project,
                      int maxShortEdge) throws IOException {
        File snapshot = new File(context.getCacheDir(), "export-project.json");
        ProjectStore.save(snapshot, project);
        ExportState.begin(name);
        Intent intent = new Intent(context, ExportService.class);
        intent.putExtra(EXTRA_OUTPUT, output.toString());
        intent.putExtra(EXTRA_NAME, name);
        intent.putExtra(EXTRA_MAX_SHORT, maxShortEdge);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        worker = Executors.newSingleThreadExecutor();
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                "ভিডিও এক্সপোর্ট", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("এক্সপোর্টের অগ্রগতি ও বাতিল করার অপশন");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelExport();
            return START_NOT_STICKY;
        }
        if (ExportState.snapshot().status != ExportState.Status.RUNNING) {
            stopSelf();
            return START_NOT_STICKY;
        }
        outputUri = Uri.parse(intent.getStringExtra(EXTRA_OUTPUT));
        fileName = intent.getStringExtra(EXTRA_NAME);
        int maxShort = intent.getIntExtra(EXTRA_MAX_SHORT, 0);
        startForeground(NOTIFICATION_ID, progressNotification(0, "এক্সপোর্ট প্রস্তুত হচ্ছে…"));
        worker.execute(() -> runExport(maxShort));
        return START_NOT_STICKY;
    }

    private void runExport(int maxShort) {
        try {
            EditProject project = ProjectStore.load(new File(getCacheDir(), "export-project.json"));
            LinkedHashMap<String, String> paths = new LinkedHashMap<>();
            for (EditProject.EditClip clip : project.clips) {
                if (paths.containsKey(clip.sourceId)) continue;
                EditProject.MediaSource source = project.sources.get(clip.sourceId);
                if (source == null || source.missing) throw new IOException("সোর্স ভিডিওটি পাওয়া যায়নি");
                String saf = FFmpegKitConfig.getSafParameterForRead(this, Uri.parse(source.uri));
                if (saf == null || saf.isEmpty()) throw new IOException("ভিডিওটি পড়ার অনুমতি নেই");
                paths.put(clip.sourceId, saf);
            }
            LinkedHashMap<String, String> audioPaths = new LinkedHashMap<>();
            for (EditProject.AudioTrack track : project.audioTracks) {
                if (track.missing) throw new IOException("অডিও ট্র্যাকটি পাওয়া যায়নি: " + track.name);
                String saf = FFmpegKitConfig.getSafParameterForRead(this, Uri.parse(track.uri));
                if (saf == null || saf.isEmpty()) throw new IOException("অডিও ট্র্যাক পড়ার অনুমতি নেই: " + track.name);
                audioPaths.put(track.id, saf);
            }
            File dir = new File(getCacheDir(), "exports");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("অস্থায়ী ফোল্ডার তৈরি করা যায়নি");
            tempFile = new File(dir, "export-" + System.currentTimeMillis() + ".mp4");
            int[] size = ExportPlanner.outputSize(project, maxShort);
            LinkedHashMap<String, String> textImages = new LinkedHashMap<>();
            if (!project.texts.isEmpty()) {
                textDir = new File(dir, "text-" + System.currentTimeMillis());
                if (!textDir.mkdirs()) throw new IOException("টেক্সট রেন্ডার ফোল্ডার তৈরি হয়নি");
                for (EditProject.TextOverlay t : project.texts) {
                    if (cancelRequested) { finishCancelled(); return; }
                    File png = new File(textDir, t.id + ".png");
                    TextRenderer.render(t, size[0], size[1], png);
                    textImages.put(t.id, png.getAbsolutePath());
                }
            }
            String voiceModel = null;
            boolean needsVoice = false;
            for (EditProject.EditClip c : project.clips) if (c.voiceMode == 1) needsVoice = true;
            if (needsVoice) voiceModel = ensureVoiceModel().getAbsolutePath();
            ExportPlanner.Plan plan = ExportPlanner.build(project, paths, audioPaths, textImages,
                    maxShort, tempFile.getAbsolutePath(), voiceModel);
            if (cancelRequested) {
                finishCancelled();
                return;
            }
            ExportState.progress(1, "ভিডিও প্রসেস হচ্ছে…");
            session = FFmpegKit.executeWithArgumentsAsync(plan.arguments,
                    completed -> {
                        if (!worker.isShutdown()) worker.execute(() -> finishEncode(completed, plan));
                    },
                    log -> {
                        synchronized (logTail) {
                            logTail.append(log.getMessage()).append('\n');
                            if (logTail.length() > 3500) logTail.delete(0, logTail.length() - 3500);
                        }
                    },
                    statistics -> {
                        if (cancelRequested) return;
                        double totalMs = plan.durationUs / 1000.0;
                        int percent = totalMs <= 0 ? 1 :
                                (int) Math.max(1, Math.min(94, statistics.getTime() / totalMs * 94));
                        ExportState.progress(percent, "ভিডিও প্রসেস হচ্ছে… " + percent + "%");
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - lastNotificationAt > 700) {
                            lastNotificationAt = now;
                            NotificationManager nm = getSystemService(NotificationManager.class);
                            if (nm != null) nm.notify(NOTIFICATION_ID,
                                    progressNotification(percent, "ভিডিও প্রসেস হচ্ছে… " + percent + "%"));
                        }
                    });
            if (cancelRequested && session != null) session.cancel();
        } catch (Throwable e) {
            fail("এক্সপোর্ট শুরু করা যায়নি", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private File ensureVoiceModel() throws IOException {
        File model = new File(getCacheDir(), "voice-bd.rnnn");
        if (model.exists() && model.length() > 0) return model;
        File tmp = new File(getCacheDir(), "voice-bd.rnnn.tmp");
        try (InputStream in = getAssets().open("models/voice-bd.rnnn");
             OutputStream out = new java.io.FileOutputStream(tmp)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            out.flush();
        }
        if (!tmp.renameTo(model)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            if (!model.exists()) throw new IOException("ভয়েস মডেল কপি করা যায়নি");
        }
        return model;
    }

    private void finishEncode(FFmpegSession completed, ExportPlanner.Plan plan) {
        session = null;
        ReturnCode code = completed.getReturnCode();
        if (cancelRequested || ReturnCode.isCancel(code)) {
            finishCancelled();
            return;
        }
        if (!ReturnCode.isSuccess(code)) {
            fail("এক্সপোর্ট ব্যর্থ হয়েছে", tail());
            return;
        }
        try {
            if (tempFile == null || !tempFile.exists() || tempFile.length() == 0) {
                throw new IOException("আউটপুট ফাইল তৈরি হয়নি");
            }
            ExportState.progress(96, "ফাইল সেভ করা হচ্ছে…");
            copyToDocument(tempFile, outputUri);
            ExportState.success(outputUri.toString());
            showFinalNotification(true, "ভিডিও সেভ হয়েছে", outputUri);
            deleteTemp();
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
        } catch (Throwable e) {
            if (cancelRequested) finishCancelled();
            else fail("ফাইল সেভ করা যায়নি", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void copyToDocument(File source, Uri destination) throws IOException {
        try (InputStream in = new FileInputStream(source);
             OutputStream out = getContentResolver().openOutputStream(destination, "wt")) {
            if (out == null) throw new IOException("আউটপুট ফাইল খোলা যায়নি");
            byte[] buffer = new byte[1024 * 1024];
            long total = Math.max(1, source.length());
            long copied = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (cancelRequested) throw new IOException("বাতিল করা হয়েছে");
                out.write(buffer, 0, read);
                copied += read;
                int percent = 96 + (int) Math.min(3, copied * 3 / total);
                ExportState.progress(percent, "ফাইল সেভ করা হচ্ছে…");
            }
            out.flush();
        }
    }

    private void cancelExport() {
        cancelRequested = true;
        FFmpegSession current = session;
        if (current != null) current.cancel();
        else FFmpegKit.cancel();
        ExportState.cancelled();
        deleteTemp();
        deleteDestinationQuietly();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void finishCancelled() {
        deleteTemp();
        deleteDestinationQuietly();
        ExportState.cancelled();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void fail(String message, String details) {
        deleteTemp();
        deleteDestinationQuietly();
        ExportState.error(message, details == null ? "" : details);
        showFinalNotification(false, message, null);
        stopForeground(STOP_FOREGROUND_DETACH);
        stopSelf();
    }

    private Notification progressNotification(int percent, String text) {
        PendingIntent cancel = PendingIntent.getService(this, 1,
                new Intent(this, ExportService.class).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("মুক্তকাট এক্সপোর্ট")
                .setContentText(text)
                .setSubText(fileName)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(100, percent, percent == 0)
                .addAction(new Notification.Action.Builder(R.drawable.ic_notification, "বাতিল", cancel).build())
                .build();
    }

    private void showFinalNotification(boolean success, String text, Uri uri) {
        Intent intent;
        if (success && uri != null) {
            intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "video/mp4");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        }
        PendingIntent pending = PendingIntent.getActivity(this, 2, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(success ? "এক্সপোর্ট সম্পন্ন" : "এক্সপোর্ট")
                .setContentText(text)
                .setSubText(fileName)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, notification);
    }

    private String tail() {
        synchronized (logTail) { return logTail.toString(); }
    }

    private void deleteTemp() {
        if (tempFile != null && tempFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
        }
        if (textDir != null && textDir.exists()) {
            File[] files = textDir.listFiles();
            if (files != null) for (File f : files) { //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            //noinspection ResultOfMethodCallIgnored
            textDir.delete();
        }
    }

    private void deleteDestinationQuietly() {
        if (outputUri == null) return;
        try { DocumentsContract.deleteDocument(getContentResolver(), outputUri); }
        catch (Exception ignored) {}
    }

    @Override public void onDestroy() {
        cancelRequested = true;
        FFmpegSession current = session;
        if (current != null) current.cancel();
        if (worker != null) worker.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
