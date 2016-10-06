package com.yahoo.vespa.model.application.validation;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.google.common.base.Joiner;
import com.yahoo.tensor.TensorType;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ConstantTensorJsonValidator strictly validates a constant tensor in JSON format read from a Reader object
 *
 * @author Vegard Sjonfjell
 */
public class ConstantTensorJsonValidator {
    private static final String FIELD_CELLS = "cells";
    private static final String FIELD_ADDRESS = "address";
    private static final String FIELD_VALUE = "value";

    private static final JsonFactory jsonFactory = new JsonFactory();
    private TensorType tensorType;
    private JsonParser parser;

    public static class InvalidConstantTensor extends RuntimeException {
        public InvalidConstantTensor(JsonParser parser, String message) {
            super(message + " " + parser.getCurrentLocation().toString());
        }

        public InvalidConstantTensor(JsonParser parser, Exception base) {
            super("Failed to parse JSON stream " + parser.getCurrentLocation().toString(), base);
        }
    }

    @FunctionalInterface
    private static interface SubroutineThrowingIOException {
        void invoke() throws IOException;
    }

    private void wrapIOException(SubroutineThrowingIOException lambda) {
        try {
            lambda.invoke();
        } catch (IOException e) {
            throw new InvalidConstantTensor(parser, e);
        }
    }

    public ConstantTensorJsonValidator(Reader tensorFile, TensorType tensorType) {
        wrapIOException(() -> {
            this.parser = jsonFactory.createParser(tensorFile);
            this.tensorType = tensorType;
        });
    }

    public void validate() {
        wrapIOException(() -> {
            assertNextTokenIs(JsonToken.START_OBJECT);
            assertNextTokenIs(JsonToken.FIELD_NAME);
            assertFieldNameIs(FIELD_CELLS);

            assertNextTokenIs(JsonToken.START_ARRAY);

            while (parser.nextToken() != JsonToken.END_ARRAY) {
                validateTensorCell(tensorType.dimensions());
            }

            assertNextTokenIs(JsonToken.END_OBJECT);
        });
    }

    private void validateTensorCell(Collection<TensorType.Dimension> tensorDimensions) {
        wrapIOException(() -> {
            assertCurrentTokenIs(JsonToken.START_OBJECT);

            final List<String> fieldNameCandidates = new ArrayList<>(Arrays.asList(FIELD_ADDRESS, FIELD_VALUE));
            for (int i = 0; i < 2; i++) {
                assertNextTokenIs(JsonToken.FIELD_NAME);
                final String fieldName = parser.getCurrentName();

                if (fieldNameCandidates.contains(fieldName)) {
                    fieldNameCandidates.remove(fieldName);

                    if (fieldName.equals(FIELD_ADDRESS)) {
                        validateTensorAddress(tensorDimensions);
                    } else if (fieldName.equals(FIELD_VALUE)) {
                        validateTensorValue();
                    }
                } else {
                    throw new InvalidConstantTensor(parser, "Only \"address\" or \"value\" fields are permitted within a cell object");
                }
            }

            assertNextTokenIs(JsonToken.END_OBJECT);
        });
    }

    private void validateTensorAddress(Collection<TensorType.Dimension> tensorDimensions) throws IOException {
        assertNextTokenIs(JsonToken.START_OBJECT);

        final Map<String, TensorType.Dimension> tensorDimensionsMapping = tensorDimensions.stream()
                .collect(Collectors.toMap(TensorType.Dimension::name, Function.identity()));

        // Iterate within the address key, value pairs
        while ((parser.nextToken() != JsonToken.END_OBJECT)) {
            assertCurrentTokenIs(JsonToken.FIELD_NAME);

            final String dimensionName = parser.getCurrentName();
            TensorType.Dimension dimension = tensorDimensionsMapping.get(dimensionName);
            if (dimension == null) {
                throw new InvalidConstantTensor(parser, String.format("Tensor dimension with name \"%s\" does not exist", parser.getCurrentName()));
            }

            tensorDimensionsMapping.remove(dimensionName);
            validateTensorCoordinate(dimension);
        }

        if (!tensorDimensionsMapping.isEmpty()) {
            throw new InvalidConstantTensor(parser, String.format("Tensor address missing dimension(s): %s", Joiner.on(", ").join(tensorDimensionsMapping.keySet())));
        }
    }

    /*
     * Tensor coordinates are always strings. Coordinates for a mapped dimension can be any string,
     * but those for indexed dimensions needs to be able to be interpreted as integers, and,
     * additionally, those for indexed bounded dimensions needs to fall within the dimension size.
     */
    private void validateTensorCoordinate(TensorType.Dimension dimension) throws IOException {
        assertNextTokenIs(JsonToken.VALUE_STRING);

        if (dimension instanceof TensorType.IndexedBoundDimension) {
            validateBoundedCoordinate((TensorType.IndexedBoundDimension) dimension);
        } else if (dimension instanceof TensorType.IndexedUnboundDimension) {
            validateUnboundedCoordinate(dimension);
        }
    }

    private void validateBoundedCoordinate(TensorType.IndexedBoundDimension dimension) {
        wrapIOException(() -> {
            try {
                final int value = Integer.parseInt(parser.getValueAsString());
                if (value >= dimension.size().get()) {
                    throw new InvalidConstantTensor(parser, String.format("Coordinate \"%s\" not within limits of bounded dimension %s", value, dimension.name()));

                }
            } catch (NumberFormatException e) {
                throwCoordinateIsNotInteger(parser.getValueAsString(), dimension.name());
            }
        });
    }

    private void validateUnboundedCoordinate(TensorType.Dimension dimension) {
        wrapIOException(() -> {
            try {
                Integer.parseInt(parser.getValueAsString());
            } catch (NumberFormatException e) {
                throwCoordinateIsNotInteger(parser.getValueAsString(), dimension.name());
            }
        });
    }

    private void throwCoordinateIsNotInteger(String value, String dimensionName) {
        throw new InvalidConstantTensor(parser, String.format("Coordinate \"%s\" for dimension %s is not an integer", value, dimensionName));
    }

    private void validateTensorValue() throws IOException {
        final JsonToken token = parser.nextToken();

        if (token != JsonToken.VALUE_NUMBER_FLOAT && token != JsonToken.VALUE_NUMBER_INT) {
            throw new InvalidConstantTensor(parser, String.format("Expected a number, but got %s", token.toString()));
        }
    }

    private void assertCurrentTokenIs(JsonToken wantedToken) {
        assertTokenIs(parser.getCurrentToken(), wantedToken);
    }

    private void assertNextTokenIs(JsonToken wantedToken) throws IOException {
        assertTokenIs(parser.nextToken(), wantedToken);
    }

    private void assertTokenIs(JsonToken token, JsonToken wantedToken) {
        if (token != wantedToken) {
            throw new InvalidConstantTensor(parser, String.format("Expected JSON token %s, but got %s", wantedToken.toString(), token.toString()));
        }
    }

    private void assertFieldNameIs(String wantedFieldName) throws IOException {
        final String actualFieldName = parser.getCurrentName();

        if (!actualFieldName.equals(wantedFieldName)) {
            throw new InvalidConstantTensor(parser, String.format("Expected field name \"%s\", got \"%s\"", wantedFieldName, actualFieldName));
        }
    }
}
