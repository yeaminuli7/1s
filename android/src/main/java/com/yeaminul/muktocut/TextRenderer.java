package com.yeaminul.muktocut;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Renders Bengali/Latin overlays with Android's text shaper, then exports as PNG. */
final class TextRenderer {
    private TextRenderer() {}

    static void render(EditProject.TextOverlay t, int width, int height, File out) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(Math.max(2, width), Math.max(2, height), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        draw(canvas, t, bitmap.getWidth(), bitmap.getHeight(), 1f);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)) throw new IOException("টেক্সট PNG সেভ হয়নি");
        } finally {
            bitmap.recycle();
        }
    }

    static void draw(Canvas canvas, EditProject.TextOverlay t, int width, int height, float alpha) {
        if (t.text == null || t.text.trim().isEmpty()) return;
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        paint.setColor(t.color);
        paint.setAlpha(Math.round(Color.alpha(t.color) * alpha));
        float textSize = Math.max(10f, t.size * height);
        paint.setTextSize(textSize);
        int maxWidth = Math.max(1, Math.round(width * 0.9f));
        StaticLayout layout = StaticLayout.Builder.obtain(t.text, 0, t.text.length(), paint, maxWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build();
        float cx = clamp01(t.x) * width;
        float cy = clamp01(t.y) * height;
        float left = cx - layout.getWidth() / 2f;
        float top = cy - layout.getHeight() / 2f;
        left = Math.max(0, Math.min(left, width - layout.getWidth()));
        top = Math.max(0, Math.min(top, height - layout.getHeight()));
        canvas.save();
        canvas.translate(left, top);
        if (t.box) {
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(0xB0000000);
            bg.setAlpha(Math.round(0xB0 * alpha));
            float pad = textSize * 0.35f;
            canvas.drawRoundRect(new RectF(-pad, -pad * 0.6f, layout.getWidth() + pad,
                    layout.getHeight() + pad * 0.6f), textSize * 0.25f, textSize * 0.25f, bg);
        }
        layout.draw(canvas);
        canvas.restore();
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return 0.5f;
        return Math.max(0f, Math.min(1f, v));
    }
}
