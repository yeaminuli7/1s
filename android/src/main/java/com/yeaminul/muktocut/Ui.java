package com.yeaminul.muktocut;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small native UI kit: no WebView, no remote fonts, no Material dependency. */
final class Ui {
    static final int BG = 0xFF0B1118;
    static final int SURFACE = 0xFF121C26;
    static final int CARD = 0xFF1B2935;
    static final int STROKE = 0xFF2B3D4C;
    static final int TEXT = 0xFFF2F7FA;
    static final int MUTED = 0xFF91A6B7;
    static final int ACCENT = 0xFF67E8C0;
    static final int ACCENT_DARK = 0xFF143D35;
    static final int DANGER = 0xFFFF8397;
    static final int AMBER = 0xFFFFCF70;

    private Ui() {}

    static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable shape(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (stroke != Color.TRANSPARENT) d.setStroke(dp(c, 1), stroke);
        return d;
    }

    static void ripple(View v, int fill, int stroke, float radiusDp) {
        Drawable content = shape(v.getContext(), fill, stroke, radiusDp);
        Drawable mask = shape(v.getContext(), Color.WHITE, Color.TRANSPARENT, radiusDp);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3367E8C0), content, mask));
    }

    static TextView text(Context c, String value, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif",
                bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    static TextView button(Context c, String value, boolean primary) {
        TextView t = text(c, value, 13, primary ? BG : TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(c, 44));
        t.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        t.setClickable(true);
        t.setFocusable(true);
        ripple(t, primary ? ACCENT : CARD, primary ? Color.TRANSPARENT : STROKE, 12);
        return t;
    }

    static ImageView icon(Context c, String name, int color, int sizeDp) {
        ImageView image = new ImageView(c);
        image.setImageDrawable(new IconDrawable(name, color));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setMinimumWidth(dp(c, sizeDp));
        image.setMinimumHeight(dp(c, sizeDp));
        return image;
    }

    static ImageView iconButton(Context c, String name, String description,
                                int color, Runnable action) {
        ImageView b = icon(c, name, color, 44);
        b.setContentDescription(description);
        b.setPadding(dp(c, 10), dp(c, 10), dp(c, 10), dp(c, 10));
        b.setClickable(true);
        b.setFocusable(true);
        ripple(b, Color.TRANSPARENT, Color.TRANSPARENT, 12);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static void pad(View v, int l, int t, int r, int b) {
        Context c = v.getContext();
        v.setPadding(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams lpDp(Context c, float w, float h) {
        return new LinearLayout.LayoutParams(layoutSize(c, w), layoutSize(c, h));
    }

    /** dp conversion that preserves Android's special MATCH_PARENT/WRAP_CONTENT values. */
    static int layoutSize(Context c, float value) {
        if (value == ViewGroup.LayoutParams.MATCH_PARENT
                || value == ViewGroup.LayoutParams.WRAP_CONTENT) {
            return (int) value;
        }
        return dp(c, value);
    }

    static LinearLayout.LayoutParams weight(float heightDp, float weight, Context c) {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                heightDp == 0 ? 0 : dp(c, heightDp), weight);
    }

    /** Original 24×24 line icons drawn directly on Canvas. */
    static final class IconDrawable extends Drawable {
        private final String name;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;

        IconDrawable(String name, int color) {
            this.name = name;
            this.color = color;
            paint.setColor(color);
            paint.setStrokeWidth(1.8f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStyle(Paint.Style.STROKE);
        }

        @Override public int getIntrinsicWidth() { return 24; }
        @Override public int getIntrinsicHeight() { return 24; }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            if (b.isEmpty()) return;
            canvas.save();
            float scale = Math.min(b.width(), b.height()) / 24f;
            canvas.translate(b.left + (b.width() - 24 * scale) / 2f,
                    b.top + (b.height() - 24 * scale) / 2f);
            canvas.scale(scale, scale);
            paint.setColor(color);
            paint.setAlpha(Color.alpha(color));
            paint.setStyle(Paint.Style.STROKE);
            Path p = new Path();
            switch (name) {
                case "plus":
                    line(canvas, 12, 5, 12, 19); line(canvas, 5, 12, 19, 12); break;
                case "close":
                    line(canvas, 6, 6, 18, 18); line(canvas, 18, 6, 6, 18); break;
                case "check":
                    p.moveTo(5, 12); p.lineTo(10, 17); p.lineTo(19, 7); canvas.drawPath(p, paint); break;
                case "left":
                    p.moveTo(14.5f, 5); p.lineTo(8, 12); p.lineTo(14.5f, 19); canvas.drawPath(p, paint); break;
                case "right":
                    p.moveTo(9.5f, 5); p.lineTo(16, 12); p.lineTo(9.5f, 19); canvas.drawPath(p, paint); break;
                case "undo":
                    p.moveTo(8, 6); p.lineTo(4, 10); p.lineTo(8, 14);
                    p.moveTo(4, 10); p.lineTo(14, 10); p.cubicTo(19, 10, 20, 14, 18, 18);
                    canvas.drawPath(p, paint); break;
                case "redo":
                    p.moveTo(16, 6); p.lineTo(20, 10); p.lineTo(16, 14);
                    p.moveTo(20, 10); p.lineTo(10, 10); p.cubicTo(5, 10, 4, 14, 6, 18);
                    canvas.drawPath(p, paint); break;
                case "export":
                    line(canvas, 12, 3, 12, 14);
                    p.moveTo(8, 10); p.lineTo(12, 14); p.lineTo(16, 10); canvas.drawPath(p, paint);
                    p.reset(); p.moveTo(5, 15); p.lineTo(5, 20); p.lineTo(19, 20); p.lineTo(19, 15);
                    canvas.drawPath(p, paint); break;
                case "play":
                    paint.setStyle(Paint.Style.FILL);
                    p.moveTo(8, 5); p.lineTo(19, 12); p.lineTo(8, 19); p.close(); canvas.drawPath(p, paint); break;
                case "pause":
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawRoundRect(new RectF(7, 5, 10, 19), 1, 1, paint);
                    canvas.drawRoundRect(new RectF(14, 5, 17, 19), 1, 1, paint); break;
                case "prev":
                    line(canvas, 6, 5, 6, 19);
                    paint.setStyle(Paint.Style.FILL);
                    p.moveTo(18, 5); p.lineTo(8, 12); p.lineTo(18, 19); p.close(); canvas.drawPath(p, paint); break;
                case "next":
                    line(canvas, 18, 5, 18, 19);
                    paint.setStyle(Paint.Style.FILL);
                    p.moveTo(6, 5); p.lineTo(16, 12); p.lineTo(6, 19); p.close(); canvas.drawPath(p, paint); break;
                case "split":
                    canvas.drawCircle(6, 7, 2.5f, paint); canvas.drawCircle(6, 17, 2.5f, paint);
                    line(canvas, 8, 8.5f, 19, 17); line(canvas, 8, 15.5f, 19, 7);
                    line(canvas, 12, 10.5f, 12, 13.5f); break;
                case "trim":
                    canvas.drawRoundRect(new RectF(4, 6, 20, 18), 2, 2, paint);
                    line(canvas, 8, 4, 8, 20); line(canvas, 16, 4, 16, 20); break;
                case "volume":
                    p.moveTo(4, 10); p.lineTo(8, 10); p.lineTo(12, 6); p.lineTo(12, 18);
                    p.lineTo(8, 14); p.lineTo(4, 14); p.close(); canvas.drawPath(p, paint);
                    canvas.drawArc(new RectF(12, 8, 18, 16), -60, 120, false, paint);
                    canvas.drawArc(new RectF(12, 5, 21, 19), -60, 120, false, paint); break;
                case "copy":
                    canvas.drawRoundRect(new RectF(8, 8, 19, 19), 2, 2, paint);
                    p.moveTo(15, 5); p.lineTo(5, 5); p.lineTo(5, 15); canvas.drawPath(p, paint); break;
                case "trash":
                    line(canvas, 4, 7, 20, 7);
                    p.moveTo(9, 7); p.lineTo(9, 4); p.lineTo(15, 4); p.lineTo(15, 7);
                    p.moveTo(6, 7); p.lineTo(7, 20); p.lineTo(17, 20); p.lineTo(18, 7);
                    p.moveTo(10, 10); p.lineTo(10, 17); p.moveTo(14, 10); p.lineTo(14, 17);
                    canvas.drawPath(p, paint); break;
                case "film":
                    canvas.drawRoundRect(new RectF(3, 5, 21, 19), 2, 2, paint);
                    line(canvas, 7, 5, 7, 19); line(canvas, 17, 5, 17, 19);
                    line(canvas, 3, 9, 7, 9); line(canvas, 3, 15, 7, 15);
                    line(canvas, 17, 9, 21, 9); line(canvas, 17, 15, 21, 15); break;
                case "folder":
                    p.moveTo(3, 7); p.lineTo(9, 7); p.lineTo(11, 9); p.lineTo(21, 9);
                    p.lineTo(21, 19); p.lineTo(3, 19); p.close(); canvas.drawPath(p, paint); break;
                case "info":
                    canvas.drawCircle(12, 12, 9, paint); line(canvas, 12, 11, 12, 16);
                    paint.setStyle(Paint.Style.FILL); canvas.drawCircle(12, 7.5f, 1, paint); break;
                case "zoomIn":
                case "zoomOut":
                    canvas.drawCircle(10.5f, 10.5f, 6.5f, paint); line(canvas, 15.5f, 15.5f, 20, 20);
                    line(canvas, 7.5f, 10.5f, 13.5f, 10.5f);
                    if (name.equals("zoomIn")) line(canvas, 10.5f, 7.5f, 10.5f, 13.5f);
                    break;
                case "save":
                    p.moveTo(5, 4); p.lineTo(17, 4); p.lineTo(20, 7); p.lineTo(20, 20);
                    p.lineTo(4, 20); p.lineTo(4, 4); p.close();
                    p.moveTo(8, 4); p.lineTo(8, 9); p.lineTo(16, 9); p.lineTo(16, 4);
                    p.moveTo(8, 20); p.lineTo(8, 14); p.lineTo(16, 14); p.lineTo(16, 20);
                    canvas.drawPath(p, paint); break;
                case "shield":
                    p.moveTo(12, 3); p.lineTo(20, 6); p.lineTo(20, 12);
                    p.cubicTo(20, 17, 16, 20, 12, 21); p.cubicTo(8, 20, 4, 17, 4, 12);
                    p.lineTo(4, 6); p.close(); canvas.drawPath(p, paint);
                    p.reset(); p.moveTo(8.5f, 12); p.lineTo(11, 14.5f); p.lineTo(15.5f, 9.5f);
                    canvas.drawPath(p, paint); break;
                case "mic":
                    canvas.drawRoundRect(new RectF(9, 3, 15, 14), 3, 3, paint);
                    canvas.drawArc(new RectF(6, 8, 18, 20), 0, 180, false, paint);
                    line(canvas, 12, 20, 12, 22); line(canvas, 9, 22, 15, 22); break;
                case "speed":
                    canvas.drawArc(new RectF(4, 5, 20, 21), 180, 180, false, paint);
                    line(canvas, 12, 13, 16, 9); line(canvas, 4, 21, 20, 21); break;
                case "adjust":
                    line(canvas, 5, 7, 19, 7); line(canvas, 5, 12, 19, 12); line(canvas, 5, 17, 19, 17);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(9, 7, 2, paint); canvas.drawCircle(15, 12, 2, paint); canvas.drawCircle(8, 17, 2, paint);
                    paint.setStyle(Paint.Style.STROKE); break;
                case "text":
                    line(canvas, 5, 6, 19, 6); line(canvas, 12, 6, 12, 19); line(canvas, 8, 19, 16, 19); break;
                case "music":
                    line(canvas, 9, 18, 9, 6); line(canvas, 9, 6, 19, 4); line(canvas, 19, 4, 19, 16);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(7, 18, 2.5f, paint); canvas.drawCircle(17, 16, 2.5f, paint);
                    paint.setStyle(Paint.Style.STROKE); break;
                case "canvas":
                    canvas.drawRoundRect(new RectF(4, 5, 20, 19), 2, 2, paint);
                    line(canvas, 8, 9, 16, 9); line(canvas, 8, 15, 16, 15); break;
                case "list":
                    line(canvas, 8, 6, 20, 6); line(canvas, 8, 12, 20, 12); line(canvas, 8, 18, 20, 18);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(4, 6, 1, paint); canvas.drawCircle(4, 12, 1, paint); canvas.drawCircle(4, 18, 1, paint);
                    paint.setStyle(Paint.Style.STROKE); break;
                case "logo":
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawRoundRect(new RectF(1, 1, 23, 23), 6, 6, paint);
                    paint.setColor(BG); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2.4f);
                    p.moveTo(6, 17); p.lineTo(6, 7); p.lineTo(12, 13); p.lineTo(18, 7); p.lineTo(18, 17);
                    canvas.drawPath(p, paint);
                    paint.setStrokeWidth(1.8f); break;
                default:
                    canvas.drawCircle(12, 12, 8, paint); break;
            }
            canvas.restore();
        }

        private void line(Canvas c, float x1, float y1, float x2, float y2) {
            c.drawLine(x1, y1, x2, y2, paint);
        }
    }
}
