
package edu.hust.parser;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class TikaDocumentExtractor {

    public static String extractTextFromStream(
            InputStream stream) throws Exception {

        BodyContentHandler handler =
                new BodyContentHandler(-1);

        Metadata metadata = new Metadata();
        AutoDetectParser parser = new AutoDetectParser();
        ParseContext context = new ParseContext();

        parser.parse(stream, handler, metadata, context);

        return handler.toString().trim();
    }

    public static void main(String[] args)
            throws Exception {

        if (args.length == 0) {
            System.out.println("Chua cung cap file!");
            return;
        }

        try (InputStream stream =
                Files.newInputStream(Path.of(args[0]))) {

            String content =
                    extractTextFromStream(stream);

            System.out.println("NOI DUNG VAN BAN:");
            System.out.println(content);
        }
    }
}
