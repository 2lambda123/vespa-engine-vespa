// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * This class holds the extracted information after parsing a "struct"
 * block, using simple data structures as far as possible.  Do not put
 * advanced logic here!
 * @author arnej27959
 **/
public class ParsedStruct extends ParsedBlock {
    private final List<String> inherited = new ArrayList<>();
    private final Map<String, ParsedField> fields = new LinkedHashMap<>();
    private String ownedBy = null;

    public ParsedStruct(String name) {
        super(name, "struct");
    }

    List<ParsedField> getFields() { return List.copyOf(fields.values()); }
    List<String> getInherited() { return List.copyOf(inherited); }
    String getOwner() { return ownedBy; }

    void addField(ParsedField field) {
        String fieldName = field.name();
        verifyThat(! fields.containsKey(fieldName), "already has field", fieldName);
        fields.put(fieldName, field);
    }

    void inherit(String other) {
        verifyThat(! name().equals(other), "cannot inherit from itself");
        inherited.add(other);
    }

    void tagOwner(String document) {
        verifyThat(ownedBy == null, "already owned by document "+ownedBy);
        this.ownedBy = document;
    }

}

