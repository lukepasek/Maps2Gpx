package pl.net.xtech.maps2gpx;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GpxReverserTest {

    @Test
    public void reversesTrackPointsAndPreservesExtensions() throws Exception {
        String source = "<?xml version=\"1.0\"?><gpx xmlns=\"http://www.topografix.com/GPX/1/1\">"
                + "<metadata><name>Ride</name></metadata><trk><extensions><x>keep</x></extensions>"
                + "<trkseg><trkpt lat=\"1\" lon=\"2\"><ele>10</ele></trkpt>"
                + "<trkpt lat=\"3\" lon=\"4\"><ele>20</ele></trkpt>"
                + "<extensions><segment>keep-last</segment></extensions></trkseg></trk></gpx>";

        String reversed = new String(GpxReverser.reverse(
                source.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);

        assertTrue(reversed.indexOf("lat=\"3\"") < reversed.indexOf("lat=\"1\""));
        assertTrue(reversed.indexOf("lat=\"1\"") < reversed.indexOf("keep-last"));
        assertTrue(reversed.contains("<x>keep</x>"));
        assertTrue(reversed.contains("<name>Ride</name>"));
    }

    @Test
    public void reversesSegmentOrderAndRoutePoints() throws Exception {
        String source = "<gpx xmlns=\"http://www.topografix.com/GPX/1/1\"><trk>"
                + "<trkseg><trkpt lat=\"1\" lon=\"0\"/></trkseg>"
                + "<trkseg><trkpt lat=\"2\" lon=\"0\"/></trkseg></trk>"
                + "<rte><rtept lat=\"3\" lon=\"0\"/><rtept lat=\"4\" lon=\"0\"/></rte>"
                + "</gpx>";

        String reversed = new String(GpxReverser.reverse(
                source.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);

        assertTrue(reversed.indexOf("lat=\"2\"") < reversed.indexOf("lat=\"1\""));
        assertTrue(reversed.indexOf("lat=\"4\"") < reversed.indexOf("lat=\"3\""));
    }

        @Test
        public void reversesTrackPointsWithoutNamespace() throws Exception {
                String source = "<gpx><trk><trkseg><trkpt lat=\"1\" lon=\"0\"/>"
                                + "<trkpt lat=\"2\" lon=\"0\"/></trkseg></trk></gpx>";

                String reversed = new String(GpxReverser.reverse(
                                source.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);

                assertTrue(reversed.indexOf("lat=\"2\"") < reversed.indexOf("lat=\"1\""));
        }

        @Test
        public void rejectsDoctypeDeclarations() throws Exception {
                String source = "<!DOCTYPE gpx [<!ENTITY point \"1\">]><gpx><trk><trkseg>"
                                + "<trkpt lat=\"&point;\" lon=\"0\"/><trkpt lat=\"2\" lon=\"0\"/>"
                                + "</trkseg></trk></gpx>";

                try {
                        GpxReverser.reverse(source.getBytes(StandardCharsets.UTF_8));
                        fail("Expected the DOCTYPE declaration to be rejected");
                } catch (java.io.IOException expected) {
                        assertTrue(expected.getMessage().contains("DOCTYPE"));
                }
        }

        @Test
        public void reversesEndpointWaypointsAndCanReverseAgain() throws Exception {
                String source = "<gpx xmlns=\"http://www.topografix.com/GPX/1/1\">"
                                + "<wpt lat=\"1\" lon=\"0\"><name>Start label</name></wpt>"
                                + "<wpt lat=\"2\" lon=\"0\"><name>End label</name></wpt>"
                                + "<trk><trkseg><trkpt lat=\"1\" lon=\"0\"/>"
                                + "<trkpt lat=\"2\" lon=\"0\"/></trkseg></trk></gpx>";

                byte[] reversed = GpxReverser.reverse(source.getBytes(StandardCharsets.UTF_8));
                String reversedText = new String(reversed, StandardCharsets.UTF_8);
                assertTrue(reversedText.indexOf("End label") < reversedText.indexOf("Start label"));

                String restored = new String(GpxReverser.reverse(reversed), StandardCharsets.UTF_8);
                assertTrue(restored.indexOf("Start label") < restored.indexOf("End label"));
                int track = restored.indexOf("<trk>");
                assertTrue(restored.indexOf("lat=\"1\"", track)
                        < restored.indexOf("lat=\"2\"", track));
        }
}