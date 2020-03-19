/*
 * Copyright (C) 2019 University of Dundee & Open Microscopy Environment.
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

import org.hibernate.EmptyInterceptor;

import ome.tools.hibernate.SqlQueryTransformer;

/**
 * Pulls SQL query rewriting into a simple superclass of {@link OmeroInterceptor} for use outside Blitz by other server threads.
 * @see SqlQueryTransformer
 * @author m.t.b.carroll@dundee.ac.uk
 * @since 5.5.6
 */
public class SqlQueryInterceptor extends EmptyInterceptor {

    private final SqlQueryTransformer sqlQueryTransformer;

    public SqlQueryInterceptor(SqlQueryTransformer sqlQueryTransformer) {
        this.sqlQueryTransformer = sqlQueryTransformer;
    }

    @Override
    public String onPrepareStatement(String sql) {
        return sqlQueryTransformer.transformQuery(sql);
    }
}
