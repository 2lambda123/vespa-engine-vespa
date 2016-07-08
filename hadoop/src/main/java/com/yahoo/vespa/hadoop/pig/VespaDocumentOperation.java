package com.yahoo.vespa.hadoop.pig;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.yahoo.vespa.hadoop.mapreduce.util.TupleTools;
import com.yahoo.vespa.hadoop.mapreduce.util.VespaConfiguration;
import org.apache.pig.EvalFunc;
import org.apache.pig.PigWarning;
import org.apache.pig.data.DataBag;
import org.apache.pig.data.DataByteArray;
import org.apache.pig.data.DataType;
import org.apache.pig.data.Tuple;
import org.joda.time.DateTime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/**
 * A Pig UDF to convert simple Pig types into a valid Vespa JSON document format.
 *
 * @author lesters
 */
public class VespaDocumentOperation extends EvalFunc<String> {

    public enum Operation {
        PUT,
        ID,
        REMOVE,
        UPDATE;

        @Override
        public String toString() {
            return super.toString().toLowerCase();
        }

        public static Operation fromString(String text) {
            for (Operation op : Operation.values()) {
                if (op.toString().equalsIgnoreCase(text)) {
                    return op;
                }
            }
            throw new IllegalArgumentException("Unknown operation: " + text);
        }

        public static boolean valid(String text) {
            for (Operation op : Operation.values()) {
                if (op.toString().equalsIgnoreCase(text)) {
                    return true;
                }
            }
            return false;
        }

    }

    private static final String PROPERTY_ID_TEMPLATE = "docid";
    private static final String PROPERTY_OPERATION = "operation";
    private static final String SIMPLE_ARRAY_FIELDS = "simple-array-fields";
    private static final String CREATE_TENSOR_FIELDS = "create-tensor-fields";

    private static final String PARTIAL_UPDATE_ASSIGN = "assign";

    private final String template;
    private final Operation operation;
    private final Properties properties;

    public VespaDocumentOperation(String... params) {
        properties = VespaConfiguration.loadProperties(params);
        template = properties.getProperty(PROPERTY_ID_TEMPLATE);
        operation = Operation.fromString(properties.getProperty(PROPERTY_OPERATION, "put"));
    }

    @Override
    public String exec(Tuple tuple) throws IOException {
        if (tuple == null || tuple.size() == 0) {
            return null;
        }
        if (template == null || template.length() == 0) {
            warn("No valid document id template found. Skipping.", PigWarning.UDF_WARNING_1);
            return null;
        }
        if (operation == null) {
            warn("No valid operation found. Skipping.", PigWarning.UDF_WARNING_1);
            return null;
        }

        String json = null;

        try {
            if (reporter != null) {
                reporter.progress();
            }

            Map<String, Object> fields = TupleTools.tupleMap(getInputSchema(), tuple);
            String docId = TupleTools.toString(fields, template);

            // create json
            json = create(operation, docId, fields, properties);
            if (json == null || json.length() == 0) {
                warn("No valid document operation could be created.", PigWarning.UDF_WARNING_1);
                return null;
            }


        } catch (Exception e) {
            throw new IOException("Caught exception processing input row ", e);
        }

        return json;
    }


    /**
     * Create a JSON Vespa document operation given the supplied fields,
     * operation and document id template.
     *
     * @param op        Operation (put, remove, update)
     * @param docId     Document id
     * @param fields    Fields to put in document operation
     * @return          A valid JSON Vespa document operation
     * @throws IOException
     */
    public static String create(Operation op, String docId, Map<String, Object> fields, Properties properties) throws IOException {
        if (op == null) {
            return null;
        }
        if (docId == null || docId.length() == 0) {
            return null;
        }
        if (fields.isEmpty()) {
            return null;
        }

        // create json format
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JsonGenerator g = new JsonFactory().createGenerator(out, JsonEncoding.UTF8);
        g.writeStartObject();

        g.writeStringField(op.toString(), docId);

        if (op != Operation.REMOVE) {
            writeField("fields", fields, DataType.MAP, g, properties, op, 0);
        }

        g.writeEndObject();
        g.close();

        return out.toString();
    }


