package edu.hust.search.query;

import java.io.Serializable;
import java.util.Objects;

/**
 * Data Transfer Object đại diện cho 1 kết quả tìm kiếm được trả về từ LuceneSearchService.
 */
public class SearchResultDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private String url;
    private String title;
    private String snippet;
    private float score;
    private String subdomain;
    private long crawledDate;
    private String fileType;

    public SearchResultDTO() {
    }

    public SearchResultDTO(String id, String url, String title, String snippet, float score,
                           String subdomain, long crawledDate, String fileType) {
        this.id = id;
        this.url = url;
        this.title = title;
        this.snippet = snippet;
        this.score = score;
        this.subdomain = subdomain;
        this.crawledDate = crawledDate;
        this.fileType = fileType;
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

    public String getSnippet() {
        return snippet;
    }

    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }

    public float getScore() {
        return score;
    }

    public void setScore(float score) {
        this.score = score;
    }

    public String getSubdomain() {
        return subdomain;
    }

    public void setSubdomain(String subdomain) {
        this.subdomain = subdomain;
    }

    public long getCrawledDate() {
        return crawledDate;
    }

    public void setCrawledDate(long crawledDate) {
        this.crawledDate = crawledDate;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SearchResultDTO that = (SearchResultDTO) o;
        return Float.compare(that.score, score) == 0 &&
                crawledDate == that.crawledDate &&
                Objects.equals(id, that.id) &&
                Objects.equals(url, that.url) &&
                Objects.equals(title, that.title) &&
                Objects.equals(subdomain, that.subdomain);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, url, title, score, subdomain, crawledDate);
    }

    @Override
    public String toString() {
        return "SearchResultDTO{" +
                "id='" + id + '\'' +
                ", url='" + url + '\'' +
                ", title='" + title + '\'' +
                ", score=" + score +
                ", subdomain='" + subdomain + '\'' +
                ", fileType='" + fileType + '\'' +
                '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String url;
        private String title;
        private String snippet;
        private float score;
        private String subdomain;
        private long crawledDate;
        private String fileType;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder snippet(String snippet) {
            this.snippet = snippet;
            return this;
        }

        public Builder score(float score) {
            this.score = score;
            return this;
        }

        public Builder subdomain(String subdomain) {
            this.subdomain = subdomain;
            return this;
        }

        public Builder crawledDate(long crawledDate) {
            this.crawledDate = crawledDate;
            return this;
        }

        public Builder fileType(String fileType) {
            this.fileType = fileType;
            return this;
        }

        public SearchResultDTO build() {
            return new SearchResultDTO(id, url, title, snippet, score, subdomain, crawledDate, fileType);
        }
    }
}
