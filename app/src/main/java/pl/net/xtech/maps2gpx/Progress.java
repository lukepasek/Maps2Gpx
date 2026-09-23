package pl.net.xtech.maps2gpx;

/** Progress sink so the conversion pipeline can narrate itself to the UI. */
public interface Progress {
    void step(String message);
}
