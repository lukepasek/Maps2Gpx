package pl.net.xtech.maps2gpx;

import java.util.ArrayList;
import java.util.List;

/**
 * Decoder for Google's encoded polyline algorithm, which OSRM also uses.
 * Precision 5 is the classic format, 6 is what we ask OSRM for.
 */
final class PolylineCodec {

    private PolylineCodec() {
    }

    static List<LatLng> decode(String encoded, int precision) {
        double factor = Math.pow(10, precision);
        List<LatLng> points = new ArrayList<>();
        int index = 0;
        int lat = 0;
        int lon = 0;

        while (index < encoded.length()) {
            int result = 1;
            int shift = 0;
            int b;
            do {
                b = encoded.charAt(index++) - 63 - 1;
                result += b << shift;
                shift += 5;
            } while (b >= 0x1f && index < encoded.length());
            lat += (result & 1) != 0 ? ~(result >> 1) : (result >> 1);

            result = 1;
            shift = 0;
            do {
                b = encoded.charAt(index++) - 63 - 1;
                result += b << shift;
                shift += 5;
            } while (b >= 0x1f && index < encoded.length());
            lon += (result & 1) != 0 ? ~(result >> 1) : (result >> 1);

            points.add(new LatLng(lat / factor, lon / factor));
        }
        return points;
    }
}
