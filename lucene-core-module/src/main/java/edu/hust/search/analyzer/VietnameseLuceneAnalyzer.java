package edu.hust.search.analyzer;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Custom Lucene Analyzer for Vietnamese IR in hust-search-engine.
 * Specifically designed for pre-segmented Vietnamese text where compound words
 * are connected with underscore '_' (e.g. "sinh_viên", "Bách_Khoa").
 *
 * Pipeline:
 * 1. WhitespaceTokenizer (preserves internal underscores)
 * 2. LowerCaseFilter (normalizes tokens to lower case)
 * 3. PunctuationStripFilter (removes leading/trailing punctuation while keeping internal '_')
 * 4. StopFilter (eliminates Vietnamese stop words)
 */
public class VietnameseLuceneAnalyzer extends Analyzer {

    private static final Logger log = LoggerFactory.getLogger(VietnameseLuceneAnalyzer.class);
    private static final String DEFAULT_STOPWORDS_FILE = "/vietnamese_stopwords.txt";

    private final CharArraySet stopWords;

    public VietnameseLuceneAnalyzer() {
        this(DEFAULT_STOPWORDS_FILE);
    }

    public VietnameseLuceneAnalyzer(String stopWordsResourcePath) {
        this.stopWords = loadVietnameseStopWords(stopWordsResourcePath);
    }

    public VietnameseLuceneAnalyzer(CharArraySet stopWords) {
        this.stopWords = stopWords != null ? stopWords : CharArraySet.EMPTY_SET;
    }

    private CharArraySet loadVietnameseStopWords(String resourcePath) {
        Set<String> words = new HashSet<>();
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim().toLowerCase();
                        if (!line.isEmpty() && !line.startsWith("#")) {
                            words.add(line);
                        }
                    }
                }
                log.debug("Loaded {} Vietnamese stop words from {}", words.size(), resourcePath);
            } else {
                log.warn("Stop words resource '{}' not found, using default fallback list.", resourcePath);
                words.addAll(getDefaultFallbackStopWords());
            }
        } catch (Exception e) {
            log.warn("Error loading stop words from '{}', using fallback list. Cause: {}", resourcePath, e.getMessage());
            words.addAll(getDefaultFallbackStopWords());
        }
        return new CharArraySet(words, true);
    }

    private static Set<String> getDefaultFallbackStopWords() {
        return Set.of(
                "và", "của", "là", "những", "các", "thì", "mà", "tại", "với", "cho",
                "được", "trong", "đã", "đang", "sẽ", "về", "từ", "do", "bởi", "nếu",
                "nhưng", "hoặc", "hay", "vì", "nên", "ra", "vào", "lên", "xuống", "một",
                "này", "đó", "kia", "đây", "rồi", "cũng", "chỉ", "rất", "quá", "nhiều",
                "ít", "như", "vậy", "theo", "qua", "sau", "trước", "khi", "lúc", "nơi"
        );
    }

    public CharArraySet getStopWords() {
        return stopWords;
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        // Step 1: WhitespaceTokenizer preserves tokens including internal '_'
        Tokenizer tokenizer = new WhitespaceTokenizer();

        // Step 2: Lowercase filter
        TokenStream stream = new LowerCaseFilter(tokenizer);

        // Step 3: Strip leading & trailing punctuation while strictly keeping internal '_'
        stream = new PunctuationStripFilter(stream);

        // Step 4: Remove Vietnamese stop words
        stream = new StopFilter(stream, stopWords);

        return new TokenStreamComponents(tokenizer, stream);
    }

    /**
     * TokenFilter that removes leading and trailing punctuation from tokens,
     * but preserves characters that are letters, digits, or underscore '_'.
     * Tokens composed entirely of punctuation are dropped.
     */
    public static final class PunctuationStripFilter extends TokenFilter {

        private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);

        public PunctuationStripFilter(TokenStream input) {
            super(input);
        }

        @Override
        public boolean incrementToken() throws IOException {
            while (input.incrementToken()) {
                char[] buffer = termAtt.buffer();
                int length = termAtt.length();
                if (length == 0) {
                    continue;
                }

                int start = 0;
                while (start < length && isPunctuation(buffer[start])) {
                    start++;
                }

                int end = length;
                while (end > start && isPunctuation(buffer[end - 1])) {
                    end--;
                }

                int newLength = end - start;
                if (newLength <= 0) {
                    // Entire token was punctuation (e.g. ".", ",", "---")
                    continue;
                }

                if (start > 0 || end < length) {
                    termAtt.copyBuffer(buffer, start, newLength);
                }
                return true;
            }
            return false;
        }

        private static boolean isPunctuation(char c) {
            if (c == '_' || Character.isLetterOrDigit(c)) {
                return false;
            }
            return true;
        }
    }
}
