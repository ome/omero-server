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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InMemoryExperimenterGroupCache implements ExperimenterGroupCache {

    private final DataSource dataSource;
    /* Mapping from experimenter ID to a list of group IDs that experimenter is a member of*/
    public Map<Long, List<Long>> experimenterGroupsMap = new HashMap<Long, List<Long>>();
    public Map<Long, Long> groupPermissions = new HashMap<Long, Long>();
    private final Logger log = LoggerFactory.getLogger(getClass());
    private boolean isValid;
    
    public InMemoryExperimenterGroupCache(DataSource dataSource) throws SQLException {
        this.dataSource = dataSource;
        updateCache();
    }

    @Override
    public synchronized boolean isRelatedUser(Long experimenterId, Long currentUserId,
            Long userGroupId) {
        log.debug("Using cache for isRelatedUser");
        List<Long> experimenterGroups = experimenterGroupsMap.get(experimenterId);
        List<Long> currentUserGroups = experimenterGroupsMap.get(currentUserId);
        if (experimenterGroups == null || currentUserGroups == null) {
            log.error("Failed to find groups for " + Long.toString(experimenterId) +
                " or " + Long.toString(currentUserId));
            return false;
        }
        boolean isRelated = false;
        for (Long groupId : experimenterGroups) {
            if (groupId.equals(userGroupId)) {
                continue;
            }
            if (currentUserGroups.contains(groupId)) {
                Long permissions = groupPermissions.get(groupId);
                if ((permissions & 64) == 64) {
                    isRelated = true;
                }
                break;
            }
        }
        return isRelated;
    }
    
    @Override
    public synchronized void updateCache() {
        log.debug("Updating permissions cache");
        Map<Long, List<Long>> newexperimenterGroups = new HashMap<Long, List<Long>>();
        Map<Long, Long> newGroupPermissions = new HashMap<Long, Long>();
        try (final Connection connection = dataSource.getConnection()) {
            final PreparedStatement statement = connection.prepareStatement(
                    "SELECT parent, child FROM GroupExperimenterMap");
            ResultSet results = statement.executeQuery();
            while (results.next()) {
                long groupId = results.getLong("parent");
                long experimenterId = results.getLong("child");
                if (!newexperimenterGroups.containsKey(experimenterId)) {
                    newexperimenterGroups.put(experimenterId, new ArrayList<Long>());
                }
                newexperimenterGroups.get(experimenterId).add(groupId);
            }
            statement.close();
            final PreparedStatement permissionsStatement = connection.prepareStatement(
                "SELECT id, permissions FROM ExperimenterGroup");
            ResultSet permResults = permissionsStatement.executeQuery();
            while (permResults.next()) {
                long groupId = permResults.getLong("id");
                long permissions = permResults.getLong("permissions");
                newGroupPermissions.put(groupId, permissions);
            }
            permissionsStatement.close();
        } catch (SQLException e) {
            log.error("Error updating experimenter group cache", e);
            isValid = false;
            return;
        }
        experimenterGroupsMap = newexperimenterGroups;
        groupPermissions = newGroupPermissions;
        isValid = true;
    }

    @Override
    public synchronized boolean cacheIsValid() {
        return isValid;
    }

    @Override
    public synchronized void addGroup(Long groupId, Long permissions) {
        log.debug("Adding Group " + groupId.toString());
        groupPermissions.put(groupId, permissions);
    }

    @Override
    public synchronized void addExperimenter(Long experimenterId) {
        log.debug("Adding Experimenter " + experimenterId.toString());
        experimenterGroupsMap.put(experimenterId, new ArrayList<Long>());
    }

    @Override
    public synchronized void changeGroupPermissions(Long groupId, Long permissions) {
        log.debug("Changing Group Permissions " + groupId.toString() + " " + permissions.toString());
        groupPermissions.put(groupId, permissions);
    }

    @Override
    public synchronized void addExperimenterToGroup(Long experimenterId, Long groupId) {
        log.debug("Adding Experimenter " + experimenterId.toString() +
                " to Group " + groupId.toString());
        if (!experimenterGroupsMap.get(experimenterId).contains(groupId)) {
            experimenterGroupsMap.get(experimenterId).add(groupId);
        }
    }

    @Override
    public synchronized void removeExperimenterFromGroup(Long experimenterId, Long groupId) {
        log.debug("Removing Experimenter " + experimenterId.toString() +
                " from Group " + groupId.toString());
        experimenterGroupsMap.get(experimenterId).remove(groupId);
    }

    @Override
    public void updateGroupPermissions(Long groupId) throws SQLException {
        log.debug("Updating group permissions " + groupId.toString());
        try (final Connection connection = dataSource.getConnection()) {
            final PreparedStatement statement = connection.prepareStatement(
                    "SELECT permissions FROM ExperimenterGroup WHERE id=?");
            statement.setLong(1, groupId);
            ResultSet results = statement.executeQuery();
            while (results.next()) {
                long permissions = results.getLong("permissions");
                log.info("Updating group " + Long.toString(groupId) + " permissions to " + Long.toString(permissions));
                groupPermissions.put(groupId, permissions);
            }
            statement.close();
        }
    }
}
