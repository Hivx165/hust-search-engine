package edu.hust.search.query;

import edu.hust.search.index.LuceneIndexManager;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.highlight.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class LuceneSearchService {

    private final LuceneIndexManager indexManager;

    public LuceneSearchService(LuceneIndexManager indexManager) {
        this.indexManager = indexManager;
    }

    public PagedSearchResponse doSearch(String keyword, String subdomain, int page, int size) {
        List<SearchResultDTO> results = new ArrayList<>();
        IndexSearcher searcher = null;
        long totalHits = 0; // Biến lưu tổng số lượng

        try {
            searcher = indexManager.getSearcherManager().acquire();
            Query query = QueryBuilderHelper.buildAdvancedQuery(keyword, null, subdomain, indexManager.getAnalyzer());

            int totalHitsToFetch = page * size;
            TopDocs topDocs = searcher.search(query, Math.max(totalHitsToFetch, 1));

            // Lấy TỔNG SỐ LƯỢNG THỰC TẾ khớp với truy vấn trong toàn bộ cơ sở dữ liệu
            totalHits = topDocs.totalHits.value;

            QueryScorer scorer = new QueryScorer(query);
            Highlighter highlighter = new Highlighter(new SimpleHTMLFormatter("<mark>", "</mark>"), scorer);
            highlighter.setTextFragmenter(new SimpleFragmenter(160));

            int startIndex = (page - 1) * size;
            int endIndex = Math.min(startIndex + size, topDocs.scoreDocs.length);

            for (int i = startIndex; i < endIndex; i++) {
                ScoreDoc scoreDoc = topDocs.scoreDocs[i];
                Document doc = searcher.storedFields().document(scoreDoc.doc);

                String title = doc.get("title");
                String url = doc.get("url");
                String content = doc.get("content");
                String snippet = highlighter.getBestFragment(indexManager.getAnalyzer(), "content", content);

                if (snippet == null && content != null) {
                    snippet = content.length() > 160 ? content.substring(0, 160) + "..." : content;
                }

                results.add(new SearchResultDTO(title, url, snippet, scoreDoc.score));
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (searcher != null) {
                try {
                    indexManager.getSearcherManager().release(searcher);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        // Trả về cả tổng số lượng (totalHits) và danh sách cắt trang (results)
        return new PagedSearchResponse(totalHits, results);
    }
}