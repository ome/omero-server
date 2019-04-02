/*
 * Copyright (C) 2019 University of Dundee & Open Microscopy Environment.
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package ome.services.search;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

import javax.sql.DataSource;

import ome.model.IObject;
import ome.model.enums.EventType;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The index watcher writes REINDEX entries to the event log and waits for the indexing thread to process them.
 * It operates within Blitz but outside its transaction handling so that the communication with the Indexer thread
 * can occur while the update service waits inside the {@link ome.logic.UpdateImpl#indexObject(IObject)} transaction.
 * @author m.t.b.carroll@dundee.ac.uk
 * @since 5.5.0
 */
public class IndexWatcher {

    /**
     * A simple struct for noting the event log entry that must be created.
     * @author m.t.b.carroll@dundee.ac.uk
     * @since 5.5.0
     */
    private class ToIndex {
        final Class<? extends IObject> objectType;
        final long objectId;
        final long userId;
        final long groupId;
        final long sessionId;
        final Semaphore semaphore = new Semaphore(0);

        /**
         * Construct a note of an event log entry.
         * @param object the object to index, may be unloaded
         * @param userId the user to record for the reindexing request
         * @param groupId the group to record for the reindexing request
         * @param sessionId the session to record for the reindexing request
         */
        ToIndex(IObject object, long userId, long groupId, long sessionId) {
            this.objectType = object.getClass();
            this.objectId = object.getId();
            this.userId = userId;
            this.groupId = groupId;
            this.sessionId = sessionId;
        }
    }

    private final List<ToIndex> objectsToIndex = new ArrayList<>();
    private final List<Function<Long, Boolean>> eventLogFilters = new ArrayList<>();

    private final JdbcTemplate dataSource;
    private final String[] countKey;
    private final long fullTextType;

    /**
     * Construct a new index watcher.
     * @param dataSource the data source to be used for JDBC access to the database
     * @param countKey the name of the configuration key for tracking progress through the event log
     */
    public IndexWatcher(DataSource dataSource, String countKey) {
        this.dataSource = new JdbcTemplate(dataSource);
        this.countKey = new String[] {countKey};

        /* Events will be of the FullText type. */
        this.fullTextType = this.dataSource.queryForObject("SELECT id FROM eventtype WHERE value = ?",
                new String[] {EventType.VALUE_FULL_TEXT}, Long.class);
    }

    /**
     * Write the {@link ome.model.meta.Event} and {@link ome.model.meta.EventLog} instances that trigger indexing.
     */
    private void createLogEntries() {
        for (final ToIndex object : objectsToIndex) {
            final String status = UUID.randomUUID().toString();
            dataSource.update("INSERT INTO event (id, type, experimenter, experimentergroup, session, time, permissions, status) " +
                    "VALUES (ome_nextval('seq_event'), ?, ?, ?, ?, NOW(), -35, ?)",
                    fullTextType, object.userId, object.groupId, object.sessionId, status);
            final long eventId = this.dataSource.queryForObject("SELECT id FROM event WHERE status = ?",
                    new String[] {status}, Long.class);
            dataSource.update("UPDATE event SET status = NULL WHERE id = ?", eventId);
            dataSource.update("INSERT INTO eventlog (id, action, entitytype, entityid, event, permissions) " +
                    "VALUES (ome_nextval('seq_eventlog'), 'REINDEX', ?, ?, ?, -35)",
                    object.objectType.getName(), object.objectId, eventId);
        }
    }

    /**
     * Note how to respond to the progress of the Indexer thread.
     */
    private void createFilters() {
        final long latestId = dataSource.queryForObject("SELECT id FROM eventlog WHERE action = 'REINDEX' ORDER BY id DESC LIMIT 1",
                Long.class);
        eventLogFilters.add(new Function<Long, Boolean>() {
            /* Proceed no further until the indexer has caught up with the event log as it is now. */
            @Override
            public Boolean apply(Long currentId) {
                return currentId >= latestId;
            }
        });
        for (final ToIndex object : objectsToIndex) {
            eventLogFilters.add(new Function<Long, Boolean>() {
                /* The object has been indexed so free its semaphore. */
                @Override
                public Boolean apply(Long currentId) {
                    object.semaphore.release();
                    return true;
                }
            });
        }
        objectsToIndex.clear();
    }

    /**
     * Respond to the progress of the Indexer thread.
     */
    private void applyFilters() {
        final long currentId;
        try {
            final String value = dataSource.queryForObject("SELECT value FROM configuration WHERE name = ?", countKey,
                    String.class);
            currentId = Long.valueOf(value);
        } catch (EmptyResultDataAccessException | NullPointerException | NumberFormatException e) {
            return;
        }
        final Iterator<Function<Long, Boolean>> entryIter = eventLogFilters.iterator();
        while (entryIter.hasNext()) {
            final Function<Long, Boolean> entry = entryIter.next();
            if (entry.apply(currentId)) {
                /* The filter now passes and can be dispensed with. */
                entryIter.remove();
            } else {
                /* Earlier filters block later ones so do not check any more. */
                break;
            }
        }
    }

    /**
     * Check for new objects to index and for progress with indexing past objects.
     */
    public synchronized void poll() {
        if (!objectsToIndex.isEmpty()) {
            /* There are new objects to index. */
            createLogEntries();
            createFilters();
        }
        if (!eventLogFilters.isEmpty()) {
            /* Objects still await indexing. */
            applyFilters();
        }
    }

    /**
     * Index the given object.
     * @param object the object to index, may be unloaded
     * @param userId the user to record for the reindexing request
     * @param groupId the group to record for the reindexing request
     * @param sessionId the session to record for the reindexing request
     * @return a semaphore that is released only after the indexer has processed the request
     */
    public synchronized Semaphore indexObject(IObject object, long userId, long groupId, long sessionId) {
        final ToIndex objectToIndex = new ToIndex(object, userId, groupId, sessionId);
        objectsToIndex.add(objectToIndex);
        return objectToIndex.semaphore;
    }
}
