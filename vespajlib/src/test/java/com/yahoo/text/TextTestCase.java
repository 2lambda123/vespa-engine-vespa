// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.text;

import org.junit.Test;

import java.util.OptionalInt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class TextTestCase {

    @Test
    public void testValidateTextString() {
        assertFalse(Text.validateTextString("valid").isPresent());
        assertEquals(OptionalInt.of(1), Text.validateTextString("text\u0001text\u0003"));
        assertEquals(OptionalInt.of(0xDFFFF),
                     Text.validateTextString(new StringBuilder().appendCodePoint(0xDFFFF).toString()));
        assertEquals(OptionalInt.of(0xDFFFF),
                     Text.validateTextString(new StringBuilder("foo").appendCodePoint(0xDFFFF).toString()));
        assertEquals(OptionalInt.of(0xDFFFF),
                     Text.validateTextString(new StringBuilder().appendCodePoint(0xDFFFF).append("foo").toString()));
        assertEquals(OptionalInt.of(0xDFFFF),
                     Text.validateTextString(new StringBuilder("foo").appendCodePoint(0xDFFFF).append("foo").toString()));
    }

    @Test
    public void testStripTextString() {
        assertEquals("", Text.stripInvalidCharacters(""));
        assertEquals("valid", Text.stripInvalidCharacters("valid"));
        assertEquals("text text ", Text.stripInvalidCharacters("text\u0001text\u0003"));
        assertEquals(" ",
                     Text.stripInvalidCharacters(new StringBuilder().appendCodePoint(0xDFFFF).toString()));
        assertEquals("foo ",
                     Text.stripInvalidCharacters(new StringBuilder("foo").appendCodePoint(0xDFFFF).toString()));
        assertEquals(" foo",
                     Text.stripInvalidCharacters(new StringBuilder().appendCodePoint(0xDFFFF).append("foo").toString()));
        assertEquals("foo foo",
                     Text.stripInvalidCharacters(new StringBuilder("foo").appendCodePoint(0xDFFFF).append("foo").toString()));
        assertEquals("foo foo",
                Text.stripInvalidCharacters(new StringBuilder("foo").appendCodePoint(0xD800).append("foo").toString()));
    }

    @Test
    public void testThatHighSurrogateRequireLowSurrogate() {
        assertEquals(OptionalInt.of(0xD800), Text.validateTextString(new StringBuilder().appendCodePoint(0xD800).toString()));
        assertEquals(OptionalInt.of(0xD800), Text.validateTextString(new StringBuilder().appendCodePoint(0xD800).append(0x0000).toString()));
    }

}
