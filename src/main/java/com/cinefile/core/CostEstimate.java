package com.cinefile.core;

import java.util.List;

public record CostEstimate(int clips, double planUsd, double reviewUsd, double typicalUsd, double worstUsd) {
    public static CostEstimate of(CineFileConfig cfg) {
        int clips = AutoCreateJob.targetClips(cfg);
        if (cfg.apiKey.isBlank()) return new CostEstimate(clips, 0, 0, 0, 0);
        double plan = AiUsage.cost(AiClient.planEstIn(120), AiClient.planEstOut(Math.min(clips, 120)));
        double review = AiUsage.cost(AiClient.reviewEstIn(3), AiClient.REVIEW_EST_OUT);
        if (!cfg.visualReview) return new CostEstimate(clips, plan, review, plan, plan);
        List<Style> styles = Styles.resolve(cfg.styleId);
        int tries = Styles.triesPerStyle(cfg.tries, styles.size()), levels = cfg.focusLevel.candidates().size();
        int variants = styles.stream().mapToInt(s -> tries * Styles.clipsPerTry(s, levels)).sum();
        double typical = plan + clips * review;
        double worst = plan + (AutoCreateJob.SLICE_TRIES * clips + AutoCreateJob.FILL_MAX) * variants * review;
        return new CostEstimate(clips, plan, review, typical, worst);
    }
}
