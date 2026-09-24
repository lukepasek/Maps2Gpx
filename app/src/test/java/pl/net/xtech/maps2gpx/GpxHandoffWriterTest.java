package pl.net.xtech.maps2gpx;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.w3c.dom.Document;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilderFactory;

public class GpxHandoffWriterTest {

    @Test
    public void removesTaggedSourceTrackForExternalApps() throws Exception {
        byte[] source = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<gpx xmlns=\"http://www.topografix.com/GPX/1/1\""
                + " xmlns:m2g=\"" + GpxWriter.EXT_NS + "\">"
                + "<trk><name>Rerouted</name><trkseg>"
                + "<trkpt lat=\"52.1\" lon=\"21.0\"/><trkpt lat=\"52.2\" lon=\"21.1\"/>"
                + "</trkseg></trk>"
                + "<trk><name>Original track</name><extensions>"
                + "<m2g:role>source</m2g:role></extensions><trkseg>"
                + "<trkpt lat=\"52.0\" lon=\"20.9\"/><trkpt lat=\"52.3\" lon=\"21.2\"/>"
                + "</trkseg></trk>"
                + "<extensions><m2g:surfaceProfile version=\"1\">"
                + "<m2g:section from=\"0\" to=\"1\" surface=\"paved\"/>"
                + "</m2g:surfaceProfile></extensions></gpx>")
                .getBytes(StandardCharsets.UTF_8);

        Document written = parse(GpxHandoffWriter.write(source));

        assertEquals(1, written.getElementsByTagNameNS("*", "trk").getLength());
        assertEquals("Rerouted", written.getElementsByTagNameNS("*", "name")
                .item(0).getTextContent());
        assertEquals(1, written.getElementsByTagNameNS(
                GpxWriter.EXT_NS, "surfaceProfile").getLength());
    }

    @Test
    public void leavesSingleTrackFileBytesUnchanged() throws Exception {
        byte[] source = ("<gpx xmlns=\"http://www.topografix.com/GPX/1/1\">"
                + "<trk><trkseg><trkpt lat=\"52.1\" lon=\"21.0\"/>"
                + "<trkpt lat=\"52.2\" lon=\"21.1\"/></trkseg></trk></gpx>")
                .getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(source, GpxHandoffWriter.write(source));
    }

    private static Document parse(byte[] gpx) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(gpx));
    }
}