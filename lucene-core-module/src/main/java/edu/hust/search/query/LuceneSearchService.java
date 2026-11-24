package edu.hust.search.query;

import edu.hust.search.index.LuceneIndexManager;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.highlight.*;
import org.apache.lucene.queryparser.classic.ParseException;
import org.springframework.stereotype.Service;

import java.io.IOException;
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
        IndexSearcher searcher;
        try {
            searcher = indexManager.getSearcherManager().acquire();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to acquire a Lucene searcher.", e);
        }

        RuntimeException searchFailure = null;
        try {
            Query query = QueryBuilderHelper.buildAdvancedQuery(keyword, null, subdomain, indexManager.getAnalyzer());

            int totalHitsToFetch = page * size;
            TopDocs topDocs = searcher.search(query, Math.max(totalHitsToFetch, 1));

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

            return new PagedSearchResponse(topDocs.totalHits.value, results);
        } catch (IOException | ParseException | InvalidTokenOffsetsException e) {
            IllegalStateException failure =
                    new IllegalStateException("Lucene search failed for keyword: " + keyword, e);
            searchFailure = failure;
            throw failure;
        } catch (RuntimeException e) {
            searchFailure = e;
            throw e;
        } finally {
            try {
                indexManager.getSearcherManager().release(searcher);
            } catch (IOException e) {
                if (searchFailure != null) {
                    searchFailure.addSuppressed(e);
                } else {
                    throw new IllegalStateException("Unable to release the Lucene searcher.", e);
                }
            }
        }
    }
}