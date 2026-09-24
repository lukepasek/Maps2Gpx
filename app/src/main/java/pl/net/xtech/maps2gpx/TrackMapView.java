package pl.net.xtech.maps2gpx;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import org.maplibre.android.MapLibre;
import org.maplibre.android.annotations.Icon;
import org.maplibre.android.annotations.IconFactory;
import org.maplibre.android.annotations.Marker;
import org.maplibre.android.annotations.MarkerOptions;
import org.maplibre.android.annotations.PolylineOptions;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.WellKnownTileServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import androidx.core.content.ContextCompat;

/** Interactive MapLibre track map with the existing outline as a no-token/load fallback. */
public class TrackMapView extends FrameLayout {
    static final String STYLE_URL = "mapbox://styles/mapbox/outdoors-v12";

    private final TrackOutlineView fallback;
    private MapView mapView;
    private MapLibreMap map;
    private List<LatLng> track = Collections.emptyList();
    private List<LatLng> referenceTrack = Collections.emptyList();
    private SurfaceProfile surfaceProfile;
    private LatLng currentLocation;
    private Icon startIcon;
    private Icon finishIcon;
    private Icon positionIcon;
    private Icon inspectionIcon;
    private Marker positionMarker;
    private Marker inspectionMarker;
    private TrackPosition inspectionPosition;
    private int compassTopMargin;

    public TrackMapView(Context context) {
        this(context, null);
    }

