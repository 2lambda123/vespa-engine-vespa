// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.document.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yahoo.document.ArrayDataType;
import com.yahoo.document.DataType;
import com.yahoo.document.Document;
import com.yahoo.document.DocumentId;
import com.yahoo.document.DocumentOperation;
import com.yahoo.document.DocumentPut;
import com.yahoo.document.DocumentRemove;
import com.yahoo.document.DocumentType;
import com.yahoo.document.DocumentTypeManager;
import com.yahoo.document.Field;
import com.yahoo.document.MapDataType;
import com.yahoo.document.PositionDataType;
import com.yahoo.document.StructDataType;
import com.yahoo.document.WeightedSetDataType;
import com.yahoo.document.datatypes.TensorFieldValue;
import com.yahoo.text.Utf8;
import org.apache.commons.codec.binary.Base64;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * Functional tests for com.yahoo.document.json.JsonWriter.
 *
 * @author <a href="mailto:steinar@yahoo-inc.com">Steinar Knutsen</a>
 */
public class JsonWriterTestCase {

    private static final JsonFactory parserFactory = new JsonFactory();
    private DocumentTypeManager types;

    @Before
    public void setUp() throws Exception {
        types = new DocumentTypeManager();
        {
            DocumentType x = new DocumentType("smoke");
            x.addField(new Field("something", DataType.STRING));
            x.addField(new Field("nalle", DataType.STRING));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("mirrors");
            StructDataType woo = new StructDataType("woo");
            woo.addField(new Field("sandra", DataType.STRING));
            woo.addField(new Field("cloud", DataType.STRING));
            x.addField(new Field("skuggsjaa", woo));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testarray");
            DataType d = new ArrayDataType(DataType.STRING);
            x.addField(new Field("actualarray", d));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testset");
            DataType d = new WeightedSetDataType(DataType.STRING, true, true);
            x.addField(new Field("actualset", d));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testmap");
            DataType d = new MapDataType(DataType.STRING, DataType.STRING);
            x.addField(new Field("actualmap", d));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testraw");
            DataType d = DataType.RAW;
            x.addField(new Field("actualraw", d));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testpredicate");
            DataType d = DataType.PREDICATE;
            x.addField(new Field("actualpredicate", d));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testMapStringToArrayOfInt");
            DataType value = new ArrayDataType(DataType.INT);
            DataType d = new MapDataType(DataType.STRING, value);
            x.addField(new Field("actualMapStringToArrayOfInt", d));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testsinglepos");
            DataType d = PositionDataType.INSTANCE;
            x.addField(new Field("singlepos", d));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testmultipos");
            DataType d = new ArrayDataType(PositionDataType.INSTANCE);
            x.addField(new Field("multipos", d));
            types.registerDocumentType(x);
        }
        {
            DocumentType x = new DocumentType("testtensor");
            x.addField(new Field("tensorfield", DataType.TENSOR));
            types.registerDocumentType(x);
        }
    }

    @After
    public void tearDown() throws Exception {
        types = null;
    }

    @Test
    public final void smokeTest() throws JsonParseException,
            JsonMappingException, IOException {
        roundTripEquality("id:unittest:smoke::whee", "{"
                + " \"something\": \"smoketest\"," + " \"nalle\": \"bamse\""
                + "}");
    }

    @Test
    public final void hideEmptyStringsTest() throws JsonParseException,
            JsonMappingException, IOException {
        final String fields = "{"
                        + " \"something\": \"\"," + " \"nalle\": \"bamse\""
                        + "}";
        final String filteredFields = "{"
                + " \"nalle\": \"bamse\""
                + "}";

        Document doc = readDocumentFromJson("id:unittest:smoke::whee", fields);
        assertEqualJson(asDocument("id:unittest:smoke::whee", filteredFields), JsonWriter.toByteArray(doc));
    }

    private void roundTripEquality(final String docId, final String fields)
            throws JsonParseException, JsonMappingException, IOException {
        Document doc = readDocumentFromJson(docId, fields);
        assertEqualJson(asDocument(docId, fields), JsonWriter.toByteArray(doc));
    }

    @Test
    public final void structTest() throws JsonParseException,
            JsonMappingException, IOException {
        roundTripEquality("id:unittest:mirrors::whee", "{ "
                + "\"skuggsjaa\": {" + "\"sandra\": \"person\","
                + " \"cloud\": \"another person\"}}");
    }

    @Test
    public final void singlePosTest() throws JsonParseException,
            JsonMappingException, IOException {
        roundTripEquality("id:unittest:testsinglepos::bamf", "{ \"singlepos\": \"N60.222333;E10.12\" }");
    }

