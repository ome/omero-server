/*
 * Copyright (C) 2020 University of Dundee & Open Microscopy Environment.
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

import java.util.Iterator;
import java.util.List;

import ome.model.annotations.Annotation;
import ome.model.annotations.CommentAnnotation;
import ome.security.ACLVoter;
import ome.security.EventProvider;
import ome.security.SecurityFilter;
import ome.security.SystemTypes;
import ome.security.policy.PolicyService;
import ome.services.sessions.SessionManager;
import ome.services.sessions.SessionManagerImpl;
import ome.services.sessions.SessionProvider;
import ome.system.Roles;
import ome.system.ServiceFactory;

/**
 * Provides a group context check that does not rely on SQL to bypass interception by Hibernate.
 * This read-only variant of the service queries group sudo annotations from the session provider instead of the database.
 * @author m.t.b.carroll@dundee.ac.uk
 * @since 5.5.7
 */
public class BasicSecuritySystemReadOnly extends BasicSecuritySystem {

    public BasicSecuritySystemReadOnly(OmeroInterceptor interceptor, SystemTypes sysTypes, CurrentDetails cd,
            SessionManager sessionManager, SessionProvider sessionProvider, EventProvider eventProvider, Roles roles,
            ServiceFactory sf, TokenHolder tokenHolder, List<SecurityFilter> filters, PolicyService policyService,
            ACLVoter aclVoter) {
        super(interceptor, sysTypes, cd, sessionManager, sessionProvider, eventProvider, roles, sf, tokenHolder, filters,
                policyService, aclVoter);
    }

    @Override
    protected boolean isGroupContextPermitted(long sessionId, long groupId) {
        final ome.model.meta.Session session = sessionProvider.findSessionById(sessionId, sf);
        final Iterator<Annotation> sessionAnnotations = session.linkedAnnotationIterator();
        while (sessionAnnotations.hasNext()) {
            final Annotation sessionAnnotation = sessionAnnotations.next();
            if (sessionAnnotation instanceof CommentAnnotation &&
                    SessionManagerImpl.GROUP_SUDO_NS.equals(sessionAnnotation.getNs()) &&
                    roles.isRootUser(sessionAnnotation.getDetails().getOwner()) &&
                    !isGroupContextPermitted(groupId, ((CommentAnnotation) sessionAnnotation).getTextValue())) {
                return false;
            }
        }
        return true;
    }
}
