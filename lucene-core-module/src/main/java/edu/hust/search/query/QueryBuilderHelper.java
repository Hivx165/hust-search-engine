package edu.hust.search.query;

import edu.hust.search.analyzer.VietnameseLuceneAnalyzer;
import edu.hust.search.index.DocumentSchema;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Lớp tiện ích hỗ trợ xây dựng các câu truy vấn Apache Lucene (BooleanQuery, TermQuery, BoostQuery, Filter).
 * Chịu trách nhiệm triển khai logic MUST, SHOULD và Field Boosting (Title > Content).
 */
public class QueryBuilderHelper {

    public static final float DEFAULT_TITLE_BOOST = 2.0f;
    public static final float DEFAULT_CONTENT_BOOST = 1.0f;

    private final Analyzer analyzer;

    public QueryBuilderHelper() {
        this(new VietnameseLuceneAnalyzer());
    }

    public QueryBuilderHelper(Analyzer analyzer) {
        this.analyzer = analyzer != null ? analyzer : new VietnameseLuceneAnalyzer();
    }

    /**
     * Tách một chuỗi văn bản thành danh sách các token qua Analyzer.
     */
    public List<String> analyzeTokens(String text, String fieldName) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }

        try (TokenStream tokenStream = analyzer.tokenStream(fieldName, new StringReader(text))) {
            CharTermAttribute termAtt = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                tokens.add(termAtt.toString());
            }
            tokenStream.end();
        } catch (IOException e) {
            // Fallback: tách khoảng trắng thông thường
            for (String part : text.toLowerCase().split("\\s+")) {
                if (!part.isBlank()) {
                    tokens.add(part);
                }
            }
        }
        return tokens;
    }

    /**
     * Xây dựng BooleanQuery kết hợp điều kiện MUST (bắt buộc) và SHOULD (tăng điểm xếp hạng).
     *
     * @param mustKeyword   Từ khóa bắt buộc có
     * @param shouldKeyword Từ khóa khuyến khích có (được boost điểm)
     * @param subdomain     Subdomain cần lọc (tùy chọn, có thể null hoặc rỗng)
     * @return BooleanQuery hoàn chỉnh
     */
    public Query buildAdvancedQuery(String mustKeyword, String shouldKeyword, String subdomain) {
        return buildAdvancedQuery(mustKeyword, shouldKeyword, subdomain, DEFAULT_TITLE_BOOST, DEFAULT_CONTENT_BOOST);
    }

    public Query buildAdvancedQuery(String mustKeyword, String shouldKeyword, String subdomain,
                                    float titleBoost, float contentBoost) {
        BooleanQuery.Builder mainBuilder = new BooleanQuery.Builder();

        // 1. MUST clause (Bắt buộc phải khớp trong Title HOẶC Content)
        if (mustKeyword != null && !mustKeyword.isBlank()) {
            List<String> mustTokens = analyzeTokens(mustKeyword, DocumentSchema.FIELD_CONTENT);
            if (!mustTokens.isEmpty()) {
                BooleanQuery.Builder mustClauseBuilder = new BooleanQuery.Builder();
                for (String token : mustTokens) {
                    BooleanQuery.Builder tokenOrBuilder = new BooleanQuery.Builder();
                    Query titleQuery = new BoostQuery(new TermQuery(new Term(DocumentSchema.FIELD_TITLE, token)), titleBoost);
                    Query contentQuery = new BoostQuery(new TermQuery(new Term(DocumentSchema.FIELD_CONTENT, token)), contentBoost);
                    tokenOrBuilder.add(titleQuery, BooleanClause.Occur.SHOULD);
                    tokenOrBuilder.add(contentQuery, BooleanClause.Occur.SHOULD);
                    mustClauseBuilder.add(tokenOrBuilder.build(), BooleanClause.Occur.MUST);
                }
                mainBuilder.add(mustClauseBuilder.build(), BooleanClause.Occur.MUST);
            }
        }

        // 2. SHOULD clause (Nếu có sẽ cộng điểm, ưu tiên Title)
        if (shouldKeyword != null && !shouldKeyword.isBlank()) {
            List<String> shouldTokens = analyzeTokens(shouldKeyword, DocumentSchema.FIELD_CONTENT);
            for (String token : shouldTokens) {
                Query shouldTitle = new BoostQuery(new TermQuery(new Term(DocumentSchema.FIELD_TITLE, token)), titleBoost);
                Query shouldContent = new BoostQuery(new TermQuery(new Term(DocumentSchema.FIELD_CONTENT, token)), contentBoost);
                mainBuilder.add(shouldTitle, BooleanClause.Occur.SHOULD);
                mainBuilder.add(shouldContent, BooleanClause.Occur.SHOULD);
            }
        }

        // 3. Subdomain Filter (nếu có chỉ định)
        applySubdomainFilter(mainBuilder, subdomain);

        return mainBuilder.build();
    }

    /**
     * Xây dựng câu truy vấn thông minh từ chuỗi tìm kiếm thô của người dùng.
     * Mặc định các từ khóa được kết hợp dạng SHOULD hoặc MUST tùy theo cờ matchAll.
     * Áp dụng Field Boosting: Title được nhân điểm theo titleBoost.
     */
    public Query buildSearchQuery(String rawQuery, String subdomain, boolean matchAllTerms) {
        return buildSearchQuery(rawQuery, subdomain, matchAllTerms, DEFAULT_TITLE_BOOST, DEFAULT_CONTENT_BOOST);
    }

    public Query buildSearchQuery(String rawQuery, String subdomain, boolean matchAllTerms,
                                  float titleBoost, float contentBoost) {
        if (rawQuery == null || rawQuery.isBlank()) {
            if (subdomain != null && !subdomain.isBlank()) {
                BooleanQuery.Builder builder = new BooleanQuery.Builder();
                applySubdomainFilter(builder, subdomain);
                builder.add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);
                return builder.build();
            }
            return new MatchAllDocsQuery();
        }

        List<String> tokens = analyzeTokens(rawQuery, DocumentSchema.FIELD_CONTENT);
        if (tokens.isEmpty()) {
            return new MatchAllDocsQuery();
        }

        BooleanQuery.Builder mainBuilder = new BooleanQuery.Builder();
        BooleanClause.Occur tokenOccur = matchAllTerms ? BooleanClause.Occur.MUST : BooleanClause.Occur.SHOULD;

        for (String token : tokens) {
            BooleanQuery.Builder tokenBuilder = new BooleanQuery.Builder();

            // Khớp title (được boost)
            Query titleTermQuery = new BoostQuery(
                    new TermQuery(new Term(DocumentSchema.FIELD_TITLE, token)),
                    titleBoost
            );
            // Khớp content
            Query contentTermQuery = new BoostQuery(
                    new TermQuery(new Term(DocumentSchema.FIELD_CONTENT, token)),
                    contentBoost
            );

            tokenBuilder.add(titleTermQuery, BooleanClause.Occur.SHOULD);
            tokenBuilder.add(contentTermQuery, BooleanClause.Occur.SHOULD);

            mainBuilder.add(tokenBuilder.build(), tokenOccur);
        }

        // Áp dụng bộ lọc subdomain nếu có
        applySubdomainFilter(mainBuilder, subdomain);

        return mainBuilder.build();
    }

    /**
     * Tạo PhraseQuery tìm kiếm chính xác nguyên cụm từ với trọng số Boost.
     */
    public Query buildPhraseQuery(String phrase, float titleBoost, float contentBoost) {
        List<String> tokens = analyzeTokens(phrase, DocumentSchema.FIELD_CONTENT);
        if (tokens.isEmpty()) {
            return new MatchAllDocsQuery();
        }
        if (tokens.size() == 1) {
            return buildSearchQuery(tokens.get(0), null, true, titleBoost, contentBoost);
        }

        PhraseQuery.Builder titlePhrase = new PhraseQuery.Builder();
        PhraseQuery.Builder contentPhrase = new PhraseQuery.Builder();

        for (String token : tokens) {
            titlePhrase.add(new Term(DocumentSchema.FIELD_TITLE, token));
            contentPhrase.add(new Term(DocumentSchema.FIELD_CONTENT, token));
        }

        BooleanQuery.Builder bq = new BooleanQuery.Builder();
        bq.add(new BoostQuery(titlePhrase.build(), titleBoost), BooleanClause.Occur.SHOULD);
        bq.add(new BoostQuery(contentPhrase.build(), contentBoost), BooleanClause.Occur.SHOULD);

        return bq.build();
    }

    private void applySubdomainFilter(BooleanQuery.Builder builder, String subdomain) {
        if (subdomain != null && !subdomain.isBlank()) {
            String cleanSubdomain = subdomain.trim().toLowerCase();
            Query filterQuery = new TermQuery(new Term(DocumentSchema.FIELD_SUBDOMAIN, cleanSubdomain));
            builder.add(filterQuery, BooleanClause.Occur.FILTER);
        }
    }
}
