package com.cinefile.core;

public record CostEstimate(int clips, double planUsd, double reviewUsd, double typicalUsd, double worstUsd) {
    public static CostEstimate of(CineFileConfig cfg) {
        int clips = AutoCreateJob.targetClips(cfg);
        if (cfg.apiKey.isBlank()) return new CostEstimate(clips, 0, 0, 0, 0);
        double plan = AiUsage.cost(AiClient.planEstIn(120), AiClient.planEstOut(Math.min(clips, 120)));
        double review = AiUsage.cost(AiClient.reviewEstIn(3), AiClient.REVIEW_EST_OUT);
        if (!cfg.visualReview) return new CostEstimate(clips, plan, review, plan, plan);
        int styles = Styles.resolve(cfg.styleId).size();
        int variants = styles * Styles.triesPerStyle(cfg.tries, styles) * cfg.focusLevel.candidates().size();
        double typical = plan + clips * review;
        double worst = plan + (AutoCreateJob.SLICE_TRIES * clips + AutoCreateJob.FILL_MAX) * variants * review;
        return new CostEstimate(clips, plan, review, typical, worst);
    }
}
