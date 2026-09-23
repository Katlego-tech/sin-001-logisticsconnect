package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class IngestionServiceApp {

    static final int PORT = 7050;
    private static final String SOURCE = "/hubs-global.csv";
    private static final Logger log = LoggerFactory.getLogger(IngestionServiceApp.class);

    public static void main(String[] args) throws IOException {
        create(cleanBundledCsv()).start(PORT);
    }

    /** The export is bundled and never changes while running, so it is cleaned once, at startup. */
    static CleaningReport cleanBundledCsv() throws IOException {
        try (InputStream in = IngestionServiceApp.class.getResourceAsStream(SOURCE)) {
            if (in == null) {
                throw new IllegalStateException(SOURCE + " is not on the classpath");
            }
            CleaningReport report = new HubCsvCleaner().clean(new InputStreamReader(in, StandardCharsets.UTF_8));
            log.info("Cleaned {}: {} rows -> {} hubs, {} rejected",
                    SOURCE, report.rowsRead(), report.hubs().size(), report.rejected().size());
            if (!report.ignoredColumns().isEmpty()) {
                log.warn("{} has columns this service does not use: {}", SOURCE, report.ignoredColumns());
            }
            return report;
        }
    }

    static Javalin create(CleaningReport report) {
        Javalin app = Javalin.create();

        app.get("/health", ctx -> ctx.result("OK"));

        // The cleaned records hub-service loads as its place-name data.
        app.get("/hubs", ctx -> ctx.json(report.hubs()));

        // How the records were derived: rows read, columns ignored, and every rejected row and why.
        app.get("/report", ctx -> ctx.json(report));

        return app;
    }
}
