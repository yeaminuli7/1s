package com.yeaminul.muktocut;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/** Fixed-center playhead timeline with real thumbnails, scrubbing and edge trim. */
final class TimelineView extends View {
    interface Listener {
        void onScrub(long timelineUs);
        void onSelect(String clipId);
        void onTrimStart();
        void onTrimDelta(String clipId, boolean leftEdge, long deltaUs);
        void onTrimEnd();
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final Path clipPath = new Path();
    private final Path head = new Path();
    private final GestureDetector gestures;
    private final ScaleGestureDetector scale;
    private final MediaLibrary library;
    private Listener listener;
    private EditProject project = new EditProject();
    private String selectedId;
    private long currentUs;
    private float pixelsPerSecond;
    private int dragEdge; // -1 left, +1 right
    private String dragClipId;

    TimelineView(Context context, MediaLibrary library) {
        super(context);
        this.library = library;
        pixelsPerSecond = Ui.dp(context, 40);
        setBackgroundColor(Ui.BG);
        setContentDescription("ভিডিও টাইমলাইন। স্ক্রাব করতে টানুন, ক্লিপ বেছে নিতে চাপ দিন।");
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                dragEdge = 0;
                dragClipId = null;
                String hit = hitClip(e.getX());
                if (hit != null && hit.equals(selectedId) && e.getY() > Ui.dp(context, 30)) {
                    EditProject.EditClip c = project.find(hit);
                    float x = originX() + project.startUs(hit) / 1_000_000f * pixelsPerSecond;
                    float right = x + c.durationUs() / 1_000_000f * pixelsPerSecond;
                    int edge = Ui.dp(context, 13);
                    if (e.getX() - x <= edge) { dragEdge = -1; dragClipId = hit; }
                    else if (right - e.getX() <= edge) { dragEdge = 1; dragClipId = hit; }
                    if (dragEdge != 0 && listener != null) listener.onTrimStart();
                }
                return true;
            }

