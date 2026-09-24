package pl.net.xtech.maps2gpx;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/** Reverses GPX track and route geometry while preserving metadata and extension elements. */
final class GpxReverser {

    private GpxReverser() {
    }

    static byte[] reverse(byte[] source) throws IOException {
        if (containsDoctype(source)) {
            throw new IOException("DOCTYPE declarations are not supported in GPX files.");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document document = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(source));

            reverseChildren(document.getDocumentElement(), "wpt");
            reverseTracks(document);
            reverseRoutes(document);

            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            ByteArrayOutputStream output = new ByteArrayOutputStream(source.length + 256);
            transformer.transform(new DOMSource(document), new StreamResult(output));
            return output.toByteArray();
        } catch (Exception e) {
            throw new IOException("Could not reverse this GPX file: " + e.getMessage(), e);
        }
    }

    private static boolean containsDoctype(byte[] source) {
        byte[] marker = "<!DOCTYPE".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int matched = 0;
        for (byte value : source) {
            int current = value & 0xff;
            if (current == 0) {
                continue;
            }
            if (current == marker[matched]) {
                matched++;
                if (matched == marker.length) {
                    return true;
                }
            } else {
                matched = current == marker[0] ? 1 : 0;
            }
        }
        return false;
    }

    private static void reverseTracks(Document document) {
        NodeList tracks = document.getElementsByTagNameNS("*", "trk");
        for (int trackIndex = 0; trackIndex < tracks.getLength(); trackIndex++) {
            Element track = (Element) tracks.item(trackIndex);
            List<Element> segments = directChildren(track, "trkseg");
            List<Element> reversed = clonedReverse(segments);
            for (Element segment : reversed) {
                reverseChildren(segment, "trkpt");
            }
            replaceSlots(track, segments, reversed);
        }
    }

    private static void reverseRoutes(Document document) {
        NodeList routes = document.getElementsByTagNameNS("*", "rte");
        for (int routeIndex = 0; routeIndex < routes.getLength(); routeIndex++) {
            reverseChildren((Element) routes.item(routeIndex), "rtept");
        }
    }

    private static void reverseChildren(Element parent, String localName) {
        List<Element> children = directChildren(parent, localName);
        replaceSlots(parent, children, clonedReverse(children));
    }

    private static List<Element> clonedReverse(List<Element> elements) {
        List<Element> reversed = new ArrayList<>(elements.size());
        for (int i = elements.size() - 1; i >= 0; i--) {
            reversed.add((Element) elements.get(i).cloneNode(true));
        }
        return reversed;
    }

    private static void replaceSlots(Element parent, List<Element> slots,
                                     List<Element> replacements) {
        for (int i = 0; i < slots.size(); i++) {
            parent.replaceChild(replacements.get(i), slots.get(i));
        }
    }

    private static List<Element> directChildren(Element parent, String localName) {
        List<Element> found = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && localName.equals(child.getLocalName())) {
                found.add((Element) child);
            }
        }
        return found;
    }
}