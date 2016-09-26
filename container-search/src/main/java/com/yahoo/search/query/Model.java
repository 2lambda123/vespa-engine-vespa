// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.search.query;

import com.yahoo.language.Language;
import com.yahoo.language.Linguistics;
import com.yahoo.language.LocaleFactory;
import com.yahoo.prelude.query.CompositeItem;
import com.yahoo.prelude.query.Item;
import com.yahoo.prelude.query.TaggableItem;
import com.yahoo.prelude.query.textualrepresentation.TextualQueryRepresentation;
import com.yahoo.processing.request.CompoundName;
import com.yahoo.search.Query;
import com.yahoo.search.query.parser.Parsable;
import com.yahoo.search.query.parser.Parser;
import com.yahoo.search.query.parser.ParserEnvironment;
import com.yahoo.search.query.parser.ParserFactory;
import com.yahoo.search.query.profile.types.FieldDescription;
import com.yahoo.search.query.profile.types.QueryProfileType;
import com.yahoo.search.searchchain.Execution;

import java.util.*;

import static com.yahoo.text.Lowercase.toLowerCase;

/**
 * The parameters defining the recall of a query.
 *
 * @author Arne Bergene Fossaa
 * @author bratseth
 */
public class Model implements Cloneable {

    /** The type representing the property arguments consumed by this */
    private static final QueryProfileType argumentType;
    private static final CompoundName argumentTypeName;

    public static final String MODEL = "model";
    public static final String PROGRAM = "program";
    public static final String QUERY_STRING = "queryString";
    public static final String TYPE = "type";
    public static final String FILTER = "filter";
    public static final String DEFAULT_INDEX = "defaultIndex";
    public static final String LANGUAGE = "language";
    public static final String ENCODING = "encoding";
    public static final String SOURCES = "sources";
    public static final String SEARCH_PATH = "searchPath";
    public static final String RESTRICT = "restrict";

    static {
        argumentType =new QueryProfileType(MODEL);
        argumentType.setStrict(true);
        argumentType.setBuiltin(true);
        //argumentType.addField(new FieldDescription(PROGRAM, "string", "yql")); // TODO: Custom type
        argumentType.addField(new FieldDescription(QUERY_STRING, "string", "query"));
        argumentType.addField(new FieldDescription(TYPE, "string", "type"));
        argumentType.addField(new FieldDescription(FILTER, "string","filter"));
        argumentType.addField(new FieldDescription(DEFAULT_INDEX, "string", "default-index def-idx defidx"));
        argumentType.addField(new FieldDescription(LANGUAGE, "string", "language lang"));
        argumentType.addField(new FieldDescription(ENCODING, "string", "encoding"));
        argumentType.addField(new FieldDescription(SOURCES, "string", "sources search"));
        argumentType.addField(new FieldDescription(SEARCH_PATH, "string", "searchpath"));
        argumentType.addField(new FieldDescription(RESTRICT, "string", "restrict"));
        argumentType.freeze();
        argumentTypeName=new CompoundName(argumentType.getId().getName());
    }

    public static QueryProfileType getArgumentType() { return argumentType; }

    /** The name of the query property used for generating hit count estimate queries. */
    public static final CompoundName ESTIMATE = new CompoundName("hitcountestimate");

    private String encoding = null;
    private String queryString = "";
    private String filter = null;
    private Language language = null;
    private Locale locale = null;
    private QueryTree queryTree = null; // The actual query. This is lazily created from the program
    private String defaultIndex = null;
    private Query.Type type = Query.Type.ALL;
    private Query parent;
    private Set<String> sources=new LinkedHashSet<>();
    private Set<String> restrict=new LinkedHashSet<>();
    private String searchPath;
    private String documentDbName = null;
    private Execution execution=new Execution(new Execution.Context(null, null, null, null, null));

    public Model(Query query) {
        setParent(query);
    }

    /**
     * Creates trace a message of language detection results into this Model
     * instance's parent query. Do note this will give bogus results if the
     * Execution instance is not set correctly. This is done automatically
     * inside {@link Execution#search(Query)}. If tracing the same place as
     * creating the query instance, {@link #setExecution(Execution)} has to be
     * invoked first with the same Execution instance the query is intended to
     * be run by.
     * 
     * @deprecated do not use; language can now be assigned later and for parts of the query tree, making this quite useless
     */
    @Deprecated
    public void traceLanguage() {
        if (getParent().getTraceLevel()<2) return;
        if (language != null) {
            getParent().trace("Language " + getLanguage() + " specified directly as a parameter", false, 2);
        }
        else {
            Language l = getParsingLanguage();
            // Don't include the query, it will trigger query parsing
            getParent().trace("Detected language: " + l, false, 2);
            getParent().trace("Language " + l + " determined by " +
                              (Language.fromEncoding(encoding) != Language.UNKNOWN ? "query encoding" :
                               "the characters in the terms") + ".", false, 2);
        }
    }

