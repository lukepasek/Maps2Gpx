package pl.net.xtech.maps2gpx;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/** Adds a compact Maps2Gpx surface profile while preserving the rest of the GPX document. */
final class GpxSurfaceWriter {
    private static final String PROFILE = "surfaceProfile";

    private GpxSurfaceWriter() {
    }

    static byte[] write(byte[] source, SurfaceProfile surfaceProfile) throws IOException {
        if (surfaceProfile == null || surfaceProfile.intervals.isEmpty()) {
            throw new IOException("There is no surface profile to save.");
        }
        if (containsDoctype(source)) {
            throw new IOException("DOCTYPE declarations are not supported in GPX files.");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document document = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(source));
            Element root = document.getDocumentElement();
            Element extensions = directChild(root, "extensions");
            if (extensions == null) {
                extensions = createGpxElement(document, root, "extensions");
                root.appendChild(extensions);
            }
            removeExistingProfile(extensions);

            Element profile = document.createElementNS(GpxWriter.EXT_NS,
                    GpxWriter.EXT_PREFIX + ":" + PROFILE);
            profile.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI,
                    "xmlns:" + GpxWriter.EXT_PREFIX, GpxWriter.EXT_NS);
            profile.setAttribute("version", "1");
            profile.setAttribute("source", "valhalla-openstreetmap");
            for (SurfaceProfile.Interval interval : surfaceProfile.intervals) {
                Element section = document.createElementNS(GpxWriter.EXT_NS,
                        GpxWriter.EXT_PREFIX + ":section");
                section.setAttribute("from", fraction(interval.startFraction));
                section.setAttribute("to", fraction(interval.endFraction));
                section.setAttribute("surface", interval.surface);
                profile.appendChild(section);
            }
            extensions.appendChild(profile);

            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            ByteArrayOutputStream output = new ByteArrayOutputStream(source.length + 2048);
            transformer.transform(new DOMSource(document), new StreamResult(output));
            return output.toByteArray();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not add surface data to this GPX file: "
                    + e.getMessage(), e);
        }
    }

    private static Element createGpxElement(Document document, Element root, String localName) {
        String namespace = root.getNamespaceURI();
        String prefix = root.getPrefix();
        return namespace == null
                ? document.createElement(localName)
                : document.createElementNS(namespace,
                        prefix == null ? localName : prefix + ":" + localName);
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

    private static void removeExistingProfile(Element extensions) {
        for (Node child = extensions.getFirstChild(); child != null; ) {
            Node next = child.getNextSibling();
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && PROFILE.equals(child.getLocalName())
                    && GpxWriter.EXT_NS.equals(child.getNamespaceURI())) {
                extensions.removeChild(child);
            }
            child = next;
        }
    }

    private static String fraction(double value) {
        return String.format(Locale.US, "%.8f", value);
    }

    private static boolean containsDoctype(byte[] source) {
        String text = new String(source, StandardCharsets.ISO_8859_1).toUpperCase(Locale.US);
        return text.contains("<!DOCTYPE");
    }
}