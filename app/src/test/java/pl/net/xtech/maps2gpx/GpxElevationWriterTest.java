package pl.net.xtech.maps2gpx;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GpxElevationWriterTest {

    @Test
    public void addsTrackElevationAndPreservesMetadataAndExtensions() throws Exception {
        String source = "<gpx xmlns=\"http://www.topografix.com/GPX/1/1\">"
                + "<metadata><name>Ride</name></metadata><trk><trkseg>"
                + "<trkpt lat=\"1\" lon=\"2\"><time>2026-01-01T00:00:00Z</time>"
                + "<extensions><x>keep</x></extensions></trkpt>"
                + "<trkpt lat=\"3\" lon=\"4\"/></trkseg></trk></gpx>";

        byte[] written = GpxElevationWriter.write(source.getBytes(StandardCharsets.UTF_8),
                Arrays.asList(new LatLng(1, 2, null, 10.25),
                        new LatLng(3, 4, null, 20.5)));
        String text = new String(written, StandardCharsets.UTF_8);
        NodeList elevations = parse(written).getElementsByTagNameNS("*", "ele");

        assertEquals(10.25, Double.parseDouble(elevations.item(0).getTextContent()), 0.001);
        assertEquals(20.5, Double.parseDouble(elevations.item(1).getTextContent()), 0.001);
        assertTrue(text.contains("<name>Ride</name>"));
        assertTrue(text.contains("<x>keep</x>"));
        assertTrue(text.indexOf("<ele>10.25</ele>") < text.indexOf("<time>"));
    }

    @Test
    public void addsElevationToNamespaceLessRoutePoints() throws Exception {
        String source = "<gpx><rte><rtept lat=\"1\" lon=\"2\"/>"
                + "<rtept lat=\"3\" lon=\"4\"/></rte></gpx>";

        byte[] written = GpxElevationWriter.write(source.getBytes(StandardCharsets.UTF_8),
                Arrays.asList(new LatLng(1, 2, null, 11.0),
                        new LatLng(3, 4, null, 22.0)));
        NodeList elevations = parse(written).getElementsByTagName("ele");

        assertEquals(11, Double.parseDouble(elevations.item(0).getTextContent()), 0.001);
        assertEquals(22, Double.parseDouble(elevations.item(1).getTextContent()), 0.001);
    }

    @Test
    public void rejectsDoctypeDeclarations() throws Exception {
        String source = "<!DOCTYPE gpx><gpx><rte><rtept lat=\"1\" lon=\"2\"/>"
                + "<rtept lat=\"3\" lon=\"4\"/></rte></gpx>";
        try {
            GpxElevationWriter.write(source.getBytes(StandardCharsets.UTF_8),
                    Arrays.asList(new LatLng(1, 2, null, 1.0),
                            new LatLng(3, 4, null, 2.0)));
            fail("Expected the DOCTYPE declaration to be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("DOCTYPE"));
        }
    }

    private static Document parse(byte[] source) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(source));
    }
}