    @Test
    public final void multiPosTest() throws JsonParseException,
            JsonMappingException, IOException {
        roundTripEquality("id:unittest:testmultipos::bamf", "{ \"multipos\": [ \"N0.0;E0.0\", \"S1.1;W1.1\", \"N10.2;W122.2\" ] }");
    }

    @Test
    public final void arrayTest() throws JsonParseException,
            JsonMappingException, IOException {
        roundTripEquality("id:unittest:testarray::whee", "{ \"actualarray\": ["
                + " \"nalle\"," + " \"tralle\"]}");
    }

    @Test
    public final void weightedSetTest() throws JsonParseException,
            JsonMappingException, IOException {
        roundTripEquality("id:unittest:testset::whee", "{ \"actualset\": {"
                + " \"nalle\": 2," + " \"tralle\": 7 }}");
    }

    @Test
    public final void mapTest() throws JsonParseException,
            JsonMappingException, IOException {
        final String fields = "{ \"actualmap\": ["
                        + " { \"key\": \"nalle\", \"value\": \"kalle\"},"
                        + " { \"key\": \"tralle\", \"value\": \"skalle\"} ]}";
        final String docId = "id:unittest:testmap::whee";
        Document doc = readDocumentFromJson(docId, fields);
        // we have to do everything by hand to check, as maps are unordered, but
        // are serialized as an ordered structure

        ObjectMapper m = new ObjectMapper();
        Map<?, ?> generated = m.readValue(JsonWriter.toByteArray(doc), Map.class);
        assertEquals(docId, generated.get("id"));
        // and from here on down there will be lots of unchecked casting and
        // other fun. This is OK here, because if the casts fail, the should and
        // will fail anyway
        List<?> inputMap = (List<?>) m.readValue(Utf8.toBytes(fields), Map.class).get("actualmap");
        List<?> generatedMap = (List<?>) ((Map<?, ?>) generated.get("fields")).get("actualmap");
        assertEquals(populateMap(inputMap), populateMap(generatedMap));
    }

    // should very much blow up if the assumptions are incorrect
    @SuppressWarnings("rawtypes")
    private Map<Object, Object> populateMap(List<?> actualMap) {
        Map<Object, Object> m = new HashMap<>();
        for (Object o : actualMap) {
            Object key = ((Map) o).get(JsonReader.MAP_KEY);
            Object value = ((Map) o).get(JsonReader.MAP_VALUE);
            m.put(key, value);
        }
        return m;
    }

    @Test
    public final void rawTest() throws JsonParseException, JsonMappingException, IOException {
        String payload = new String(
                new JsonStringEncoder().quoteAsString(new Base64()
                        .encodeToString(Utf8.toBytes("smoketest"))));
        String docId = "id:unittest:testraw::whee";

        String fields = "{ \"actualraw\": \"" + payload + "\"" + " }";
        roundTripEquality(docId, fields);
    }

    @Test
    public final void predicateTest() throws JsonParseException,
            JsonMappingException, IOException {
        roundTripEquality("id:unittest:testpredicate::whee", "{ "
                + "\"actualpredicate\": \"foo in [bar]\" }");
    }

    @Test
    public final void stringToArrayOfIntMapTest() throws JsonParseException,
            JsonMappingException, IOException {
        String docId = "id:unittest:testMapStringToArrayOfInt::whee";
        String fields = "{ \"actualMapStringToArrayOfInt\": ["
                + "{ \"key\": \"bamse\", \"value\": [1, 2, 3] }" + "]}";
        Document doc = readDocumentFromJson(docId, fields);
        // we have to do everything by hand to check, as maps are unordered, but
        // are serialized as an ordered structure

        ObjectMapper m = new ObjectMapper();
        Map<?, ?> generated = m.readValue(JsonWriter.toByteArray(doc), Map.class);
        assertEquals(docId, generated.get("id"));
        // and from here on down there will be lots of unchecked casting and
        // other fun. This is OK here, because if the casts fail, the should and
        // will fail anyway
        List<?> inputMap = (List<?>) m.readValue(Utf8.toBytes(fields), Map.class).get("actualMapStringToArrayOfInt");
        List<?> generatedMap = (List<?>) ((Map<?, ?>) generated.get("fields")).get("actualMapStringToArrayOfInt");
        assertEquals(populateMap(inputMap), populateMap(generatedMap));
    }

    private Document readDocumentFromJson(final String docId,
            final String fields) {
        InputStream rawDoc = new ByteArrayInputStream(asFeed(
                docId, fields));
        JsonReader r = new JsonReader(types, rawDoc, parserFactory);
        JsonReader.DocumentParseInfo raw = r.parseDocument().get();
        DocumentType docType = r.readDocumentType(raw.documentId);
        DocumentPut put = new DocumentPut(new Document(docType, raw.documentId));
        r.readPut(put);
        return put.getDocument();
    }

