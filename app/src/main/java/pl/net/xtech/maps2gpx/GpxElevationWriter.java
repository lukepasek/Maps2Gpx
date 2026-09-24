package pl.net.xtech.maps2gpx;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/** Adds resolved elevations to GPX geometry while preserving the original document structure. */
final class GpxElevationWriter {

    private GpxElevationWriter() {
    }

    static byte[] write(byte[] source, List<LatLng> elevatedTrack) throws IOException {
        if (containsDoctype(source)) {
            throw new IOException("DOCTYPE declarations are not supported in GPX files.");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document document = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(source));

            NodeList trackPoints = document.getElementsByTagNameNS("*", "trkpt");
            NodeList geometry = trackPoints.getLength() > 0 ? trackPoints
                    : document.getElementsByTagNameNS("*", "rtept");
            int elevatedIndex = 0;
            for (int i = 0; i < geometry.getLength() && elevatedIndex < elevatedTrack.size(); i++) {
                Element pointElement = (Element) geometry.item(i);
                if (!hasValidCoordinates(pointElement)) {
                    continue;
                }
                LatLng point = elevatedTrack.get(elevatedIndex++);
                if (point.ele != null && !Double.isNaN(point.ele)) {
                    setElevation(document, pointElement, point.ele);
                }
            }
            if (elevatedIndex != elevatedTrack.size()) {
                throw new IOException("GPX geometry changed while adding elevation data.");
            }

            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            ByteArrayOutputStream output = new ByteArrayOutputStream(source.length + 1024);
            transformer.transform(new DOMSource(document), new StreamResult(output));
            return output.toByteArray();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not add elevation to this GPX file: "
                    + e.getMessage(), e);
        }
    }

    private static boolean hasValidCoordinates(Element point) {
        try {
            return new LatLng(Double.parseDouble(point.getAttribute("lat")),
                    Double.parseDouble(point.getAttribute("lon"))).isValid();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static void setElevation(Document document, Element point, double elevation) {
        Element elevationElement = directChild(point, "ele");
        if (elevationElement == null) {
            String namespace = point.getNamespaceURI();
            String prefix = point.getPrefix();
            elevationElement = namespace == null
                    ? document.createElement("ele")
                    : document.createElementNS(namespace,
                            prefix == null ? "ele" : prefix + ":ele");
            Node before = firstElementChild(point);
            point.insertBefore(elevationElement, before);
        }
        elevationElement.setTextContent(String.format(Locale.US, "%.2f", elevation));
    }

    private static Element directChild(Element parent, String localName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && localName.equals(child.getLocalName())) {
                return (Element) child;
            }
        }
        return null;
    }

    private static Node firstElementChild(Element parent) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                return child;
            }
        }
        return null;
    }

    private static boolean containsDoctype(byte[] source) {
        String text = new String(source, StandardCharsets.ISO_8859_1).toUpperCase(Locale.US);
        return text.contains("<!DOCTYPE");
    }
}