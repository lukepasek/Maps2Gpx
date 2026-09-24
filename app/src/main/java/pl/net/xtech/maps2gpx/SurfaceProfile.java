package pl.net.xtech.maps2gpx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Ordered surface intervals along a track, expressed as fractions of total route length. */
final class SurfaceProfile {

    enum Category {
        TARMAC(0xFF1565C0),
        PAVED(0xFF2E7D32),
        GRAVEL(0xFFF59E0B),
        DIRT(0xFFD84315),
        UNKNOWN(0xFF9E9E9E);

        final int color;

        Category(int color) {
            this.color = color;
        }
    }

    static final class Interval {
        final double startFraction;
        final double endFraction;
        final String surface;
        final Category category;

        Interval(double startFraction, double endFraction, String surface) {
            this.startFraction = startFraction;
            this.endFraction = endFraction;
            this.surface = surface;
            this.category = categoryOf(surface);
        }
    }

    final List<Interval> intervals;

    SurfaceProfile(List<Interval> intervals) {
        this.intervals = Collections.unmodifiableList(new ArrayList<>(intervals));
    }

    boolean isUnpavedAt(double fraction) {
        Category category = categoryAt(fraction);
        return category == Category.GRAVEL || category == Category.DIRT;
    }

    Category categoryAt(double fraction) {
        for (Interval interval : intervals) {
            if (fraction >= interval.startFraction && fraction <= interval.endFraction) {
                return interval.category;
            }
        }
        return Category.UNKNOWN;
    }

    double fraction(Category category) {
        double total = 0;
        for (Interval interval : intervals) {
            if (interval.category == category) {
                total += interval.endFraction - interval.startFraction;
            }
        }
        return Math.max(0, Math.min(1, total));
    }

    int[] percentages() {
        Category[] categories = Category.values();
        int[] percentages = new int[categories.length];
        double[] remainders = new double[categories.length];
        int assigned = 0;
        for (int i = 0; i < categories.length; i++) {
            double exact = fraction(categories[i]) * 100;
            percentages[i] = (int) Math.floor(exact);
            remainders[i] = exact - percentages[i];
            assigned += percentages[i];
        }
        while (assigned < 100) {
            int largest = 0;
            for (int i = 1; i < remainders.length; i++) {
                if (remainders[i] > remainders[largest]) {
                    largest = i;
                }
            }
            percentages[largest]++;
            remainders[largest] = -1;
            assigned++;
        }
        return percentages;
    }

    SurfaceProfile reversed() {
        List<Interval> reversed = new ArrayList<>(intervals.size());
        for (int i = intervals.size() - 1; i >= 0; i--) {
            Interval interval = intervals.get(i);
            reversed.add(new Interval(1 - interval.endFraction,
                    1 - interval.startFraction, interval.surface));
        }
        return new SurfaceProfile(reversed);
    }

    static Category categoryOf(String surface) {
        if (surface == null) {
            return Category.UNKNOWN;
        }
        String value = surface.toLowerCase(java.util.Locale.US);
        if ("paved_smooth".equals(value)) {
            return Category.TARMAC;
        }
        if ("paved".equals(value) || "paved_rough".equals(value)) {
            return Category.PAVED;
        }
        if ("gravel".equals(value) || "compacted".equals(value)) {
            return Category.GRAVEL;
        }
        if ("dirt".equals(value) || "path".equals(value)) {
            return Category.DIRT;
        }
        return Category.UNKNOWN;
    }
}