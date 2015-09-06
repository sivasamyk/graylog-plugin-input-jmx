package org.graylog.inputs.jmx.model;

import org.graylog.inputs.jmx.model.GLQuery;

import java.util.List;

/**
 * Created on 6/9/15.
 */
public class GLQueryConfig {
    private String type;
    private List<GLQuery> queries;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<GLQuery> getQueries() {
        return queries;
    }

    public void setQueries(List<GLQuery> queries) {
        this.queries = queries;
    }
}
