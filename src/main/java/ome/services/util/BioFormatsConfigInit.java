/*
 * Copyright (C) 2021 Glencoe Software, Inc.
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

import loci.formats.FormatTools;
import ome.api.IConfig;

/**
 * Propagate the server's Bio-Formats version metadata into corresponding keys
 * in the configuration service.
 * @author callan@glencoesoftware.com
 * @since 5.6.2
 */
public class BioFormatsConfigInit {

    private static final String KEY_PREFIX = "omero.bioformats.";

    /**
     * Set Bio-Formats verison metadata in the current configuration.
     * @param iConfig the configuration service
     */
    public BioFormatsConfigInit(IConfig iConfig) {
        iConfig.setConfigValue(KEY_PREFIX + "version", FormatTools.VERSION);
        iConfig.setConfigValue(
                KEY_PREFIX + "vcs_revision", FormatTools.VCS_REVISION);
        iConfig.setConfigValue(KEY_PREFIX + "date", FormatTools.DATE);
    }
}
