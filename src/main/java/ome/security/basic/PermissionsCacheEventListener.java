/*
 * Copyright (C) 2023 Glencoe Software, Inc. All rights reserved.
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

package ome.security.basic;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.event.PostDeleteEvent;
import org.hibernate.event.PostDeleteEventListener;
import org.hibernate.event.PostInsertEvent;
import org.hibernate.event.PostInsertEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;

import ome.model.meta.Experimenter;
import ome.model.meta.ExperimenterGroup;
import ome.services.messages.EventLogMessage;
import ome.model.internal.Permissions;

public class PermissionsCacheEventListener
        implements PostInsertEventListener, PostDeleteEventListener,
        ApplicationListener<EventLogMessage> {

    private static final long serialVersionUID = 1L;

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final Map<String, Long> permissionsMap;
    
    private ExperimenterGroupCache cache;
    
    private List<String> relevantClassNames;
    
    PermissionsCacheEventListener(ExperimenterGroupCache cache) {
        this.cache = cache;
        relevantClassNames = new ArrayList<String>();
        relevantClassNames.add("GroupExperimenterMap");
        relevantClassNames.add("Experimenter");
        relevantClassNames.add("ExperimenterGroup");
        permissionsMap = new HashMap<String, Long>();
        permissionsMap.put("rw----", -120l); //Read Only
        permissionsMap.put("rwr---", -56l); //Read Only
        permissionsMap.put("rwra--", -40l); //Read Annotate
        permissionsMap.put("rwrw--", -8l); //Read Write
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        String className = event.getEntity().getClass().getSimpleName();
        if (className.equals("GroupExperimenterMap")) {
            // A user has been removed from a group
            Experimenter experimenter = (Experimenter) event.getDeletedState()[0];
            ExperimenterGroup group = (ExperimenterGroup) event.getDeletedState()[3];
            cache.removeExperimenterFromGroup(experimenter.getId(), group.getId());
        }
    }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        String className = event.getEntity().getClass().getSimpleName();
        if (className.equals("GroupExperimenterMap")) {
            // A user has been added to a group
            Experimenter experimenter = (Experimenter) event.getState()[0];
            ExperimenterGroup group = (ExperimenterGroup) event.getState()[3];
            cache.addExperimenterToGroup(experimenter.getId(), group.getId());
        } else if (className.equals("ExperimenterGroup")) {
            // New group created
            Object[] stateInfo = event.getState();
            ome.model.meta.ExperimenterGroup.Details details = (ome.model.meta.ExperimenterGroup.Details) stateInfo[4];
            Permissions permissions = details.getPermissions();
            Long groupId = (Long) event.getId();
            cache.addGroup(groupId, permissionsMap.get(permissions.toString()));
        } else if (className.equals("Experimenter")) {
            Long experimenterId = (Long) event.getId();
            cache.addExperimenter(experimenterId);
        }
    }

    @Override
    public void onApplicationEvent(EventLogMessage event) {
        if (ExperimenterGroup.class.equals(event.entityType)) {
            if (!event.entityIds.isEmpty()) {
                try {
                    cache.updateGroupPermissions(event.entityIds.get(0));
                } catch (SQLException e) {
                    log.error("Error updating cache", e);
                }
            }
        }
    }
}
