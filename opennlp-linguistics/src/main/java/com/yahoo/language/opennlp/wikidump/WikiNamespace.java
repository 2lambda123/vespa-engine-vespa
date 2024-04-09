package com.yahoo.language.opennlp.wikidump;

public enum WikiNamespace {

    MAIN_ARTICLE(0),
    TALK(1),
    USER(2),

    USER_TALK(3),
    WIKIPEDIA(4),

    WIKIPEDIA_TALK(5),
    FILE(6),

    FILE_TALK(7),
    MEDIAWIKI(8),

    MEDIAWIKI_TALK(9),

    TEMPLATE(10),
    TEMPLATE_TALK(11),
    HELP(12),

    HELP_TALK(13),
    CATEGORY(14),
    CATEGORY_TALK(15),
    PORTAL(100),
    PORTAL_TALK(101),
    DRAFT(118),
    DRAFT_TALK(119),
    TIMEDTEXT(710),
    TIMEDTEXT_TALK(711),
    MODULE(828),
    MODULE_TALK(829),

    UNKNOWN(-1);

    private final int namespaceId;

    WikiNamespace(int namespaceId) {
        this.namespaceId = namespaceId;
    }

    public static WikiNamespace fromValue(int id) {
        for (var enumValue : WikiNamespace.values()) {
            if (enumValue.getNamespaceId() == id) {
                return enumValue;
            }
        }
        return WikiNamespace.UNKNOWN;
    }

    public int getNamespaceId() {
        return namespaceId;
    }
}