package edu.hust.search.query;

import edu.hust.search.index.DocumentSchema;
import org.apache.lucene.search.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryBuilderHelperTest {

    private QueryBuilderHelper helper;

    @BeforeEach
    void setUp() {
        helper = new QueryBuilderHelper();
    }

    @Test
    @DisplayName("Kiểm tra tách tokens qua Analyzer và loại bỏ stop words tiếng Việt")
    void testAnalyzeTokens() {
        String text = "Học bổng khuyến khích và hỗ trợ sinh viên";
        List<String> tokens = helper.analyzeTokens(text, DocumentSchema.FIELD_CONTENT);

        assertThat(tokens).isNotEmpty();
        // "và" là stopword trong vietnamese_stopwords.txt nên phải bị loại bỏ
        assertThat(tokens).doesNotContain("và");
        assertThat(tokens).contains("học", "bổng", "sinh", "viên");
    }

    @Test
    @DisplayName("Kiểm tra tạo BooleanQuery cơ bản với matchAll=true (MUST)")
    void testBuildSearchQueryMatchAll() {
        Query query = helper.buildSearchQuery("học bổng", null, true);

        assertThat(query).isInstanceOf(BooleanQuery.class);
        BooleanQuery bq = (BooleanQuery) query;

        // Có 2 token ("học", "bổng"), mỗi token phải có Occur.MUST
        assertThat(bq.clauses()).isNotEmpty();
        for (BooleanClause clause : bq.clauses()) {
            assertThat(clause.getOccur()).isEqualTo(BooleanClause.Occur.MUST);
        }
    }

    @Test
    @DisplayName("Kiểm tra tạo BooleanQuery với matchAll=false (SHOULD)")
    void testBuildSearchQueryMatchAny() {
        Query query = helper.buildSearchQuery("học bổng", null, false);

        assertThat(query).isInstanceOf(BooleanQuery.class);
        BooleanQuery bq = (BooleanQuery) query;

        for (BooleanClause clause : bq.clauses()) {
            assertThat(clause.getOccur()).isEqualTo(BooleanClause.Occur.SHOULD);
        }
    }

    @Test
    @DisplayName("Kiểm tra bộ lọc subdomain (FILTER clause)")
    void testSubdomainFilter() {
        Query query = helper.buildSearchQuery("đồ án", "soict.hust.edu.vn", false);

        assertThat(query).isInstanceOf(BooleanQuery.class);
        BooleanQuery bq = (BooleanQuery) query;

        boolean hasFilter = bq.clauses().stream()
                .anyMatch(c -> c.getOccur() == BooleanClause.Occur.FILTER &&
                        c.getQuery().toString().contains("soict.hust.edu.vn"));
        assertThat(hasFilter).isTrue();
    }

    @Test
    @DisplayName("Kiểm tra Advanced Query: MUST keyword và SHOULD keyword với Title Boost")
    void testBuildAdvancedQuery() {
        Query query = helper.buildAdvancedQuery("tốt nghiệp", "soict", null);

        assertThat(query).isInstanceOf(BooleanQuery.class);
        BooleanQuery bq = (BooleanQuery) query;

        // Phải có ít nhất 1 clause MUST và 1 clause SHOULD
        boolean hasMust = bq.clauses().stream().anyMatch(c -> c.getOccur() == BooleanClause.Occur.MUST);
        boolean hasShould = bq.clauses().stream().anyMatch(c -> c.getOccur() == BooleanClause.Occur.SHOULD);

        assertThat(hasMust).isTrue();
        assertThat(hasShould).isTrue();

        // Kiểm tra trọng số Boost cho Title (phải chứa BoostQuery với boost = 2.0)
        String queryString = query.toString();
        assertThat(queryString).contains("title:");
        assertThat(queryString).contains("content:");
    }

    @Test
    @DisplayName("Kiểm tra trường hợp query rỗng")
    void testEmptyQuery() {
        Query query = helper.buildSearchQuery("", null, false);
        assertThat(query).isInstanceOf(MatchAllDocsQuery.class);
    }
}
