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

public interface ExperimenterGroupCache {
    
    public void updateCache() throws SQLException;

    public void updateGroupPermissions(Long groupId) throws SQLException;

    public void addGroup(Long groupId, Long permissions);

    public void addExperimenter(Long experimenterId);

    public void changeGroupPermissions(Long groupId, Long permissions);

    public void addExperimenterToGroup(Long experimenterId, Long groupId);

    public void removeExperimenterFromGroup(Long experimenterId, Long groupId);

    public boolean cacheIsValid();

    public boolean isRelatedUser(Long experimenterId, Long currentUserId, Long userGroupId);
}
