package com.cinefile.core;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** One-line end-of-run text for the overlay. */
public final class Summary {
    private Summary() {}

    public static String text(AutoCreateJob.Result r, double usd, String aiError) {
        if (r.error() != null) return r.error();
        StringBuilder s = new StringBuilder(r.cancelled() ? "Cancelled. " : "");
        s.append("Placed ").append(r.placed()).append('/').append(r.target()).append(" clips.");
        if (!r.rejected().isEmpty()) {
            s.append(" Rejected: ").append(r.rejected().entrySet().stream()
                .sorted(Map.Entry.<Verdict, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", "))).append('.');
        }
        if (usd > 0) s.append(String.format(Locale.ROOT, " AI cost ~$%.2f.", usd));
        if (aiError != null && !aiError.isBlank()) s.append(" AI: ").append(aiError);
        return s.toString();
    }
}
