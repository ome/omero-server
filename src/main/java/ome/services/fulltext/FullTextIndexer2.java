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
import java.util.concurrent.atomic.AtomicReference;

import ome.model.IObject;
import ome.model.meta.EventLog;
import ome.util.DetailsFieldBridge;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.SetMultimap;

import org.apache.commons.lang.StringUtils;
import org.hibernate.CacheMode;
import org.hibernate.FlushMode;
import org.hibernate.Hibernate;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.jdbc.Work;
import org.hibernate.search.FullTextSession;
import org.hibernate.search.Search;
import org.hibernate.search.bridge.FieldBridge;
import org.python.google.common.base.Splitter;
import org.quartz.DateBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.quartz.MethodInvokingJobDetailFactoryBean;

/**
 * An indexer bean replacing the 5.4 full-text thread with adequate functionality.
 * Exists as a stand-in while Hibernate / Spring upgrade issues remain unresolved.
 * @author m.t.b.carroll@dundee.ac.uk
 * @since 5.5.0
 */
public class FullTextIndexer2 {

    private static final Logger LOGGER = LoggerFactory.getLogger(FullTextIndexer2.class);

    private static enum Step {
        /* Determine from the event log what next to consider indexing. */
        PREPARE,
        /* Index a batch of model objects. */
        INDEX,
        /* Purge a batch of model objects. */
        PURGE,
        /* Note the progress made by the indexer. */
        NOTE;
    }

    private static enum Event {
        /* Time to wait from startup to first indexing run. Allows the services adequate initialization time. */
        STARTUP,
        /* How long to wait to try to relock the field bridge. */
        FIELD_BRIDGE_CONTENTION,
        /* How long to wait after finding nothing new to index. */
        NOTHING_NEW_TO_INDEX;
    }

    /**
     * Configures durations related to {@link Event} values.
     * @param reason the event that occurred
     * @return when next to act
     */
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

    /**
     * Maximum number of event log entries to process in one transaction.
     */
    private static final int BATCH_SIZE = 256;

    /**
     * An identifier for this indexer's Quartz jobs.
     */
    private static final String JOB_GROUP = FullTextIndexer2.class.getSimpleName();

    /**
     * Which model object types to index.
     */
    private final Set<Class<? extends IObject>> includeTypes;

    private final Scheduler scheduler;
    private final SessionFactory sessionFactory;
    private final FieldBridge bridge;
    private final String countKey;

    private boolean isIndexerDisabled;

    private final AtomicReference<JobKey[]> jobs = new AtomicReference<>();

    private final SetMultimap<String, Long> toIndex = HashMultimap.create();
    private final SetMultimap<String, Long> toPurge = HashMultimap.create();

    private long eventLogId = -1;

    /**
     * Construct a new indexer.
     * @param scheduler the Quartz scheduler for the indexing jobs
     * @param sessionFactory the Hibernate session factory
     * @param bridge the field bridge to set when indexing
     * @param countKey the name of the configuration key for tracking progress through the event log
     * @param includeTypesList the names of the model object classes to index, comma-separated
     */
    public FullTextIndexer2(Scheduler scheduler, SessionFactory sessionFactory, FieldBridge bridge, String countKey,
            String includeTypesList) {
        this.scheduler = scheduler;
        this.sessionFactory = sessionFactory;
        this.bridge = bridge;
        this.countKey = countKey;

        final ImmutableSet.Builder<Class<? extends IObject>> includeTypes = ImmutableSet.builder();
        for (final String className : Splitter.on(',').trimResults().split(includeTypesList)) {
            try {
                includeTypes.add(Class.forName(className).asSubclass(IObject.class));
            } catch (ClassCastException | ReflectiveOperationException e) {
                throw new IllegalArgumentException("include types must be a comma-separated list of model object types", e);
            }
        }
        this.includeTypes = includeTypes.build();
    }

    /**
     * @param cronExpression the cron expression configured for triggering indexing
     */
    public void setCronExpression(String cronExpression) {
        this.isIndexerDisabled = StringUtils.isBlank(cronExpression);
    }

    // HELPERS FOR SCHEDULING JOBS //

    /**
     * Schedule a job for execution.
     * @param step which job to schedule
     * @param trigger the execution trigger with the start time set
     * @param message a description of the job in terms of when it executes, for the log
     */
    private void register(Step step, TriggerBuilder<Trigger> trigger, String message) {
        try {
            scheduler.scheduleJob(trigger.forJob(jobs.get()[step.ordinal()]).build());
            LOGGER.debug("registered job {} for {}", step, message);
        } catch (NullPointerException npe) {
            LOGGER.debug("indexer is not running: not registering job {}", step);
        } catch (Throwable t) {
            LOGGER.error("failed to register job {} so indexing may have stopped", step, t);
        }
    }

