package pl.net.xtech.maps2gpx;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Retrieves road-surface intervals by map matching a GPX track with Valhalla. */
final class SurfaceAnalyzer {
    private static final String ENDPOINT =
            "https://valhalla1.openstreetmap.de/trace_attributes";
    private static final int MAX_SHAPE_POINTS = 300;
    private static final int RETRY_SHAPE_POINTS = 120;
    private static final double MAX_CHUNK_METERS = 150_000;
    private static final double MAX_PAIR_METERS = 5_000;

    private SurfaceAnalyzer() {
    }

    static SurfaceProfile fetch(List<LatLng> track) throws IOException {
        if (track == null || track.size() < 2) {
            throw new IOException("Need at least two points to analyze route surfaces.");
        }
        List<List<LatLng>> chunks = splitByDistance(track, MAX_CHUNK_METERS);
        List<SurfaceProfile> profiles = new ArrayList<>(chunks.size());
        List<Double> lengths = new ArrayList<>(chunks.size());
        for (List<LatLng> chunk : chunks) {
            profiles.add(fetchChunk(chunk));
            lengths.add(lengthOf(chunk));
        }
        return combine(profiles, lengths);
    }

    private static SurfaceProfile fetchChunk(List<LatLng> track) throws IOException {
        List<LatLng> shape = traceShape(track, MAX_SHAPE_POINTS);
        try {
            return fetchShape(shape);
        } catch (IOException firstFailure) {
            List<LatLng> retryShape = traceShape(track, RETRY_SHAPE_POINTS);
            try {
                return fetchShape(retryShape);
            } catch (IOException retryFailure) {
                retryFailure.addSuppressed(firstFailure);
                throw retryFailure;
            }
        }
    }

    static List<LatLng> traceShape(List<LatLng> track, int maxPoints) {
        return densify(TrackSimplify.thinTo(track, maxPoints), MAX_PAIR_METERS);
    }

    static List<List<LatLng>> splitByDistance(List<LatLng> track, double maxMeters) {
        List<List<LatLng>> chunks = new ArrayList<>();
        List<LatLng> chunk = new ArrayList<>();
        chunk.add(track.get(0));
        double chunkDistance = 0;
        for (int i = 1; i < track.size(); i++) {
            LatLng current = track.get(i - 1);
            LatLng destination = track.get(i);
            double remaining = LatLng.distanceMeters(current, destination);
            while (chunkDistance + remaining > maxMeters) {
                double capacity = maxMeters - chunkDistance;
                if (capacity <= 0) {
                    chunks.add(chunk);
                    chunk = new ArrayList<>();
                    chunk.add(current);
                    chunkDistance = 0;
                    continue;
                }
                double fraction = capacity / remaining;
                LatLng boundary = interpolate(current, destination, fraction);
                chunk.add(boundary);
                chunks.add(chunk);
                chunk = new ArrayList<>();
                chunk.add(boundary);
                current = boundary;
                remaining = LatLng.distanceMeters(current, destination);
                chunkDistance = 0;
            }
            chunk.add(destination);
            chunkDistance += remaining;
        }
        if (chunk.size() >= 2) {
            chunks.add(chunk);
        }
        return chunks;
    }

    private static LatLng interpolate(LatLng from, LatLng to, double fraction) {
        return new LatLng(
                from.lat + (to.lat - from.lat) * fraction,
                from.lon + (to.lon - from.lon) * fraction);
    }

    private static List<LatLng> densify(List<LatLng> shape, double maxMeters) {
        List<LatLng> dense = new ArrayList<>();
        dense.add(shape.get(0));
        for (int i = 1; i < shape.size(); i++) {
            LatLng from = shape.get(i - 1);
            LatLng to = shape.get(i);
            double distance = LatLng.distanceMeters(from, to);
            int parts = Math.max(1, (int) Math.ceil(distance / maxMeters));
            for (int part = 1; part < parts; part++) {
                double fraction = (double) part / parts;
                dense.add(interpolate(from, to, fraction));
            }
            dense.add(to);
        }
        return dense;
    }