    /**
     * Gets the language to use for parsing. If this is explicitly set in the model, that language is returned.
     * Otherwise, if a query tree is already produced and any node in it specifies a language the first such 
     * node encountered in a depth first 
     * left to right search is returned. Otherwise the language is guessed from the query string. 
     * If this does not yield an actual language, English is returned as the default.
     *
     * @return the language determined, never null
     */
    // TODO: We can support multiple languages per query by changing searchers which call this
    //       to look up the query to use at each point form item.getLanguage
    //       with this as fallback for query branches where no parent item specifies language
    public Language getParsingLanguage() {
        Language language = getLanguage();
        if (language != null) return language;

        language = Language.fromEncoding(encoding);
        if (language != Language.UNKNOWN) return language;

        if (queryTree != null)
            language = languageBelow(queryTree);
        if (language != Language.UNKNOWN) return language;
        
        Linguistics linguistics = execution.context().getLinguistics();
        if (linguistics != null)
            language = linguistics.getDetector().detect(queryString, null).getLanguage();
        if (language != Language.UNKNOWN) return language;

        return Language.ENGLISH;
    }
    
    private Language languageBelow(Item item) {
        if (item.getLanguage() != Language.UNKNOWN) return item.getLanguage();
        if (item instanceof CompositeItem) {
            for (Iterator<Item> i = ((CompositeItem) item).getItemIterator(); i.hasNext(); ) {
                Language childLanguage = languageBelow(i.next());
                if (childLanguage != Language.UNKNOWN) return childLanguage;
            }
        }
        return Language.UNKNOWN;
    }

    /** Returns the explicitly set parsing language of this query model, or null if none */
    public Language getLanguage() { return language; }

    /** Explicitly sets the language to be used during parsing */
    public void setLanguage(Language language) { this.language = language; }

    /**
     * <p>Explicitly sets the language to be used during parsing. The argument is first normalized by replacing
     * underscores with hyphens (to support locale strings being used as RFC 5646 language tags), and then forwarded to
     * {@link #setLocale(String)} so that the Locale information of the tag is preserved.</p>
     *
     * @param language The language string to parse.
     * @see #getLanguage()
     * @see #setLocale(String)
     */
    public void setLanguage(String language) {
        setLocale(language.replace("_", "-"));
    }

    /**
     * <p>Returns the explicitly set parsing locale of this query model, or null if none.</p>
     *
     * @return The locale of this.
     * @see #setLocale(Locale)
     */
    public Locale getLocale() {
        return locale;
    }

    /**
     * <p>Explicitly sets the locale to be used during parsing. This method also calls {@link #setLanguage(Language)}
     * with the corresponding {@link Language} instance.</p>
     *
     * @param locale The locale to set.
     * @see #getLocale()
     * @see #setLanguage(Language)
     */
    public void setLocale(Locale locale) {
        this.locale = locale;
        setLanguage(Language.fromLocale(locale));
    }

    /**
     * <p>Explicitly sets the locale to be used during parsing. This creates a Locale instance from the given language
     * tag, and passes that to {@link #setLocale(Locale)}.</p>
     *
     * @param languageTag The language tag to parse.
     * @see #setLocale(Locale)
     */
    public void setLocale(String languageTag) {
        setLocale(LocaleFactory.fromLanguageTag(languageTag));
    }

    /** Returns the encoding used in the query as a lowercase string */
    public String getEncoding() { return encoding; }

    /** Sets the encoding which was used in the received query string */
    public void setEncoding(String encoding) {
        this.encoding = toLowerCase(encoding);
    }

    /** Set the path for which backend nodes to forward the search too. */
    public void setSearchPath(String searchPath) { this.searchPath = searchPath; }

    public String getSearchPath() { return searchPath; }

    /**
     * Set the query from a string. This will not be parsed into a query tree until that tree is attempted accessed.
     * Note that setting this will clear the current query tree. Usually, this should <i>not</i> be modified -
     * changes to the query should be implemented as modifications on the query tree structure.
     * <p>
     * Passing null causes this to be set to an empty string.
     */
    public void setQueryString(String queryString) {
        if (queryString==null) queryString="";
        this.queryString = queryString;
        queryTree=null; // Cause parsing of the new query string next time the tree is accessed
    }

    /**
     * Returns the query string which caused the original query tree of this model to come about.
     * Note that changes to the query tree are <b>not</b> reflected in this query string.
     *
     * @return the original (or reassigned) query string - never null
     */
    public String getQueryString() { return queryString; }

