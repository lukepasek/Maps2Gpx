package pl.net.xtech.maps2gpx;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Sunrise and sunset, computed locally with the NOAA "sunrise equation".
 *
 * <p>Deliberately offline: with BRouter selected the whole conversion runs without a
 * network, and it would be daft to require one just to print two times.
 *
 * <p>Accuracy is around two minutes - checked against sunrise-sunset.org for
 * 52.4324,21.0308 on 2026-08-17, which gives 03:20:22/17:59:34 UTC against this
 * implementation's 03:22:41/17:59:56. The simplified formula omits nutation, parallax and
 * the higher-order equation-of-time terms, which is far more precision than a route summary
 * needs.
 */
final class SolarTimes {

    /** Standard sunrise altitude: the sun's upper limb at the horizon, incl. refraction. */
    private static final double SUN_ALTITUDE_DEG = -0.833;
    private static final double EARTH_OBLIQUITY_DEG = 23.4397;

    private SolarTimes() {
    }

    /**
     * @return {sunriseMillis, sunsetMillis} in UTC epoch millis, or null when the sun does
     *         not rise or set at all that day (polar day or night).
     */
    static long[] sunriseSunset(double lat, double lon, long whenMillis) {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.setTimeInMillis(whenMillis);
        long jdn = julianDayNumber(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH) + 1,
                utc.get(Calendar.DAY_OF_MONTH));

        double days = jdn - 2451545 + 0.0008;
        double meanSolarTime = days - lon / 360.0;

        double meanAnomaly = mod360(357.5291 + 0.98560028 * meanSolarTime);
        double centre = 1.9148 * sinDeg(meanAnomaly)
                + 0.02 * sinDeg(2 * meanAnomaly)
                + 0.0003 * sinDeg(3 * meanAnomaly);
        double eclipticLongitude = mod360(meanAnomaly + centre + 180 + 102.9372);

        double transit = 2451545.0 + meanSolarTime
                + 0.0053 * sinDeg(meanAnomaly)
                - 0.0069 * sinDeg(2 * eclipticLongitude);

        double sinDeclination = sinDeg(eclipticLongitude) * sinDeg(EARTH_OBLIQUITY_DEG);
        double cosDeclination = Math.cos(Math.asin(sinDeclination));

        double cosHourAngle = (sinDeg(SUN_ALTITUDE_DEG) - sinDeg(lat) * sinDeclination)
                / (cosDeg(lat) * cosDeclination);
        if (cosHourAngle < -1 || cosHourAngle > 1) {
            return null;
        }
        double hourAngle = Math.toDegrees(Math.acos(cosHourAngle));

        return new long[]{
                julianToMillis(transit - hourAngle / 360.0),
                julianToMillis(transit + hourAngle / 360.0)
        };
    }

    private static long julianDayNumber(int year, int month, int day) {
        int a = (14 - month) / 12;
        long y = year + 4800L - a;
        long m = month + 12L * a - 3;
        return day + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045;
    }

    private static long julianToMillis(double julianDate) {
        return Math.round((julianDate - 2440587.5) * 86400000.0);
    }

    private static double mod360(double degrees) {
        double wrapped = degrees % 360;
        return wrapped < 0 ? wrapped + 360 : wrapped;
    }

    private static double sinDeg(double degrees) {
        return Math.sin(Math.toRadians(degrees));
    }

    private static double cosDeg(double degrees) {
        return Math.cos(Math.toRadians(degrees));
    }
}
