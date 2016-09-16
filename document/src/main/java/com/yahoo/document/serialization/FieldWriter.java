// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.document.serialization;

import com.yahoo.document.Document;
import com.yahoo.document.annotation.AnnotationReference;
import com.yahoo.document.datatypes.*;
import com.yahoo.vespa.objects.FieldBase;
import com.yahoo.vespa.objects.Serializer;

/**
 * Interface for writing out com.yahoo.document.datatypes.FieldValue.
 *
 * @author <a href="mailto:ravishar@yahoo-inc.com">ravishar</a>
 *
 */
public interface FieldWriter extends Serializer {

    /**
     * Write out the value of field
     *
     * @param field
     *            field description (name and data type)
     * @param value
     *            field value
     */
    void write(FieldBase field, FieldValue value);

    /**
     * Write out the value of field
     *
     * @param field
     *            field description (name and data type)
     * @param value
     *            field value
     */
    public void write(FieldBase field, Document value);

    /**
     * Write out the value of array field
     *
     * @param field
     *            field description (name and data type)
     * @param value
     *            field value
     */
    <T extends FieldValue> void write(FieldBase field, Array<T> value);

    /**
     * Write the value of a map field
     */
    <K extends FieldValue, V extends FieldValue> void write(FieldBase field,
            MapFieldValue<K, V> map);

    /**
     * Write out the value of byte field
     *
     * @param field
     *            field description (name and data type)
     * @param value
     *            field value
     */
    void write(FieldBase field, ByteFieldValue value);

    /**
     * Write out the value of collection field
     *
     * @param field
     *            field description (name and data type)
     * @param value
     *            field value
     */
    <T extends FieldValue> void write(FieldBase field,
            CollectionFieldValue<T> value);

    /**
     * Write out the value of double field
     *
     * @param field
     *            field description (name and data type)
     * @param value
     *            field value
     */
    void write(FieldBase field, DoubleFieldValue value);

    /**
     * Write out the value of float field
     *
     * @param field
     *            field description (name and data type)
     * @param value
     *            field value
     */
    void write(FieldBase field, FloatFieldValue value);

    /**
     * Write out the value of integer field
     *
     * @param field
     *            field description (name and data type)
     * @param value
     *            field value
     */
    void write(FieldBase field, IntegerFieldValue value);

    /**
     * Write out the value of long field
     *
     * @param field
     *            field description (name and data type)
     * @param value
     *            field value
     */
    void write(FieldBase field, LongFieldValue value);

    /**
     * Write out the value of raw field
     *
     * @param field
     *            field description (name and data type)
     * @param value
     *            field value
     */
    void write(FieldBase field, Raw value);

    /**
     * Write out the value of predicate field
     *
     * @param field
     *            field description (name and data type)
     * @param value
     *            field value
     */
    void write(FieldBase field, PredicateFieldValue value);

    /**
     * Write out the value of string field
     *
     * @param field
     *            field description (name and data type)
     * @param value
     *            field value
     */
    void write(FieldBase field, StringFieldValue value);

    /**
     * Write out the value of the given tensor field value.
     *
     * @param field field description (name and data type)
     * @param value tensor field value
     */
    void write(FieldBase field, TensorFieldValue value);

    /**
     * Write out the value of struct field
     *
     * @param field
     *            field description (name and data type)
     * @param value
     *            field value
     */
    void write(FieldBase field, Struct value);

    /**
     * Write out the value of structured field
     *
     * @param field
     *            field description (name and data type)
     * @param value
     *            field value
     */
    void write(FieldBase field, StructuredFieldValue value);

    /**
     * Write out the value of weighted set field
     *
     * @param field
     *            field description (name and data type)
     * @param value
     *            field value
     */
    <T extends FieldValue> void write(FieldBase field, WeightedSet<T> value);

    /**
     * Write out the value of annotation data.
     *
     * @param field
     *            field description (name and data type)
     * @param value
     *            field value
     */
    void write(FieldBase field, AnnotationReference value);

    /*
     * The Serializer interface is not strictly needed when implementing FieldWriter.
     * Instead of removing the 'extends Serializer', failing default-implementations are given.
     */

    @Override
    default Serializer putByte(FieldBase field, byte value) {
        throw new UnsupportedOperationException("Method not implemented");
    }

    @Override
    default Serializer putShort(FieldBase field, short value) {
        throw new UnsupportedOperationException("Method not implemented");
    }

    @Override
    default Serializer putInt(FieldBase field, int value) {
        throw new UnsupportedOperationException("Method not implemented");
    }

    @Override
    default Serializer putLong(FieldBase field, long value) {
        throw new UnsupportedOperationException("Method not implemented");
    }

    @Override
    default Serializer putFloat(FieldBase field, float value) {
        throw new UnsupportedOperationException("Method not implemented");
    }

    @Override
    default Serializer putDouble(FieldBase field, double value) {
        throw new UnsupportedOperationException("Method not implemented");
    }

    @Override
    default Serializer put(FieldBase field, byte[] value) {
        throw new UnsupportedOperationException("Method not implemented");
    }

    @Override
    default Serializer put(FieldBase field, ByteBuffer value) {
        throw new UnsupportedOperationException("Method not implemented");
    }

    @Override
    default Serializer put(FieldBase field, String value) {
        throw new UnsupportedOperationException("Method not implemented");
    }
}
