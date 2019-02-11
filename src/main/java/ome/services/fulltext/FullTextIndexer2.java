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

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.hibernate.CacheMode;
import org.hibernate.Query;
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
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.transaction.annotation.Transactional;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.SetMultimap;

import ome.api.IConfig;
import ome.api.IQuery;
import ome.model.IObject;
import ome.model.meta.EventLog;
import ome.model.meta.Session;
import ome.parameters.Parameters;
import ome.services.sessions.SessionManager;
import ome.services.util.Executor;
import ome.system.Login;
import ome.system.Principal;
import ome.system.Roles;
import ome.system.ServiceFactory;
import ome.util.DetailsFieldBridge;

public class FullTextIndexer2 {

    private static final Logger LOGGER = LoggerFactory.getLogger(FullTextIndexer2.class);

    private final Date DELAY_FOR_STARTUP = DateBuilder.futureDate(1, DateBuilder.IntervalUnit.MINUTE);
    private final Date DELAY_FOR_FIELD_BRIDGE_CONTENTION = DateBuilder.futureDate(10, DateBuilder.IntervalUnit.SECOND);
    private final Date DELAY_FOR_NOTHING_NEW_TO_INDEX = DateBuilder.futureDate(2, DateBuilder.IntervalUnit.SECOND);

    private static final ImmutableMap<String, String> ALL_GROUPS_CONTEXT = ImmutableMap.of(Login.OMERO_GROUP, "-1");
    private static final int BATCH_SIZE = 256;

    private static final String JOB_GROUP = "full-text indexer";

    private final Scheduler scheduler;
    private final Executor executor;
    private final Principal principal;
    private final FieldBridge bridge;
    private final String countKey;

    private final SetMultimap<String, Long> toIndex = HashMultimap.create();
    private final SetMultimap<String, Long> toPurge = HashMultimap.create();

    private long eventLogId;

    private int jobId = 0;

    public FullTextIndexer2(Scheduler scheduler, Executor executor, FieldBridge bridge, SessionManager sessionManager, Roles roles,
            String countKey) {
        this.scheduler = scheduler;
        this.executor = executor;
        this.bridge = bridge;
        this.countKey = countKey;

        final Principal rootIndexer = new Principal(roles.getRootName(), roles.getSystemGroupName(), "FullText");
        final Session session = sessionManager.createWithAgent(rootIndexer, getClass().getSimpleName(), null);
        this.principal = new Principal(session.getUuid(), rootIndexer.getGroup(), rootIndexer.getEventType());
    }

    public void setSchedulerFactory(SchedulerFactoryBean schedulerFactory) {
    }

    private void register(String method) {
        try {
            scheduler.scheduleJob(createJob(method), TriggerBuilder.newTrigger().startNow().build());
            LOGGER.debug("registered job for immediate execution: {}", method);
        } catch (Throwable t) {
            LOGGER.error("failed to register immediate job so indexing may have stopped", t);
        }
    }

    private void register(String method, Date when) {
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
        LOGGER.debug("starting indexer");
        try {
            register("prepare", DELAY_FOR_STARTUP);
        } catch (Throwable t) {
            LOGGER.error("failed to start indexer", t);
        }
    }

    public void prepare() {
        LOGGER.debug("adding any new REINDEX entries");
        executor.execute(principal, new Executor.SimpleWork<Object>(this, "prepare") {
            @Override
            @Transactional(readOnly = false)
            public Object doWork(org.hibernate.Session session, ServiceFactory services) {
                getSqlAction().refreshEventLogFromUpdatedAnnotations();
                return null;
            }
        });
        try {
            register("gather");
        } catch (Throwable t) {
            LOGGER.error("failed to continue indexer", t);
        }
    }

