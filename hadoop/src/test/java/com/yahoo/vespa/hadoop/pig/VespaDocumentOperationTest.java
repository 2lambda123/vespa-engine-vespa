package com.yahoo.vespa.hadoop.pig;

import org.apache.pig.data.*;
import org.apache.pig.impl.logicalLayer.schema.Schema;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class VespaDocumentOperationTest {

    @Test
    public void requireThatUDFReturnsCorrectJson() throws Exception {
        String json = getDocumentOperationJson("docid=id:<application>:metrics::<name>-<date>");
        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree(json);
        JsonNode fields = root.path("fields");

        // operation put is default
        assertEquals("id:testapp:metrics::clicks-20160112", root.get("put").getTextValue());
        assertEquals("testapp", fields.get("application").getTextValue());
        assertEquals("clicks", fields.get("name").getTextValue());
        assertEquals(3, fields.get("value").getIntValue());
    }


    @Test
    public void requireThatUDFSupportsUpdateAssign() throws IOException {
        String json = getDocumentOperationJson("docid=id:<application>:metrics::<name>-<date>", "operation=update");
        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree(json);
        JsonNode fields = root.path("fields");

        assertEquals("id:testapp:metrics::clicks-20160112", root.get("update").getTextValue());
        assertEquals("testapp", fields.get("application").get("assign").getTextValue());
        assertEquals("clicks", fields.get("name").get("assign").getTextValue());
        assertEquals(3, fields.get("value").get("assign").getIntValue());
    }


    @Test
    public void requireThatUDFReturnsNullForMissingConfig() throws Exception {
        String json = getDocumentOperationJson();
        assertNull(json);
    }


    @Test
    public void requireThatUDFCorrectlyGeneratesRemoveOperation() throws Exception {
        String json = getDocumentOperationJson("operation=remove", "docid=id:<application>:metrics::<name>-<date>");
        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree(json);
        JsonNode fields = root.get("fields");

        assertEquals("id:testapp:metrics::clicks-20160112", root.get("remove").getTextValue());
        assertNull(fields);
    }


    @Test
    public void requireThatUDFGeneratesComplexDataTypes() throws Exception {
        Schema schema = new Schema();
        Tuple tuple = TupleFactory.getInstance().newTuple();

        Tuple intTuple = TupleFactory.getInstance().newTuple();
        int[] intArray = {1, 2, 3};
        for (int i : intArray) { intTuple.append(i); }

        Tuple stringTuple = TupleFactory.getInstance().newTuple();
        String[] stringArray = {"a", "b", "c"};
        for (String s : stringArray) { stringTuple.append(s); }

        DataBag bag = new SortedDataBag(null);
        bag.add(intTuple);
        bag.add(stringTuple);

        Map<String, Object> innerMap = new HashMap<String, Object>() {{
            put("a", "string");
            put("tuple", intTuple);
        }};

        DataByteArray bytes = new DataByteArray("testdata".getBytes());

        Map<String, Object> outerMap = new HashMap<String, Object>() {{
            put("string", "value");
            put("int", 3);
            put("float", 3.145);
            put("bool", true);
            put("byte", bytes);
            put("map", innerMap);
            put("bag", bag);
        }};

        addToTuple("map", DataType.MAP, outerMap, schema, tuple);

        VespaDocumentOperation docOp = new VespaDocumentOperation("docid=empty");
        docOp.setInputSchema(schema);
        String json = docOp.exec(tuple);

        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree(json);
        JsonNode fields = root.get("fields");
        JsonNode map = fields.get("map");

        assertEquals("value", map.get("string").getTextValue());
        assertEquals(3, map.get("int").getIntValue());
        assertEquals(3.145, map.get("float").getDoubleValue(), 1e-6);
        assertEquals(true, map.get("bool").getBooleanValue());
        assertEquals("dGVzdGRhdGE=", map.get("byte").getTextValue());

        assertEquals("string", map.get("map").get("a").getTextValue());
        for (int i = 0; i < intArray.length; ++i) {
            assertEquals(intArray[i], map.get("map").get("tuple").get(i).asInt());
        }

        JsonNode bagField = map.get("bag");
        for (int i = 0; i < intArray.length; ++i) {
            assertEquals(intArray[i], bagField.get(0).get(i).asInt());
        }
        for (int i = 0; i < stringArray.length; ++i) {
            assertEquals(stringArray[i], bagField.get(1).get(i).asText());
        }
    }


    @Test
    public void requireThatSimpleArraysMustBeConfigured() throws Exception {
        String[] stringArray = {"a", "b", "c"};
        JsonNode array = setupSimpleArrayOperation("array", stringArray, "docid=empty"); // simple arrays not configured
        // json: [["a"], ["b"], ["c"]]
        assertEquals("a", array.get(0).get(0).asText());
        assertEquals("b", array.get(1).get(0).asText());
        assertEquals("c", array.get(2).get(0).asText());
    }


    @Test
    public void requireThatSimpleArraysAreSupported() throws Exception {
        String[] stringArray = {"a", "b", "c"};
        JsonNode array = setupSimpleArrayOperation("array", stringArray, "docid=empty", "simple-array-fields=array");
        // json: ["a", "b", "c"]
        assertEquals("a", array.get(0).asText());
        assertEquals("b", array.get(1).asText());
        assertEquals("c", array.get(2).asText());
    }


    @Test
    public void requireThatSimpleArraysCanBeConfiguredWithWildcard() throws Exception {
        String[] stringArray = {"a", "b", "c"};
        JsonNode array = setupSimpleArrayOperation("array", stringArray, "docid=empty", "simple-array-fields=*");
        // json: ["a", "b", "c"]
        assertEquals("a", array.get(0).asText());
        assertEquals("b", array.get(1).asText());
        assertEquals("c", array.get(2).asText());
    }


    @Test
    public void requireThatMultipleSimpleArraysAreSupported() throws Exception {
        String[] stringArray = {"a", "b", "c"};
        JsonNode array = setupSimpleArrayOperation("array", stringArray, "docid=empty", "simple-array-fields=empty,array");
        // json: ["a", "b", "c"]
        assertEquals("a", array.get(0).asText());
        assertEquals("b", array.get(1).asText());
        assertEquals("c", array.get(2).asText());
    }


    private JsonNode setupSimpleArrayOperation(String name, String[] array, String... params) throws IOException {
        Schema schema = new Schema();
        Tuple tuple = TupleFactory.getInstance().newTuple();

        DataBag bag = new SortedDataBag(null);
        for (String s : array) {
            Tuple stringTuple = TupleFactory.getInstance().newTuple();
            stringTuple.append(s);
            bag.add(stringTuple);
        }
        addToTuple(name, DataType.BAG, bag, schema, tuple);

        VespaDocumentOperation docOp = new VespaDocumentOperation(params);
        docOp.setInputSchema(schema);
        String json = docOp.exec(tuple);

        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree(json);
        JsonNode fields = root.get("fields");
        return fields.get(name);
    }


    @Test
    public void requireThatUDFSupportsTensors() throws IOException {
        Schema schema = new Schema();
        Tuple tuple = TupleFactory.getInstance().newTuple();

        // Please refer to the tensor format documentation

        Map<String, Double> tensor = new HashMap<String, Double>() {{
            put("x:label1,y:label2,z:label4", 2.0);
            put("x:label3", 3.0);
        }};

        addToTuple("id", DataType.CHARARRAY, "123", schema, tuple);
        addToTuple("tensor", DataType.MAP, tensor, schema, tuple);

        VespaDocumentOperation docOp = new VespaDocumentOperation("docid=empty", "create-tensor-fields=tensor");
        docOp.setInputSchema(schema);
        String json = docOp.exec(tuple);

        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree(json);
        JsonNode fields = root.get("fields");
        JsonNode tensorNode = fields.get("tensor");
        JsonNode cells = tensorNode.get("cells");

        assertEquals("label1", cells.get(0).get("address").get("x").asText());
        assertEquals("label2", cells.get(0).get("address").get("y").asText());
        assertEquals("label4", cells.get(0).get("address").get("z").asText());
        assertEquals("label3", cells.get(1).get("address").get("x").asText());

        assertEquals(2.0, cells.get(0).get("value").asDouble(), 1e-6);
        assertEquals(3.0, cells.get(1).get("value").asDouble(), 1e-6);
    }


    private String getDocumentOperationJson(String... params) throws IOException {
        Schema schema = new Schema();
        Tuple tuple = TupleFactory.getInstance().newTuple();

        addToTuple("application", DataType.CHARARRAY, "testapp", schema, tuple);
        addToTuple("name", DataType.CHARARRAY, "clicks", schema, tuple);
        addToTuple("date", DataType.CHARARRAY, "20160112", schema, tuple);
        addToTuple("value", DataType.CHARARRAY, 3, schema, tuple);

        VespaDocumentOperation docOp = new VespaDocumentOperation(params);
        docOp.setInputSchema(schema);
        String json = docOp.exec(tuple);

        return json;
    }


    private void addToTuple(String alias, byte type, Object value, Schema schema, Tuple tuple) {
        schema.add(new Schema.FieldSchema(alias, type));
        tuple.append(value);
    }


}
