// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.language.opennlp;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.yahoo.component.annotation.Inject;
import com.yahoo.language.Language;
import com.yahoo.language.Linguistics;
import com.yahoo.language.detect.Detector;
import com.yahoo.language.opennlp.wikidump.MySaxHandler;
import com.yahoo.language.process.StemMode;
import com.yahoo.language.process.Tokenizer;
import com.yahoo.language.simple.SimpleLinguistics;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class OpenNlpLinguistics extends SimpleLinguistics {

    private final Detector detector;

    @Inject
    public OpenNlpLinguistics() {
        this.detector = new OpenNlpDetector();
    }

    @Override
    public Tokenizer getTokenizer() {
        return new OpenNlpTokenizer(getNormalizer(), getTransformer());
    }

    @Override
    public Detector getDetector() { return detector; }

    @Override
    public boolean equals(Linguistics other) { return (other instanceof OpenNlpLinguistics); }

    static class SignificanceModel {
        String version;
        String id;
        String description;
        long corpus_size;
        String language;
        HashMap<String, Long> frequencies;

        int token_count;

        SignificanceModel(String version, String id, String description, long corpus_size, String language, HashMap<String, Long> frequencies) {
            this.version = version;
            this.id = id;
            this.description = description;
            this.corpus_size = corpus_size;
            this.language = language;
            this.frequencies = frequencies;
            this.token_count = frequencies.size();
        }
    }

    public MySaxHandler loadWiki(String modelName) {
        try {
            SAXParserFactory parserFactory = SAXParserFactory.newInstance();
            parserFactory.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", false);
            SAXParser parser = parserFactory.newSAXParser();

            MySaxHandler handler = new MySaxHandler();

            try (var inputStream = OpenNlpLinguistics.class.getClassLoader().getResourceAsStream("models/" + modelName )) {
                parser.parse(inputStream, handler);
            }
            return handler;

        } catch (SAXException | IOException e) {
            e.printStackTrace();
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        }

        return null;
    }


    public static void main(String[] args)
    {
        var openNLPLinguistics = new OpenNlpLinguistics();
        //var tokenizer = openNLPLinguistics.getTokenizer();

        //var string = "कार्यकक्षमा";
        //var tokens = tokenizer.tokenize(string, Language.ENGLISH, StemMode.NONE, false);
        //tokens.forEach(token -> System.out.println(token.getTokenString()));



        //MySaxHandler handler = openNLPLinguistics.loadWiki("testmodel.xml-p1p41242");

        MySaxHandler handler = openNLPLinguistics.loadWiki("nowiki-20240301-pages-articles.xml");
        //MySaxHandler handler = openNLPLinguistics.loadWiki("enwiki-20240301-pages-articles.xml");

        var corpusSize        = handler.getPageCount();
        var documentFrequency = handler.getFinalDocumentFrequency();
        System.out.println("Corpus size: " + corpusSize);
        System.out.println("Document frequency: " + documentFrequency);
        //System.out.println("Final document frequency: " + finalDocumentFrequency);

        //MySaxHandler handler = openNLPLinguistics.loadWiki("testmodel.xml-p1p41242");

//        List<XMLSchemaPage> documents = handler.getArticlePages();
//
//        if (documents == null) {
//            throw new NullPointerException("Failed to load XML pages");
//        }
//
//        System.out.println("Number of article documents: " + documents.size());
//        var tokenizer = openNLPLinguistics.getTokenizer();
//
//
//        HashMap<String, Integer> document_frequency = new HashMap<>();
//        for (XMLSchemaPage document : documents) {
//            XMLSchemaRevision revision = document.getRevisions().get(0); // we are only interested in the most recent revision
//
//            Set<String> unique_words = new HashSet<>();
//            var tokens = tokenizer.tokenize(revision.getText(), handler.getLanguage(), StemMode.ALL, false);
//            for (var token : tokens) {
//                if (token.getType() == TokenType.SPACE || token.getType() != TokenType.ALPHABETIC) {
//                    continue;
//                }
//                unique_words.add(token.getTokenString());
//            }
//
//            for (var word : unique_words) {
//                if (document_frequency.containsKey(word)) {
//                    document_frequency.merge(word, 1, Integer::sum);
//                } else {
//                    document_frequency.put(word, 1);
//                }
//            }
//        }
//

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        try {

            var df  = new SignificanceModel("1.0", "test::1", "desc",
                corpusSize, handler.getLanguage().languageCode(), documentFrequency);

            Path currentPath = Paths.get("opennlp-linguistics");
            Path resourcesPath = currentPath.resolve("src")
                    .resolve("main")
                    .resolve("resources")
                    .resolve("models");
            Path outputPath = resourcesPath.resolve(handler.getLanguage().languageCode() + ".json");

            Files.createDirectories(outputPath.getParent());

            if (!Files.exists(outputPath)) {
                Files.createFile(outputPath);
            }

            ObjectWriter writer = objectMapper.writer(new DefaultPrettyPrinter());
            writer.writeValue(outputPath.toFile(), df);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
