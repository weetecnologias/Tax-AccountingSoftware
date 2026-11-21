package com.taxpilot.documents.storage;

/** Stores opaque (already encrypted) blobs under generated keys. */
public interface ObjectStorage {

    void put(String key, byte[] data);

    byte[] get(String key);

    void delete(String key);

    /** Short label for logs and health information, e.g. "local" or "s3". */
    String kind();
}
