package dev.nagsgk.fleetops.model;

/**
 * Latest known state of a robot plus the online detector's running statistics, stored together so
 * the stateless processor can resume the detector exactly where the previous invocation left it.
 */
public record RobotState(
        Heartbeat last,
        DetectorStats temp,
        DetectorStats current,
        long movingStillSinceMillis,
        long stateSinceMillis) {

    public static RobotState first(Heartbeat hb) {
        return new RobotState(hb, DetectorStats.EMPTY, DetectorStats.EMPTY, 0L, hb.tsMillis());
    }

    /**
     * Exponentially weighted mean/variance (incremental form) plus a one-sided CUSUM statistic of the
     * standardised residual, which accumulates small persistent shifts that a plain z-score misses.
     */
    public record DetectorStats(long n, double mean, double var, double cusum) {
        public static final DetectorStats EMPTY = new DetectorStats(0, 0, 0, 0);

        /** EWMA update; early on uses the running average (alpha = 1/n) so the baseline converges fast. */
        public DetectorStats learn(double x, double alpha) {
            if (n == 0) return new DetectorStats(1, x, 0, 0);
            alpha = Math.max(alpha, 1.0 / (n + 1));
            double diff = x - mean;
            double incr = alpha * diff;
            return new DetectorStats(n + 1, mean + incr, (1 - alpha) * (var + diff * incr), cusum);
        }

        public double z(double x, double sdFloor) {
            return (x - mean) / Math.max(Math.sqrt(Math.max(var, 0)), sdFloor);
        }

        public DetectorStats withCusum(double c) {
            return new DetectorStats(n, mean, var, c);
        }
    }
}
