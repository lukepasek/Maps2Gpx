package pl.net.xtech.maps2gpx;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Elevation against distance travelled - not against point index, which would distort the
 * profile wherever the router emitted points at an uneven spacing.
 *
 * <p>Points with no elevation are skipped rather than treated as sea level, so a partial gap
 * in the DEM data leaves a straight segment instead of a spike down to zero.
 */
public class ElevationProfileView extends View {

    interface OnInspectionPositionChangedListener {
        void onInspectionPositionChanged(TrackPosition position);
    }

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pavedFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pavedLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gravelFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gravelLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dirtFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dirtLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unknownFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unknownLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint positionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint positionHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inspectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path fillPath = new Path();
    private final ScaleGestureDetector scaleDetector;

    private List<LatLng> track = Collections.emptyList();
    private SurfaceProfile surfaceProfile;
    private TrackPosition currentPosition;
    private TrackPosition inspectionPosition;
    private OnInspectionPositionChangedListener inspectionListener;
    private boolean overlayMode;
    private boolean multiTouchGesture;
    private float zoom = 1;
    private float viewportStart;
    private float previousScaleFocusX;

    public ElevationProfileView(Context context) {
        this(context, null);
    }

    public ElevationProfileView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(0x441565C0);

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2 * density);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setColor(0xFF1565C0);

        configureSurfacePaints(pavedFillPaint, pavedLinePaint,
            0x5534C759, 0xFF69E58C, density);
        configureSurfacePaints(gravelFillPaint, gravelLinePaint,
            0x55F59E0B, 0xFFFFB020, density);
        configureSurfacePaints(dirtFillPaint, dirtLinePaint,
            0x55D84315, 0xFFFF7043, density);
        unknownFillPaint.setStyle(Paint.Style.FILL);
        unknownFillPaint.setColor(0x449E9E9E);
        unknownLinePaint.setStyle(Paint.Style.STROKE);
        unknownLinePaint.setStrokeWidth(3 * density);
        unknownLinePaint.setStrokeJoin(Paint.Join.ROUND);
        unknownLinePaint.setColor(0xFFBDBDBD);

        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setStrokeWidth(1);
        axisPaint.setColor(0x66888888);

        positionPaint.setColor(0xFF0288D1);
        positionPaint.setStrokeWidth(2 * density);
        positionHaloPaint.setColor(0xFFFFFFFF);
        inspectionPaint.setColor(0xFFFFB300);
        inspectionPaint.setStrokeWidth(2 * density);

        scaleDetector = new ScaleGestureDetector(context,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScaleBegin(ScaleGestureDetector detector) {
                previousScaleFocusX = detector.getFocusX();
                return true;
                }

                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                float left = chartLeft();
                float width = Math.max(1, chartRight() - left);
                float previousFocus = Math.max(0, Math.min(1,
                    (previousScaleFocusX - left) / width));
                float focus = Math.max(0, Math.min(1,
                    (detector.getFocusX() - left) / width));
                float oldSpan = 1 / zoom;
                float routeFocus = viewportStart + previousFocus * oldSpan;
                zoom = Math.max(1, Math.min(8, zoom * detector.getScaleFactor()));
                float newSpan = 1 / zoom;
                viewportStart = Math.max(0, Math.min(1 - newSpan,
                    routeFocus - focus * newSpan));
                previousScaleFocusX = detector.getFocusX();
                invalidate();
                return true;
                }
            });
    }

    private static void configureSurfacePaints(Paint fill, Paint line, int fillColor,
                                               int lineColor, float density) {
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(fillColor);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(3 * density);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(lineColor);
    }

    void setOverlayMode(boolean overlayMode) {
        this.overlayMode = overlayMode;
        if (overlayMode) {
            fillPaint.setColor(0x553F8CC9);
            linePaint.setColor(0xFF42A5F5);
            linePaint.setStrokeWidth(3 * getResources().getDisplayMetrics().density);
            axisPaint.setColor(0x55FFFFFF);
            positionPaint.setColor(0xFF35C9FF);
        }
        invalidate();
    }

    void setTrack(List<LatLng> track) {
        this.track = track == null ? Collections.<LatLng>emptyList() : track;
        currentPosition = null;
        inspectionPosition = null;
        zoom = 1;
        viewportStart = 0;
        if (inspectionListener != null) {
            inspectionListener.onInspectionPositionChanged(null);
        }
        invalidate();
    }

    void setCurrentPosition(TrackPosition position) {
        currentPosition = position;
        invalidate();
    }

    void setSurfaceProfile(SurfaceProfile surfaceProfile) {
        this.surfaceProfile = surfaceProfile;
        invalidate();
    }

    void setOnInspectionPositionChangedListener(
            OnInspectionPositionChangedListener inspectionListener) {
        this.inspectionListener = inspectionListener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            multiTouchGesture = false;
            getParent().requestDisallowInterceptTouchEvent(true);
        } else if (event.getPointerCount() > 1) {
            multiTouchGesture = true;
        }

        if (!multiTouchGesture && event.getPointerCount() == 1
                && (event.getActionMasked() == MotionEvent.ACTION_DOWN
                || event.getActionMasked() == MotionEvent.ACTION_MOVE)) {
            updateInspection(event.getX());
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            getParent().requestDisallowInterceptTouchEvent(false);
            if (event.getActionMasked() == MotionEvent.ACTION_UP && !multiTouchGesture) {
                performClick();
            }
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void updateInspection(float touchX) {
        double total = totalDistance();
        float left = chartLeft();
        float width = chartRight() - left;
        if (total <= 0 || width <= 0) {
            return;
        }
        double visibleStart = viewportStart * total;
        double visibleSpan = total / zoom;
        double fraction = Math.max(0, Math.min(1, (touchX - left) / width));
        inspectionPosition = TrackPosition.atDistance(
                track, visibleStart + fraction * visibleSpan);
        if (inspectionListener != null) {
            inspectionListener.onInspectionPositionChanged(inspectionPosition);
        }
        invalidate();
    }

    /** True when there is enough elevation data to be worth showing. */
    boolean hasProfile() {
        int withEle = 0;
        for (LatLng point : track) {
            if (point.ele != null && ++withEle >= 2) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!hasProfile()) {
            return;
        }

        // Cumulative distance and elevation for the points that actually have a height.
        List<Double> distances = new ArrayList<>();
        List<Double> elevations = new ArrayList<>();
        double running = 0;
        LatLng previous = null;
        for (LatLng point : track) {
            if (previous != null) {
                running += LatLng.distanceMeters(previous, point);
            }
            previous = point;
            if (point.ele != null) {
                distances.add(running);
                elevations.add(point.ele);
            }
        }
        if (distances.size() < 2) {
            return;
        }

        double total = totalDistance();
        double low = Collections.min(elevations);
        double high = Collections.max(elevations);
        if (total <= 0) {
            return;
        }
        // A flat route would otherwise divide by zero; give it a nominal 10 m window so the
        // line sits in the middle rather than filling the whole box.
        double range = Math.max(high - low, 10);

        float density = getResources().getDisplayMetrics().density;
        float left = chartLeft();
        float top = (overlayMode ? 8 : 4) * density;
        float right = chartRight();
        float bottom = getHeight() - 2 * density;
        if (right <= left || bottom <= top) {
            return;
        }

        double visibleStart = viewportStart * total;
        double visibleEnd = visibleStart + total / zoom;

        canvas.save();
        canvas.clipRect(left, top, right, bottom);
        path.reset();
        float firstX = 0;
        float lastX = 0;
        float lastY = 0;
        for (int i = 0; i < distances.size(); i++) {
            float x = (float) (left
                    + (distances.get(i) - visibleStart) / (visibleEnd - visibleStart)
                    * (right - left));
            float y = (float) (bottom - (elevations.get(i) - low) / range * (bottom - top));
            if (i == 0) {
                path.moveTo(x, y);
                firstX = x;
            } else if (i < distances.size() - 1) {
                float nextX = (float) (left
                        + (distances.get(i + 1) - visibleStart)
                        / (visibleEnd - visibleStart) * (right - left));
                float nextY = (float) (bottom
                        - (elevations.get(i + 1) - low) / range * (bottom - top));
                path.quadTo(x, y, (x + nextX) / 2, (y + nextY) / 2);
            } else {
                path.lineTo(x, y);
            }
            lastX = x;
            lastY = y;
        }
        path.lineTo(lastX, lastY);
        fillPath.set(path);
        fillPath.lineTo(lastX, bottom);
        fillPath.lineTo(firstX, bottom);
        fillPath.close();
        canvas.drawPath(fillPath, fillPaint);
        drawSurfaceOverlays(canvas, fillPath, true, total, visibleStart, visibleEnd,
            left, top, right, bottom);
        canvas.drawPath(path, linePaint);
        drawSurfaceOverlays(canvas, path, false, total, visibleStart, visibleEnd,
            left, top, right, bottom);

        drawPosition(canvas, currentPosition, positionPaint, low, range,
                visibleStart, visibleEnd, left, top, right, bottom, density);
        drawPosition(canvas, inspectionPosition, inspectionPaint, low, range,
                visibleStart, visibleEnd, left, top, right, bottom, density);
        canvas.restore();

        canvas.drawLine(left, bottom, right, bottom, axisPaint);
    }

    private void drawSurfaceOverlays(Canvas canvas, Path drawnPath, boolean fill, double total,
                                     double visibleStart, double visibleEnd, float left,
                                     float top, float right, float bottom) {
        if (surfaceProfile == null) {
            return;
        }
        for (SurfaceProfile.Interval interval : surfaceProfile.intervals) {
            if (interval.category == SurfaceProfile.Category.TARMAC) {
                continue;
            }
            float from = (float) (left + (interval.startFraction * total - visibleStart)
                    / (visibleEnd - visibleStart) * (right - left));
            float to = (float) (left + (interval.endFraction * total - visibleStart)
                    / (visibleEnd - visibleStart) * (right - left));
            from = Math.max(left, from);
            to = Math.min(right, to);
            if (to <= from) {
                continue;
            }
            Paint paint = surfacePaint(interval.category, fill);
            canvas.save();
            canvas.clipRect(from, top, to, bottom);
            canvas.drawPath(drawnPath, paint);
            canvas.restore();
        }
    }

    private Paint surfacePaint(SurfaceProfile.Category category, boolean fill) {
        switch (category) {
            case PAVED:
                return fill ? pavedFillPaint : pavedLinePaint;
            case GRAVEL:
                return fill ? gravelFillPaint : gravelLinePaint;
            case DIRT:
                return fill ? dirtFillPaint : dirtLinePaint;
            default:
                return fill ? unknownFillPaint : unknownLinePaint;
        }
    }

    private void drawPosition(Canvas canvas, TrackPosition position, Paint paint,
                              double low, double range, double visibleStart, double visibleEnd,
                              float left, float top, float right, float bottom, float density) {
        if (position == null || position.distanceMeters < visibleStart
                || position.distanceMeters > visibleEnd) {
            return;
        }
        float x = (float) (left + (position.distanceMeters - visibleStart)
                / (visibleEnd - visibleStart) * (right - left));
        canvas.drawLine(x, top, x, bottom, paint);
        if (position.point.ele != null) {
            float y = (float) (bottom - (position.point.ele - low) / range * (bottom - top));
            y = Math.max(top, Math.min(bottom, y));
            canvas.drawCircle(x, y, 6 * density, positionHaloPaint);
            canvas.drawCircle(x, y, 4 * density, paint);
        }
    }

    private float chartLeft() {
        return 2 * getResources().getDisplayMetrics().density;
    }

    private float chartRight() {
        return getWidth() - 2 * getResources().getDisplayMetrics().density;
    }

    private double totalDistance() {
        double total = 0;
        for (int i = 1; i < track.size(); i++) {
            total += LatLng.distanceMeters(track.get(i - 1), track.get(i));
        }
        return total;
    }
}
