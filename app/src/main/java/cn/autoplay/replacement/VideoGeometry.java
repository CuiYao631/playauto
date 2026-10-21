package cn.autoplay.replacement;

final class VideoGeometry {
    private VideoGeometry() { }

    static int[] fit(int availableWidth, int availableHeight, int videoWidth, int videoHeight) {
        if (availableWidth <= 0 || availableHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            throw new IllegalArgumentException("Video and viewport dimensions must be positive");
        }
        double scale = Math.min(availableWidth / (double) videoWidth,
                availableHeight / (double) videoHeight);
        return new int[]{Math.max(1, (int) Math.round(videoWidth * scale)),
                Math.max(1, (int) Math.round(videoHeight * scale))};
    }
}
