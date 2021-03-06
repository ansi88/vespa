// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.config.model.application.provider;

import com.yahoo.config.FileReference;
import com.yahoo.config.application.api.FileRegistry;
import com.yahoo.net.HostName;
import net.jpountz.xxhash.XXHashFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A file registry for testing, and, it seems, doubling as a null registry in some code paths.
 *
 * @author Tony Vaagenes
 * @author hmusum
 */
public class MockFileRegistry implements FileRegistry {
    private final List<Entry> entries = new ArrayList<>();

    public FileReference addFile(String relativePath) {
        FileReference fileReference = new FileReference(relativePath);
        entries.add(new Entry(relativePath, fileReference));
        return fileReference;
    }

    @Override
    public String fileSourceHost() { return HostName.getLocalhost(); }

    public List<Entry> export() { return entries; }

    @Override
    public FileReference addUri(String uri) {
        throw new IllegalArgumentException("FileReference addUri(String uri) is not implemented for " + getClass().getCanonicalName());
    }

    @Override
    public FileReference addBlob(ByteBuffer blob) {
        long blobHash = XXHashFactory.fastestJavaInstance().hash64().hash(blob, 0);
        String relativePath = Long.toHexString(blobHash) + ".blob";
        FileReference fileReference = new FileReference(relativePath);
        entries.add(new Entry(relativePath, fileReference));
        return fileReference;
    }

}
