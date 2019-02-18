/*
 * Copyright (C) 2008-2019 University of Dundee & Open Microscopy Environment.
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package ome.services.fulltext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ome.model.IObject;
import ome.model.meta.EventLog;
import ome.util.DetailsFieldBridge;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.SetMultimap;

import org.hibernate.CacheMode;
import org.hibernate.FlushMode;
import org.hibernate.Hibernate;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.engine.SessionFactoryImplementor;
import org.hibernate.jdbc.Work;
import org.hibernate.search.FullTextSession;
import org.hibernate.search.Search;
import org.hibernate.search.bridge.FieldBridge;
import org.quartz.DateBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.quartz.MethodInvokingJobDetailFactoryBean;

public class FullTextIndexer2 {

    private static final Logger LOGGER = LoggerFactory.getLogger(FullTextIndexer2.class);

    private static enum Event {
        STARTUP, FIELD_BRIDGE_CONTENTION, NOTHING_NEW_TO_INDEX;
    }

    private static Date getWhen(Event reason) {
        switch (reason) {
        case STARTUP:
            return DateBuilder.futureDate(1, DateBuilder.IntervalUnit.MINUTE);
        case FIELD_BRIDGE_CONTENTION:
            return DateBuilder.futureDate(10, DateBuilder.IntervalUnit.SECOND);
        case NOTHING_NEW_TO_INDEX:
            return DateBuilder.futureDate(2, DateBuilder.IntervalUnit.SECOND);
        default:
            throw new IllegalArgumentException("cannot provide date for: " + reason);
        }
    }

    private static final int BATCH_SIZE = 256;

    private static final String JOB_GROUP = "full-text indexer";

    private static final Set<Class<? extends IObject>> INCLUDE_TYPES =
            ImmutableSet.of(ome.model.core.Image.class,ome.model.containers.Project.class,
                    ome.model.containers.Dataset.class, ome.model.screen.Plate.class, ome.model.screen.Screen.class,
                    ome.model.screen.PlateAcquisition.class, ome.model.screen.Well.class);

    private final Scheduler scheduler;
    private final SessionFactoryImplementor sessionFactory;
    private final FieldBridge bridge;
    private final String countKey;

    private final SetMultimap<String, Long> toIndex = HashMultimap.create();
    private final SetMultimap<String, Long> toPurge = HashMultimap.create();

    private long eventLogId = -1;

    private int jobId = 0;

    public FullTextIndexer2(Scheduler scheduler, SessionFactoryImplementor sessionFactory, FieldBridge bridge, String countKey) {
        this.scheduler = scheduler;
        this.sessionFactory = sessionFactory;
        this.bridge = bridge;
        this.countKey = countKey;
    }

    private void register(String method) {
        try {
            scheduler.scheduleJob(createJob(method), TriggerBuilder.newTrigger().startNow().build());
            LOGGER.debug("registered job for immediate execution: {}", method);
        } catch (Throwable t) {
            LOGGER.error("failed to register immediate job so indexing may have stopped", t);
        }
    }

    private void register(String method, Event reason) {
        final Date when = getWhen(reason);
        try {
            scheduler.scheduleJob(createJob(method), TriggerBuilder.newTrigger().startAt(when).build());
            LOGGER.debug("registered job for execution at {}: {}", when, method);
        } catch (Throwable t) {
            LOGGER.error("failed to register scheduled job so indexing may have stopped", t);
        }
    }

    private JobDetail createJob(String method) throws ReflectiveOperationException {
        final MethodInvokingJobDetailFactoryBean factory = new MethodInvokingJobDetailFactoryBean();
        factory.setGroup(JOB_GROUP);
        factory.setName(method + "-" + jobId++);
        factory.setTargetObject(this);
        factory.setTargetMethod(method);
        factory.setConcurrent(false);
        factory.afterPropertiesSet();
        return factory.getObject();
    }

    public void start() {
        LOGGER.info("starting indexer");
        try {
            register("prepare", Event.STARTUP);
        } catch (Throwable t) {
            LOGGER.error("failed to start indexer", t);
        }
    }

    public void prepare() {
        final Session session = sessionFactory.openSession();
        try {
            LOGGER.debug("adding any new REINDEX entries");
            session.setFlushMode(FlushMode.COMMIT);
            Transaction transaction = session.beginTransaction();
            session.doWork(new Work() {
                @Override
                public void execute(Connection connection) throws SQLException {
                    final Statement stmt = connection.createStatement();
                    try {
                        stmt.execute("SELECT updated_entities_note_reindex()");
                    } finally {
                        stmt.close();
                    }
                }
            });
            transaction.commit();
            LOGGER.debug("reviewing event log for new entries");
            session.setFlushMode(FlushMode.MANUAL);
            transaction = session.beginTransaction();
            session.doWork(new Work() {
                @Override
                public void execute(Connection connection) throws SQLException {
                    final PreparedStatement stmt = connection.prepareStatement("SELECT value FROM configuration WHERE name = ?");
                    stmt.setString(1, countKey);
                    try {
                        final ResultSet results = stmt.executeQuery();
                        if (results.next()) {
                            eventLogId = Long.parseLong(results.getString(1));
                        }
                    } finally {
                        stmt.close();
                    }
                }
            });
            final String hql = "FROM EventLog WHERE id > :id ORDER BY id";
            final Query query = session.createQuery(hql);
            query.setMaxResults(BATCH_SIZE);
            query.setParameter("id", eventLogId);
            @SuppressWarnings("unchecked")
            final List<EventLog> logEntries = (List<EventLog>) query.list();
            if (logEntries.isEmpty()) {
                LOGGER.debug("no new event log entries", logEntries.size());
            } else {
                LOGGER.debug("reviewing {} event log entries", logEntries.size());
                for (final EventLog logEntry : logEntries) {
                    switch (logEntry.getAction()) {
                    case "INSERT":
                    case "UPDATE":
                    case "REINDEX":
                        toIndex.put(logEntry.getEntityType(), logEntry.getEntityId());
                        break;
                    case "DELETE":
                        toIndex.remove(logEntry.getEntityType(), logEntry.getEntityId());
                        toPurge.put(logEntry.getEntityType(), logEntry.getEntityId());
                        break;
                    }
                    eventLogId = logEntry.getId();
                }
            }
            transaction.rollback();
        } finally {
            session.close();
        }
        try {
            if (!toIndex.isEmpty()) {
                register("index");
            } else if (!toPurge.isEmpty()) {
                register("purge");
            } else {
                register("prepare", Event.NOTHING_NEW_TO_INDEX);
            }
        } catch (Throwable t) {
            LOGGER.error("failed to continue indexer", t);
        }
    }

    public void index() {
        LOGGER.info("indexing objects: count = {}", toIndex.size());
        if (DetailsFieldBridge.tryLock()) {
            DetailsFieldBridge.setFieldBridge(bridge);
        } else {
            LOGGER.info("failed to lock field bridge so will wait awhile");
            try {
                register("index", Event.FIELD_BRIDGE_CONTENTION);
            } catch (Throwable t) {
                LOGGER.error("failed to continue indexer", t);
            }
            return;
        }
        final ParserSession parserSession = new ParserSession();
        final Session session = sessionFactory.openSession();
        try {
            DetailsFieldBridge.setFieldBridge(bridge);
            final FullTextSession fullTextSession = Search.getFullTextSession(session);
            fullTextSession.setCacheMode(CacheMode.IGNORE);
            fullTextSession.setFlushMode(FlushMode.COMMIT);
            final Transaction transaction = fullTextSession.beginTransaction();
            for (final Map.Entry<String, Collection<Long>> typeAndIds : toIndex.asMap().entrySet()) {
                final String entityType = typeAndIds.getKey();
                final Collection<Long> entityIds = typeAndIds.getValue();
                final String hql = "FROM " + entityType + " WHERE id IN (:ids)";
                final Query query = fullTextSession.createQuery(hql);
                query.setParameterList("ids", entityIds);
                query.setReadOnly(true);
                for (final Object entity : query.list()) {
                    if (INCLUDE_TYPES.contains(Hibernate.getClass(entity))) {
                        LOGGER.debug("indexing {}:{}", entityType, ((IObject) entity).getId());
                        fullTextSession.index(entity);
                    } else {
                        LOGGER.debug("skipping {}:{}", entityType, ((IObject) entity).getId());
                    }
                }
            }
            transaction.commit();
            toIndex.clear();
        } finally {
            DetailsFieldBridge.unlock();
            session.close();
            parserSession.closeParsedFiles();
        }
        try {
            if (!toPurge.isEmpty()) {
                register("purge");
            } else {
                register("note");
            }
        } catch (Throwable t) {
            LOGGER.error("failed to continue indexer", t);
        }
    }

    public void purge() {
        LOGGER.info("purging objects: count = {}", toPurge.size());
        if (DetailsFieldBridge.tryLock()) {
            DetailsFieldBridge.setFieldBridge(bridge);
        } else {
            LOGGER.info("failed to lock field bridge so will wait awhile");
            try {
                register("purge", Event.FIELD_BRIDGE_CONTENTION);
            } catch (Throwable t) {
                LOGGER.error("failed to continue indexer", t);
            }
            return;
        }
        final Session session = sessionFactory.openSession();
        try {
            DetailsFieldBridge.setFieldBridge(bridge);
            final FullTextSession fullTextSession = Search.getFullTextSession(session);
            fullTextSession.setCacheMode(CacheMode.IGNORE);
            fullTextSession.setFlushMode(FlushMode.COMMIT);
            final Transaction transaction = fullTextSession.beginTransaction();
            for (final Map.Entry<String, Collection<Long>> typeAndIds : toPurge.asMap().entrySet()) {
                final String entityType = typeAndIds.getKey();
                final Collection<Long> entityIds = typeAndIds.getValue();
                final Class<? extends IObject> entityClass;
                try {
                    entityClass = Class.forName(entityType).asSubclass(IObject.class);
                } catch (ClassNotFoundException e) {
                    LOGGER.error("unknown entity type in event log: {}", entityType, e);
                    continue;
                }
                for (final Long entityId :entityIds) {
                    LOGGER.debug("purging {}:{}", entityType, entityId);
                    fullTextSession.purge(entityClass, entityId);
                }
            }
            transaction.commit();
            toPurge.clear();
        } finally {
            DetailsFieldBridge.unlock();
            session.close();
        }
        try {
            register("note");
        } catch (Throwable t) {
            LOGGER.error("failed to continue indexer", t);
        }
    }

    public void note() {
        LOGGER.debug("noting event log entries as processed");
        final Session session = sessionFactory.openSession();
        try {
            session.setFlushMode(FlushMode.COMMIT);
            final Transaction transaction = session.beginTransaction();
            session.doWork(new Work() {
                @Override
                public void execute(Connection connection) throws SQLException {
                    PreparedStatement stmt = connection.prepareStatement("UPDATE configuration SET value = ? WHERE name = ?");
                    stmt.setString(1, Long.toString(eventLogId));
                    stmt.setString(2, countKey);
                    try {
                        stmt.execute();
                        if (stmt.getUpdateCount() == 0) {
                            stmt.close();
                            stmt = connection.prepareStatement("INSERT INTO configuration (name, value) VALUES (?, ?)");
                            stmt.setString(1, countKey);
                            stmt.setString(2, Long.toString(eventLogId));
                            stmt.execute();
                        }
                    } finally {
                        stmt.close();
                    }
                }
            });
            transaction.commit();
        } finally {
            session.close();
        }
        try {
            register("prepare");
        } catch (Throwable t) {
            LOGGER.error("failed to continue indexer", t);
        }
    }
}
