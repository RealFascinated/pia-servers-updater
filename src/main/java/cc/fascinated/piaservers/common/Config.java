package cc.fascinated.piaservers.common;

public class Config {

    /** Cron expression for scheduled git commits (Quartz format: second minute hour day-of-month month day-of-week). Default: every hour. */
    public static final String COMMIT_CRON = "0 0 * * * ?";

    /**
     * Are we in production?
     *
     * @return If we are in production
     */
    public static boolean isProduction() {
        return System.getenv().containsKey("ENVIRONMENT") && System.getenv("ENVIRONMENT").equals("production");
    }
}
