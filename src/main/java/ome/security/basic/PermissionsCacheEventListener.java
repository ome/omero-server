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
import java.util.List;

import org.hibernate.event.PostDeleteEvent;
import org.hibernate.event.PostDeleteEventListener;
import org.hibernate.event.PostInsertEvent;
import org.hibernate.event.PostInsertEventListener;
import org.hibernate.event.PostUpdateEvent;
import org.hibernate.event.PostUpdateEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PermissionsCacheEventListener
        implements PostUpdateEventListener, PostInsertEventListener,
        PostDeleteEventListener {
    
    private final Logger log = LoggerFactory.getLogger(getClass());
    
    private ExperimenterGroupCache cache;
    
    private List<String> relevantClassNames;
    
    PermissionsCacheEventListener(ExperimenterGroupCache cache) {
        this.cache = cache;
        relevantClassNames = new ArrayList<String>();
        relevantClassNames.add("GroupExperimenterMap");
        relevantClassNames.add("Experimenter");
        relevantClassNames.add("ExperimenterGroup");
    }
    
    private void conditionallyUpdateCache(String className) {
        log.debug(className);
        if (relevantClassNames.contains(className)) {
            try {
                cache.updateCache();
            } catch (SQLException e) {
                log.error("Error updating ExperimenterGroupCache", e);
            }
        }
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        conditionallyUpdateCache(event.getEntity().getClass().getSimpleName());
    }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        conditionallyUpdateCache(event.getEntity().getClass().getSimpleName());
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        conditionallyUpdateCache(event.getEntity().getClass().getSimpleName());
    }

}
