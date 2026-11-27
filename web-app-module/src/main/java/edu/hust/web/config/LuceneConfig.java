package edu.hust.web.config;

import edu.hust.search.query.LuceneSearchService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class LuceneConfig {

    @Bean
    public LuceneSearchService luceneSearchService(
            @Value("${lucene.index.path:storage/lucene_index}") String indexPath) throws IOException {
        Path path = Paths.get(indexPath);
        return new LuceneSearchService(path);
    }
}