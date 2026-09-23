package pl.net.xtech.maps2gpx;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Elevation against distance travelled - not against point index, which would distort the
 * profile wherever the router emitted points at an uneven spacing.
 *
 * <p>Points with no elevation are skipped rather than treated as sea level, so a partial gap
 * in the DEM data leaves a straight segment instead of a spike down to zero.
 */
public class ElevationProfileView extends View {

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private List<LatLng> track = Collections.emptyList();

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

        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setStrokeWidth(1);
        axisPaint.setColor(0x66888888);

        textPaint.setColor(0xFF888888);
        textPaint.setTextSize(10 * getResources().getDisplayMetrics().scaledDensity);
    }

    void setTrack(List<LatLng> track) {
        this.track = track == null ? Collections.<LatLng>emptyList() : track;
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

        double total = distances.get(distances.size() - 1) - distances.get(0);
        double low = Collections.min(elevations);
        double high = Collections.max(elevations);
        if (total <= 0) {
            return;
        }
        // A flat route would otherwise divide by zero; give it a nominal 10 m window so the
        // line sits in the middle rather than filling the whole box.
        double range = Math.max(high - low, 10);

        float density = getResources().getDisplayMetrics().density;
        float left = 34 * density;
        float top = 4 * density;
        float right = getWidth() - 2 * density;
        float bottom = getHeight() - 12 * density;
        if (right <= left || bottom <= top) {
            return;
        }

        path.reset();
        float firstX = 0;
        float lastX = 0;
        for (int i = 0; i < distances.size(); i++) {
            float x = (float) (left
                    + (distances.get(i) - distances.get(0)) / total * (right - left));
            float y = (float) (bottom - (elevations.get(i) - low) / range * (bottom - top));
            if (i == 0) {
                path.moveTo(x, y);
                firstX = x;
            } else {
                path.lineTo(x, y);
            }
            lastX = x;
        }
        canvas.drawPath(path, linePaint);

        // Close the same line down to the baseline for the fill.
        path.lineTo(lastX, bottom);
        path.lineTo(firstX, bottom);
        path.close();
        canvas.drawPath(path, fillPaint);

        canvas.drawLine(left, bottom, right, bottom, axisPaint);
        canvas.drawText(String.format(Locale.US, "%.0f m", high), 0, top + textPaint.getTextSize(),
                textPaint);
        canvas.drawText(String.format(Locale.US, "%.0f m", low), 0, bottom, textPaint);
        String distanceLabel = String.format(Locale.US, "%.1f km", total / 1000.0);
        canvas.drawText(distanceLabel,
                right - textPaint.measureText(distanceLabel), getHeight() - 1, textPaint);
    }
}
