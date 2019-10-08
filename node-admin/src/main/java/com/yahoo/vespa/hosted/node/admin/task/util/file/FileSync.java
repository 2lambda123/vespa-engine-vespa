// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

package com.yahoo.vespa.hosted.node.admin.task.util.file;

import com.yahoo.vespa.hosted.node.admin.component.TaskContext;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.logging.Logger;

import static com.yahoo.yolean.Exceptions.uncheck;

/**
 * Class to minimize resource usage with repetitive and mostly identical, idempotent, and
 * mutating file operations, e.g. setting file content, setting owner, etc.
 *
 * Only changes to the file is logged.
 *
 * @author hakohall
 */
// @ThreadUnsafe
public class FileSync {
    private static final Logger logger = Logger.getLogger(FileSync.class.getName());

    private final UnixPath path;
    private final FileContentCache contentCache;

    public FileSync(Path path) {
        this.path = new UnixPath(path);
        this.contentCache = new FileContentCache(this.path);
    }

    public boolean convergeTo(TaskContext taskContext, PartialFileData partialFileData) {
        return convergeTo(taskContext, partialFileData, false);
    }

    /**
     * CPU, I/O, and memory usage is optimized for repeated calls with the same arguments.
     *
     * @param atomicWrite Whether to write updates to a temporary file in the same directory, and atomically move it
     *                    to path. Ensures the file cannot be read while in the middle of writing it.
     * @return true if the system was modified: content was written, or owner was set, etc.
     *         system is only modified if necessary (different).
     */
    public boolean convergeTo(TaskContext taskContext, PartialFileData partialFileData, boolean atomicWrite) {
        FileAttributesCache currentAttributes = new FileAttributesCache(path);

        boolean modifiedSystem = maybeUpdateContent(taskContext, partialFileData.getContent(), currentAttributes, atomicWrite);

        AttributeSync attributeSync = new AttributeSync(path.toPath()).with(partialFileData);
        modifiedSystem |= attributeSync.converge(taskContext, currentAttributes);

        return modifiedSystem;
    }

    private boolean maybeUpdateContent(TaskContext taskContext,
                                       Optional<byte[]> content,
                                       FileAttributesCache currentAttributes,
                                       boolean atomicWrite) {
        if (!content.isPresent()) {
            return false;
        }

        if (!currentAttributes.exists()) {
            taskContext.recordSystemModification(logger, "Creating file " + path);
            path.createParents();
            writeBytes(content.get(), atomicWrite);
            contentCache.updateWith(content.get(), currentAttributes.forceGet().lastModifiedTime());
            return true;
        }

        if (Arrays.equals(content.get(), contentCache.get(currentAttributes.get().lastModifiedTime()))) {
            return false;
        } else {
            taskContext.recordSystemModification(logger, "Patching file " + path);
            writeBytes(content.get(), atomicWrite);
            contentCache.updateWith(content.get(), currentAttributes.forceGet().lastModifiedTime());
            return true;
        }
    }

    private void writeBytes(byte[] content, boolean atomic) {
        if (atomic) {
            String tmpPath = path.toPath().toString() + ".FileSyncTmp";
            new UnixPath(path.toPath().getFileSystem().getPath(tmpPath))
                    .writeBytes(content)
                    .atomicMove(path.toPath());
        } else {
            path.writeBytes(content);
        }
    }
}
