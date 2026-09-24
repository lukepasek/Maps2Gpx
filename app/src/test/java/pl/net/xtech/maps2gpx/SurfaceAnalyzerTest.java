package pl.net.xtech.maps2gpx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class SurfaceAnalyzerTest {

    @Test
    public void traceShapeFillsGapsCreatedBySimplification() {
        List<LatLng> shape = SurfaceAnalyzer.traceShape(Arrays.asList(
                new LatLng(52.0, 21.0),
                new LatLng(52.0, 21.4)), 300);

        assertTrue(shape.size() > 2);
        for (int i = 1; i < shape.size(); i++) {
            assertTrue(LatLng.distanceMeters(shape.get(i - 1), shape.get(i)) <= 5_001);
        }
    }

    @Test
    public void longTrackIsSplitBelowServiceDistanceLimit() {
        List<List<LatLng>> chunks = SurfaceAnalyzer.splitByDistance(Arrays.asList(
                new LatLng(50.0, 20.0),
                new LatLng(54.0, 20.0)), 150_000);

        assertEquals(3, chunks.size());
        for (List<LatLng> chunk : chunks) {
            assertTrue(lengthOf(chunk) <= 150_001);
        }
    }

    @Test
    public void combinedProfilesAreWeightedByChunkDistance() throws Exception {
        SurfaceProfile first = SurfaceAnalyzer.fromEdges(
                Arrays.asList("paved_smooth"), Arrays.asList(1.0));
        SurfaceProfile second = SurfaceAnalyzer.fromEdges(
                Arrays.asList("gravel"), Arrays.asList(1.0));

        SurfaceProfile combined = SurfaceAnalyzer.combine(
                Arrays.asList(first, second), Arrays.asList(25.0, 75.0));
        int[] percentages = combined.percentages();

        assertEquals(25, percentages[SurfaceProfile.Category.TARMAC.ordinal()]);
        assertEquals(75, percentages[SurfaceProfile.Category.GRAVEL.ordinal()]);
    }

    private static double lengthOf(List<LatLng> track) {
        double total = 0;
        for (int i = 1; i < track.size(); i++) {
            total += LatLng.distanceMeters(track.get(i - 1), track.get(i));
        }
        return total;
    }
}