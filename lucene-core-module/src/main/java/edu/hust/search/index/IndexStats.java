package edu.hust.search.index;

/**
 * Snapshot of index metrics (numDocs, maxDoc, numDeletedDocs).
 */
public record IndexStats(int numDocs, int maxDoc, int numDeletedDocs) {

    public boolean hasDeletions() {
        return numDeletedDocs > 0;
    }

    @Override
    public String toString() {
        return "IndexStats{" +
                "numDocs=" + numDocs +
                ", maxDoc=" + maxDoc +
                ", numDeletedDocs=" + numDeletedDocs +
                '}';
    }
}