    static SurfaceProfile combine(List<SurfaceProfile> profiles,
                                  List<Double> lengths) throws IOException {
        double total = 0;
        for (double length : lengths) {
            total += length;
        }
        if (total <= 0) {
            throw new IOException("Surface route has no measurable length.");
        }
        List<SurfaceProfile.Interval> combined = new ArrayList<>();
        double offset = 0;
        for (int i = 0; i < profiles.size(); i++) {
            double length = lengths.get(i);
            for (SurfaceProfile.Interval interval : profiles.get(i).intervals) {
                double start = (offset + interval.startFraction * length) / total;
                double end = (offset + interval.endFraction * length) / total;
                combined.add(new SurfaceProfile.Interval(start, end, interval.surface));
            }
            offset += length;
        }
        if (!combined.isEmpty()) {
            SurfaceProfile.Interval last = combined.get(combined.size() - 1);
            combined.set(combined.size() - 1, new SurfaceProfile.Interval(
                    last.startFraction, 1, last.surface));
        }
        return new SurfaceProfile(combined);
    }

    private static double lengthOf(List<LatLng> track) {
        double total = 0;
        for (int i = 1; i < track.size(); i++) {
            total += LatLng.distanceMeters(track.get(i - 1), track.get(i));
        }
        return total;
    }

    private static SurfaceProfile fetchShape(List<LatLng> shape) throws IOException {
        return parse(Http.postJson(ENDPOINT, request(shape), Http.APP_USER_AGENT));
    }

    private static String request(List<LatLng> shape) throws IOException {
        try {
            JSONArray points = new JSONArray();
            for (LatLng point : shape) {
                points.put(new JSONObject().put("lat", point.lat).put("lon", point.lon));
            }
            JSONArray attributes = new JSONArray()
                    .put("edge.surface")
                    .put("edge.length");
            JSONObject filters = new JSONObject()
                    .put("action", "include")
                    .put("attributes", attributes);
            return new JSONObject()
                    .put("shape", points)
                    .put("costing", "bicycle")
                    .put("shape_match", "walk_or_snap")
                    .put("filters", filters)
                    .toString();
        } catch (JSONException e) {
            throw new IOException("Could not build the surface request", e);
        }
    }

    static SurfaceProfile parse(String body) throws IOException {
        try {
            JSONObject json = new JSONObject(body);
            if (json.has("error")) {
                throw new IOException("Surface service: " + json.optString("error"));
            }
            JSONArray edges = json.getJSONArray("edges");
            List<String> surfaces = new ArrayList<>();
            List<Double> lengths = new ArrayList<>();
            double total = 0;
            for (int i = 0; i < edges.length(); i++) {
                JSONObject edge = edges.getJSONObject(i);
                double length = edge.optDouble("length", 0);
                if (length <= 0) {
                    continue;
                }
                surfaces.add(edge.optString("surface", "unknown"));
                lengths.add(length);
                total += length;
            }
            if (total <= 0) {
                throw new IOException("Surface service returned no matched route edges.");
            }

            return fromEdges(surfaces, lengths);
        } catch (JSONException e) {
            throw new IOException("Unexpected surface response", e);
        }
    }

    static SurfaceProfile fromEdges(List<String> surfaces, List<Double> lengths)
            throws IOException {
        if (surfaces.size() != lengths.size()) {
            throw new IOException("Surface edge values do not match their lengths.");
        }
        double total = 0;
        for (double length : lengths) {
            total += Math.max(0, length);
        }
        if (total <= 0) {
            throw new IOException("Surface service returned no matched route edges.");
        }

        List<SurfaceProfile.Interval> intervals = new ArrayList<>();
            double start = 0;
            for (int i = 0; i < surfaces.size(); i++) {
                double end = i == surfaces.size() - 1 ? 1 : start + lengths.get(i) / total;
                String surface = surfaces.get(i);
                if (!intervals.isEmpty()) {
                    SurfaceProfile.Interval previous = intervals.get(intervals.size() - 1);
                    if (previous.surface.equals(surface)) {
                        intervals.set(intervals.size() - 1, new SurfaceProfile.Interval(
                                previous.startFraction, end, surface));
                        start = end;
                        continue;
                    }
                }
                intervals.add(new SurfaceProfile.Interval(start, end, surface));
                start = end;
            }
            return new SurfaceProfile(intervals);
    }
}