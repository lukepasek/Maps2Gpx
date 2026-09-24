package pl.net.xtech.maps2gpx;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SavedRoutesActivityTest {

    @Test
    public void searchIgnoresInvisibleCharactersAndDiacritics() {
        String route = SavedRoutesActivity.searchKey(
                "Ludwika Kondratowicza to Skolimowska shortest");

        assertTrue(route.contains(SavedRoutesActivity.searchKey("kondra\u200btowicza")));
        assertTrue(route.contains(SavedRoutesActivity.searchKey("  kondratowicza ")));
        assertTrue(route.contains(SavedRoutesActivity.searchKey(
            "\u00a0kondratowicza\u00a0")));
        assertEquals("lodz", SavedRoutesActivity.searchKey("Łódź"));
    }
}