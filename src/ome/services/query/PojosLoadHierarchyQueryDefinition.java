package ome.services.query;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.Criteria;
import org.hibernate.FetchMode;
import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.hibernate.criterion.Restrictions;
import org.springframework.orm.hibernate3.HibernateCallback;

import ome.model.containers.Category;
import ome.model.containers.CategoryGroup;
import ome.model.containers.Dataset;
import ome.model.containers.Project;
import ome.model.core.Image;
import ome.util.builders.PojoOptions;

public class PojosLoadHierarchyQueryDefinition extends Query
{

    static { // TODO same as findHierarchy
        addDefinition(new OptionsQueryParameterDef());
        addDefinition(new QueryParameterDef(QP.CLASS, Class.class, false));
        addDefinition(new CollectionQueryParameterDef( QP.IDS, true, Long.class ));        
    }
    
    public PojosLoadHierarchyQueryDefinition(QueryParameter... parameters)
    {
        super(parameters);
    }

    @Override
    protected Object runQuery(Session session) throws HibernateException, SQLException
    {
        PojoOptions po = new PojoOptions((Map) value(QP.OPTIONS));
        
        Criteria c = session.createCriteria((Class)value(QP.CLASS));
        c.setResultTransformer(Criteria.DISTINCT_ROOT_ENTITY);
        
        // optional ids
        Collection ids = (Collection) value(QP.IDS);
        if ( ids != null && ids.size() > 0)
            c.add(Restrictions.in("id",(Collection) value(QP.IDS)));
        
        int depth = po.isLeaves() ? Integer.MAX_VALUE : 1; 
        Hierarchy.fetchChildren(c,Project.class,depth); 
      
        return c.list();
    }

    @Override
    protected void enableFilters(Session session)
    {
        ownerFilter(session, 
                CategoryGroup.OWNER_FILTER, 
                Category.OWNER_FILTER,
                Project.OWNER_FILTER,
                Dataset.OWNER_FILTER
                );
    }

}
