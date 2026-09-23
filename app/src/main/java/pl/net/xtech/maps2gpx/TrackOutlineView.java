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

/**
 * Plan view of the track: the route's shape, scaled to fit, with start and finish markers
 * matching the launcher icon (green triangle, red square).
 *
 * <p>An optional reference track is drawn behind it in grey. That is what makes a re-route
 * readable: the two shapes share one projection and one bounding box, so where the new track
 * leaves the old one is visible directly rather than inferred from two totals.
 *
 * <p>Drawn straight onto a Canvas rather than pulling in a map library - there is no basemap
 * here, just the outline, which is enough to recognise a route at a glance.
 */
public class TrackOutlineView extends View {

    private static final float PADDING_DP = 8;

    /**
     * A recorded track can carry tens of thousands of points, and a Path that long costs real
     * time to draw for sub-pixel detail nobody can see at this size.
     */
    private static final int MAX_DRAWN_POINTS = 3000;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint referencePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint startPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint finishPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The play/stop glyphs inside the markers - white reads on both fills, in both themes. */
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path glyph = new Path();

    private List<LatLng> track = Collections.emptyList();
    private List<LatLng> reference = Collections.emptyList();

    public TrackOutlineView(Context context) {
        this(context, null);
    }

    public TrackOutlineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;

        trackPaint.setStyle(Paint.Style.STROKE);
        // A touch heavier now that there is no casing behind it.
        trackPaint.setStrokeWidth(3 * density);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setStrokeJoin(Paint.Join.ROUND);
        trackPaint.setColor(0xFF1565C0);

        // Deliberately WIDER than the route above it, not thinner. A re-route usually follows
        // most of the original, and a thin grey line under a thicker blue one is simply
        // invisible for the whole shared stretch - measured on a real 18 km re-route, the grey
        // could not be seen at all. As a casing it reads as a halo where the two agree and as a
        // separate line where they part, which is the comparison the page exists to show.
        referencePaint.setStyle(Paint.Style.STROKE);
        referencePaint.setStrokeWidth(7 * density);
        referencePaint.setStrokeCap(Paint.Cap.ROUND);
        referencePaint.setStrokeJoin(Paint.Join.ROUND);
        // Mid-grey, kept semi-transparent so it reads as background against either theme.
        referencePaint.setColor(0x999E9E9E);

        startPaint.setColor(0xFF2E7D32);
        finishPaint.setColor(0xFFD32F2F);
        glyphPaint.setColor(0xFFFFFFFF);
    }

    void setTrack(List<LatLng> track) {
        this.track = thin(track);
        invalidate();
    }

    /**
     * The track this one was re-routed from, drawn behind it. Pass null to clear it, which
     * matters on a reroute: the view is reused across conversions.
     */
    void setReferenceTrack(List<LatLng> reference) {
        this.reference = thin(reference);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (track.size() < 2) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        float pad = PADDING_DP * density;
        float usableWidth = getWidth() - 2 * pad;
        float usableHeight = getHeight() - 2 * pad;
        if (usableWidth <= 0 || usableHeight <= 0) {
            return;
        }

        // Equirectangular with a cos(lat) correction, so the shape is not stretched
        // east-west. Good enough for a route that spans a few tens of kilometres.
        // Both tracks share the projection and the extent below, which is the whole point:
        // scaling them separately would make two different routes look identical.
        double meanLat = 0;
        int counted = 0;
        for (LatLng point : track) {
            meanLat += point.lat;
            counted++;
        }
        for (LatLng point : reference) {
            meanLat += point.lat;
            counted++;
        }
        double lonScale = Math.cos(Math.toRadians(meanLat / counted));

        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (int pass = 0; pass < 2; pass++) {
            for (LatLng point : pass == 0 ? track : reference) {
                double x = point.lon * lonScale;
                double y = -point.lat;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        // A dead-straight route has zero extent on one axis; keep the divisor sane.
        double spanX = Math.max(maxX - minX, 1e-9);
        double spanY = Math.max(maxY - minY, 1e-9);
        double scale = Math.min(usableWidth / spanX, usableHeight / spanY);

        // Centre whatever is left over after preserving the aspect ratio.
        float offsetX = (float) (pad + (usableWidth - spanX * scale) / 2);
        float offsetY = (float) (pad + (usableHeight - spanY * scale) / 2);

        // Underneath, so the new route stays the one being read.
        if (reference.size() >= 2) {
            trace(reference, lonScale, minX, minY, scale, offsetX, offsetY);
            canvas.drawPath(path, referencePaint);
        }
        trace(track, lonScale, minX, minY, scale, offsetX, offsetY);
        canvas.drawPath(path, trackPaint);

        LatLng first = track.get(0);
        LatLng last = track.get(track.size() - 1);
        float radius = 7 * density;
        drawStart(canvas, screenX(first, lonScale, minX, scale, offsetX),
                screenY(first, minY, scale, offsetY), radius);
        drawFinish(canvas, screenX(last, lonScale, minX, scale, offsetX),
                screenY(last, minY, scale, offsetY), radius);
    }

    /** Reuses the one Path object; both tracks are drawn before either is traced again. */
    private void trace(List<LatLng> points, double lonScale, double minX, double minY,
                       double scale, float offsetX, float offsetY) {
        path.reset();
        for (int i = 0; i < points.size(); i++) {
            LatLng point = points.get(i);
            float x = screenX(point, lonScale, minX, scale, offsetX);
            float y = screenY(point, minY, scale, offsetY);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
    }

    /**
     * Every n-th point, with the last one always kept so the finish marker and the shape's
     * extent stay where they belong.
     */
    private static List<LatLng> thin(List<LatLng> points) {
        if (points == null) {
            return Collections.emptyList();
        }
        if (points.size() <= MAX_DRAWN_POINTS) {
            return points;
        }
        int stride = (points.size() + MAX_DRAWN_POINTS - 1) / MAX_DRAWN_POINTS;
        List<LatLng> out = new ArrayList<>(points.size() / stride + 2);
        for (int i = 0; i < points.size(); i += stride) {
            out.add(points.get(i));
        }
        LatLng last = points.get(points.size() - 1);
        if (out.get(out.size() - 1) != last) {
            out.add(last);
        }
        return out;
    }

    private float screenX(LatLng p, double lonScale, double minX, double scale, float offset) {
        return (float) (offset + (p.lon * lonScale - minX) * scale);
    }

    private float screenY(LatLng p, double minY, double scale, float offset) {
        return (float) (offset + (-p.lat - minY) * scale);
    }

    /** Green circle with a white play glyph. */
    private void drawStart(Canvas canvas, float x, float y, float radius) {
        canvas.drawCircle(x, y, radius, startPaint);
        float size = radius * 0.5f;
        // Nudged left, because a right-pointing triangle's visual centre sits behind its apex.
        float shift = size * 0.15f;
        glyph.reset();
        glyph.moveTo(x + size - shift, y);
        glyph.lineTo(x - size * 0.8f - shift, y + size * 0.95f);
        glyph.lineTo(x - size * 0.8f - shift, y - size * 0.95f);
        glyph.close();
        canvas.drawPath(glyph, glyphPaint);
    }

    /** Red circle with a white stop glyph. */
    private void drawFinish(Canvas canvas, float x, float y, float radius) {
        canvas.drawCircle(x, y, radius, finishPaint);
        float half = radius * 0.42f;
        canvas.drawRect(x - half, y - half, x + half, y + half, glyphPaint);
    }
}
