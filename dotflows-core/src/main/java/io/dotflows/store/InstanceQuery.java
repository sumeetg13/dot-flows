package io.dotflows.store;

/** Filter for listing instances; null fields are not filtered on. Newest instances come first. */
public record InstanceQuery(String processKey, InstanceRecord.Status status, String businessKey, int limit, int offset) {

    public InstanceQuery {
        limit = limit <= 0 ? 50 : Math.min(limit, 500);
        offset = Math.max(offset, 0);
    }
}