    public void gather() {
        LOGGER.debug("reviewing event log for new entries");
        executor.execute(principal, new Executor.SimpleWork<Object>(this, "gather") {
            @Override
            @Transactional(readOnly = true)
            public Object doWork(org.hibernate.Session session, ServiceFactory services) {
                final IConfig iConfig = services.getConfigService();
                final IQuery iQuery = services.getQueryService();
                final String currentLogIdString = iConfig.getConfigValue(countKey);
                eventLogId = currentLogIdString == null ? -1 : Long.parseLong(currentLogIdString);
                final String hql = "FROM EventLog WHERE id > :id ORDER BY id";
                final Parameters params = new Parameters().addId(eventLogId).page(0, BATCH_SIZE);
                final List<EventLog> logEntries = iQuery.findAllByQuery(hql, params);
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
                return null;
            }
        });
        try {
            if (!toIndex.isEmpty()) {
                register("index");
            } else if (!toPurge.isEmpty()) {
                register("purge");
            } else {
                register("prepare", DELAY_FOR_NOTHING_NEW_TO_INDEX);
            }
        } catch (Throwable t) {
            LOGGER.error("failed to continue indexer", t);
        }
    }

    public void index() {
        LOGGER.debug("indexing objects: count = {}", toIndex.size());
        if (DetailsFieldBridge.tryLock()) {
            DetailsFieldBridge.setFieldBridge(bridge);
        } else {
            LOGGER.info("failed to lock field bridge so will wait awhile");
            try {
                register("index", DELAY_FOR_FIELD_BRIDGE_CONTENTION);
            } catch (Throwable t) {
                LOGGER.error("failed to continue indexer", t);
            }
            return;
        }
        try {
            DetailsFieldBridge.setFieldBridge(bridge);
            executor.execute(ALL_GROUPS_CONTEXT, principal, new Executor.SimpleWork<Object>(this, "index") {
                @Override
                @Transactional(readOnly = false)
                public Object doWork(org.hibernate.Session session, ServiceFactory services) {
                    final FullTextSession fullTextSession = Search.getFullTextSession(session);
                    fullTextSession.setCacheMode(CacheMode.IGNORE);
                    for (final Map.Entry<String, Collection<Long>> typeAndIds : toIndex.asMap().entrySet()) {
                        final String entityType = typeAndIds.getKey();
                        final Collection<Long> entityIds = typeAndIds.getValue();
                        final String hql = "FROM " + entityType + " WHERE id IN (:ids)";
                        final Query query = fullTextSession.createQuery(hql);
                        query.setParameterList("ids", entityIds);
                        for (final Object object : query.list()) {
                            fullTextSession.index(object);
                        }
                    }
                    toIndex.clear();
                    return null;
                }
            });
        } finally {
            DetailsFieldBridge.unlock();
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
        LOGGER.debug("purging objects: count = {}", toPurge.size());
        if (DetailsFieldBridge.tryLock()) {
            DetailsFieldBridge.setFieldBridge(bridge);
        } else {
            LOGGER.info("failed to lock field bridge so will wait awhile");
            try {
                register("purge", DELAY_FOR_FIELD_BRIDGE_CONTENTION);
            } catch (Throwable t) {
                LOGGER.error("failed to continue indexer", t);
            }
            return;
        }
        try {
            executor.execute(ALL_GROUPS_CONTEXT, principal, new Executor.SimpleWork<Object>(this, "purge") {
                @Override
                @Transactional(readOnly = false)
                public Object doWork(org.hibernate.Session session, ServiceFactory services) {
                    final FullTextSession fullTextSession = Search.getFullTextSession(session);
                    fullTextSession.setCacheMode(CacheMode.IGNORE);
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
                            fullTextSession.purge(entityClass, entityId);
                        }
                    }
                    toPurge.clear();
                    return null;
                }
            });
        } finally {
            DetailsFieldBridge.unlock();
        }
        try {
            register("note");
        } catch (Throwable t) {
            LOGGER.error("failed to continue indexer", t);
        }
    }

    public void note() {
        LOGGER.debug("noting event log entries as processed");
        executor.execute(principal, new Executor.SimpleWork<Object>(this, "note") {
            @Override
            @Transactional(readOnly = false)
            public Object doWork(org.hibernate.Session session, ServiceFactory services) {
                final IConfig iConfig = services.getConfigService();
                iConfig.setConfigValue(countKey, Long.toString(eventLogId));
                return null;
            }
        });
        try {
            register("prepare");
        } catch (Throwable t) {
            LOGGER.error("failed to continue indexer", t);
        }
    }
}
