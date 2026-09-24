package pl.net.xtech.maps2gpx;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TrackPositionTest {
    private static final double TOLERANCE = 0.00001;

    @Test
    public void nearestProjectsOntoSegmentAndMeasuresProgress() {
        TrackPosition position = TrackPosition.nearest(Arrays.asList(
                new LatLng(0, 0, null, 100.0),
                new LatLng(0, 0.01, null, 200.0)),
                new LatLng(0.001, 0.004));

        assertEquals(0, position.point.lat, TOLERANCE);
        assertEquals(0.004, position.point.lon, TOLERANCE);
        assertEquals(140, position.point.ele, 0.1);
        assertEquals(0.4, position.fraction, 0.001);
        assertEquals(111.2, position.distanceFromTrackMeters, 0.5);
    }

    @Test
    public void nearestClampsBeforeTrackStart() {
        TrackPosition position = TrackPosition.nearest(Arrays.asList(
                new LatLng(1, 1), new LatLng(1, 1.01)), new LatLng(1, 0.99));

        assertEquals(1, position.point.lon, TOLERANCE);
        assertEquals(0, position.distanceMeters, 0.01);
        assertEquals(0, position.fraction, 0.001);
    }

    @Test
    public void nearestChoosesClosestSegment() {
        TrackPosition position = TrackPosition.nearest(Arrays.asList(
                new LatLng(0, 0), new LatLng(0, 0.01), new LatLng(0.01, 0.01)),
                new LatLng(0.006, 0.012));

        assertEquals(0.006, position.point.lat, TOLERANCE);
        assertEquals(0.01, position.point.lon, TOLERANCE);
        assertEquals(0.8, position.fraction, 0.01);
    }

    @Test
    public void nearestReportsWhenLocationIsFarFromTrack() {
        TrackPosition position = TrackPosition.nearest(Arrays.asList(
                new LatLng(52.09, 21.00), new LatLng(52.01, 21.10)),
                new LatLng(52.292005, 21.050814));

        assertTrue(position.distanceFromTrackMeters > 20_000);
    }

    @Test
    public void atDistanceInterpolatesCoordinatesAndElevation() {
        LatLng start = new LatLng(0, 0, null, 100.0);
        LatLng finish = new LatLng(0, 0.01, null, 200.0);
        double total = LatLng.distanceMeters(start, finish);

        TrackPosition position = TrackPosition.atDistance(
                Arrays.asList(start, finish), total * 0.4);

        assertEquals(0.004, position.point.lon, TOLERANCE);
        assertEquals(140, position.point.ele, 0.1);
        assertEquals(0.4, position.fraction, 0.001);
    }

    @Test
    public void atDistanceClampsToTrackEndpoints() {
        LatLng start = new LatLng(1, 1);
        LatLng finish = new LatLng(1, 1.01);

        assertEquals(start.lon, TrackPosition.atDistance(
                Arrays.asList(start, finish), -100).point.lon, TOLERANCE);
        assertEquals(finish.lon, TrackPosition.atDistance(
                Arrays.asList(start, finish), Double.MAX_VALUE).point.lon, TOLERANCE);
    }
}