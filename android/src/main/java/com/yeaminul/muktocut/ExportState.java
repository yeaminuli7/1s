package com.yeaminul.muktocut;

import java.util.concurrent.CopyOnWriteArrayList;

/** Process-local export state shared by the foreground service and editor UI. */
final class ExportState {
    enum Status { IDLE, RUNNING, SUCCESS, ERROR, CANCELLED }

    interface Listener { void onExportState(Snapshot snapshot); }

    static final class Snapshot {
        final Status status;
        final int progress;
        final String fileName;
        final String message;
        final String outputUri;
        final String details;

        Snapshot(Status status, int progress, String fileName, String message,
                 String outputUri, String details) {
            this.status = status;
            this.progress = progress;
            this.fileName = fileName;
            this.message = message;
            this.outputUri = outputUri;
            this.details = details;
        }
    }

    private static Snapshot snapshot = new Snapshot(Status.IDLE, 0, "", "", "", "");
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private ExportState() {}

    static synchronized Snapshot snapshot() { return snapshot; }
    static boolean isRunning() { return snapshot().status == Status.RUNNING; }
    static void addListener(Listener l) { listeners.add(l); }
    static void removeListener(Listener l) { listeners.remove(l); }
    static synchronized void reset() { set(new Snapshot(Status.IDLE, 0, "", "", "", "")); }

    static synchronized void begin(String fileName) {
        set(new Snapshot(Status.RUNNING, 0, fileName, "এক্সপোর্ট প্রস্তুত হচ্ছে…", "", ""));
    }

    static synchronized void progress(int percent, String message) {
        if (snapshot.status != Status.RUNNING) return;
        int p = Math.max(0, Math.min(99, percent));
        set(new Snapshot(Status.RUNNING, p, snapshot.fileName, message, "", ""));
    }

    static synchronized void success(String outputUri) {
        set(new Snapshot(Status.SUCCESS, 100, snapshot.fileName,
                "ভিডিও সেভ হয়েছে — ওয়াটারমার্ক ছাড়াই", outputUri, ""));
    }

    static synchronized void error(String message, String details) {
        set(new Snapshot(Status.ERROR, snapshot.progress, snapshot.fileName, message, "", details));
    }

    static synchronized void cancelled() {
        set(new Snapshot(Status.CANCELLED, 0, snapshot.fileName, "এক্সপোর্ট বাতিল করা হয়েছে", "", ""));
    }

    private static void set(Snapshot next) {
        snapshot = next;
        for (Listener l : listeners) l.onExportState(next);
    }
}