    /**
     * Returns the query as an object structure.
     * This causes parsing of the query string if it has changed since this was last called
     * (i.e query parsing is lazy)
     */
    public QueryTree getQueryTree() {
        if (queryTree == null) {
            Parser parser = ParserFactory.newInstance(type, ParserEnvironment.fromExecutionContext(execution.context()));
            queryTree = parser.parse(Parsable.fromQueryModel(this));
            if (parent.getTraceLevel() >= 2) {
                parent.trace("Query parsed to: " + parent.yqlRepresentation(), 2);
            }
        }
        return queryTree;
    }

    /**
     * Returns the filter string set for this query.
     * The filter is included in the query tree at the time the query tree is parsed
     */
    public String getFilter() { return filter; }

    /**
     * Sets the filter string set for this query.
     * The filter is included in the query tree at the time the query tree is parsed.
     * Setting this does <i>not</i> cause the query to be reparsed.
     */
    public void setFilter(String filter) {  this.filter = filter; }

    /**
     * Returns the default index for this query.
     * The default index is taken into account at the time the query tree is parsed.
     */
    public String getDefaultIndex() { return defaultIndex; }

    /**
     * Sets the default index for this query.
     * The default index is taken into account at the time the query tree is parsed.
     * Setting this does <i>not</i> cause the query to be reparsed.
     */
    public void setDefaultIndex(String defaultIndex) { this.defaultIndex = defaultIndex; }

    /**
     * Sets the query type of for this query.
     * The type is taken into account at the time the query tree is parsed.
     */
    public Query.Type getType() { return type; }

    /**
     * Sets the query type of for this query.
     * The type is taken into account at the time the query tree is parsed.
     * Setting this does <i>not</i> cause the query to be reparsed.
     */
    public void setType(Query.Type type) { this.type = type; }

    /**
     * Sets the query type of for this query.
     * The type is taken into account at the time the query tree is parsed.
     * Setting this does <i>not</i> cause the query to be reparsed.
     */
    public void setType(String typeString) { this.type = Query.Type.getType(typeString); }

    public boolean equals(Object o) {
        if ( ! (o instanceof Model)) return false;

        Model other = (Model) o;
        if ( ! (
                QueryHelper.equals(other.encoding, this.encoding) &&
                QueryHelper.equals(other.language, this.language) &&
                QueryHelper.equals(other.searchPath, this.searchPath) &&
                QueryHelper.equals(other.sources, this.sources) &&
                QueryHelper.equals(other.restrict, this.restrict) &&
                QueryHelper.equals(other.defaultIndex, this.defaultIndex) &&
                QueryHelper.equals(other.type, this.type) ))
            return false;

        if (other.queryTree == null && this.queryTree == null) // don't cause query parsing
            return QueryHelper.equals(other.queryString, this.queryString) &&
                   QueryHelper.equals(other.filter, this.filter);
        else // make sure we compare a parsed variant of both
            return QueryHelper.equals(other.getQueryTree(), this.getQueryTree());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode() +
               QueryHelper.combineHash(encoding,filter,language,getQueryTree(),sources,restrict,defaultIndex,type,searchPath);
    }


    public Object clone() {
        try {
            Model clone = (Model) super.clone();
            if (queryTree != null)
                clone.queryTree = this.queryTree.clone();
            if (sources !=null)
                clone.sources = new LinkedHashSet<>(this.sources);
            if (restrict !=null)
                clone.restrict = new LinkedHashSet<>(this.restrict);
            return clone;
        }
        catch (CloneNotSupportedException e) {
            throw new RuntimeException("Someone inserted a noncloneable superclass",e);
        }
    }

    public Model cloneFor(Query q)  {
        Model model = (Model) this.clone();
        model.setParent(q);
        return model;
    }

    /** returns the query owning this, never null */
    public Query getParent() { return parent; }

    /** Assigns the query owning this */
    public void setParent(Query parent) {
        if (parent==null) throw new NullPointerException("A query models owner cannot be null");
        this.parent = parent;
    }

    /** Sets the set of sources this query will search from a comma-separated string of source names */
    public void setSources(String sourceString) {
        setFromString(sourceString,sources);
    }

    /**
     * Returns the set of sources this query will search.
     * This set can be modified to change the set of sources. If all sources are to be searched, this returns
     * an empty set
     *
     * @return the set of sources to search, never null
     */
    public Set<String> getSources() { return sources; }

    /**
     * Sets the set of types (document type or search definition names) this query will search from a
     * comma-separated string of type names. This is useful to narrow a search to just a subset of the types available
     * from a sources
     */
    public void setRestrict(String restrictString) {
        setFromString(restrictString,restrict);
    }

