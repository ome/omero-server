package ome.security.basic;

import java.sql.SQLException;

public interface ExperimenterGroupCache {
    
    public void updateCache() throws SQLException;
    
    public boolean cacheIsValid();

    public boolean isRelatedUser(long experimenterId, long currentUserId, long userGroupId);
}
