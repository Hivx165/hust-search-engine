package edu.hust.web.config;

import edu.hust.search.index.LuceneIndexManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class LuceneConfig {

    @Bean(destroyMethod = "close")
    public LuceneIndexManager luceneIndexManager() throws IOException {
        return new LuceneIndexManager();
    }
}