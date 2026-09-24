package pl.net.xtech.maps2gpx;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SurfaceProfileTest {

    @Test
    public void classifiesAndSummarizesValhallaSurfaceValues() throws Exception {
        SurfaceProfile profile = SurfaceAnalyzer.fromEdges(
            Arrays.asList("paved_smooth", "paved", "paved_rough", "compacted",
                "gravel", "dirt", "path", "impassable"),
            Arrays.asList(2.0, 1.0, 1.0, 0.5, 0.5, 0.25, 0.25, 0.5));

        assertEquals(1.0 / 3.0, profile.fraction(SurfaceProfile.Category.TARMAC), 0.001);
        assertEquals(1.0 / 3.0, profile.fraction(SurfaceProfile.Category.PAVED), 0.001);
        assertEquals(1.0 / 6.0, profile.fraction(SurfaceProfile.Category.GRAVEL), 0.001);
        assertEquals(1.0 / 12.0, profile.fraction(SurfaceProfile.Category.DIRT), 0.001);
        assertEquals(1.0 / 12.0, profile.fraction(SurfaceProfile.Category.UNKNOWN), 0.001);
        assertEquals(100, Arrays.stream(profile.percentages()).sum());
        assertFalse(profile.isUnpavedAt(0.2));
        assertTrue(profile.isUnpavedAt(0.7));
    }

    @Test
    public void reversedProfileMirrorsSurfaceIntervals() throws Exception {
        SurfaceProfile profile = SurfaceAnalyzer.fromEdges(
            Arrays.asList("paved", "compacted"),
            Arrays.asList(3.0, 1.0)).reversed();

        assertTrue(profile.isUnpavedAt(0.1));
        assertFalse(profile.isUnpavedAt(0.9));
    }
}