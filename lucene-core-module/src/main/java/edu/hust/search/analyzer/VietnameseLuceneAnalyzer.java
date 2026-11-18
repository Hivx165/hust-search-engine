package edu.hust.search.analyzer;

import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.standard.StandardTokenizer;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Custom Analyzer cho tiếng Việt, tích hợp StandardTokenizer, LowerCaseFilter
 * và StopFilter loại bỏ từ dừng tiếng Việt.
 */
public class VietnameseLuceneAnalyzer extends Analyzer {
    private final CharArraySet stopWords;

    public VietnameseLuceneAnalyzer() {
        this.stopWords = loadVietnameseStopWords();
    }

    public VietnameseLuceneAnalyzer(CharArraySet stopWords) {
        this.stopWords = stopWords != null ? stopWords : CharArraySet.EMPTY_SET;
    }

    private CharArraySet loadVietnameseStopWords() {
        Set<String> words = new HashSet<>();
        try (InputStream is = getClass().getResourceAsStream("/vietnamese_stopwords.txt")) {
            if (is != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (!line.isEmpty()) {
                            words.add(line);
                        }
                    }
                }
            } else {
                words.addAll(Set.of("và", "là", "của", "những", "các", "thì", "mà", "với", "trong", "cho", "được", "một", "ở", "từ", "đến"));
            }
        } catch (Exception e) {
            words.addAll(Set.of("và", "là", "của", "những", "các", "thì", "mà", "với", "trong", "cho", "được", "một", "ở", "từ", "đến"));
        }
        return new CharArraySet(words, true);
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        Tokenizer tokenizer = new StandardTokenizer();
        TokenStream filter = new LowerCaseFilter(tokenizer);
        filter = new StopFilter(filter, stopWords);
        return new TokenStreamComponents(tokenizer, filter);
    }
}
