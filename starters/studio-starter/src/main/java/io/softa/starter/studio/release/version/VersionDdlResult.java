package io.softa.starter.studio.release.version;

/**
 * Structured DDL generation result.
 *
 * @param tableDdl table-related DDL
 * @param indexDdl index-related DDL
 */
public record VersionDdlResult(String tableDdl, String indexDdl) {

    public String combinedDdl() {
        return (tableDdl != null ? tableDdl : "") + (indexDdl != null ? indexDdl : "");
    }
}
