package cn.autoplay.replacement;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Outline;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ProgressBar;

/** Custom-drawn widgets for the connection screen. */
final class Widgets {
    private Widgets() {
    }

    /** The CarPlay app tile: white while idle, green while looking for a phone. */
    static final class CarPlayLogo extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path triangle = new Path();
        private boolean active;

        CarPlayLogo(Context context) {
            super(context);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            if (android.os.Build.VERSION.SDK_INT >= 21) setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    float size = Math.min(view.getWidth(), view.getHeight());
                    int left = Math.round((view.getWidth() - size) / 2f);
                    int top = Math.round((view.getHeight() - size) / 2f);
                    outline.setRoundRect(left, top, left + Math.round(size), top + Math.round(size), size * 0.22f);
                }
            });
        }

        void setActive(boolean active) {
            if (this.active != active) {
                this.active = active;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float size = Math.min(getWidth(), getHeight());
            float left = (getWidth() - size) / 2f;
            float top = (getHeight() - size) / 2f;
            float cx = left + size / 2f;
            float cy = top + size / 2f;
            rect.set(left, top, left + size, top + size);

            int glyph;
            if (active) {
                fill.setShader(new LinearGradient(0, top, 0, top + size,
                        0xFF17D132, 0xFF86F47A, Shader.TileMode.CLAMP));
                glyph = 0xFFFFFFFF;
            } else {
                fill.setShader(null);
                fill.setColor(0xFFFFFFFF);
                glyph = 0xFF111111;
            }
            fill.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(rect, size * 0.22f, size * 0.22f, fill);
            if (!active) {
                stroke.setColor(Ui.DIVIDER);
                stroke.setStrokeWidth(Math.max(1f, size * 0.008f));
                canvas.drawRoundRect(rect, size * 0.22f, size * 0.22f, stroke);
            }

            float radius = size * 0.27f;
            stroke.setColor(glyph);
            stroke.setStrokeWidth(size * 0.055f);
            rect.set(cx - radius, cy - radius, cx + radius, cy + radius);
            canvas.drawArc(rect, 40, 280, false, stroke);

            fill.setShader(null);
            fill.setColor(glyph);
            triangle.reset();
            triangle.moveTo(cx - size * 0.07f, cy - size * 0.115f);
            triangle.lineTo(cx - size * 0.07f, cy + size * 0.115f);
            triangle.lineTo(cx + size * 0.125f, cy);
            triangle.close();
            canvas.drawPath(triangle, fill);
        }
    }

    static final class Spinner extends ProgressBar {
        Spinner(Context context) {
            super(context, null, android.R.attr.progressBarStyleSmall);
            AndroidCompat.tintSpinner(this);
            setBackground(Ui.oval(Ui.ACCENT_SOFT, Ui.dp(context, 18)));
            int padding = Ui.dp(context, 3);
            setPadding(padding, padding, padding, padding);
        }
    }

    /** Radio button circle used by the inline option rows. */
    static final class RadioDot extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean checked;

        RadioDot(Context context) {
            super(context);
        }

        void setChecked(boolean checked) {
            if (this.checked != checked) {
                this.checked = checked;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float size = Math.min(getWidth(), getHeight());
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float stroke = size * 0.1f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(checked ? Ui.ACCENT : Ui.TEXT_DISABLED);
            canvas.drawCircle(cx, cy, size / 2f - stroke, paint);
            if (checked) {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy, size * 0.2f, paint);
            }
        }
    }

    /** Connection and navigation glyphs. */
    static final class LinkIcon extends View {
        static final int BLUETOOTH = 0;
        static final int WIFI = 1;
        static final int GEAR = 2;
        static final int BACK = 3;

        private final int kind;
        private final float density;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private boolean on;

        LinkIcon(Context context, int kind) {
            super(context);
            this.kind = kind;
            this.density = context.getResources().getDisplayMetrics().density;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            fill.setStyle(Paint.Style.FILL);
        }

        void setOn(boolean on) {
            if (this.on != on) {
                this.on = on;
                invalidate();
            }
        }

        private int color() {
            if (kind == GEAR || kind == BACK) {
                return Ui.TEXT_SECONDARY;
            }
            return on ? Ui.ACCENT : (Ui.ACCENT & 0x00FFFFFF) | 0x66000000;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float d = density;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            int color = color();
            stroke.setColor(color);
            fill.setColor(color);
            if (kind == BACK) {
                stroke.setStrokeWidth(1.8f * d);
                path.reset();
                path.moveTo(cx - d, cy - 7 * d);
                path.lineTo(cx - 8 * d, cy);
                path.lineTo(cx - d, cy + 7 * d);
                path.moveTo(cx - 8 * d, cy);
                path.lineTo(cx + 8 * d, cy);
                canvas.drawPath(path, stroke);
            } else if (kind == BLUETOOTH) {
                stroke.setStrokeWidth(2f * d);
                path.reset();
                path.moveTo(cx - 5 * d, cy - 5 * d);
                path.lineTo(cx + 5 * d, cy + 5 * d);
                path.lineTo(cx, cy + 10 * d);
                path.lineTo(cx, cy - 10 * d);
                path.lineTo(cx + 5 * d, cy - 5 * d);
                path.lineTo(cx - 5 * d, cy + 5 * d);
                canvas.drawPath(path, stroke);
            } else if (kind == WIFI) {
                stroke.setStrokeWidth(2.2f * d);
                float baseY = cy + 8 * d;
                canvas.drawCircle(cx, baseY, 1.8f * d, fill);
                for (float radius : new float[]{6 * d, 11 * d, 16 * d}) {
                    rect.set(cx - radius, baseY - radius, cx + radius, baseY + radius);
                    canvas.drawArc(rect, 225, 90, false, stroke);
                }
            } else {
                stroke.setStrokeWidth(1.6f * d);
                path.reset();
                for (int i = 0; i < 32; i++) {
                    double angle = Math.toRadians(i * 11.25 - 5.625);
                    float radius = (i % 4 < 2 ? 10f : 8f) * d;
                    float x = cx + (float) Math.cos(angle) * radius;
                    float y = cy + (float) Math.sin(angle) * radius;
                    if (i == 0) {
                        path.moveTo(x, y);
                    } else {
                        path.lineTo(x, y);
                    }
                }
                path.close();
                canvas.drawPath(path, stroke);
                canvas.drawCircle(cx, cy, 3.2f * d, stroke);
            }
        }
    }
}