            @Override public boolean onSingleTapUp(MotionEvent e) {
                if (e.getY() < Ui.dp(context, 30)) {
                    if (listener != null) listener.onScrub(timeAt(e.getX()));
                    return true;
                }
                String hit = hitClip(e.getX());
                if (hit != null && listener != null) listener.onSelect(hit);
                else if (listener != null) listener.onScrub(timeAt(e.getX()));
                return true;
            }

            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (listener == null) return false;
                if (dragEdge != 0 && dragClipId != null) {
                    long delta = Math.round(-dx / pixelsPerSecond * 1_000_000f);
                    listener.onTrimDelta(dragClipId, dragEdge < 0, delta);
                } else {
                    long delta = Math.round(dx / pixelsPerSecond * 1_000_000f);
                    listener.onScrub(currentUs + delta);
                }
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                zoomBy(1.5f);
                return true;
            }
        });
        scale = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                zoomBy(detector.getScaleFactor());
                return true;
            }
        });
    }

    void setListener(Listener listener) { this.listener = listener; }
    void setProject(EditProject project) { this.project = project; invalidate(); }
    void setSelected(String id) { selectedId = id; invalidate(); }
    void setTime(long us) { currentUs = Math.max(0, us); invalidate(); }
    void zoomBy(float factor) {
        float min = Ui.dp(getContext(), 8), max = Ui.dp(getContext(), 160);
        pixelsPerSecond = Math.max(min, Math.min(max, pixelsPerSecond * factor));
        invalidate();
    }

    private float originX() {
        return getWidth() / 2f - currentUs / 1_000_000f * pixelsPerSecond;
    }

    private long timeAt(float x) {
        float seconds = (x - getWidth() / 2f) / pixelsPerSecond + currentUs / 1_000_000f;
        return Math.max(0, Math.min(project.totalUs(), Math.round(seconds * 1_000_000f)));
    }

    private String hitClip(float x) {
        float origin = originX();
        long start = 0;
        for (EditProject.EditClip c : project.clips) {
            float left = origin + start / 1_000_000f * pixelsPerSecond;
            float right = left + Math.max(Ui.dp(getContext(), 3),
                    c.durationUs() / 1_000_000f * pixelsPerSecond);
            if (x >= left && x <= right) return c.id;
            start += c.durationUs();
        }
        return null;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        getParent().requestDisallowInterceptTouchEvent(true);
        scale.onTouchEvent(event);
        gestures.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            if (dragEdge != 0 && listener != null) listener.onTrimEnd();
            dragEdge = 0;
            dragClipId = null;
        }
        return true;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        float origin = originX();
        int rulerH = Ui.dp(getContext(), 28);
        float clipTop = Ui.dp(getContext(), 36);
        float clipH = Ui.dp(getContext(), 72);
        float radius = Ui.dp(getContext(), 8);
        float gap = Ui.dp(getContext(), 2);

        drawRuler(canvas, w, rulerH, origin);
        if (project.clips.isEmpty()) {
            paint.setColor(Ui.MUTED);
            paint.setTextSize(Ui.dp(getContext(), 12));
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("ভিডিও যোগ করলে এখানে টাইমলাইন দেখা যাবে", w / 2f, clipTop + clipH / 2f, paint);
            paint.setTextAlign(Paint.Align.LEFT);
            drawPlayhead(canvas, h);
            return;
        }

        long start = 0;
        for (int i = 0; i < project.clips.size(); i++) {
            EditProject.EditClip c = project.clips.get(i);
            float left = origin + start / 1_000_000f * pixelsPerSecond;
            float width = Math.max(Ui.dp(getContext(), 3), c.durationUs() / 1_000_000f * pixelsPerSecond);
            float right = left + width;
            start += c.durationUs();
            if (right < 0 || left > w) continue;
            boolean selected = c.id.equals(selectedId);
            rect.set(left + gap / 2, clipTop, right - gap / 2, clipTop + clipH);
            if (rect.width() < 1) rect.right = rect.left + 1;
            clipPath.reset();
            clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clipPath);
            paint.setColor(selected ? 0xFF214A41 : 0xFF223341);
            canvas.drawRect(rect, paint);
            EditProject.MediaSource source = project.sourceFor(c);
            if (source != null && !source.missing) drawThumbnails(canvas, c, source, rect, left);
            paint.setColor(0xB0000000);
            canvas.drawRect(rect.left, rect.bottom - Ui.dp(getContext(), 23), rect.right, rect.bottom, paint);
            paint.setColor(selected ? Ui.ACCENT : Ui.TEXT);
            paint.setTextSize(Ui.dp(getContext(), 10));
            paint.setTextAlign(Paint.Align.LEFT);
            String label = (i + 1) + " · " + Timecode.clock(c.durationUs());
            canvas.drawText(label, rect.left + Ui.dp(getContext(), 7), rect.bottom - Ui.dp(getContext(), 8), paint);
            if (c.volume <= 0.001f) {
                paint.setColor(Ui.AMBER);
                canvas.drawText("মিউট", rect.left + Ui.dp(getContext(), 7), rect.top + Ui.dp(getContext(), 14), paint);
            }
            canvas.restore();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(getContext(), selected ? 2 : 1));
            paint.setColor(selected ? Ui.ACCENT : Ui.STROKE);
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setStyle(Paint.Style.FILL);
            if (selected && rect.width() > Ui.dp(getContext(), 20)) {
                paint.setColor(Ui.ACCENT);
                float handleW = Ui.dp(getContext(), 4);
                float handleH = Ui.dp(getContext(), 22);
                canvas.drawRoundRect(new RectF(rect.left + 2, rect.centerY() - handleH / 2,
                        rect.left + 2 + handleW, rect.centerY() + handleH / 2), 2, 2, paint);
                canvas.drawRoundRect(new RectF(rect.right - 2 - handleW, rect.centerY() - handleH / 2,
                        rect.right - 2, rect.centerY() + handleH / 2), 2, 2, paint);
            }
        }
        drawPlayhead(canvas, h);
    }

    private void drawThumbnails(Canvas canvas, EditProject.EditClip c,
                                EditProject.MediaSource source, RectF clipRect, float clipLeft) {
        float tileW = Ui.dp(getContext(), 56);
        float first = clipLeft + (float) Math.floor((clipRect.left - clipLeft) / tileW) * tileW;
        for (float x = first; x < clipRect.right && x < getWidth(); x += tileW) {
            if (x + tileW < clipRect.left) continue;
            float center = Math.max(clipRect.left, Math.min(clipRect.right, x + tileW / 2));
            long sourceUs = c.inUs + c.sourceOffsetForTimeline(Math.round((center - clipLeft) / pixelsPerSecond * 1_000_000f));
            sourceUs = Math.max(c.inUs, Math.min(c.outUs - 1, sourceUs));
            Bitmap bitmap = library.thumbnail(source, sourceUs, this::invalidate);
            if (bitmap == null || bitmap.isRecycled()) continue;
            float scale = Math.max(tileW / bitmap.getWidth(), clipRect.height() / bitmap.getHeight());
            float dw = bitmap.getWidth() * scale, dh = bitmap.getHeight() * scale;
            float dx = x + (tileW - dw) / 2f;
            float dy = clipRect.top + (clipRect.height() - dh) / 2f;
            canvas.save();
            canvas.clipRect(Math.max(x, clipRect.left), clipRect.top,
                    Math.min(x + tileW, clipRect.right), clipRect.bottom);
            canvas.drawBitmap(bitmap, null, new RectF(dx, dy, dx + dw, dy + dh), bitmapPaint);
            canvas.restore();
        }
    }

    private void drawRuler(Canvas canvas, int width, int rulerH, float origin) {
        double[] steps = {0.1, 0.2, 0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 1800, 3600};
        double step = steps[steps.length - 1];
        float minGap = Ui.dp(getContext(), 58);
        for (double s : steps) {
            if (s * pixelsPerSecond >= minGap) { step = s; break; }
        }
        double start = Math.max(0, Math.floor((-origin / pixelsPerSecond) / step) * step);
        double end = Math.max(0, (width - origin) / pixelsPerSecond);
        paint.setTextSize(Ui.dp(getContext(), 9));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setStrokeWidth(1);
        for (double t = start; t <= end + step; t += step) {
            float x = origin + (float) (t * pixelsPerSecond);
            if (x < -20 || x > width + 20) continue;
            paint.setColor(Ui.STROKE);
            canvas.drawLine(x, rulerH - Ui.dp(getContext(), 7), x, rulerH, paint);
            paint.setColor(Ui.MUTED);
            canvas.drawText(Timecode.clock(Math.round(t * 1_000_000)), x, Ui.dp(getContext(), 12), paint);
        }
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(Ui.STROKE);
        canvas.drawLine(0, rulerH, width, rulerH, paint);
    }

    private void drawPlayhead(Canvas canvas, int height) {
        float x = getWidth() / 2f;
        paint.setColor(Ui.ACCENT);
        paint.setStrokeWidth(Ui.dp(getContext(), 1.5f));
        canvas.drawLine(x, Ui.dp(getContext(), 18), x, height - Ui.dp(getContext(), 4), paint);
        head.reset();
        head.moveTo(x - Ui.dp(getContext(), 5), Ui.dp(getContext(), 18));
        head.lineTo(x + Ui.dp(getContext(), 5), Ui.dp(getContext(), 18));
        head.lineTo(x, Ui.dp(getContext(), 25));
        head.close();
        canvas.drawPath(head, paint);
        paint.setStrokeWidth(1);
    }
}
