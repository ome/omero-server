/*
 *   Copyright 2019 University of Dundee. All rights reserved.
 *   Use is subject to license terms supplied in LICENSE.txt
 */

package ome.api.local;

import ome.api.ISession;
import ome.model.meta.Session;

/**
 * Provides local (internal) extensions for session management
 */
public interface LocalSession extends ome.api.ISession {

    /**
     * Lookups a Session without updating the last access time.
     *
     * Otherwise, behaves identically to {@link ome.services.sessions.SessionContext#getSession()}.
     */
    Session getSessionQuietly(String uuid);
}
