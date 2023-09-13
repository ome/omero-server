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
    public Map<Long, List<Long>> groupMembership = new HashMap<Long, List<Long>>();
    public Map<Long, Long> groupPermissions = new HashMap<Long, Long>();
    private final Logger log = LoggerFactory.getLogger(getClass());
    private boolean isValid;
    
    public InMemoryExperimenterGroupCache(DataSource dataSource) throws SQLException {
        this.dataSource = dataSource;
        updateCache();
    }

    @Override
    public synchronized boolean isRelatedUser(long experimenterId, long currentUserId,
            long userGroupId) {
        log.debug("Using cache for isRelatedUser");
        List<Long> experimenterGroups = groupMembership.get(experimenterId);
        List<Long> currentUserGroups = groupMembership.get(currentUserId);
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
                isRelated = true;
                break;
            }
        }
        return isRelated;
    }
    
    @Override
    public synchronized void updateCache() {
        log.info("Updating permissions cache");
        Map<Long, List<Long>> newGroupMembership = new HashMap<Long, List<Long>>();
        Map<Long, Long> newGroupPermissions = new HashMap<Long, Long>();
        try (final Connection connection = dataSource.getConnection()) {
            final PreparedStatement statement = connection.prepareStatement(
                    "SELECT parent, child FROM GroupExperimenterMap");
            ResultSet results = statement.executeQuery();
            while (results.next()) {
                long groupId = results.getLong("parent");
                long experimenterId = results.getLong("child");
                if (!newGroupMembership.containsKey(experimenterId)) {
                    newGroupMembership.put(experimenterId, new ArrayList<Long>());
                }
                newGroupMembership.get(experimenterId).add(groupId);
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
        groupMembership = newGroupMembership;
        groupPermissions = newGroupPermissions;
        isValid = true;
    }

    @Override
    public synchronized boolean cacheIsValid() {
        return isValid;
    }
}
