/*
 * Copyright (C) 2019-2020 University of Dundee & Open Microscopy Environment.
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

package ome.security.basic;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import javax.sql.DataSource;

import ome.model.enums.AdminPrivilege;
import ome.system.EventContext;
import ome.system.Roles;
import ome.util.PrivilegedStringTypeDescriptor;

/**
 * Initialize the filters for the subclasses of {@link ome.util.PrivilegedStringType}.
 * @author m.t.b.carroll@dundee.ac.uk
 * @since 5.5.6
 */
public class PropertyFilterInitializer {

    private final CurrentDetails currentDetails;
    private final DataSource dataSource;
    private final AdminPrivilege privilegeReadSession;
    private final Roles roles;

    /**
     * The filter for {@link PrivilegedStringTypeDescriptor.Filter#FULL_ADMIN}.
     * Requires either the session owner or a user with {AdminPrivilege#VALUE_READ_SESSION}.
     * @param sessionOwnerId the ID of the {@link ome.model.meta.Session} whose properties are being filtered
     * @return if property values may be read
     */
    private boolean isFullAdmin(long sessionOwnerId) {
        if (currentDetails.size() == 0) {
            /* This cannot be an external user request so permit it. */
            return true;
        }
        /* Determine the currently effective user ID. */
        final EventContext ec = currentDetails.getCurrentEventContext();
        final Long currentUserId = ec.getCurrentUserId();
        if (currentUserId == null) {
            /* This cannot be an external user request so permit it. */
            return true;
        }
        /* Check filter criteria. */
        if (ec.getCurrentAdminPrivileges().contains(privilegeReadSession)) {
            /* User is a full administrator. */
            return true;
        }
        if (sessionOwnerId == currentUserId) {
            /* User is reading their own session. */
            return true;
        }
        /* No requirement is satisfied. */
        return false;
    }

    /**
     * The filter for {@link PrivilegedStringTypeDescriptor.Filter#RELATED_USER}.
     * Requires the experimenter themself, an administrator, a group owner or a fellow group member of a non-private group.
     * @param experimenterId the ID of the {@link ome.model.meta.Experimenter} whose properties are being filtered
     * @return if property values may be read
     */
    private boolean isRelatedUser(long experimenterId) {
        if (currentDetails.size() == 0) {
            /* This cannot be an external user request so permit it. */
            return true;
        }
        /* Never filter system users. */
        if (experimenterId == roles.getRootId() || experimenterId == roles.getGuestId()) {
            return true;
        }
        /* Determine the currently effective user ID. */
        final EventContext ec = currentDetails.getCurrentEventContext();
        final Long currentUserId = ec.getCurrentUserId();
        if (currentUserId == null) {
            /* This cannot be an external user request so permit it. */
            return true;
        }
        /* Check filter criteria. */
        if (ec.isCurrentUserAdmin() || !ec.getLeaderOfGroupsList().isEmpty()) {
            /* User is an administrator or a group owner. */
            return true;
        }
        if (experimenterId == currentUserId) {
            /* User is reading their own user metadata. */
            return true;
        }
        /* The only remaining option is for the user to be a fellow group member of a non-private group. */
        boolean isFellowMember = false;
        try (final Connection connection = dataSource.getConnection()) {
            // note: The "64" in the SQL corresponds to: Permissions.Right.READ.mask() << Permissions.Role.GROUP.shift().
            final PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM GroupExperimenterMap m1 WHERE m1.child = ? AND m1.parent <> ? AND EXISTS " +
                    "(SELECT 1 FROM GroupExperimenterMap m2 WHERE m2.child = ? AND m1.parent = m2.parent) AND EXISTS " +
                    "(SELECT 1 FROM ExperimenterGroup g WHERE m1.parent = g.id AND g.permissions & 64 = 64)");
            statement.setLong(1, experimenterId);
            statement.setLong(2, roles.getUserGroupId());
            statement.setLong(3, currentUserId);
            statement.setMaxRows(1);
            isFellowMember = statement.executeQuery().next();
            statement.close();
        } catch (SQLException sqle) {
            /* Assume no results. */
        }
        if (isFellowMember) {
            /* User is a fellow group member. */
            return true;
        }
        /* No requirement is satisfied. */
        return false;
    }

    /**
     * Provide the property filter implementations for {@link ome.util.PrivilegedStringType}.
     * @param adminPrivileges the light administrator privileges helper
     * @param currentDetails the details of the current thread's security context
     * @param dataSource the data source to be used for JDBC access to the database
     * @param roles the users and groups that are special to OMERO
     */
    public PropertyFilterInitializer(LightAdminPrivileges adminPrivileges, CurrentDetails currentDetails, DataSource dataSource,
            Roles roles) {
        this.currentDetails = currentDetails;
        this.dataSource = dataSource;
        this.privilegeReadSession = adminPrivileges.getPrivilege(AdminPrivilege.VALUE_READ_SESSION);
        this.roles = roles;

        PrivilegedStringTypeDescriptor.setFilter(PrivilegedStringTypeDescriptor.Filter.FULL_ADMIN, this::isFullAdmin);
        PrivilegedStringTypeDescriptor.setFilter(PrivilegedStringTypeDescriptor.Filter.RELATED_USER, this::isRelatedUser);
    }
}
