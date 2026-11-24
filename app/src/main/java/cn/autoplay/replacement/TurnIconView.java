package cn.autoplay.replacement;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 导航转向图标。
 *
 * <p>原版按 {@code NaviOrderTypeNew} 查 AutoKit 的 drawable 表（1~72 等键值），
 * 那些图片资源无法在不引入原包资源的前提下复现，这里改为用 Canvas 绘制
 * 语义等价的箭头：直行 / 靠左 / 靠右 / 稍向左 / 稍向右 / 左转 / 右转 /
 * 急左转 / 急右转，另有掉头、到达、出发与环岛几个专用图形；匝道/出口沿用
 * 「稍向右」的形状，靠 label 区分。
 *
 * <p>注意：<b>不要</b>用 {@code NaviTurnAngle} 当作旋转角度——实测该字段只有
 * {@code 1~4}，是道路走向的微小变化，不是转向分类；转向形状由
 * {@link Maneuver}（{@code NaviOrderTypeNew}）决定。
 */
final class TurnIconView extends View {
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF arcBounds = new RectF();
    private Maneuver maneuver = Maneuver.STRAIGHT;

    TurnIconView(Context context) {
        this(context, null);
    }

    TurnIconView(Context context, AttributeSet attributes) {
        super(context, attributes);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(0xFFFFFFFF);
        stroke.setStrokeWidth(Ui.dp(context, 2.8f));
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFFFFFFFF);
    }

    /** 更新转向类型；{@code null} 按直行处理。 */
    void setManeuver(Maneuver value) {
        Maneuver next = value == null ? Maneuver.STRAIGHT : value;
        if (maneuver == next) {
            return;
        }
        maneuver = next;
        invalidate();
    }

    void setColor(int color) {
        stroke.setColor(color);
        fill.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        float centerX = width / 2f;
        float centerY = height / 2f;
        float radius = Math.min(width, height) * 0.34f;

        switch (maneuver) {
            case UTURN:
                drawUturn(canvas, centerX, centerY, radius);
                return;
            case ROUNDABOUT:
                drawRoundabout(canvas, centerX, centerY, radius);
                return;
            case ARRIVE:
                drawDestination(canvas, centerX, centerY, radius);
                return;
            case START:
                drawStart(canvas, centerX, centerY, radius);
                return;
            default:
                // UNKNOWN 与 STRAIGHT 都画直行箭头
                drawBentArrow(canvas, centerX, centerY, radius, maneuver.degrees());
        }
    }

    /** 箭杆从底部竖直向上，到膝点后按 {@code degrees} 折向，顶端画箭头。 */
    private void drawBentArrow(Canvas canvas, float centerX, float centerY, float radius,
            float degrees) {
        double radians = Math.toRadians(degrees);
        float dirX = (float) Math.sin(radians);
        float dirY = -(float) Math.cos(radians);

        float bottomY = centerY + radius;
        float kneeY = centerY + radius * 0.12f;
        // 转向越大，斜段越短，避免箭头超出视图
        float arm = radius * (Math.abs(degrees) > 100f ? 0.80f : 0.95f);
        float tipX = centerX + dirX * arm;
        float tipY = kneeY + dirY * arm;

        path.reset();
        path.moveTo(centerX, bottomY);
        path.lineTo(centerX, kneeY);
        path.lineTo(tipX, tipY);
        canvas.drawPath(path, stroke);

        drawHead(canvas, tipX, tipY, radians, radius * 0.58f);
    }

    /** 在 {@code (tipX, tipY)} 处沿 {@code radians} 方向画实心箭头。 */
    private void drawHead(Canvas canvas, float tipX, float tipY, double radians, float size) {
        double wing = Math.toRadians(152);
        float wingX1 = tipX + (float) Math.sin(radians - wing) * size;
        float wingY1 = tipY - (float) Math.cos(radians - wing) * size;
        float wingX2 = tipX + (float) Math.sin(radians + wing) * size;
        float wingY2 = tipY - (float) Math.cos(radians + wing) * size;
        path.reset();
        path.moveTo(tipX, tipY);
        path.lineTo(wingX1, wingY1);
        path.lineTo(wingX2, wingY2);
        path.close();
        canvas.drawPath(path, fill);
    }

    /** 掉头：左侧上行 → 顶部半圆 → 右侧下行，箭头朝下。 */
    private void drawUturn(Canvas canvas, float centerX, float centerY, float radius) {
        float turnRadius = radius * 0.52f;
        float leftX = centerX - turnRadius;
        float rightX = centerX + turnRadius;
        float arcCenterY = centerY - radius * 0.42f;
        float bottomY = centerY + radius;
        float tipY = centerY + radius * 0.42f;

        canvas.drawLine(leftX, bottomY, leftX, arcCenterY, stroke);
        arcBounds.set(leftX, arcCenterY - turnRadius, rightX, arcCenterY + turnRadius);
        // 从 180° 顺时针扫 180°：左侧经顶部到右侧
        canvas.drawArc(arcBounds, 180f, 180f, false, stroke);
        canvas.drawLine(rightX, arcCenterY, rightX, tipY, stroke);
        drawHead(canvas, rightX, tipY, Math.PI, radius * 0.52f);
    }

    /** 环岛：圆形 + 顶部驶出箭头。 */
    private void drawRoundabout(Canvas canvas, float centerX, float centerY, float radius) {
        float ringRadius = radius * 0.62f;
        float ringCenterY = centerY + radius * 0.16f;
        canvas.drawCircle(centerX, ringCenterY, ringRadius, stroke);
        float tipY = ringCenterY - ringRadius - radius * 0.58f;
        canvas.drawLine(centerX, ringCenterY - ringRadius, centerX, tipY, stroke);
        drawHead(canvas, centerX, tipY, 0, radius * 0.52f);
    }

    /** 到达目的地：旗杆 + 三角旗。 */
    private void drawDestination(Canvas canvas, float centerX, float centerY, float radius) {
        float poleX = centerX - radius * 0.42f;
        float topY = centerY - radius * 0.86f;
        canvas.drawLine(poleX, centerY + radius, poleX, topY, stroke);

        path.reset();
        path.moveTo(poleX, topY);
        path.lineTo(poleX + radius * 1.05f, topY + radius * 0.42f);
        path.lineTo(poleX, topY + radius * 0.84f);
        path.close();
        canvas.drawPath(path, fill);
    }

    /** 出发：底部圆点 + 向上的箭头。 */
    private void drawStart(Canvas canvas, float centerX, float centerY, float radius) {
        float tipY = centerY - radius * 0.55f;
        canvas.drawLine(centerX, centerY + radius * 0.55f, centerX, tipY, stroke);
        drawHead(canvas, centerX, tipY, 0, radius * 0.52f);
        canvas.drawCircle(centerX, centerY + radius * 0.80f, radius * 0.24f, fill);
    }
}
