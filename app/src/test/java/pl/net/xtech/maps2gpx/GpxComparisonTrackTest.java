package pl.net.xtech.maps2gpx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import javax.xml.parsers.DocumentBuilderFactory;

public class GpxComparisonTrackTest {

    @Test
    public void reroutedFileKeepsPrimaryAndSourceTracksSeparate() throws Exception {
        Route route = route(new LatLng(52.10, 21.00), new LatLng(52.20, 21.10));
        Route source = route(new LatLng(52.11, 21.01, null, 100.0),
                new LatLng(52.19, 21.09, null, 110.0));

        String gpx = GpxWriter.write("Test", Collections.emptyList(), route, null,
                new Date(0), "OSRM", null, "Re-routed from GPX", source);
        Document document = parse(gpx);
        NodeList tracks = document.getElementsByTagNameNS("*", "trk");
        Element primary = (Element) tracks.item(0);
        Element comparison = (Element) tracks.item(1);

        assertEquals(2, tracks.getLength());
        assertEquals(52.10, coordinate(firstPoint(primary), "lat"), 0.000001);
        assertEquals(52.11, coordinate(firstPoint(comparison), "lat"), 0.000001);
        assertEquals("100.00", comparison.getElementsByTagNameNS("*", "ele")
                .item(0).getTextContent());
        assertTrue(gpx.contains("<m2g:role>source</m2g:role>"));
    }

    @Test
    public void regularConversionHasNoSourceTrack() throws Exception {
        Route route = route(new LatLng(52.10, 21.00), new LatLng(52.20, 21.10));

        String gpx = GpxWriter.write("Test", Collections.emptyList(), route, null,
                new Date(0), "OSRM", null, "Converted from Maps link", null);

        assertEquals(1, parse(gpx).getElementsByTagNameNS("*", "trk").getLength());
    }

    private static Route route(LatLng... points) {
        return new Route(Arrays.asList(points), 1000, 600, "cycling", "Bike");
    }

        private static Document parse(String gpx) throws Exception {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                return factory.newDocumentBuilder().parse(new ByteArrayInputStream(
                                gpx.getBytes(StandardCharsets.UTF_8)));
        }

        private static Element firstPoint(Element track) {
                return (Element) track.getElementsByTagNameNS("*", "trkpt").item(0);
        }

        private static double coordinate(Element point, String attribute) {
                return Double.parseDouble(point.getAttribute(attribute));
        }
}