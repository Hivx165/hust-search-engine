package edu.hust.search.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * POJO representing standard crawled document matching JSON Schema 5.1.
 * Handed over from parser-module (Member 2) to lucene-core-module (Member 3).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DocumentModel implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonProperty("id")
    private String id;

    @JsonProperty("url")
    private String url;

    @JsonProperty("title")
    private String title;

    @JsonProperty("subdomain")
    private String subdomain;

    @JsonProperty("content")
    private String content;

    @JsonProperty("file_type")
    private String fileType;

    @JsonProperty("crawled_at")
    private String crawledAt; // ISO-8601 format e.g. "2026-10-24T18:00:00Z"

    @JsonProperty("content_hash")
    private String contentHash;

    public DocumentModel() {
    }

    public DocumentModel(String id, String url, String title, String subdomain,
                         String content, String fileType, String crawledAt, String contentHash) {
        this.id = id;
        this.url = url;
        this.title = title;
        this.subdomain = subdomain;
        this.content = content;
        this.fileType = fileType;
        this.crawledAt = crawledAt;
        this.contentHash = contentHash;
    }

    /**
     * Converts crawled_at ISO-8601 timestamp string into epoch milliseconds.
     * Falls back to System.currentTimeMillis() if null or unparseable.
     */
    public long getCrawledEpochMillis() {
        if (crawledAt == null || crawledAt.isBlank()) {
            return System.currentTimeMillis();
        }
        try {
            return Instant.parse(crawledAt).toEpochMilli();
        } catch (DateTimeParseException e) {
            try {
                return Long.parseLong(crawledAt);
            } catch (NumberFormatException nfe) {
                return System.currentTimeMillis();
            }
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSubdomain() {
        return subdomain;
    }

    public void setSubdomain(String subdomain) {
        this.subdomain = subdomain;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public String getCrawledAt() {
        return crawledAt;
    }

    public void setCrawledAt(String crawledAt) {
        this.crawledAt = crawledAt;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DocumentModel that = (DocumentModel) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "DocumentModel{" +
                "id='" + id + '\'' +
                ", url='" + url + '\'' +
                ", title='" + title + '\'' +
                ", subdomain='" + subdomain + '\'' +
                ", fileType='" + fileType + '\'' +
                ", crawledAt='" + crawledAt + '\'' +
                ", contentHash='" + contentHash + '\'' +
                '}';
    }
}
