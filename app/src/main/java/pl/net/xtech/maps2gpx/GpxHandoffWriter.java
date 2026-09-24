package pl.net.xtech.maps2gpx;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/** Produces a single-track GPX copy for apps that reject comparison tracks. */
final class GpxHandoffWriter {

    private GpxHandoffWriter() {
    }

    static byte[] write(byte[] source) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document document = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(source));
            Element root = document.getDocumentElement();
            boolean changed = false;
            for (Node child = root.getFirstChild(); child != null; ) {
                Node next = child.getNextSibling();
                if (child.getNodeType() == Node.ELEMENT_NODE
                        && "trk".equals(child.getLocalName())
                        && isSourceTrack((Element) child)) {
                    root.removeChild(child);
                    changed = true;
                }
                child = next;
            }
            if (!changed) {
                return source;
            }

            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            ByteArrayOutputStream output = new ByteArrayOutputStream(source.length);
            transformer.transform(new DOMSource(document), new StreamResult(output));
            return output.toByteArray();
        } catch (Exception e) {
            throw new IOException("Could not prepare a compatible GPX file: "
                    + e.getMessage(), e);
        }
    }

    private static boolean isSourceTrack(Element track) {
        for (Node child = track.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() != Node.ELEMENT_NODE
                    || !"extensions".equals(child.getLocalName())) {
                continue;
            }
            for (Node extension = child.getFirstChild(); extension != null;
                 extension = extension.getNextSibling()) {
                if (extension.getNodeType() == Node.ELEMENT_NODE
                        && "role".equals(extension.getLocalName())
                        && GpxWriter.EXT_NS.equals(extension.getNamespaceURI())
                        && "source".equals(extension.getTextContent().trim())) {
                    return true;
                }
            }
        }
        return false;
    }
}