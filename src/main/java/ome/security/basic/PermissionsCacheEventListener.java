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
