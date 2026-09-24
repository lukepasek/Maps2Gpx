package pl.net.xtech.maps2gpx;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.offline.OfflineManager;
import org.maplibre.android.offline.OfflineRegion;
import org.maplibre.android.offline.OfflineRegionError;
import org.maplibre.android.offline.OfflineRegionStatus;
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Persistent MapLibre offline regions covering saved GPX tracks. */
final class RouteTileCache {
    private static final String TAG = "Maps2Gpx";
    private static final String KEY = "gpx";
    private static final String VERSION_KEY = "version";
    private static final int VERSION = 2;

    private RouteTileCache() {
    }

    static void cache(Context context, String cacheKey, List<LatLng> track) {
        OfflineManager manager = OfflineManager.getInstance(context.getApplicationContext());
        manager.listOfflineRegions(new OfflineManager.ListOfflineRegionsCallback() {
            @Override
            public void onList(OfflineRegion[] regions) {
                for (OfflineRegion region : regions) {
                    if (cacheKey.equals(keyOf(region))) {
                        region.setDownloadState(OfflineRegion.STATE_INACTIVE);
                        if (versionOf(region) == VERSION) {
                            return;
                        }
                    }
                }
                create(manager, context, cacheKey, track);
            }

            @Override
            public void onError(String error) {
                Log.w(TAG, "Could not list offline maps: " + error);
            }
        });
    }

    static void delete(Context context, String cacheKey) {
        OfflineManager.getInstance(context.getApplicationContext())
                .listOfflineRegions(new OfflineManager.ListOfflineRegionsCallback() {
                    @Override
                    public void onList(OfflineRegion[] regions) {
                        for (OfflineRegion region : regions) {
                            if (cacheKey.equals(keyOf(region))) {
                                region.delete(new OfflineRegion.OfflineRegionDeleteCallback() {
                                    @Override
                                    public void onDelete() {
                                    }

                                    @Override
                                    public void onError(String error) {
                                        Log.w(TAG, "Could not delete offline map: " + error);
                                    }
                                });
                            }
                        }
                    }

                    @Override
                    public void onError(String error) {
                        Log.w(TAG, "Could not list offline maps: " + error);
                    }
                });
    }

    private static void create(OfflineManager manager, Context context, String cacheKey,
                               List<LatLng> track) {
        LatLngBounds bounds = paddedBounds(track);
        OfflineTilePyramidRegionDefinition definition =
                new OfflineTilePyramidRegionDefinition(TrackMapView.STYLE_URL, bounds,
                    8, 15, context.getResources().getDisplayMetrics().density, false);
        manager.createOfflineRegion(definition, metadata(cacheKey),
                new OfflineManager.CreateOfflineRegionCallback() {
                    @Override
                    public void onCreate(OfflineRegion region) {
                        region.setObserver(new OfflineRegion.OfflineRegionObserver() {
                            @Override
                            public void onStatusChanged(OfflineRegionStatus status) {
                                if (status.isComplete()) {
                                    region.setDownloadState(OfflineRegion.STATE_INACTIVE);
                                }
                            }

                            @Override
                            public void onError(OfflineRegionError error) {
                                region.setDownloadState(OfflineRegion.STATE_INACTIVE);
                                Log.w(TAG, "Offline map download failed: " + error.getMessage());
                            }

                            @Override
                            public void mapboxTileCountLimitExceeded(long limit) {
                                Log.w(TAG, "Offline map tile limit exceeded: " + limit);
                            }
                        });
                        region.setDownloadState(OfflineRegion.STATE_ACTIVE);
                    }

                    @Override
                    public void onError(String error) {
                        Log.w(TAG, "Could not create offline map: " + error);
                    }
                });
    }

    private static LatLngBounds paddedBounds(List<LatLng> track) {
        double north = -90;
        double south = 90;
        double east = -180;
        double west = 180;
        for (LatLng point : track) {
            north = Math.max(north, point.lat);
            south = Math.min(south, point.lat);
            east = Math.max(east, point.lon);
            west = Math.min(west, point.lon);
        }
        double latPad = Math.max((north - south) * 0.1, 0.005);
        double lonPad = Math.max((east - west) * 0.1, 0.005);
        return LatLngBounds.from(Math.min(90, north + latPad),
                Math.min(180, east + lonPad), Math.max(-90, south - latPad),
                Math.max(-180, west - lonPad));
    }

    private static byte[] metadata(String cacheKey) {
        try {
            return new JSONObject().put(KEY, cacheKey).put(VERSION_KEY, VERSION).toString()
                    .getBytes(StandardCharsets.UTF_8);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String keyOf(OfflineRegion region) {
        return metadataOf(region).optString(KEY, "");
    }

    private static int versionOf(OfflineRegion region) {
        return metadataOf(region).optInt(VERSION_KEY, 0);
    }

    private static JSONObject metadataOf(OfflineRegion region) {
        try {
            return new JSONObject(new String(region.getMetadata(), StandardCharsets.UTF_8));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }
}