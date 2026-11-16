package com.proyector.sender;

final class AdaptiveVideo {
    private static final int[] ADAPTIVE_FPS = {15, 24, 36, 60};
    private int level = 1;
    private int slow;
    private int good;
    private int maxResolution;
    private int maxFps;
    private boolean automatic;

    void configure(int maxResolution, int maxFps, boolean automatic) {
        this.maxResolution = maxResolution;
        this.maxFps = maxFps;
        this.automatic = automatic;
        level = Math.min(level, maxResolution);
    }

    void feedback(long roundTripMs, long processingMs) {
        if (!automatic) return;
        int budget = 1000 / fps();
        if (roundTripMs > Math.max(95, budget * 3) || processingMs > budget * 1.25) {
            good = 0;
            if (++slow >= 4 && level > 0) {
                level--;
                slow = 0;
            }
        } else {
            slow = 0;
            if (roundTripMs < Math.max(70, budget * 2) && processingMs <= budget && ++good >= 50) {
                if (level < maxResolution) level++;
                good = 0;
            }
        }
    }

    int resolution() { return automatic ? level : maxResolution; }

    int fps() { return automatic ? Math.min(maxFps, ADAPTIVE_FPS[level]) : maxFps; }
}
