/*
 *   $Id$
 *
 *   Copyright 2010 Glencoe Software, Inc. All rights reserved.
 *   Use is subject to license terms supplied in LICENSE.txt
 */
package ome.server.itests.perms42;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Test of the re-enabled group permissions in Beta4.2
 *
 * @since Beta-4.2.0
 *
 */
@Test(groups = "ticket:1434")
public class MiscPermissionsTest extends PermissionsTest {

    @Test
    public void testUserLoggedIntoGroupDuringGroupChangeIsNotified() throws Exception {
        Assert.fail();
        // Do we need to lock the group?
        // Best-effort at using umask to keep user tx going
    }

    @Test
    public void testIAdminChangeGroupIsNotSupported() throws Exception {
        Assert.fail();
    }

    @Test
    public void testIAdminChangePermissionsIsSeverlyLimited() throws Exception {
        Assert.fail();
    }

    @Test
    public void testUserInDiffGroupCantSeeObjects() throws Exception {
        Assert.fail();
    }

    @Test
    public void testUserCantReadOwnFromAnotherContext() throws Exception {
        Assert.fail();
    }

    @Test
    public void testPICanStillDoAnythingInGroup() throws Exception {
        Assert.fail();
    }

    @Test
    public void testAdminCantChangePermsOfGroupByAccident() throws Exception {
        Assert.fail();
    }

    @Test
    public void testOwnerCanCallChgrpOnCoherentGraph() throws Exception {
        Assert.fail();
    }

    @Test
    public void testOwnerCantCallChgrpForAnotherOwner() throws Exception {
        Assert.fail();
    }

    @Test
    public void testChgrpLeavesADeletionRecordWhereNecessary() throws Exception {
        Assert.fail();
    }

    @Test
    public void testChgrpCanAlternativelyStoreByValue() throws Exception {
        Assert.fail();
    }

    @Test
    public void testTriggersPreventMixingGraphs() throws Exception {
        Assert.fail();
    }

    @Test
    public void testWorldPermissionsAreKeptCoherent() throws Exception {
        Assert.fail();
    }

    @Test
    public void testUserCanSafelyStoreInfoInAPrivateGroup() throws Exception {
        Assert.fail();
    }

    @Test
    public void testPICanMoveAGroupToShared() throws Exception {
        Assert.fail();
    }

    @Test
    public void testUserWillBeInformedIfGroupIsShared() throws Exception {
        Assert.fail();
    }

}
