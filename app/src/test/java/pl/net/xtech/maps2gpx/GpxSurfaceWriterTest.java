package pl.net.xtech.maps2gpx;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GpxSurfaceWriterTest {

    @Test
    public void addsSurfaceProfileAndPreservesExistingExtensions() throws Exception {
        String source = "<gpx xmlns=\"http://www.topografix.com/GPX/1/1\" "
                + "xmlns:x=\"https://example.com/x\"><metadata><name>Ride</name></metadata>"
                + "<trk><trkseg><trkpt lat=\"1\" lon=\"2\"/>"
                + "<trkpt lat=\"3\" lon=\"4\"/></trkseg></trk>"
                + "<extensions><x:keep>yes</x:keep></extensions></gpx>";
        SurfaceProfile profile = SurfaceAnalyzer.fromEdges(
                Arrays.asList("paved_smooth", "gravel", "dirt"),
                Arrays.asList(2.0, 1.0, 1.0));

        Document document = parse(GpxSurfaceWriter.write(
                source.getBytes(StandardCharsets.UTF_8), profile));
        NodeList sections = document.getElementsByTagNameNS(GpxWriter.EXT_NS, "section");

        assertEquals(3, sections.getLength());
        assertEquals("0.50000000", ((Element) sections.item(0)).getAttribute("to"));
        assertEquals("gravel", ((Element) sections.item(1)).getAttribute("surface"));
        assertEquals("yes", document.getElementsByTagNameNS(
                "https://example.com/x", "keep").item(0).getTextContent());
        assertEquals("Ride", document.getElementsByTagNameNS(
                "http://www.topografix.com/GPX/1/1", "name").item(0).getTextContent());
    }

    @Test
    public void replacesAnExistingMaps2GpxSurfaceProfile() throws Exception {
        String source = "<gpx xmlns=\"http://www.topografix.com/GPX/1/1\" "
                + "xmlns:m2g=\"" + GpxWriter.EXT_NS + "\"><trk><trkseg>"
                + "<trkpt lat=\"1\" lon=\"2\"/><trkpt lat=\"3\" lon=\"4\"/>"
                + "</trkseg></trk><extensions><m2g:surfaceProfile version=\"1\">"
                + "<m2g:section from=\"0\" to=\"1\" surface=\"dirt\"/>"
                + "</m2g:surfaceProfile></extensions></gpx>";
        SurfaceProfile replacement = SurfaceAnalyzer.fromEdges(
                Arrays.asList("paved", "gravel"), Arrays.asList(3.0, 1.0));

        Document document = parse(GpxSurfaceWriter.write(
                source.getBytes(StandardCharsets.UTF_8), replacement));

        assertEquals(1, document.getElementsByTagNameNS(
                GpxWriter.EXT_NS, "surfaceProfile").getLength());
        assertEquals(2, document.getElementsByTagNameNS(
                GpxWriter.EXT_NS, "section").getLength());
    }

    @Test
    public void rejectsDoctypeDeclarations() throws Exception {
        String source = "<!DOCTYPE gpx><gpx><trk><trkseg>"
                + "<trkpt lat=\"1\" lon=\"2\"/><trkpt lat=\"3\" lon=\"4\"/>"
                + "</trkseg></trk></gpx>";
        SurfaceProfile profile = SurfaceAnalyzer.fromEdges(
                Arrays.asList("paved"), Arrays.asList(1.0));
        try {
            GpxSurfaceWriter.write(source.getBytes(StandardCharsets.UTF_8), profile);
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