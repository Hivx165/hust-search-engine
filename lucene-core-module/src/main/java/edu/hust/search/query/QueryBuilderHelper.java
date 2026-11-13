package edu.hust.search.query;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.*;

import java.util.HashMap;
import java.util.Map;

public class QueryBuilderHelper {

    /**
     * Xây dựng câu truy vấn nâng cao kết hợp MUST, SHOULD và FILTER (Subdomain)
     * 
     * @param mustKeyword   Từ khóa bắt buộc phải có (AND)
     * @param shouldKeyword Từ khóa khuyến khích có, ưu tiên cộng điểm (OR)
     * @param subdomain     Lọc theo tên miền phụ
     * @param analyzer      Bộ phân tích từ (Analyzer)
     */
    public static Query buildAdvancedQuery(String mustKeyword, String shouldKeyword, String subdomain,
            Analyzer analyzer) throws ParseException {
        BooleanQuery.Builder booleanQuery = new BooleanQuery.Builder();

        // Điều kiện MUST: Bắt buộc phải chứa từ khóa chính
        if (mustKeyword != null && !mustKeyword.trim().isEmpty()) {
            String[] fields = { "title", "content" };
            MultiFieldQueryParser parser = new MultiFieldQueryParser(fields, analyzer);
            Query mustQuery = parser.parse(mustKeyword);

            booleanQuery.add(mustQuery, BooleanClause.Occur.MUST);
        }

        // Điều kiện SHOULD: Xử lý an toàn qua Analyzer và tự động boost điểm
        if (shouldKeyword != null && !shouldKeyword.trim().isEmpty()) {
            String[] shouldFields = { "title", "content" };

            Map<String, Float> boosts = new HashMap<>();
            boosts.put("title", 2.0f);
            boosts.put("content", 1.0f);

            MultiFieldQueryParser shouldParser = new MultiFieldQueryParser(shouldFields, analyzer, boosts);
            Query shouldQuery = shouldParser.parse(shouldKeyword);

            booleanQuery.add(shouldQuery, BooleanClause.Occur.SHOULD);
        }

        if (subdomain != null && !subdomain.trim().isEmpty()) {
            Query domainQuery = new TermQuery(new Term("subdomain", subdomain.trim()));
            booleanQuery.add(domainQuery, BooleanClause.Occur.FILTER);
        }

        return booleanQuery.build();
    }
}