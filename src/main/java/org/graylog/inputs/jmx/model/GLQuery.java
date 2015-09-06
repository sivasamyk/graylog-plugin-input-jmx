package org.graylog.inputs.jmx.model;

import java.util.List;

/**
 * Created on 6/9/15.
 */
public class GLQuery {
    String object;
    List<GLAttribute> attributes;

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public List<GLAttribute> getAttributes() {
        return attributes;
    }

    public void setAttributes(List<GLAttribute> attributes) {
        this.attributes = attributes;
    }
}
