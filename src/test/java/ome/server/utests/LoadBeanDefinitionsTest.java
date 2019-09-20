/*
 * Copyright (C) 2019 Glencoe Software, Inc.
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

package ome.server.utests;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.testng.annotations.Test;

/**
 * Test to ensure that the beans that comprise "ome.server" can be loaded.
 * Should catch any issues with Spring XML formatting.
 *
 * @author Chris Allan
 * @since 5.5.5
 */
public class LoadBeanDefinitionsTest {

    @Test
    public void testLoadBeanDefinitions() {
        DefaultListableBeanFactory factory =
                new DefaultListableBeanFactory();
        XmlBeanDefinitionReader reader =
                new XmlBeanDefinitionReader(factory);
        reader.loadBeanDefinitions(
            "classpath:ome/config.xml",
            "classpath:ome/services/messaging.xml",
            "classpath:ome/services/checksum.xml",
            "classpath:ome/services/datalayer.xml",
            "classpath*:ome/services/db-*.xml",
            "classpath:ome/services/sec-primitives.xml",
            "classpath:ome/services/hibernate.xml",
            "classpath:ome/services/services.xml",
            "classpath*:ome/services/service-*.xml",
            "classpath:ome/services/sec-system.xml",
            "classpath:ome/services/startup.xml"
        );
    }

}