    private void assertEqualJson(byte[] expected, byte[] generated)
            throws JsonParseException, JsonMappingException, IOException {
        ObjectMapper m = new ObjectMapper();
        Map<?, ?> exp = m.readValue(expected, Map.class);
        Map<?, ?> gen = m.readValue(generated, Map.class);
        assertEquals(exp, gen);
    }

    private byte[] asFeed(String docId, String fields) {
        return completeDocString("put", docId, fields);
    }

    private byte[] asDocument(String docId, String fields) {
        return completeDocString("id", docId, fields);
    }

    private byte[] completeDocString(String operation, String docId, String fields) {
        return Utf8.toBytes("{\"" + operation + "\": \"" + docId + "\", \"fields\": " + fields + "}");
    }

    @Test
    public void removeTest() {
        final DocumentId documentId = new DocumentId("id:unittest:smoke::whee");
        InputStream rawDoc = new ByteArrayInputStream(
                Utf8.toBytes("["
                + Utf8.toString(JsonWriter.documentRemove(documentId))
                + "]"));
        JsonReader r = new JsonReader(types, rawDoc, parserFactory);
        DocumentOperation actualRemoveAsBaseType = r.next();
        assertSame(DocumentRemove.class, actualRemoveAsBaseType.getClass());
        assertEquals(actualRemoveAsBaseType.getId(), documentId);
    }

    @Test
    public void testWritingWithoutTensorFieldValue() throws IOException {
        roundTripEquality("id:unittest:testtensor::0", "{}");
    }

    @Test
    public void testWritingOfEmptyTensor() throws IOException {
        assertTensorRoundTripEquality("{}",
                "{ \"dimensions\": [], \"cells\": [] }");
    }

    @Test
    public void testWritingOfTensorWithCellsOnly() throws IOException {
        assertTensorRoundTripEquality("{ "
                + "  \"cells\": [ "
                + "    { \"address\": { \"x\": \"a\", \"y\": \"b\" }, "
                + "      \"value\": 2.0 }, "
                + "    { \"address\": { \"x\": \"c\" }, "
                + "      \"value\": 3.0 } "
                + "  ]"
                + "}", "{ "
                + "  \"dimensions\": [\"x\", \"y\"], "
                + "  \"cells\": [ "
                + "    { \"address\": { \"x\": \"a\", \"y\": \"b\" }, "
                + "      \"value\": 2.0 }, "
                + "    { \"address\": { \"x\": \"c\" }, "
                + "      \"value\": 3.0 } "
                + "  ]"
                + "}");
    }

    @Test
    public void testWritingOfTensorWithSingleCellWithEmptyAddress() throws IOException {
        assertTensorRoundTripEquality("{ "
                + "  \"dimensions\": [], "
                + "  \"cells\": [ "
                + "    { \"address\": {}, \"value\": 2.0 } "
                + "  ]"
                + "}");
    }

    @Test
    public void testWritingOfTensorWithDimensionsAndCells() throws IOException {
        assertTensorRoundTripEquality("{ "
                + "  \"dimensions\": [\"x\",\"y\",\"z\"], "
                + "  \"cells\": [ "
                + "    { \"address\": { \"x\": \"a\", \"y\": \"b\" }, "
                + "      \"value\": 2.0 }, "
                + "    { \"address\": { \"x\": \"c\" }, "
                + "      \"value\": 3.0 } "
                + "  ]"
                + "}");
    }

    @Test
    public void testWritingOfTensorFieldValueWithoutTensor() throws IOException {
        DocumentType tensorType = types.getDocumentType("testtensor");
        String docId = "id:unittest:testtensor::0";
        Document doc = new Document(tensorType, docId);
        doc.setFieldValue(tensorType.getField("tensorfield"), new TensorFieldValue());
        assertEqualJson(asDocument(docId, "{ \"tensorfield\": {} }"), JsonWriter.toByteArray(doc));
    }

    private void assertTensorRoundTripEquality(String tensorField) throws IOException {
        assertTensorRoundTripEquality(tensorField, tensorField);
    }

    private void assertTensorRoundTripEquality(String inputTensorField, String outputTensorField) throws IOException {
        String inputFields = "{ \"tensorfield\": " + inputTensorField + " }";
        String outputFields = "{ \"tensorfield\": " + outputTensorField + " }";
        String docId = "id:unittest:testtensor::0";
        Document doc = readDocumentFromJson(docId, inputFields);
        assertEqualJson(asDocument(docId, outputFields), JsonWriter.toByteArray(doc));
    }

}