    /**
     * Schedule a job for immediate execution.
     * @param step which job to schedule
     */
    private void register(Step step) {
        register(step, TriggerBuilder.newTrigger().startNow(), "immediate execution");
    }

    /**
     * Schedule a job for delayed execution.
     * @param step which job to schedule
     * @param reason the event that occurred to prompt the delay
     */
    private void register(Step step, Event reason) {
        final Date when = getWhen(reason);
        register(step, TriggerBuilder.newTrigger().startAt(when), "execution at " + when);
    }

    // LIFECYCLE METHODS INVOKED BY SPRING //

    /**
     * Start the indexer. Ongoing operation occurs via Quartz.
     */
    public void start() {
        if (isIndexerDisabled) {
            LOGGER.info("not starting indexer: the configured cron expression is blank");
            return;
        }
        final JobKey[] newJobs = new JobKey[Step.values().length];
        if (!jobs.compareAndSet(null, newJobs)) {
            LOGGER.warn("not starting indexer: it is already running");
            return;
        }
        LOGGER.info("starting indexer");
        try {
            for (final Step step : Step.values()) {
                final MethodInvokingJobDetailFactoryBean factory = new MethodInvokingJobDetailFactoryBean();
                factory.setGroup(JOB_GROUP);
                factory.setName(step.toString());
                factory.setTargetObject(this);
                factory.setTargetMethod(step.name().toLowerCase());
                factory.setConcurrent(false);
                factory.afterPropertiesSet();
                final JobDetail jobDetail = factory.getObject();
                final JobKey job = jobDetail.getKey();
                newJobs[step.ordinal()] = job;
                LOGGER.debug("adding job {}", job);
                scheduler.addJob(jobDetail, false);
            }
            register(Step.PREPARE, Event.STARTUP);
        } catch (Throwable t) {
            LOGGER.error("failed to start indexer", t);
        }
    }

    /**
     * Stop the indexer.
     */
    public void stop() {
        final JobKey[] oldJobs = jobs.getAndSet(null);
        if (oldJobs == null) {
            if (isIndexerDisabled) {
                LOGGER.info("not stopping indexer: the configured cron expression is blank");
            } else {
                LOGGER.warn("not stopping indexer: it is not running");
            }
            return;
        }
        try {
            for (final JobKey job : oldJobs) {
                LOGGER.debug("deleting job {}", job);
                scheduler.deleteJob(job);
            }
        } catch (Throwable t) {
            LOGGER.error("failed to stop indexer promptly", t);
        }
        LOGGER.info("stopped indexer");
    }

    // STEPS OF INDEXING LAUNCHED BY QUARTZ //

    /**
     * Query the database for new event log entries indicating model objects to process.
     */
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
                LOGGER.debug("no new event log entries");
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
                register(Step.INDEX);
            } else if (!toPurge.isEmpty()) {
                register(Step.PURGE);
            } else {
                register(Step.PREPARE, Event.NOTHING_NEW_TO_INDEX);
            }
        } catch (Throwable t) {
            LOGGER.error("failed to continue indexer", t);
        }
    }

    /**
     * Index a batch of model objects.
     */
    public void index() {
        LOGGER.info("indexing objects: count = {}", toIndex.size());
        if (DetailsFieldBridge.tryLock()) {
            DetailsFieldBridge.setFieldBridge(bridge);
        } else {
            LOGGER.info("failed to lock field bridge so will wait awhile");
            try {
                register(Step.INDEX, Event.FIELD_BRIDGE_CONTENTION);
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
                    @SuppressWarnings("unchecked")
                    final Class<? extends IObject> entityClass = Hibernate.getClass(entity);
                    boolean isInclude = false;
                    for (final Class<? extends IObject> includeType : includeTypes) {
                        if (includeType.isAssignableFrom(entityClass)) {
                            isInclude = true;
                            break;
                        }
                    }
                    if (isInclude) {
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
                register(Step.PURGE);
            } else {
                register(Step.NOTE);
            }
        } catch (Throwable t) {
            LOGGER.error("failed to continue indexer", t);
        }
    }

    /**
     * Purge a batch of model objects.
     */
    public void purge() {
        LOGGER.info("purging objects: count = {}", toPurge.size());
        if (DetailsFieldBridge.tryLock()) {
            DetailsFieldBridge.setFieldBridge(bridge);
        } else {
            LOGGER.info("failed to lock field bridge so will wait awhile");
            try {
                register(Step.PURGE, Event.FIELD_BRIDGE_CONTENTION);
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
            register(Step.NOTE);
        } catch (Throwable t) {
            LOGGER.error("failed to continue indexer", t);
        }
    }

    /**
     * Note which event log entries were processed.
     */
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
            register(Step.PREPARE);
        } catch (Throwable t) {
            LOGGER.error("failed to continue indexer", t);
        }
    }
}