    /**
     * Returns the set of types this query will search.
     * This set can be modified to change the set of types. If all types are to be searched, this returns
     * an empty set.
     *
     * @return the set of types to search, never null
     */
    public Set<String> getRestrict() { return restrict; }

    /** Sets the execution working on this. For internal use. */
    public void setExecution(Execution execution) {
        if (execution==this.execution) return;

        // If not already coupled, bind the trace of the new execution into the existing execution trace
        if (execution.trace().traceNode().isRoot()
            && execution.trace().traceNode() != this.execution.trace().traceNode().root()) {
            this.execution.trace().traceNode().add(execution.trace().traceNode());
        }

        this.execution = execution;
    }

    /** Sets the document database this will search - a document type */
    public void setDocumentDb(String documentDbName) {
        this.documentDbName = documentDbName;
    }

    /** Returns the name of the document db this should search, or null if not set. */
    public String getDocumentDb() { return documentDbName; }

    /** Returns the Execution working on this, or a null execution if none. For internal use. */
    public Execution getExecution() { return execution; }

    private void setFromString(String string,Set<String> set) {
        set.clear();
        for (String item : string.split(","))
            set.add(item.trim());
    }

    public static Model getFrom(Query q) {
        return (Model)q.properties().get(argumentTypeName);
    }

    public @Override String toString() {
        return "query representation [queryTree: " + queryTree + ", filter: " + filter + "]";
    }

    /** Prepares this for binary serialization. For internal use. */
    public void prepare(Ranking ranking) {
        prepareRankFeaturesFromModel(ranking);
    }

    private void prepareRankFeaturesFromModel(Ranking ranking) {
        Item root = getQueryTree().getRoot();
        if (root != null) {
            List<Item> tagged = setUniqueIDs(root);
            addLabels(tagged, ranking);
            addConnectivityRankProperties(tagged, ranking);
            addSignificances(tagged, ranking);
        }
    }

    private List<Item> setUniqueIDs(Item root) {
        List<Item> items = new ArrayList<>();
        collectTaggableItems(root, items);
        int id = 1;
        for (Item i : items) {
            TaggableItem t = (TaggableItem) i;
            t.setUniqueID(id++);
        }
        return items;
    }

    private void addLabels(List<Item> candidates, Ranking ranking) {
        for (Item candidate : candidates) {
            String label = candidate.getLabel();
            if (label != null) {
                String name = "vespa.label." + label + ".id";
                TaggableItem t = (TaggableItem) candidate;
                ranking.getProperties().put(name, String.valueOf(t.getUniqueID()));
            }
        }
    }

    private void addConnectivityRankProperties(List<Item> connectedItems, Ranking ranking) {
        for (Item link : connectedItems) {
            TaggableItem t = (TaggableItem) link;
            Item connectedTo = t.getConnectedItem();
            if (connectedTo != null && strictContains(connectedTo, connectedItems)) {
                TaggableItem t2 = (TaggableItem) connectedTo;
                String name = "vespa.term." + t.getUniqueID() + ".connexity";
                ranking.getProperties().put(name, String.valueOf(t2.getUniqueID()));
                ranking.getProperties().put(name, String.valueOf(t.getConnectivity()));
            }
        }
    }

    private void addSignificances(List<Item> candidates, Ranking ranking) {
        for (Item  candidate : candidates) {
            TaggableItem t = (TaggableItem) candidate;
            if ( ! t.hasExplicitSignificance()) continue;
            String name = "vespa.term." + t.getUniqueID() + ".significance";
            ranking.getProperties().put(name, String.valueOf(t.getSignificance()));
        }
    }

    private void collectTaggableItems(Item root, List<Item> terms) {
        if (root == null) return;

        if (root instanceof TaggableItem) {
            // This is tested before descending, as phrases are viewed
            // as leaf nodes in the ranking code in the backend
            terms.add(root);
        } else if (root instanceof CompositeItem) {
            CompositeItem c = (CompositeItem) root;
            for (Iterator<Item> i = c.getItemIterator(); i.hasNext();) {
                collectTaggableItems(i.next(), terms);
            }
        } else {} // nop
    }

    private boolean strictContains(Object needle, Collection<?> haystack) {
        for (Object pin : haystack)
            if (pin == needle) return true;
        return false;
    }


    /**
     * Set the YTrace header value to use when transmitting this model to a
     * search backend (of some kind).
     *
     * @param next string representation of header value
     * @deprecated not used, ytrace has been discontinued
     */
    @Deprecated
    public void setYTraceHeaderToNext(String next) { }

    /**
     * Get the YTrace header value to use when transmitting this model to a
     * search backend (of some kind). Returns null if no ytrace data is not
     * turned on.
     * 
     * @deprecated not used, ytrace has been discontinued
     */
    @Deprecated
    public String getYTraceHeaderToNext() {
        return null;
    }

}
