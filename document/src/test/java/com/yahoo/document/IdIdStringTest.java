// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.document;

import com.yahoo.document.idstring.IdIdString;
import com.yahoo.document.idstring.IdString;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Created with IntelliJ IDEA.
 * User: magnarn
 * Date: 10/16/12
 * Time: 9:10 AM
 */
public class IdIdStringTest {
    @Test
    public void requireThatIdIdStringGeneratesProperString() throws Exception {
        DocumentId docId = new DocumentId(new IdIdString("namespace", "type", "g=group", "foobar"));
        assertEquals("id:namespace:type:g=group:foobar", docId.toString());
    }

    @Test
    public void requireThatEmptyKeyValuesAreOk() throws Exception {
        DocumentId docId = new DocumentId(new IdIdString("namespace", "type", "", "foobar"));
        assertEquals("id:namespace:type::foobar", docId.toString());
    }

    @Test
    public void requireThatIdIdStringCanBehaveLikeGroupDoc() throws Exception {
        DocumentId docId1 = new DocumentId(new IdIdString("namespace", "type", "g=foo", "foo"));
        DocumentId docId2 = new DocumentId(new IdIdString("namespace", "type", "g=foo", "bar"));
        DocumentId docId3 = new DocumentId(new IdIdString("namespace", "type", "g=bar", "baz"));
        assertEquals(docId1.getScheme().getLocation(), docId2.getScheme().getLocation());
        assert(docId1.getScheme().getLocation() != docId3.getScheme().getLocation());
    }

    @Test
    public void requireThatIdIdStringCanBehaveLikeUserDoc() throws Exception {
        DocumentId docId1 = new DocumentId(new IdIdString("namespace", "type", "n=10", "foo"));
        DocumentId docId2 = new DocumentId(new IdIdString("namespace", "type", "n=10", "bar"));
        DocumentId docId3 = new DocumentId(new IdIdString("namespace", "type", "n=20", "baz"));
        assertEquals(docId1.getScheme().getLocation(), docId2.getScheme().getLocation());
        assert(docId1.getScheme().getLocation() != docId3.getScheme().getLocation());
    }

    @Test
    public void requireThatIllegalKeyValuesThrow() throws Exception {
        try {
            new IdIdString("namespace", "type", "illegal=key", "foo");
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals("Illegal key 'illegal'", e.getMessage());
        }
    }

    @Test
    public void requireThatKeysWithoutValuesThrow() throws Exception {
        try {
            new IdIdString("namespace", "type", "illegal-pair", "foo");
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals("Illegal key-value pair 'illegal-pair'", e.getMessage());
        }
    }

    @Test
    public void requireThatTooLongPreNamespaceSpecificThrowsWhileParsing() throws Exception {
        StringBuilder builder = new StringBuilder("id:");
        for (int i = 0; i < 0x10000; i++) {
            builder.append('n');
        }
        builder.append(":type::namespacespecificpart_01");
        try {
            IdString.createIdString(builder.toString());
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals("Document id prior to the namespace specific part, 65545, is longer than 65534", e.getMessage().substring(0, 77));
        }
    }
    @Test
    public void requireThatTooLongPreNamespaceSpecificThrowsOnConstruction() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 0x10000; i++) {
            builder.append('n');
        }
        try {
            new IdIdString(builder.toString(), "type", "", "namespacespecificpart_01");
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals("Length of namespace(65536) + doctype(4) + key/values(0), is longer than 65529", e.getMessage());
        }
    }

    @Test
    public void requireThatIdIdStringCanReplaceType() throws Exception {
        String type = IdIdString.replaceType("id:namespace:type::foo", "newType");
        assertEquals("id:namespace:newType::foo", type);
    }
}
