package com.yeaminul.muktocut;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Live text/fade overlay. Voice isolation and color presets are export effects. */
final class PreviewOverlayView extends View {
    private EditProject project = new EditProject();
    private long timeUs;
    private final Paint fade = new Paint();

    PreviewOverlayView(Context context) { super(context); setWillNotDraw(false); }
    void setProject(EditProject p) { project = p; invalidate(); }
    void setTime(long us) { timeUs = us; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || project.clips.isEmpty()) return;
        for (EditProject.TextOverlay t : project.texts) {
            if (timeUs >= t.startUs && timeUs < t.startUs + t.durationUs) {
                TextRenderer.draw(canvas, t, w, h, 1f);
            }
        }
        EditProject.EditClip c = project.clipAt(timeUs);
        if (c != null) {
            long local = timeUs - project.startUs(c.id);
            float black = 0f;
            if (c.fadeInUs > 0 && local < c.fadeInUs) black = Math.max(black, 1f - local / (float) c.fadeInUs);
            if (c.fadeOutUs > 0 && local > c.durationUs() - c.fadeOutUs)
                black = Math.max(black, 1f - (c.durationUs() - local) / (float) c.fadeOutUs);
            if (black > 0) {
                fade.setColor(Color.BLACK);
                fade.setAlpha(Math.round(255 * Math.max(0, Math.min(1, black))));
                canvas.drawRect(0, 0, w, h, fade);
            }
        }
    }
}
