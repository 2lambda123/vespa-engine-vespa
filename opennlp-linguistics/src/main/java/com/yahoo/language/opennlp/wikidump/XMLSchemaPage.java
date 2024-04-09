package com.yahoo.language.opennlp.wikidump;

import java.util.ArrayList;
import java.util.List;

public class XMLSchemaPage {

    private String id;

    private WikiNamespace namespace;
    private String title;
    private String restrictions;

    private final List<XMLSchemaRevision> revisions = new ArrayList<>();


    public XMLSchemaPage(String id) {
        this.id = id;
    }

    public WikiNamespace getNamespace() {
        return this.namespace;
    }

    public String getId() {
        return this.id;
    }

    public String getTitle() {
        return this.title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setRestrictions(String restrictions) {
        this.restrictions = restrictions;
    }

    public void setNamespace(String namespace) {
        this.namespace = WikiNamespace.fromValue(Integer.parseInt(namespace));
    }

    public String getRestrictions() {
        return this.restrictions;
    }

    public List<XMLSchemaRevision> getRevisions() {
        return new ArrayList<>(this.revisions);
    }

    public void addRevision(XMLSchemaRevision revision) {
       this.revisions.add(revision);
    }

    @Override
    public String toString() {
       return "XMLSchema: " + this.id + "\n" +
               "<title>" + this.title + "</title>\n" +
               "<restrictions>" + this.restrictions + "</restrictions>\n" +
               "<revisions>\n" + this.revisions + "</revisions>\n";
    }

}



