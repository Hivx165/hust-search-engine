
package edu.hust.parser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class StopwordFilter {

    private final Set<String> stopwords = new HashSet<>();

    public StopwordFilter() throws IOException {

        InputStream stream = getClass()
            .getClassLoader()
            .getResourceAsStream("vietnamese_stopwords.txt");

        if (stream == null) {
            throw new IOException("Khong tim thay file stopwords!");
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                    stream, StandardCharsets.UTF_8))) {

            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim().toLowerCase(Locale.ROOT);

                if (!line.isEmpty()) {
                    stopwords.add(line);
                }
            }
        }
    }

    public String removeStopwords(String text) {

        if (text == null || text.isBlank()) {
            return "";
        }

        List<String> result = new ArrayList<>();

        for (String word : text.split("\\s+")) {
            if (!stopwords.contains(
                    word.toLowerCase(Locale.ROOT))) {
                result.add(word);
            }
        }

        return String.join(" ", result);
    }

    public static void main(String[] args)
            throws IOException {

        StopwordFilter filter = new StopwordFilter();

        String text = "Sinh_viên được nhận học_bổng "
                    + "và hỗ_trợ trong học_tập";

        System.out.println("TRUOC KHI LOC:");
        System.out.println(text);

        System.out.println("SAU KHI LOC:");
        System.out.println(filter.removeStopwords(text));
    }
}
