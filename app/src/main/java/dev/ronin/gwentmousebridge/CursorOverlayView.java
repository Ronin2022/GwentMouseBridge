package dev.ronin.gwentmousebridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

final class CursorOverlayView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    CursorOverlayView(Context context) {
        super(context);
        fill.setColor(context.getColor(R.color.cursor_fill));
        fill.setStyle(Paint.Style.FILL);
        stroke.setColor(context.getColor(R.color.cursor_stroke));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(2));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(cx, cy) - dp(3);
        canvas.drawCircle(cx, cy, radius, fill);
        canvas.drawCircle(cx, cy, radius, stroke);
        canvas.drawLine(cx - radius / 2f, cy, cx + radius / 2f, cy, stroke);
        canvas.drawLine(cx, cy - radius / 2f, cx, cy + radius / 2f, stroke);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
