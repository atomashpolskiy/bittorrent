/*
 * Copyright (c) 2016—2017 Andrei Tomashpolskiy and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package bt.data;

import bt.net.buffer.ByteBufferView;

import java.io.Closeable;
import java.nio.Buffer;
import java.nio.ByteBuffer;

/**
 * Storage for a single torrent file
 *
 * @since 1.0
 */
public interface StorageUnit extends Closeable {

    /**
     * Try to read a block of data into the provided buffer, starting with a given offset.
     * Maximum number of bytes to be read is determined by {@link Buffer#remaining()}.
     * <p>Storage must throw an exception if
     * <blockquote>
     * <code>offset &gt; {@link #capacity()} - buffer.remaining()</code>
     * </blockquote>
     *
     * @param buffer Buffer to read bytes into.
     *               Value returned by <b>buffer.remaining()</b> determines
     *               the maximum number of bytes to read.
     * @param offset Index to start reading from (0-based)
     * @return Actual number of bytes read
     * @since 1.0
     */
    int readBlock(ByteBuffer buffer, long offset);

    /**
     * @since 1.9
     */
    default void readBlockFully(ByteBuffer buffer, long offset) {
        int read = 0, total = 0;
        do {
            total += read;
            read = readBlock(buffer, offset + total);
        } while (read >= 0 && buffer.hasRemaining());
    }

    /**
     * Try to read a block of data into the provided array, starting with a given offset.
     * Maximum number of bytes to be read is determined by {@link Buffer#remaining()}.
     * <p>Storage must throw an exception if
     * <blockquote>
     * <code>offset &gt; {@link #capacity()} - length</code>
     * </blockquote>
     *
     * @param buffer Array to read bytes into.
     *               Array's length determines the maximum number of bytes to read.
     * @param offset Index to starting reading from (0-based)
     * @return Actual number of bytes read
     * @since 1.0
     */
    default int readBlock(byte[] buffer, long offset) {
        return readBlock(ByteBuffer.wrap(buffer), offset);
    }

    /**
     * Try to write a block of data from the provided buffer to this storage, starting with a given offset.
     * <p>Maximum number of bytes to be written is determined by {@link Buffer#remaining()}.
     * <p>Storage must throw an exception if
     * <blockquote>
     * <code>offset &gt; {@link #capacity()} - buffer.remaining()</code>
     * </blockquote>
     *
     * @param buffer Buffer containing the block of data to write to this storage.
     *               Value returned by <b>buffer.remaining()</b> determines
     *               the maximum number of bytes to write.
     * @param offset Offset in this storage's data to start writing to (0-based)
     * @return Actual number of bytes written
     * @since 1.0
     */
    int writeBlock(ByteBuffer buffer, long offset);

    /**
     * @since 1.9
     */
    default void writeBlockFully(ByteBuffer buffer, long offset) {
        int written = 0, total = 0;
        do {
            total += written;
            written = writeBlock(buffer, offset + total);
        } while (written >= 0 && buffer.hasRemaining());
    }

    /**
     * Try to write a block of data from the provided buffer to this storage, starting with a given offset.
     * <p>Maximum number of bytes to be written is determined by {@link bt.net.buffer.ByteBufferView#remaining()}.
     * <p>Storage must throw an exception if
     * <blockquote>
     * <code>offset &gt; {@link #capacity()} - buffer.remaining()</code>
     * </blockquote>
     *
     * @param buffer Buffer containing the block of data to write to this storage.
     *               Value returned by <b>buffer.remaining()</b> determines
     *               the maximum number of bytes to write.
     * @param offset Offset in this storage's data to start writing to (0-based)
     * @return Actual number of bytes written
     * @since 1.9
     */
    int writeBlock(ByteBufferView buffer, long offset);

    /**
     * @since 1.9
     */
    default void writeBlockFully(ByteBufferView buffer, long offset) {
        int written = 0, total = 0;
        do {
            total += written;
            written = writeBlock(buffer, offset + total);
        } while (written >= 0 && buffer.hasRemaining());
    }

    /**
     * Try to write a block of data to this storage, starting with a given offset.
     * <p>Maximum number of bytes to be written is determined by block's length.
     * <p>Storage must throw an exception if
     * <blockquote>
     * <code>offset &gt; {@link #capacity()} - block.length</code>
     * </blockquote>
     *
     * @param block Block of data to write to this storage.
     *              Block's length determines the maximum number of bytes to write.
     * @param offset Offset in this storage's data to start writing to (0-based)
     * @return Actual number of bytes written
     * @since 1.0
     */
    default int writeBlock(byte[] block, long offset) {
        return writeBlock(ByteBuffer.wrap(block), offset);
    }

    /**
     * Creates this empty unit in the file system
     *
     * @return true if the creation was successful
     */
    default boolean createEmpty() {
        return 0 <= writeBlock(ByteBuffer.allocate(0), 0);
    }

    /**
     * Get total maximum capacity of this storage.
     *
     * @return Total maximum capacity of this storage
     * @since 1.0
     */
    long capacity();

    /**
     * Get current amount of data in this storage.
     *
     * @return Current amount of data in this storage
     * @since 1.1
     */
    long size();
}