    @SuppressWarnings("unchecked")
    private static void writeField(String name, Object value, Byte type, JsonGenerator g, Properties properties, Operation op, int depth) throws IOException {
        g.writeFieldName(name);
        if (shouldWritePartialUpdate(op, depth)) {
            writePartialUpdate(value, type, g, name, properties, op, depth);
        } else {
            writeValue(value, type, g, name, properties, op, depth);
        }
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(Object value, Byte type, JsonGenerator g, String name, Properties properties, Operation op, int depth) throws IOException {
        switch (type) {
            case DataType.UNKNOWN:
                break;
            case DataType.NULL:
                g.writeNull();
                break;
            case DataType.BOOLEAN:
                g.writeBoolean((boolean) value);
                break;
            case DataType.INTEGER:
                g.writeNumber((int) value);
                break;
            case DataType.LONG:
                g.writeNumber((long) value);
                break;
            case DataType.FLOAT:
                g.writeNumber((float) value);
                break;
            case DataType.DOUBLE:
                g.writeNumber((double) value);
                break;
            case DataType.DATETIME:
                g.writeNumber(((DateTime) value).getMillis());
                break;
            case DataType.BYTEARRAY:
                DataByteArray bytes = (DataByteArray) value;
                String raw = Base64.getEncoder().encodeToString(bytes.get());
                g.writeString(raw);
                break;
            case DataType.CHARARRAY:
                g.writeString((String) value);
                break;
            case DataType.BIGINTEGER:
                g.writeNumber((BigInteger) value);
                break;
            case DataType.BIGDECIMAL:
                g.writeNumber((BigDecimal) value);
                break;
            case DataType.MAP:
                g.writeStartObject();
                Map<Object, Object> map = (Map<Object, Object>) value;
                if (shouldCreateTensor(map, name, properties)) {
                    writeTensor(map, g);
                } else {
                    for (Map.Entry<Object, Object> entry : map.entrySet()) {
                        String k = entry.getKey().toString();
                        Object v = entry.getValue();
                        Byte   t = DataType.findType(v);
                        writeField(k, v, t, g, properties, op, depth+1);
                    }
                }
                g.writeEndObject();
                break;
            case DataType.TUPLE:
                Tuple tuple = (Tuple) value;
                boolean writeStartArray = shouldWriteTupleStart(tuple, name, properties);
                if (writeStartArray) {
                    g.writeStartArray();
                }
                for (Object v : tuple) {
                    writeValue(v, DataType.findType(v), g, name, properties, op, depth);
                }
                if (writeStartArray) {
                    g.writeEndArray();
                }
                break;
            case DataType.BAG:
                DataBag bag = (DataBag) value;
                g.writeStartArray();
                for (Tuple t : bag) {
                    writeValue(t, DataType.TUPLE, g, name, properties, op, depth);
                }
                g.writeEndArray();
                break;
        }

    }

    private static boolean shouldWritePartialUpdate(Operation op, int depth) {
        return op == Operation.UPDATE && depth == 1;
    }

    private static void writePartialUpdate(Object value, Byte type, JsonGenerator g, String name, Properties properties, Operation op, int depth) throws IOException {
        g.writeStartObject();
        g.writeFieldName(PARTIAL_UPDATE_ASSIGN); // TODO: lookup field name in a property to determine correct operation
        writeValue(value, type, g, name, properties, op, depth);
        g.writeEndObject();
    }

    private static boolean shouldWriteTupleStart(Tuple tuple, String name, Properties properties) {
        if (tuple.size() > 1 || properties == null) {
            return true;
        }
        String simpleArrayFields = properties.getProperty(SIMPLE_ARRAY_FIELDS);
        if (simpleArrayFields == null) {
            return true;
        }
        if (simpleArrayFields.equals("*")) {
            return false;
        }
        String[] fields = simpleArrayFields.split(",");
        for (String field : fields) {
            if (field.trim().equalsIgnoreCase(name)) {
                return false;
            }
        }
        return true;
    }

    private static boolean shouldCreateTensor(Map<Object, Object> map, String name, Properties properties) {
        if (properties == null) {
            return false;
        }
        String tensorFields = properties.getProperty(CREATE_TENSOR_FIELDS);
        if (tensorFields == null) {
            return false;
        }
        String[] fields = tensorFields.split(",");
        for (String field : fields) {
            if (field.trim().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static void writeTensor(Map<Object, Object> map, JsonGenerator g) throws IOException {
        g.writeFieldName("cells");
        g.writeStartArray();
        for (Map.Entry<Object, Object> entry : map.entrySet()) {
            String k = entry.getKey().toString();
            Double v = Double.parseDouble(entry.getValue().toString());

            g.writeStartObject();

            // Write address
            g.writeFieldName("address");
            g.writeStartObject();

            String[] dimensions = k.split(",");
            for (String dimension : dimensions) {
                if (dimension == null || dimension.isEmpty()) {
                    continue;
                }
                String[] address = dimension.split(":");
                if (address.length != 2) {
                    throw new IllegalArgumentException("Malformed cell address: " + dimension);
                }
                String dim = address[0];
                String label = address[1];
                if (dim == null || label == null || dim.isEmpty() || label.isEmpty()) {
                    throw new IllegalArgumentException("Malformed cell address: " + dimension);
                }
                g.writeFieldName(dim.trim());
                g.writeString(label.trim());
            }
            g.writeEndObject();

            // Write value
            g.writeFieldName("value");
            g.writeNumber(v);

            g.writeEndObject();
        }
        g.writeEndArray();
    }

}
