package dev.martinkm.strata;

import java.nio.file.Path;

/**
 * Thrown when a data block read back from an SSTable fails its CRC check.
 *
 * The block's stored checksum did not match the one computed over the bytes on
 * disk, so those bytes are corrupt and the store refuses to hand back a value it
 * cannot trust. The message names the table and the block offset, which is enough
 * to point at the exact bytes rather than leaving a silent wrong answer. It is
 * unchecked so it travels the read path the same way an I/O failure does.
 */
public final class ChecksumException extends RuntimeException {

    ChecksumException(Path table, long offset) {
        super("sstable block checksum mismatch in " + table + " at offset " + offset);
    }
}
