package dev.martinkm.strata;

import dev.martinkm.strata.wal.WriteAheadLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A group of puts and deletes applied by {@link StrataStore#write} as one unit.
 *
 * <p>The batch is durable and visible all at once: it goes to the write-ahead log
 * as a single record under one CRC and one fsync, so a crash during the write
 * loses every operation in it or none, and it is applied to the memtable under the
 * writer's lock, so a reader that arrives in between sees the store before the
 * batch or after it. Operations apply in the order they were added, so a later
 * write of the same key wins over an earlier one in the same batch.
 *
 * <p>A batch copies each key and value when it is added, so a caller may reuse
 * its arrays afterwards. It is not thread safe; build it on one thread and hand
 * it to the store.
 */
public final class WriteBatch {

    private final List<WriteAheadLog.Record> records = new ArrayList<>();

    /** Adds a put of {@code value} under {@code key}. Returns this batch. */
    public WriteBatch put(byte[] key, byte[] value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        records.add(new WriteAheadLog.Record(WriteAheadLog.PUT, key.clone(), value.clone()));
        return this;
    }

    /** Adds a delete of {@code key}. Returns this batch. */
    public WriteBatch delete(byte[] key) {
        Objects.requireNonNull(key, "key");
        records.add(new WriteAheadLog.Record(WriteAheadLog.DELETE, key.clone(), null));
        return this;
    }

    /** The number of operations added so far. */
    public int size() {
        return records.size();
    }

    /** True if nothing has been added. */
    public boolean isEmpty() {
        return records.isEmpty();
    }

    /** Removes every operation, so the batch can be filled again. */
    public void clear() {
        records.clear();
    }

    /** The operations in the order they were added. */
    List<WriteAheadLog.Record> records() {
        return Collections.unmodifiableList(records);
    }
}
