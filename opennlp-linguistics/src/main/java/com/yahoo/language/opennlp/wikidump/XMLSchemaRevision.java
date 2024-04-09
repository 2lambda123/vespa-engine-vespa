package com.yahoo.language.opennlp.wikidump;

public class XMLSchemaRevision {

    private String id;
    private String timestamp;

    private String contributor;

    private String comment;

    private String text;

    public XMLSchemaRevision(String id) {
        this.id = id;
    }

    public String getTimestamp() {
        return this.timestamp;
    }

    public String getContributor() {
        return this.contributor;
    }

    public String getComment() {
        return this.comment;
    }

    public String getText() {
        return this.text;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public void setContributor(String contributor) {
        this.contributor = contributor;
    }

    public void setText(String text) {
        this.text = text;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return  "Revision id: " + this.id + "\n" +
                    "<timestamp>" + this.timestamp + "</timestamp>\n" +
                    "<contributor" + this.contributor + "</contributor\n" +
                    "<comment>" + this.comment + "</comment>\n" +
                    "<text>" + this.text + "</text>\n";
    }
}
