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

package ome.services.util;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ome.security.SecuritySystem;
import ome.system.EventContext;

/**
 * Sets timeouts for queries according to event context.
 * @author m.t.b.carroll@dundee.ac.uk
 * @since 5.5.0
 */
public class TimeoutSetter {

    private static final Logger LOGGER = LoggerFactory.getLogger(TimeoutSetter.class);

    private final SecuritySystem securitySystem;

    private final int timeout, timeoutAdmin;  /* in seconds */

    /**
     * Construct the timeout setter.
     * @param securitySystem the security system
     * @param timeout the timeout to set for regular users, in seconds
     * @param timeoutAdmin the timeout to set for administrative users, in seconds
     */
    public TimeoutSetter(SecuritySystem securitySystem, int timeout, int timeoutAdmin) {
        if (timeout < 1 || timeoutAdmin < 1) {
            throw new IllegalArgumentException("query timeouts must be strictly positive");
        }
        this.securitySystem = securitySystem;
        this.timeout = timeout;
        this.timeoutAdmin = timeoutAdmin;
        if (timeout == timeoutAdmin) {
            LOGGER.info("Query timeout set to {}s for all users.", timeout);
        } else {
            LOGGER.info("Query timeout set to {}s and for administrators to {}s.", timeout, timeoutAdmin);
        }
    }

    /**
     * Set the timeout on the given query.
     * @param query a query consuming a timeout setting
     */
    public void setTimeout(Consumer<Integer> query) {
        final EventContext ec = securitySystem.getEventContext();
        final Long userId = ec.getCurrentUserId();
        final int selectedTimeout = ec.isCurrentUserAdmin() ? timeoutAdmin : timeout;
        query.accept(selectedTimeout);
        if (userId == null) {
            LOGGER.debug("Set timeout for unknown user's query to {}s.", selectedTimeout);
        } else {
            LOGGER.debug("Set timeout for user {}'s query to {}s.", userId, selectedTimeout);
        }
    }
}