    public TrackMapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        fallback = new TrackOutlineView(context, attrs);
        addView(fallback, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        if (BuildConfig.MAPBOX_ACCESS_TOKEN.isEmpty()) {
            return;
        }
        MapLibre.getInstance(context.getApplicationContext(), BuildConfig.MAPBOX_ACCESS_TOKEN,
            WellKnownTileServer.Mapbox);
        mapView = new MapView(context, attrs);
        addView(mapView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        mapView.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                getParent().requestDisallowInterceptTouchEvent(true);
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });
        mapView.addOnDidFailLoadingMapListener(error -> {
            mapView.setVisibility(View.GONE);
            fallback.setVisibility(View.VISIBLE);
        });
        mapView.onCreate(null);
        mapView.getMapAsync(readyMap -> {
            map = readyMap;
            configureCompass();
            map.setStyle(STYLE_URL, style -> {
                fallback.setVisibility(View.GONE);
                drawTrack(true);
            });
        });
    }

    void setCompassTopMargin(int margin) {
        compassTopMargin = margin;
        configureCompass();
    }

    private void configureCompass() {
        if (map == null) {
            return;
        }
        int endMargin = Math.round(12 * getResources().getDisplayMetrics().density);
        map.getUiSettings().setCompassEnabled(true);
        map.getUiSettings().setCompassGravity(Gravity.TOP | Gravity.END);
        map.getUiSettings().setCompassMargins(0, compassTopMargin, endMargin, 0);
        map.getUiSettings().setCompassFadeFacingNorth(false);
        map.getUiSettings().setCompassImage(
            ContextCompat.getDrawable(getContext(), R.drawable.ic_map_compass));
    }

    void setTrack(List<LatLng> points, String cacheKey) {
        track = points == null ? Collections.emptyList() : points;
        inspectionPosition = null;
        fallback.setTrack(track);
        drawTrack(true);
        if (track.size() >= 2 && !BuildConfig.MAPBOX_ACCESS_TOKEN.isEmpty()) {
            RouteTileCache.cache(getContext(), cacheKey, track);
        }
    }

    void setReferenceTrack(List<LatLng> points) {
        referenceTrack = points == null ? Collections.emptyList() : points;
        fallback.setReferenceTrack(referenceTrack);
        drawTrack(false);
    }

    void setSurfaceProfile(SurfaceProfile surfaceProfile) {
        this.surfaceProfile = surfaceProfile;
        fallback.setSurfaceProfile(surfaceProfile);
        drawTrack(false);
    }

    void setCurrentPosition(LatLng location, TrackPosition trackPosition) {
        currentLocation = location;
        fallback.setCurrentPosition(trackPosition);
        drawCurrentPosition();
    }

    void setInspectionPosition(TrackPosition position) {
        inspectionPosition = position;
        drawInspectionPosition();
    }

    boolean centerOnCurrentPosition() {
        if (map == null || currentLocation == null) {
            return false;
        }
        CameraPosition currentCamera = map.getCameraPosition();
        CameraPosition centered = new CameraPosition.Builder(currentCamera)
                .target(mapPoint(currentLocation))
                .zoom(Math.max(currentCamera.zoom, 15))
                .build();
        map.animateCamera(CameraUpdateFactory.newCameraPosition(centered));
        return true;
    }

    private void drawTrack(boolean fitCamera) {
        if (map == null || map.getStyle() == null || track.size() < 2) {
            return;
        }
        map.clear();
        positionMarker = null;
        inspectionMarker = null;
        List<org.maplibre.android.geometry.LatLng> points = new ArrayList<>(track.size());
        LatLngBounds.Builder bounds = new LatLngBounds.Builder();
        for (LatLng point : track) {
            org.maplibre.android.geometry.LatLng mapPoint = mapPoint(point);
            points.add(mapPoint);
            bounds.include(mapPoint);
        }
        List<org.maplibre.android.geometry.LatLng> referencePoints =
                new ArrayList<>(referenceTrack.size());
        for (LatLng point : referenceTrack) {
            org.maplibre.android.geometry.LatLng mapPoint = mapPoint(point);
            referencePoints.add(mapPoint);
            bounds.include(mapPoint);
        }
        addLine(referencePoints, 0xFF757575, 7);
        drawSurfaceLines(points);
        ensureMarkerIcons();
        map.addMarker(new MarkerOptions().position(points.get(0))
            .icon(startIcon).title("Start"));
        map.addMarker(new MarkerOptions().position(points.get(points.size() - 1))
            .icon(finishIcon).title("Finish"));
        drawCurrentPosition();
        drawInspectionPosition();
        if (fitCamera) {
            float density = getResources().getDisplayMetrics().density;
            int horizontalPadding = Math.round(42 * density);
            int topPadding = Math.round(100 * density);
            int bottomPadding = Math.round(430 * density);
            post(() -> map.animateCamera(CameraUpdateFactory.newLatLngBounds(
                bounds.build(), horizontalPadding, topPadding,
                horizontalPadding, bottomPadding)));
        }
    }

    private void drawSurfaceLines(List<org.maplibre.android.geometry.LatLng> points) {
        if (surfaceProfile == null) {
            addLine(points, 0xFF1565C0);
            return;
        }
        double total = 0;
        for (int i = 1; i < track.size(); i++) {
            total += LatLng.distanceMeters(track.get(i - 1), track.get(i));
        }
        if (total <= 0) {
            addLine(points, 0xFF9E9E9E);
            return;
        }

        List<org.maplibre.android.geometry.LatLng> run = new ArrayList<>();
        run.add(points.get(0));
        double distance = 0;
        SurfaceProfile.Category active = null;
        for (int i = 1; i < track.size(); i++) {
            double segment = LatLng.distanceMeters(track.get(i - 1), track.get(i));
            SurfaceProfile.Category category = surfaceProfile.categoryAt(
                    (distance + segment / 2) / total);
            if (active != null && category != active) {
                addLine(run, colorOf(active));
                run = new ArrayList<>();
                run.add(points.get(i - 1));
            }
            active = category;
            run.add(points.get(i));
            distance += segment;
        }
        addLine(run, colorOf(active));
    }

    private void addLine(List<org.maplibre.android.geometry.LatLng> points, int color) {
        addLine(points, color, 5);
    }

    private void addLine(List<org.maplibre.android.geometry.LatLng> points, int color,
                         float width) {
        if (points.size() >= 2) {
            map.addPolyline(new PolylineOptions().addAll(points).color(color).width(width));
        }
    }

    private static int colorOf(SurfaceProfile.Category category) {
        return category.color;
    }

    private void drawCurrentPosition() {
        if (map == null || map.getStyle() == null || currentLocation == null
                || track.size() < 2) {
            return;
        }
        ensureMarkerIcons();
        org.maplibre.android.geometry.LatLng point = mapPoint(currentLocation);
        if (positionMarker == null) {
            positionMarker = map.addMarker(new MarkerOptions()
                    .position(point)
                    .icon(positionIcon)
                    .title("Current position"));
        } else {
            positionMarker.setPosition(point);
            map.updateMarker(positionMarker);
        }
    }

    private void drawInspectionPosition() {
        if (map == null || map.getStyle() == null || inspectionPosition == null
                || track.size() < 2) {
            return;
        }
        ensureMarkerIcons();
        org.maplibre.android.geometry.LatLng point = mapPoint(inspectionPosition.point);
        if (inspectionMarker == null) {
            inspectionMarker = map.addMarker(new MarkerOptions()
                    .position(point)
                    .icon(inspectionIcon)
                    .title("Profile position"));
        } else {
            inspectionMarker.setPosition(point);
            map.updateMarker(inspectionMarker);
        }
    }

    private void ensureMarkerIcons() {
        if (startIcon != null) {
            return;
        }
        IconFactory icons = IconFactory.getInstance(getContext());
        startIcon = icons.fromBitmap(markerBitmap(0xFF2E7D32, true, 30));
        finishIcon = icons.fromBitmap(markerBitmap(0xFFD32F2F, false, 30));
        positionIcon = icons.fromBitmap(positionBitmap(0xFF0288D1, 22));
        inspectionIcon = icons.fromBitmap(positionBitmap(0xFFFFB300, 22));
    }

    private Bitmap markerBitmap(int color, boolean start, int sizeDp) {
        float density = getResources().getDisplayMetrics().density;
        int diameter = Math.round(sizeDp * density);
        int padding = Math.round(5 * density);
        int size = diameter + 2 * padding;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float center = size / 2f;
        paint.setColor(color);
        paint.setShadowLayer(2.5f * density, 0, 1.5f * density, 0x70000000);
        canvas.drawCircle(center, center, diameter / 2f, paint);
        paint.clearShadowLayer();
        paint.setColor(0xFFFFFFFF);
        if (start) {
            android.graphics.Path play = new android.graphics.Path();
            float radius = 8 * density;
            float shift = density;
            play.moveTo(center + radius + shift, center);
            play.lineTo(center - radius * 0.65f + shift, center - radius);
            play.lineTo(center - radius * 0.65f + shift, center + radius);
            play.close();
            canvas.drawPath(play, paint);
        } else {
            float half = 6 * density;
            canvas.drawRect(center - half, center - half, center + half, center + half, paint);
        }
        return bitmap;
    }

    private Bitmap positionBitmap(int color, int sizeDp) {
        float density = getResources().getDisplayMetrics().density;
        int diameter = Math.round(sizeDp * density);
        int padding = Math.round(5 * density);
        int size = diameter + 2 * padding;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float center = size / 2f;
        paint.setColor(0xFFFFFFFF);
        paint.setShadowLayer(2.5f * density, 0, 1.5f * density, 0x70000000);
        canvas.drawCircle(center, center, diameter / 2f, paint);
        paint.clearShadowLayer();
        paint.setColor(color);
        canvas.drawCircle(center, center, diameter / 2f - 2 * density, paint);
        return bitmap;
    }

    private static org.maplibre.android.geometry.LatLng mapPoint(LatLng point) {
        return new org.maplibre.android.geometry.LatLng(point.lat, point.lon);
    }

    void onStart() {
        if (mapView != null) mapView.onStart();
    }

    void onResume() {
        if (mapView != null) mapView.onResume();
    }

    void onPause() {
        if (mapView != null) mapView.onPause();
    }

    void onStop() {
        if (mapView != null) mapView.onStop();
    }

    void onLowMemory() {
        if (mapView != null) mapView.onLowMemory();
    }

    void onSaveInstanceState(Bundle state) {
        if (mapView != null) mapView.onSaveInstanceState(state);
    }

    void onDestroy() {
        if (mapView != null) mapView.onDestroy();
    }
}