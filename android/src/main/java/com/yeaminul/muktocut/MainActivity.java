package com.yeaminul.muktocut;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements PreviewController.Listener,
        TimelineView.Listener, ExportState.Listener {
    private static final int REQ_IMPORT = 100;
    private static final int REQ_CREATE_EXPORT = 101;
    private static final int MAX_HISTORY = 50;

    private EditProject project = new EditProject();
    private final ArrayList<EditProject> undo = new ArrayList<>();
    private final ArrayList<EditProject> redo = new ArrayList<>();
    private String selectedId;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService io;
    private MediaLibrary library;
    private PreviewController preview;
    private TimelineView timeline;
    private TextureView texture;
    private LinearLayout emptyState;
    private TextView projectName, savedLabel, timeLabel, clipCount, clipTitle, clipMeta,
            clipBadge, exportButton, progressText, progressMessage;
    private ImageView playIcon, undoIcon, redoIcon;
    private ProgressBar exportProgress;
    private final ArrayList<View> tools = new ArrayList<>();
    private AlertDialog progressDialog, resultDialog, importDialog;
    private boolean resumed, importing, resultShowing, trimGestureChanged;
    private long lastBackPress;
    private int pendingExportMaxShort;
    private EditProject pendingExportProject;
    private String pendingExportName;

    private final Runnable saveRunnable = this::saveNow;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        io = Executors.newSingleThreadExecutor();
        library = new MediaLibrary(this);
        loadProject();
        buildUi();
        refreshAll(false);
        validateSources();
    }

    private void buildUi() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });

        LinearLayout header = Ui.row(this);
        Ui.pad(header, 16, 8, 12, 8);
        ImageView logo = Ui.icon(this, "logo", Ui.ACCENT, 34);
        header.addView(logo, Ui.lpDp(this, 34, 34));
        LinearLayout titles = Ui.column(this);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleLp.leftMargin = Ui.dp(this, 10);
        titles.addView(Ui.text(this, "মুক্তকাট", 20, Ui.TEXT, true));
        titles.addView(Ui.text(this, "ফ্রি · অফলাইন · ওয়াটারমার্ক নেই", 9, Ui.MUTED, false));
        header.addView(titles, titleLp);
        undoIcon = Ui.iconButton(this, "undo", "আনডু", Ui.TEXT, this::undo);
        redoIcon = Ui.iconButton(this, "redo", "রিডু", Ui.TEXT, this::redo);
        header.addView(undoIcon, Ui.lpDp(this, 38, 40));
        header.addView(redoIcon, Ui.lpDp(this, 38, 40));
        exportButton = Ui.button(this, "এক্সপোর্ট", true);
        exportButton.setTextSize(12);
        exportButton.setMinHeight(0);
        exportButton.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        exportButton.setOnClickListener(v -> showExportDialog());
        LinearLayout.LayoutParams exportLp = Ui.lpDp(this, 84, 38);
        exportLp.leftMargin = Ui.dp(this, 6);
        header.addView(exportButton, exportLp);
        root.addView(header, Ui.lpDp(this, ViewGroup.LayoutParams.MATCH_PARENT, 60));

        LinearLayout projectBar = Ui.row(this);
        Ui.pad(projectBar, 16, 0, 8, 0);
        projectBar.addView(Ui.icon(this, "film", Ui.MUTED, 16), Ui.lpDp(this, 16, 16));
        projectName = Ui.text(this, project.name, 12, Ui.TEXT, true);
        projectName.setSingleLine(true);
        projectName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        nameLp.leftMargin = Ui.dp(this, 8);
        projectBar.addView(projectName, nameLp);
        savedLabel = Ui.text(this, "সংরক্ষিত", 10, Ui.MUTED, false);
        projectBar.addView(savedLabel, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        projectBar.addView(Ui.iconButton(this, "info", "প্রজেক্ট মেনু ও অ্যাপ সম্পর্কে", Ui.MUTED,
                this::showProjectMenu), Ui.lpDp(this, 38, 38));
        root.addView(projectBar, Ui.lpDp(this, ViewGroup.LayoutParams.MATCH_PARENT, 38));

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(Color.BLACK);
        previewFrame.setMinimumHeight(Ui.dp(this, 150));
        texture = new TextureView(this);
        preview = new PreviewController(this, texture, this);
        previewFrame.addView(texture, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        emptyState = Ui.column(this);
        emptyState.setGravity(Gravity.CENTER);
        emptyState.setBackgroundColor(Ui.BG);
        emptyState.addView(Ui.icon(this, "film", Ui.ACCENT, 46), Ui.lpDp(this, 46, 46));
        TextView emptyTitle = Ui.text(this, "আপনার গল্প শুরু হোক এখানে", 17, Ui.TEXT, true);
        LinearLayout.LayoutParams emptyTitleLp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        emptyTitleLp.topMargin = Ui.dp(this, 12);
        emptyState.addView(emptyTitle, emptyTitleLp);
        TextView emptySub = Ui.text(this, "ভিডিও যোগ করুন — বাকিটা মুক্তকাটে", 12, Ui.MUTED, false);
        LinearLayout.LayoutParams emptySubLp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        emptySubLp.topMargin = Ui.dp(this, 6);
        emptySubLp.bottomMargin = Ui.dp(this, 18);
        emptyState.addView(emptySub, emptySubLp);
        TextView importButton = Ui.button(this, "＋  ভিডিও যোগ করুন", true);
        importButton.setOnClickListener(v -> pickVideos());
        emptyState.addView(importButton, Ui.lpDp(this, 190, 46));
        previewFrame.addView(emptyState, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(previewFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout transport = Ui.row(this);
        Ui.pad(transport, 16, 2, 16, 2);
        timeLabel = Ui.text(this, "00:00.00 / 00:00.00", 11, Ui.MUTED, false);
        timeLabel.setTypeface(Typeface.MONOSPACE);
        timeLabel.setSingleLine(true);
        transport.addView(timeLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout controls = Ui.row(this);
        controls.setGravity(Gravity.CENTER);
        controls.addView(Ui.iconButton(this, "prev", "১ সেকেন্ড পেছনে", Ui.TEXT,
                () -> seekBy(-1_000_000L)), Ui.lpDp(this, 40, 44));
        playIcon = Ui.icon(this, "play", Ui.BG, 48);
        playIcon.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        playIcon.setContentDescription("প্রিভিউ চালান বা থামান");
        playIcon.setClickable(true);
        playIcon.setFocusable(true);
        Ui.ripple(playIcon, Ui.ACCENT, Color.TRANSPARENT, 24);
        playIcon.setOnClickListener(v -> {
            if (project.clips.isEmpty()) { pickVideos(); return; }
            preview.togglePlay();
        });
        controls.addView(playIcon, Ui.lpDp(this, 48, 48));
        controls.addView(Ui.iconButton(this, "next", "১ সেকেন্ড সামনে", Ui.TEXT,
                () -> seekBy(1_000_000L)), Ui.lpDp(this, 40, 44));
        transport.addView(controls, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        transport.addView(new View(this), Ui.lpDp(this, 28, 1));
        root.addView(transport, Ui.lpDp(this, ViewGroup.LayoutParams.MATCH_PARENT, 50));

        LinearLayout timelineHeader = Ui.row(this);
        Ui.pad(timelineHeader, 16, 0, 8, 0);
        timelineHeader.addView(Ui.text(this, "টাইমলাইন", 13, Ui.TEXT, true));
        clipCount = Ui.text(this, "", 10, Ui.MUTED, false);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        countLp.leftMargin = Ui.dp(this, 8);
        timelineHeader.addView(clipCount, countLp);
        timelineHeader.addView(Ui.iconButton(this, "zoomOut", "টাইমলাইন জুম কমান", Ui.MUTED,
                () -> timeline.zoomBy(1f / 1.35f)), Ui.lpDp(this, 32, 32));
        timelineHeader.addView(Ui.iconButton(this, "zoomIn", "টাইমলাইন জুম বাড়ান", Ui.MUTED,
                () -> timeline.zoomBy(1.35f)), Ui.lpDp(this, 32, 32));
        timelineHeader.addView(Ui.iconButton(this, "plus", "আরও ভিডিও যোগ করুন", Ui.ACCENT,
                this::pickVideos), Ui.lpDp(this, 36, 32));
        root.addView(timelineHeader, Ui.lpDp(this, ViewGroup.LayoutParams.MATCH_PARENT, 34));
        timeline = new TimelineView(this, library);
        timeline.setListener(this);
        root.addView(timeline, Ui.lpDp(this, ViewGroup.LayoutParams.MATCH_PARENT, 116));

        LinearLayout selected = Ui.row(this);
        selected.setBackgroundColor(Ui.SURFACE);
        Ui.pad(selected, 16, 6, 16, 6);
        clipBadge = Ui.text(this, "—", 12, Ui.ACCENT, true);
        clipBadge.setGravity(Gravity.CENTER);
        Ui.ripple(clipBadge, Ui.ACCENT_DARK, Color.TRANSPARENT, 8);
        selected.addView(clipBadge, Ui.lpDp(this, 28, 28));
        LinearLayout selectedText = Ui.column(this);
        clipTitle = Ui.text(this, "ক্লিপ বেছে নিন", 12, Ui.TEXT, true);
        clipTitle.setSingleLine(true);
        clipTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        clipMeta = Ui.text(this, "কাটা, ট্রিম ও ভলিউম বদলাতে নিচের টুল ব্যবহার করুন", 9, Ui.MUTED, false);
        clipMeta.setSingleLine(true);
        clipMeta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        selectedText.addView(clipTitle);
        selectedText.addView(clipMeta);
        LinearLayout.LayoutParams selectedTextLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        selectedTextLp.leftMargin = Ui.dp(this, 10);
        selected.addView(selectedText, selectedTextLp);
        root.addView(selected, Ui.lpDp(this, ViewGroup.LayoutParams.MATCH_PARENT, 50));

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setFillViewport(true);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setBackgroundColor(Ui.SURFACE);
        LinearLayout toolbar = Ui.row(this);
        Ui.pad(toolbar, 8, 4, 8, 4);
        toolbar.addView(tool("split", "স্প্লিট", this::splitSelected));
        toolbar.addView(tool("trim", "ট্রিম", this::showTrimDialog));
        toolbar.addView(tool("volume", "ভলিউম", this::showVolumeDialog));
        toolbar.addView(tool("copy", "কপি", this::duplicateSelected));
        toolbar.addView(tool("left", "বামে", () -> moveSelected(-1)));
        toolbar.addView(tool("right", "ডানে", () -> moveSelected(1)));
        toolbar.addView(tool("trash", "মুছুন", this::deleteSelected));
        scroller.addView(toolbar, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(scroller, Ui.lpDp(this, ViewGroup.LayoutParams.MATCH_PARENT, 76));

        LinearLayout footer = Ui.row(this);
        footer.setGravity(Gravity.CENTER);
        footer.addView(Ui.icon(this, "shield", Ui.ACCENT, 12), Ui.lpDp(this, 12, 12));
        TextView footerText = Ui.text(this, "সব ফিচার ফ্রি · ভিডিও ফোনেই থাকে", 9, Ui.MUTED, false);
        LinearLayout.LayoutParams footerLp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerLp.leftMargin = Ui.dp(this, 5);
        footer.addView(footerText, footerLp);
        root.addView(footer, Ui.lpDp(this, ViewGroup.LayoutParams.MATCH_PARENT, 26));
        setContentView(root);
    }

    private LinearLayout tool(String icon, String label, Runnable action) {
        LinearLayout item = Ui.column(this);
        item.setGravity(Gravity.CENTER);
        item.setClickable(true);
        item.setFocusable(true);
        item.setContentDescription(label);
        Ui.ripple(item, Color.TRANSPARENT, Color.TRANSPARENT, 12);
        item.addView(Ui.icon(this, icon, icon.equals("trash") ? Ui.DANGER : Ui.TEXT, 23),
                Ui.lpDp(this, 23, 23));
        TextView text = Ui.text(this, label, 10, icon.equals("trash") ? Ui.DANGER : Ui.MUTED, false);
        LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 5);
        item.addView(text, lp);
        item.setOnClickListener(v -> action.run());
        tools.add(item);
        LinearLayout.LayoutParams itemLp = Ui.lpDp(this, 66, 66);
        itemLp.rightMargin = Ui.dp(this, 2);
        item.setLayoutParams(itemLp);
        return item;
    }

    private void loadProject() {
        File file = new File(getFilesDir(), "project.json");
        try {
            project = ProjectStore.load(file);
        } catch (Exception e) {
            try {
                File backup = new File(getFilesDir(), "project-corrupt-" + System.currentTimeMillis() + ".json");
                java.nio.file.Files.copy(file.toPath(), backup.toPath());
            } catch (Exception ignored) {}
            project = new EditProject();
            toast("পুরোনো প্রজেক্টটি পড়া যায়নি; একটি ব্যাকআপ রাখা হয়েছে");
        }
    }

    private void validateSources() {
        EditProject snapshot = project.deepCopy();
        io.execute(() -> {
            ArrayList<String> unreadable = new ArrayList<>();
            for (EditProject.MediaSource s : snapshot.sources.values()) {
                if (!MediaLibrary.isReadable(this, Uri.parse(s.uri))) unreadable.add(s.id);
            }
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                boolean changed = false;
                for (EditProject.MediaSource live : project.sources.values()) {
                    boolean missing = unreadable.contains(live.id);
                    if (live.missing != missing) {
                        live.missing = missing;
                        changed = true;
                    }
                }
                if (changed) {
                    refreshAll(true);
                    toast("কিছু সোর্স ফাইল খোলা যাচ্ছে না। ফাইলগুলো আবার ইমপোর্ট করুন।");
                }
            });
        });
    }

    private void refreshAll(boolean reloadPreview) {
        projectName.setText(project.name);
        boolean empty = project.clips.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        texture.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (selectedId == null || project.indexOf(selectedId) < 0) {
            EditProject.EditClip at = project.clipAt(preview == null ? 0 : preview.currentTimeUs());
            selectedId = at == null ? null : at.id;
        }
        timeline.setProject(project);
        timeline.setSelected(selectedId);
        clipCount.setText(project.clips.size() + " ক্লিপ · " + Timecode.clock(project.totalUs()));
        updateTime(preview == null ? 0 : preview.currentTimeUs(), preview != null && preview.isPlaying());
        updateSelectedPanel();
        updateEnabled();
        if (reloadPreview && preview != null) {
            preview.setProject(project, preview.currentTimeUs(), false);
        }
    }

    private void updateSelectedPanel() {
        int index = project.indexOf(selectedId);
        EditProject.EditClip c = index < 0 ? null : project.clips.get(index);
        if (c == null) {
            clipBadge.setText("—");
            clipTitle.setText("ক্লিপ বেছে নিন");
            clipMeta.setText("কাটা, ট্রিম ও ভলিউম বদলাতে নিচের টুল ব্যবহার করুন");
            return;
        }
        EditProject.MediaSource s = project.sourceFor(c);
        clipBadge.setText(Integer.toString(index + 1));
        clipTitle.setText(s == null ? "ভিডিও" : s.name);
        String volume = c.volume <= 0.001f ? " · মিউট" : " · ভলিউম " + Math.round(c.volume * 100) + "%";
        clipMeta.setText(Timecode.seconds(c.durationUs()) + " সেকেন্ড · সোর্স "
                + Timecode.seconds(c.inUs) + "–" + Timecode.seconds(c.outUs) + volume);
    }

    private void updateEnabled() {
        boolean hasSelection = project.indexOf(selectedId) >= 0 && !importing;
        for (View tool : tools) {
            tool.setEnabled(hasSelection);
            tool.setAlpha(hasSelection ? 1f : 0.32f);
        }
        undoIcon.setEnabled(!undo.isEmpty() && !importing);
        undoIcon.setAlpha(undoIcon.isEnabled() ? 1f : 0.3f);
        redoIcon.setEnabled(!redo.isEmpty() && !importing);
        redoIcon.setAlpha(redoIcon.isEnabled() ? 1f : 0.3f);
        boolean canExport = !project.clips.isEmpty() && !importing && !ExportState.isRunning();
        exportButton.setEnabled(canExport);
        exportButton.setAlpha(canExport ? 1f : 0.45f);
    }

    private void updateTime(long us, boolean playing) {
        long safe = Math.max(0, Math.min(us, project.totalUs()));
        timeLabel.setText(Timecode.precise(safe) + " / " + Timecode.precise(project.totalUs()));
        timeline.setTime(safe);
        playIcon.setImageDrawable(new Ui.IconDrawable(playing ? "pause" : "play", Ui.BG));
    }

    private void afterEdit(boolean reloadPreview) {
        long time = preview.currentTimeUs();
        if (time > project.totalUs()) time = project.totalUs();
        if (project.indexOf(selectedId) < 0) {
            EditProject.EditClip c = project.clipAt(time);
            selectedId = c == null ? null : c.id;
        }
        refreshAll(false);
        if (reloadPreview) preview.setProject(project, time, false);
        scheduleSave();
    }

    private boolean edit(Runnable action, boolean reloadPreview) {
        EditProject before = project.deepCopy();
        try {
            action.run();
        } catch (IllegalArgumentException e) {
            toast(e.getMessage());
            return false;
        }
        pushUndo(before);
        afterEdit(reloadPreview);
        return true;
    }

    private void pushUndo(EditProject before) {
        undo.add(before);
        if (undo.size() > MAX_HISTORY) undo.remove(0);
        redo.clear();
    }

    private void undo() {
        if (undo.isEmpty() || importing) return;
        redo.add(project.deepCopy());
        project = undo.remove(undo.size() - 1);
        afterEdit(true);
    }

    private void redo() {
        if (redo.isEmpty() || importing) return;
        undo.add(project.deepCopy());
        project = redo.remove(redo.size() - 1);
        afterEdit(true);
    }

    private void scheduleSave() {
        savedLabel.setText("সংরক্ষণ হচ্ছে…");
        handler.removeCallbacks(saveRunnable);
        handler.postDelayed(saveRunnable, 350);
    }

    private void saveNow() {
        EditProject snapshot = project.deepCopy();
        io.execute(() -> {
            try {
                ProjectStore.save(new File(getFilesDir(), "project.json"), snapshot);
                runOnUiThread(() -> { if (!isDestroyed()) savedLabel.setText("সংরক্ষিত"); });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) {
                        savedLabel.setText("সংরক্ষণ ব্যর্থ");
                        toast("প্রজেক্ট সেভ করা যায়নি। ফোনের খালি জায়গা দেখুন।");
                    }
                });
            }
        });
    }

    private void pickVideos() {
        if (importing) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_IMPORT);
        } catch (Exception e) {
            toast("ফাইল পিকার খোলা যায়নি");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            if (requestCode == REQ_CREATE_EXPORT) pendingExportProject = null;
            return;
        }
        if (requestCode == REQ_IMPORT) {
            ArrayList<Uri> uris = new ArrayList<>();
            ClipData clipData = data.getClipData();
            if (clipData != null) {
                for (int i = 0; i < clipData.getItemCount(); i++) uris.add(clipData.getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            importUris(uris);
        } else if (requestCode == REQ_CREATE_EXPORT && data.getData() != null && pendingExportProject != null) {
            Uri output = data.getData();
            try {
                ExportService.start(this, output, pendingExportName, pendingExportProject, pendingExportMaxShort);
            } catch (Exception e) {
                ExportState.reset();
                toast("এক্সপোর্ট শুরু করা যায়নি: " + e.getClass().getSimpleName());
            }
            pendingExportProject = null;
        }
    }

    private void importUris(ArrayList<Uri> uris) {
        if (uris.isEmpty()) return;
        importing = true;
        updateEnabled();
        LinearLayout box = Ui.column(this);
        Ui.pad(box, 22, 18, 22, 8);
        TextView message = Ui.text(this, "ভিডিও প্রস্তুত হচ্ছে…", 14, Ui.TEXT, false);
        box.addView(message);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        box.addView(bar, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 12)));
        importDialog = new AlertDialog.Builder(this)
                .setTitle("ইমপোর্ট").setView(box).setCancelable(false).create();
        importDialog.show();
        EditProject before = project.deepCopy();
        io.execute(() -> {
            ArrayList<EditProject.MediaSource> imported = new ArrayList<>();
            ArrayList<String> errors = new ArrayList<>();
            boolean nonPersisted = false;
            for (int i = 0; i < uris.size(); i++) {
                final int number = i + 1;
                runOnUiThread(() -> message.setText("ভিডিও প্রস্তুত হচ্ছে… " + number + "/" + uris.size()));
                Uri uri = uris.get(i);
                boolean persisted = false;
                try {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    persisted = true;
                } catch (Exception ignored) {}
                try {
                    imported.add(MediaLibrary.probe(this, uri, persisted));
                } catch (Exception e) {
                    errors.add(e.getMessage() == null ? "একটি ফাইল পড়া যায়নি" : e.getMessage());
                }
                if (!persisted) nonPersisted = true;
            }
            final boolean warnPermission = nonPersisted;
            runOnUiThread(() -> {
                importing = false;
                if (importDialog != null) importDialog.dismiss();
                if (isDestroyed()) return;
                if (!imported.isEmpty()) {
                    pushUndo(before);
                    EditProject.EditClip last = null;
                    for (EditProject.MediaSource s : imported) last = project.addSource(s);
                    selectedId = last == null ? null : last.id;
                    afterEdit(true);
                    toast(imported.size() + "টি ভিডিও যোগ হয়েছে");
                    if (warnPermission) {
                        toast("কিছু ফাইলের অনুমতি স্থায়ী নয়; অ্যাপ বন্ধ করলে আবার ইমপোর্ট লাগতে পারে");
                    }
                } else {
                    updateEnabled();
                    toast(errors.isEmpty() ? "কোনো ভিডিও যোগ হয়নি" : errors.get(0));
                }
            });
        });
    }

    private void splitSelected() {
        EditProject.EditClip c = project.find(selectedId);
        if (c == null) return;
        long local = preview.currentTimeUs() - project.startUs(c.id);
        if (local < EditProject.MIN_CLIP_US || local > c.durationUs() - EditProject.MIN_CLIP_US) {
            toast("প্লেহেডটি এই ক্লিপের ভেতরে রাখুন, তারপর স্প্লিট চাপুন");
            return;
        }
        long cutAt = preview.currentTimeUs();
        edit(() -> project.splitAt(c.id, cutAt), true);
    }

    private void duplicateSelected() {
        if (selectedId == null) return;
        edit(() -> {
            EditProject.EditClip copy = project.duplicate(selectedId);
            selectedId = copy.id;
        }, true);
    }

    private void moveSelected(int delta) {
        if (selectedId == null) return;
        edit(() -> {
            if (!project.move(selectedId, delta)) throw new IllegalArgumentException("ক্লিপটি আর সরানো যাচ্ছে না");
        }, true);
    }

    private void deleteSelected() {
        if (selectedId == null) return;
        edit(() -> project.remove(selectedId), true);
        toast("ক্লিপ মুছে হয়েছে — দরকার হলে আনডু চাপুন");
    }

    private void showTrimDialog() {
        EditProject.EditClip c = project.find(selectedId);
        if (c == null) return;
        EditProject.MediaSource s = project.sourceFor(c);
        if (s == null) return;
        String clipId = c.id;
        LinearLayout box = Ui.column(this);
        Ui.pad(box, 22, 12, 22, 4);
        box.addView(Ui.text(this, "সোর্স দৈর্ঘ্য: " + Timecode.seconds(s.durationUs) + " সেকেন্ড", 12, Ui.MUTED, false));
        EditText start = numericField(Timecode.seconds(c.inUs));
        EditText end = numericField(Timecode.seconds(c.outUs));
        box.addView(Ui.text(this, "শুরু (সেকেন্ড)", 12, Ui.TEXT, true));
        box.addView(start, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)));
        box.addView(Ui.text(this, "শেষ (সেকেন্ড)", 12, Ui.TEXT, true));
        box.addView(end, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)));
        box.addView(Ui.text(this, "যেমন 1.25 লিখলে ১ সেকেন্ড ২৫ সেন্ট সেকেন্ড বোঝায়। টাইমলাইনের নির্বাচিত ক্লিপের দুই প্রান্ত টেনেও ট্রিম করা যায়।", 11, Ui.MUTED, false));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("ক্লিপ ট্রিম করুন").setView(box)
                .setNegativeButton("বাতিল", null).setPositiveButton("প্রয়োগ", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                double startSec = Double.parseDouble(start.getText().toString().trim());
                double endSec = Double.parseDouble(end.getText().toString().trim());
                if (!Double.isFinite(startSec) || !Double.isFinite(endSec)) throw new NumberFormatException();
                long inUs = Math.round(startSec * 1_000_000);
                long outUs = Math.round(endSec * 1_000_000);
                if (edit(() -> project.trim(clipId, inUs, outUs), true)) {
                    dialog.dismiss();
                } else {
                    end.setError("ট্রিম সীমা ঠিক করুন");
                }
            } catch (NumberFormatException e) {
                end.setError("সঠিক সংখ্যা লিখুন");
            } catch (IllegalArgumentException e) {
                end.setError(e.getMessage());
                toast(e.getMessage());
            }
        }));
        dialog.show();
    }

    private EditText numericField(String value) {
        EditText field = new EditText(this);
        field.setText(value);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        field.setSingleLine(true);
        field.setTextColor(Ui.TEXT);
        field.setHintTextColor(Ui.MUTED);
        field.setTextSize(15);
        field.setSelectAllOnFocus(true);
        return field;
    }

    private void showVolumeDialog() {
        EditProject.EditClip c = project.find(selectedId);
        if (c == null) return;
        String clipId = c.id;
        float original = c.volume;
        LinearLayout box = Ui.column(this);
        Ui.pad(box, 22, 14, 22, 6);
        TextView value = Ui.text(this, Math.round(original * 100) + "%", 22, Ui.ACCENT, true);
        value.setGravity(Gravity.CENTER);
        box.addView(value, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 40)));
        SeekBar seek = new SeekBar(this);
        seek.setMax(200);
        seek.setProgress(Math.round(original * 100));
        box.addView(seek, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44)));
        box.addView(Ui.text(this, "০% = মিউট · ১০০% = আসল ভলিউম\n১০০%-এর বেশি বুস্ট এক্সপোর্ট করা ভিডিওতে যুক্ত হবে।", 11, Ui.MUTED, false));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                value.setText(progress + "%");
                preview.updateVolume(clipId, progress / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("ক্লিপের ভলিউম").setView(box)
                .setNegativeButton("বাতিল", (d, w) -> preview.updateVolume(clipId, original))
                .setPositiveButton("প্রয়োগ", (d, w) -> {
                    float v = seek.getProgress() / 100f;
                    edit(() -> project.setVolume(clipId, v), false);
                    preview.updateVolume(clipId, v);
                }).create();
        dialog.setOnCancelListener(d -> preview.updateVolume(clipId, original));
        dialog.show();
    }

    private void seekBy(long deltaUs) {
        if (project.clips.isEmpty()) return;
        preview.scrubTo(preview.currentTimeUs() + deltaUs);
    }

    private void showExportDialog() {
        if (project.clips.isEmpty()) { toast("আগে একটি ভিডিও যোগ করুন"); return; }
        if (ExportState.isRunning()) { toast("একটি এক্সপোর্ট ইতিমধ্যে চলছে"); return; }
        List<String> errors = project.validate();
        if (!errors.isEmpty()) { toast(errors.get(0)); return; }
        preview.pause();
        LinearLayout box = Ui.column(this);
        Ui.pad(box, 22, 12, 22, 4);
        int[] auto = ExportPlanner.outputSize(project, 0);
        box.addView(Ui.text(this, project.clips.size() + " ক্লিপ · " + Timecode.clock(project.totalUs())
                + " · অটো: " + auto[0] + "×" + auto[1], 13, Ui.TEXT, true));
        box.addView(Ui.text(this, "রেজোলিউশন", 12, Ui.MUTED, false));
        Spinner spinner = new Spinner(this);
        String[] options = {"অটো (সর্বোচ্চ 1080p, আপস্কেল নয়)", "720p", "1080p"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        box.addView(spinner, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)));
        box.addView(Ui.text(this, "MP4 · H.264 ভিডিও · AAC অডিও · 30 fps\nকোনো ওয়াটারমার্ক বা পেইড লক নেই। এক্সপোর্টের সময় ভিডিও নতুন করে এনকোড হয়, তাই ফোনের গতি ও ভিডিওর দৈর্ঘ্য অনুযায়ী সময় লাগবে।", 11, Ui.MUTED, false));
        new AlertDialog.Builder(this)
                .setTitle("এক্সপোর্ট করুন").setView(box)
                .setNegativeButton("বাতিল", null)
                .setPositiveButton("সেভ লোকেশন বেছে নিন", (d, w) -> {
                    int position = spinner.getSelectedItemPosition();
                    pendingExportMaxShort = position == 1 ? 720 : position == 2 ? 1080 : 0;
                    pendingExportProject = project.deepCopy();
                    pendingExportName = "MuktoCut_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                            .format(new Date()) + ".mp4";
                    requestNotificationPermission();
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("video/mp4");
                    intent.putExtra(Intent.EXTRA_TITLE, pendingExportName);
                    try {
                        startActivityForResult(intent, REQ_CREATE_EXPORT);
                    } catch (Exception e) {
                        pendingExportProject = null;
                        toast("সেভ ডায়ালগ খোলা যায়নি");
                    }
                }).show();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 200);
        }
    }

    @Override public void onExportState(ExportState.Snapshot snapshot) {
        runOnUiThread(() -> {
            if (!resumed || isDestroyed()) return;
            handleExportState(snapshot);
        });
    }

    private void handleExportState(ExportState.Snapshot s) {
        updateEnabled();
        if (s.status == ExportState.Status.RUNNING) {
            showProgressDialog(s);
        } else if (s.status == ExportState.Status.SUCCESS) {
            dismissProgress();
            if (!resultShowing) showExportResult(s);
        } else if (s.status == ExportState.Status.ERROR) {
            dismissProgress();
            if (!resultShowing) showExportError(s);
        } else if (s.status == ExportState.Status.CANCELLED) {
            dismissProgress();
            toast(s.message);
            ExportState.reset();
        }
    }

    private void showProgressDialog(ExportState.Snapshot s) {
        if (progressDialog == null) {
            LinearLayout box = Ui.column(this);
            Ui.pad(box, 22, 16, 22, 6);
            progressText = Ui.text(this, "0%", 24, Ui.ACCENT, true);
            box.addView(progressText);
            exportProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            exportProgress.setMax(100);
            box.addView(exportProgress, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 14)));
            progressMessage = Ui.text(this, s.message, 13, Ui.TEXT, false);
            LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(this, 10);
            box.addView(progressMessage, lp);
            box.addView(Ui.text(this, "অ্যাপ ব্যাকগ্রাউন্ডে রাখলেও এক্সপোর্ট চলবে। নোটিফিকেশন থেকেও বাতিল করা যাবে।", 11, Ui.MUTED, false));
            progressDialog = new AlertDialog.Builder(this)
                    .setTitle(s.fileName).setView(box).setCancelable(false)
                    .setNegativeButton("এক্সপোর্ট বাতিল", (d, w) ->
                            startService(new Intent(this, ExportService.class).setAction(ExportService.ACTION_CANCEL)))
                    .create();
            progressDialog.show();
        }
        progressText.setText(s.progress + "%");
        exportProgress.setProgress(s.progress);
        progressMessage.setText(s.message);
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
    }

    private void showExportResult(ExportState.Snapshot s) {
        resultShowing = true;
        resultDialog = new AlertDialog.Builder(this)
                .setTitle("সেভ হয়েছে 🎉")
                .setMessage(s.fileName + "\n\nওয়াটারমার্ক ছাড়া ভিডিওটি আপনার বেছে নেওয়া জায়গায় সেভ হয়েছে।")
                .setPositiveButton("খুলুন", (d, w) -> openUri(s.outputUri))
                .setNeutralButton("শেয়ার", (d, w) -> shareUri(s.outputUri))
                .setNegativeButton("ঠিক আছে", null)
                .setOnDismissListener(d -> { resultShowing = false; resultDialog = null; ExportState.reset(); })
                .create();
        resultDialog.show();
    }

    private void showExportError(ExportState.Snapshot s) {
        resultShowing = true;
        String details = s.details == null ? "" : s.details;
        if (details.length() > 700) details = details.substring(details.length() - 700);
        final String log = details;
        resultDialog = new AlertDialog.Builder(this)
                .setTitle("এক্সপোর্ট ব্যর্থ")
                .setMessage(s.message + "\n\nভিডিওটি আবার ইমপোর্ট করে চেষ্টা করুন। ফোনের খালি জায়গা ও ফাইল অনুমতি দেখুন।"
                        + (log.isEmpty() ? "" : "\n\nকারিগরি বিবরণ:\n" + log))
                .setPositiveButton("ঠিক আছে", null)
                .setNeutralButton("লগ কপি", (d, w) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("MuktoCut export log", s.details));
                })
                .setOnDismissListener(d -> { resultShowing = false; resultDialog = null; ExportState.reset(); })
                .create();
        resultDialog.show();
    }

    private void openUri(String uri) {
        if (uri == null || uri.isEmpty()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(uri), "video/mp4");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            toast("ভিডিও খোলার অ্যাপ পাওয়া যায়নি");
        }
    }

    private void shareUri(String uri) {
        if (uri == null || uri.isEmpty()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("video/mp4");
            intent.putExtra(Intent.EXTRA_STREAM, Uri.parse(uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "ভিডিও শেয়ার করুন"));
        } catch (Exception e) {
            toast("শেয়ার করা যায়নি");
        }
    }

    private void showProjectMenu() {
        new AlertDialog.Builder(this)
                .setTitle("প্রজেক্ট")
                .setItems(new String[]{"নাম বদলান", "নতুন প্রজেক্ট", "মুক্তকাট সম্পর্কে", "লাইসেন্স ও সোর্স কোড"},
                        (d, which) -> {
                            if (which == 0) renameProject();
                            else if (which == 1) confirmNewProject();
                            else if (which == 2) showAbout();
                            else showLicenses();
                        }).show();
    }

    private void renameProject() {
        EditText field = new EditText(this);
        field.setText(project.name);
        field.setSingleLine(true);
        field.setTextColor(Ui.TEXT);
        field.setSelectAllOnFocus(true);
        LinearLayout box = Ui.column(this);
        Ui.pad(box, 22, 10, 22, 0);
        box.addView(field, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)));
        new AlertDialog.Builder(this).setTitle("প্রজেক্টের নাম").setView(box)
                .setNegativeButton("বাতিল", null)
                .setPositiveButton("সেভ", (d, w) -> {
                    String name = field.getText().toString().trim();
                    if (name.isEmpty()) name = "আমার প্রজেক্ট";
                    String finalName = name;
                    edit(() -> project.name = finalName, false);
                }).show();
    }

    private void confirmNewProject() {
        new AlertDialog.Builder(this)
                .setTitle("নতুন প্রজেক্ট শুরু করবেন?")
                .setMessage("বর্তমান প্রজেক্টের টাইমলাইন মুছে যাবে। আসল ভিডিও ফাইলগুলো মুছবে না।")
                .setNegativeButton("বাতিল", null)
                .setPositiveButton("নতুন প্রজেক্ট", (d, w) -> {
                    preview.pause();
                    project = new EditProject();
                    selectedId = null;
                    undo.clear();
                    redo.clear();
                    refreshAll(true);
                    saveNow();
                }).show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("মুক্তকাট 0.1.0")
                .setMessage("নিজস্ব ব্র্যান্ডের ফ্রি ও অফলাইন ভিডিও এডিটর।\n\n"
                        + "এই প্রথম সংস্করণে: ইমপোর্ট, মাল্টি-ক্লিপ টাইমলাইন, স্প্লিট, ট্রিম, ভলিউম, ক্লিপ সাজানো, আনডু/রিডু, লোকাল প্রজেক্ট সেভ এবং MP4 এক্সপোর্ট।\n\n"
                        + "টেক্সট, ট্রানজিশন, AI ক্যাপশন ও ব্যাকগ্রাউন্ড রিমুভাল এখনো যুক্ত নয়।\n\n"
                        + "লাইসেন্স: GPL-3.0-or-later। FFmpeg/FFmpegKit ব্যবহৃত হয়েছে। CapCut বা ByteDance-এর সঙ্গে এই অ্যাপের কোনো সম্পর্ক নেই।")
                .setPositiveButton("ঠিক আছে", null)
                .setNeutralButton("সোর্স কোড", (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/yeaminul/1s")));
                    } catch (Exception ignored) {}
                }).show();
    }

    private void showLicenses() {
        String notice;
        try (InputStream in = getAssets().open("licenses/NOTICE.txt")) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) buffer.write(chunk, 0, read);
            notice = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            notice = "লাইসেন্স ফাইল পড়া যায়নি। সোর্স রিপোর THIRD_PARTY_NOTICES.md দেখুন।";
        }
        TextView text = Ui.text(this, notice, 11, Ui.TEXT, false);
        Ui.pad(text, 20, 14, 20, 14);
        text.setTextIsSelectable(true);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(text);
        new AlertDialog.Builder(this).setTitle("লাইসেন্স ও কৃতজ্ঞতা").setView(scroll)
                .setPositiveButton("বন্ধ", null).show();
    }

    private void toast(String message) {
        if (message == null) return;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override public void onPreviewTime(long timelineUs, boolean playing) {
        updateTime(timelineUs, playing);
    }

    @Override public void onPreviewError(String message) {
        toast(message);
    }

    @Override public void onScrub(long timelineUs) {
        if (project.clips.isEmpty()) return;
        preview.scrubTo(timelineUs);
    }

    @Override public void onSelect(String clipId) {
        selectedId = clipId;
        timeline.setSelected(clipId);
        updateSelectedPanel();
        updateEnabled();
    }

    @Override public void onTrimStart() {
        trimGestureChanged = false;
        preview.pause();
    }

    @Override public void onTrimDelta(String clipId, boolean leftEdge, long deltaUs) {
        if (!trimGestureChanged) {
            pushUndo(project.deepCopy());
            trimGestureChanged = true;
        }
        project.nudgeTrim(clipId, leftEdge, deltaUs);
        timeline.setProject(project);
        updateSelectedPanel();
        long total = project.totalUs();
        if (preview.currentTimeUs() > total) preview.scrubTo(total);
        clipCount.setText(project.clips.size() + " ক্লিপ · " + Timecode.clock(total));
        updateTime(Math.min(preview.currentTimeUs(), total), false);
    }

    @Override public void onTrimEnd() {
        if (trimGestureChanged) afterEdit(true);
        trimGestureChanged = false;
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        ExportState.addListener(this);
        handleExportState(ExportState.snapshot());
    }

    @Override protected void onPause() {
        resumed = false;
        ExportState.removeListener(this);
        if (preview != null) preview.pause();
        handler.removeCallbacks(saveRunnable);
        saveNow();
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(saveRunnable);
        if (preview != null) preview.release();
        if (library != null) library.shutdown();
        if (io != null) io.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (preview != null && preview.isPlaying()) {
            preview.pause();
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastBackPress < 2000) {
            super.onBackPressed();
        } else {
            lastBackPress = now;
            toast("অ্যাপ বন্ধ করতে আবার ব্যাক চাপুন। প্রজেক্ট সেভ থাকবে।");
        }
    }
}
