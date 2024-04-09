package com.yahoo.language.opennlp.wikidump;

import com.yahoo.language.Language;
import com.yahoo.language.opennlp.OpenNlpLinguistics;
import com.yahoo.language.process.*;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class MySaxHandler extends DefaultHandler {

    private long pageCount = 0;
    private final List<XMLSchemaPage> pages = new ArrayList<>();
    private final OpenNlpLinguistics openNlpLinguistics;
    private final Tokenizer tokenizer;

    private Language language;
    private XMLSchemaPage currentPage;

    private XMLSchemaRevision currentRevision;
    private final StringBuilder currentValue = new StringBuilder();

    private HashMap<String, Long> documentFrequency = new HashMap<>();


    public MySaxHandler() {
        this.openNlpLinguistics = new OpenNlpLinguistics();
        this.tokenizer = this.openNlpLinguistics.getTokenizer();
    }


    public List<XMLSchemaPage> getPages() {
        return new ArrayList<>(pages);
    }

    public Language getLanguage() {
        return this.language;
    }

    public long getPageCount() {
        return pageCount;
    }

    public List<XMLSchemaPage> getArticlePages() {
        return pages.stream().filter(p -> Objects.equals(p.getNamespace(), "0")).toList();
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
        currentValue.setLength(0);

        if (qName.equalsIgnoreCase("mediawiki")) {
            language = Language.fromLanguageTag(attributes.getValue("xml:lang"));
        }

        if (qName.equalsIgnoreCase("page")) {
            currentPage = new XMLSchemaPage(String.valueOf(pages.size()));
        }

        if (qName.equalsIgnoreCase("revision")) {
            currentRevision = new XMLSchemaRevision(currentPage.getId() + ":" + currentPage.getRevisions().size());
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {

        if (qName.equalsIgnoreCase("title")) {
            currentPage.setTitle(currentValue.toString());
        }

        if (qName.equalsIgnoreCase("restrictions")) {
            currentPage.setRestrictions(currentValue.toString());
        }

        if (qName.equalsIgnoreCase("ns")) {
            currentPage.setNamespace(currentValue.toString());
        }

        if (qName.equalsIgnoreCase("timestamp")) {
            currentRevision.setTimestamp(currentValue.toString());
        }

        if (qName.equalsIgnoreCase("contributor")) {
            currentRevision.setContributor(currentValue.toString());
        }

        if (qName.equalsIgnoreCase("comment")) {
            currentRevision.setComment(currentValue.toString());
        }

        if (qName.equalsIgnoreCase("text")) {
            currentRevision.setText(currentValue.toString());
        }

        if (qName.equalsIgnoreCase("revision")) {
            currentPage.addRevision(currentRevision);
        }

        if (qName.equalsIgnoreCase("page")) {
            //pages.add(currentPage);
            if (currentPage.getNamespace() == WikiNamespace.MAIN_ARTICLE) {

                pageCount++;
                handleTokenization();
                currentPage = null;
                currentRevision = null;
            }
        }
    }

    private void handleTokenization() {
        XMLSchemaRevision revision = currentPage.getRevisions().get(0); // get the most recent revision

        var tokens = tokenizer.tokenize(revision.getText(), language, StemMode.ALL, false);

        Set<String> uniqueWords = StreamSupport.stream(tokens.spliterator(), false)
                .filter(t -> t.getType() == TokenType.ALPHABETIC)
                .filter(t -> t.getScript() == TokenScript.LATIN)
                .map(Token::getTokenString)
                .collect(Collectors.toSet());

        //System.out.println("Unique words: " + uniqueWords);

        for (String word : uniqueWords) {
            if (documentFrequency.containsKey(word)) {
                documentFrequency.merge(word, 1L, Long::sum);
            } else {
                documentFrequency.put(word, 1L);
            }
        }
    }

    public HashMap<String, Long> getDocumentFrequency() {
        return documentFrequency;
    }

    public HashMap<String, Long> getFinalDocumentFrequency() {
        return documentFrequency.entrySet().stream()
                .filter(k -> k.getValue() > 1)
                .collect(HashMap::new, (m, v) -> m.put(v.getKey(), v.getValue()), HashMap::putAll);
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        currentValue.append(ch, start, length);

    }
}
