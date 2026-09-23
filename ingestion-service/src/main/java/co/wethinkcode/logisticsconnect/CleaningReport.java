package co.wethinkcode.logisticsconnect;

import java.util.List;

/**
 * What came out of one pass over the CSV: how many rows were read, the header columns the model
 * doesn't use, the rows that could not be used and why, and the hubs.
 *
 * @param ignoredColumns header columns other than hub_id, province, sorting_center and active.
 *                       Reported rather than silently dropped, so a new column in a future export
 *                       is noticed.
 */
public record CleaningReport(
        int rowsRead,
        List<String> ignoredColumns,
        List<RejectedRow> rejected,
        List<CleanHub> hubs) {

    /**
     * A data row that was skipped, and why.
     *
     * @param line the row's line in the file, counting from the header as line 1
     */
    public record RejectedRow(long line, String reason, String raw) {
    }
}
