/*
 *   $Id$
 *
 *   Copyright 2007 Glencoe Software, Inc. All rights reserved.
 *   Use is subject to license terms supplied in LICENSE.txt
 */

package ome.server.itests;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import ome.api.IPixels;
import ome.api.ThumbnailStore;
import ome.conditions.ApiUsageException;
import ome.conditions.InternalException;
import ome.conditions.ReadOnlyAdminGroupSecurityViolation;
import ome.conditions.ResourceError;
import ome.model.annotations.ExperimenterAnnotationLink;
import ome.model.annotations.FileAnnotation;
import ome.model.core.Pixels;
import ome.model.display.ChannelBinding;
import ome.model.display.RenderingDef;
import ome.model.internal.Permissions;
import ome.model.meta.Experimenter;
import ome.model.meta.ExperimenterGroup;
import ome.parameters.Parameters;
import ome.system.ServiceFactory;
import omeis.providers.re.RenderingEngine;
import omeis.providers.re.data.PlaneDef;

import org.testng.annotations.Test;

/**
 *
 * @author Josh Moore, josh at glencoesoftware.com
 *
 */
public class RenderingSessionTest extends AbstractManagedContextTest {

    class Fixture {

        Pixels pixels;
        long pixelsID;
        long userID;
        RenderingEngine re;

        public Fixture() {
            pixels = makePixels();
            pixelsID = pixels.getId();
            userID = iAdmin.getEventContext().getCurrentUserId();

            re = factory.createRenderingEngine();
            re.lookupPixels(pixelsID);
            if (!(re.lookupRenderingDef(pixelsID))) {
               re.resetDefaults();
               re.lookupRenderingDef(pixelsID);
            }
            re.load();
        }
    }

    @Test(groups = "ticket:1205")
    public void testSaveRenderingSettingsFails() {

        Fixture f = new Fixture();

        //then I had to do the following call b/c pixels were not loaded. If now it is the case, I will remove that call
        IPixels service = factory.getPixelsService();
        f.pixels = service.retrievePixDescription(f.pixelsID);

        //To speed up the process to populate my local copy of the settings
        RenderingDef def = service.retrieveRndSettingsFor(f.pixelsID, f.userID);

        /*
        Collection l = pixels.unmodifiableChannels();
        Iterator i = l.iterator();
        List<ChannelData> m = new ArrayList<ChannelData>(l.size());
        int index = 0;
        while (i.hasNext()) {
         m.add(new ChannelData(index, (Channel) i.next()));
         index++;
        }
        */

        //internal code from insight
        // proxy = PixelsServicesFactory.createRenderingControl(context, re,
        // pixels, m, compressionLevel, def);

        f.re.setDefaultZ(0);
        f.re.saveCurrentSettings();
        //crash
    }

    @Test(groups = "ticket:1205")
    public void testValuesGetSaved() {

        Fixture f = new Fixture();

        f.re.resetDefaults();

        boolean active = f.re.isActive(0);
        f.re.setActive(0, !active);
        assertEquals(!active,f.re.isActive(0));

        f.re.resetDefaults();
        assertEquals(active, f.re.isActive(0));

        f.re.setActive(0, !active);
        f.re.saveCurrentSettings();
        assertEquals(!active, f.re.isActive(0));

    }

    @Test
    public void testIPixelsHasSecuritySystem() {
        RenderingEngine re = this.factory.createRenderingEngine();
        try {
            re.lookupRenderingDef(-1L);
        } catch (InternalException ie) {
            if (ie.getMessage().contains("NullPointerException")) {
                fail("Improperly configured");
            }
        }
    }

    @Test
    public void testReadWriteMethod() throws Exception {
        final ServiceFactory sf = this.factory;// new InternalServiceFactory();
        // isf.setApplicationContext(OmeroContext.getManagedServerContext());
        // new Wrap("root", new Wrap.REBackdoor() {
        // @RolesAllowed("user")
        // public void run() {
        RenderingEngine re = sf.createRenderingEngine();

        Pixels pix = makePixels();

        try {
            re.lookupPixels(pix.getId());
            if (!re.lookupRenderingDef(pix.getId())) {
                re.resetDefaults();
                re.lookupRenderingDef(pix.getId());
            }

            re.load();
            re.setChannelWindow(0, 0, re.getChannelWindowEnd(0) - 0.00001);
            re.saveCurrentSettings();

            re.setChannelWindow(0, 0, re.getChannelWindowEnd(0) - 0.00002);
            re.resetDefaults();
            re.resetDefaults();

            re.setChannelWindow(0, 0, re.getChannelWindowEnd(0) - 0.00003);
            re.saveCurrentSettings();
            re.saveCurrentSettings();

            re.resetDefaults();
            re.saveCurrentSettings();
            re.resetDefaults();
            re.renderAsPackedInt(new PlaneDef(0, 0));
            re.resetDefaults();

        } finally {
            re.close();
        }
        // }
        // });
    }

    @Test
    public void testReadWriteMethodViaThumbnails() throws Exception {
        final ServiceFactory sf = this.factory;// new InternalServiceFactory();
        Pixels pix = makePixels();
        ThumbnailStore tb = sf.createThumbnailService();
        try {
            boolean value = tb.setPixelsId(pix.getId());
            // tb.resetDefaults();
            tb.setPixelsId(pix.getId());
            tb.getThumbnail(10, 10);
        } finally {
            tb.close();
        }

    }

    @Test(groups = "ticket:1655")
    public void testRootWithAnnotation() throws Exception {
        loginRoot();
        Map<?, ?> m = factory.getMetadataService().loadAnnotations(
                Experimenter.class, Collections.singleton(0L),
                Collections.singleton("FileAnnotation"), null, null);
        if (m.size() == 0) {
            FileAnnotation fa = new FileAnnotation();
            fa.setNs("ticket:1655");
            ExperimenterAnnotationLink link = new ExperimenterAnnotationLink();
            link.link(new Experimenter(0L, false), fa);
            iUpdate.saveObject(link);
        }

        Pixels p = iQuery.findByQuery("select p from Pixels p",
                new Parameters().page(0, 1));
        long pix_id = p.getId();

        RenderingEngine re = factory.createRenderingEngine();
        re.setCompressionLevel(.25f);
        re.lookupPixels(pix_id);
        boolean available = re.lookupRenderingDef(pix_id);
        if (!available) {
            re.resetDefaults();
            re.lookupRenderingDef(pix_id);
        }
        re.load();
        re.setActive(0,false);
        re.saveCurrentSettings();
        re.setChannelWindow(0, 0.0, 1.0);
        re.saveCurrentSettings();
        re.setChannelWindow(0, 0.0, 2.0);
        re.saveCurrentSettings();
    }

    @Test(groups = {"ticket:1434","shoola:ticket:1157"})
    public void testAdminViewsImage() {
        loginNewUser();
        final ServiceFactory sf = this.factory;// new InternalServiceFactory();
        Pixels pix = makePixels();
        RenderingEngine reUser = sf.createRenderingEngine();
        reUser.lookupPixels(pix.getId());
        assertTrue(reUser.lookupRenderingDef(pix.getId()));
        reUser.load();
        reUser.render(new PlaneDef(0,0));
        reUser.resetDefaults();

        loginRootKeepGroup();
        RenderingEngine reRoot = sf.createRenderingEngine();
        reRoot.lookupPixels(pix.getId());
        // Before the ticket fix, the following was true
        // assertFalse(reRoot.lookupRenderingDef(pix.getId()));

        assertTrue(reRoot.lookupRenderingDef(pix.getId()));
        reRoot.load();
        reRoot.render(new PlaneDef(0,0));

        try {
            reRoot.resetDefaults();
            fail("group-sec-vio");
        } catch (ReadOnlyAdminGroupSecurityViolation roagsv) {
            // ok.
        }

    }

    @Test(groups = {"ticket:1434","ticket:1769","shoola:ticket:1157"})
    public void testAdminViewsThumbnails() {
        loginNewUser();
        final ServiceFactory sf = this.factory;// new InternalServiceFactory();
        Pixels pix = makePixels();
        ThumbnailStore tbUser = sf.createThumbnailService();
        tbUser.setPixelsId(pix.getId());
        tbUser.getThumbnail(64, 64);
        //tbUser.resetDefaults();

        loginRootKeepGroup();
        ThumbnailStore tbRoot = sf.createThumbnailService();
        assertTrue(tbRoot.setPixelsId(pix.getId()));
        tbRoot.getThumbnail(64, 64);

        try {
            // tbRoot.resetDefaults();
            // fail("group-sec-vio");
        } catch (ReadOnlyAdminGroupSecurityViolation roagsv) {
            // ok.
        }
    }

    @Test(groups = {"ticket:1801"},
          expectedExceptions = { InternalException.class })
    public void testUserViewsThumbnailWithNoSettingsAndNoSetPixelsId() {
        loginNewUser();
        final ServiceFactory sf = this.factory;// new InternalServiceFactory();
        Pixels pix = makePixels();
        deleteRenderingSettings(pix);
        ThumbnailStore tbUser = sf.createThumbnailService();
        assertFalse(tbUser.setPixelsId(pix.getId()));
        tbUser.resetDefaults();
        //tbUser.setPixelsId(pix.getId() // Code that should be called
        tbUser.getThumbnail(64, 64);
    }

    @Test(groups = {"ticket:1801"})
    public void testUserViewsThumbnailWithNoSettings() {
        loginNewUser();
        final ServiceFactory sf = this.factory;// new InternalServiceFactory();
        Pixels pix = makePixels();
        deleteRenderingSettings(pix);
        ThumbnailStore tbUser = sf.createThumbnailService();
        assertFalse(tbUser.setPixelsId(pix.getId()));
        tbUser.resetDefaults();
        tbUser.setPixelsId(pix.getId());
        tbUser.getThumbnail(64, 64);
    }

    @Test(groups = {"ticket:1801"})
    public void testUserViewsThumbnailDirect() {
        loginNewUser();
        final ServiceFactory sf = this.factory;// new InternalServiceFactory();
        Pixels pix = makePixels();
        ThumbnailStore tbUser = sf.createThumbnailService();
        tbUser.setPixelsId(pix.getId());
        tbUser.getThumbnailDirect(64, 64);
    }

    @Test(groups = {"ticket:1801"})
    public void testUserViewsThumbnailByLongestSideDirect() {
        loginNewUser();
        final ServiceFactory sf = this.factory;// new InternalServiceFactory();
        Pixels pix = makePixels();
        ThumbnailStore tbUser = sf.createThumbnailService();
        tbUser.setPixelsId(pix.getId());
        tbUser.getThumbnailByLongestSideDirect(64);
    }

    @Test(groups = {"ticket:1929"},
          expectedExceptions = { ResourceError.class })
    public void testOtherUserViewsThumbnailInsufficientPermissions() {
        Experimenter e1 = loginNewUser();
        final ServiceFactory sf = this.factory;// new InternalServiceFactory();
        Pixels pix = makePixels();
        loginNewUserInOtherUsersGroup(e1);
        ThumbnailStore tbUser = sf.createThumbnailService();
        tbUser.setPixelsId(pix.getId());
    }

    @Test(groups = {"ticket:1929"},
          expectedExceptions = { InternalException.class })
    public void testOtherUserViewsThumbnailWithNoSettingsAndNoSetPixelsId() {
        Experimenter e1 = loginNewUser();
        final ServiceFactory sf = this.factory;// new InternalServiceFactory();
        Pixels pix = makePixels();
        deleteRenderingSettings(pix);
        loginRoot();
        makeDefaultGroupReadWrite(e1);
        loginNewUserInOtherUsersGroup(e1);
        ThumbnailStore tbUser = sf.createThumbnailService();
        assertFalse(tbUser.setPixelsId(pix.getId()));
        tbUser.resetDefaults();
        //tbUser.setPixelsId(pix.getId() // Code that should be called
        tbUser.getThumbnail(64, 64);
    }

    @Test(groups = {"ticket:1929"})
    public void testOtherUserViewsThumbnailWithNoSettings() {
        Experimenter e1 = loginNewUser();
        final ServiceFactory sf = this.factory;// new InternalServiceFactory();
        Pixels pix = makePixels();
        deleteRenderingSettings(pix);
        loginRoot();
        makeDefaultGroupReadWrite(e1);
        loginNewUserInOtherUsersGroup(e1);
        ThumbnailStore tbUser = sf.createThumbnailService();
        assertFalse(tbUser.setPixelsId(pix.getId()));
        tbUser.resetDefaults();
        assertTrue(tbUser.setPixelsId(pix.getId()));
        tbUser.getThumbnail(64, 64);
    }

    @Test(groups = {"ticket:1929"})
    public void testOtherUserViewsThumbnailDirect() {
        Experimenter e1 = loginNewUser();
        final ServiceFactory sf = this.factory;// new InternalServiceFactory();
        Pixels pix = makePixels();
        loginRoot();
        makeDefaultGroupReadWrite(e1);
        loginNewUserInOtherUsersGroup(e1);
        ThumbnailStore tbUser = sf.createThumbnailService();
        assertFalse(tbUser.setPixelsId(pix.getId()));
        tbUser.resetDefaults();
        assertTrue(tbUser.setPixelsId(pix.getId()));
        tbUser.getThumbnailDirect(64, 64);
    }

    @Test(groups = {"ticket:1929"})
    public void testOtherUserViewsThumbnailByLongestSideDirect() {
        Experimenter e1 = loginNewUser();
        final ServiceFactory sf = this.factory;// new InternalServiceFactory();
        Pixels pix = makePixels();
        loginRoot();
        makeDefaultGroupReadWrite(e1);
        loginNewUserInOtherUsersGroup(e1);
        ThumbnailStore tbUser = sf.createThumbnailService();
        assertFalse(tbUser.setPixelsId(pix.getId()));
        tbUser.resetDefaults();
        assertTrue(tbUser.setPixelsId(pix.getId()));
        tbUser.getThumbnailByLongestSideDirect(64);
    }

    @Test(groups = {"ticket:1929"})
    public void testOtherUserViewsSingleThumbnailByLongestSideSet() {
        Experimenter e1 = loginNewUser();
        final ServiceFactory sf = this.factory;// new InternalServiceFactory();
        Pixels pix = makePixels();
        loginRoot();
        makeDefaultGroupReadWrite(e1);
        loginNewUserInOtherUsersGroup(e1);
        ThumbnailStore tbUser = sf.createThumbnailService();
        Set<Long> pixelsIds = Collections.singleton(pix.getId());
        Map<Long, byte[]> thumbnails = 
            tbUser.getThumbnailByLongestSideSet(96, pixelsIds);
        assertNotNull(thumbnails);
        assertEquals(pixelsIds.size(), thumbnails.size());
        for (byte[] thumbnail : thumbnails.values())
        {
            assertNotNull(thumbnail);
        }
    }

    @Test(groups = {"ticket:1929"})
    public void testOtherUserViewsMultipleThumbnailsByLongestSideSet() {
        Experimenter e1 = loginNewUser();
        final ServiceFactory sf = this.factory;// new InternalServiceFactory();
        Pixels pix1 = makePixels();
        Pixels pix2 = makePixels();
        loginRoot();
        makeDefaultGroupReadWrite(e1);
        loginNewUserInOtherUsersGroup(e1);
        ThumbnailStore tbUser = sf.createThumbnailService();
        Set<Long> pixelsIds = new HashSet<Long>();
        pixelsIds.add(pix1.getId());
        pixelsIds.add(pix2.getId());
        Map<Long, byte[]> thumbnails = 
            tbUser.getThumbnailByLongestSideSet(96, pixelsIds);
        assertNotNull(thumbnails);
        assertEquals(pixelsIds.size(), thumbnails.size());
        for (byte[] thumbnail : thumbnails.values())
        {
            assertNotNull(thumbnail);
        }
    }

    @Test(groups = {"ticket:1801"}, expectedExceptions = {ResourceError.class})
    public void testAdminViewsThumbnailsWithNoMetadata() {
        loginNewUser();
        final ServiceFactory sf = this.factory;// new InternalServiceFactory();
        Pixels pix = makePixels();

        loginRootKeepGroup();
        ThumbnailStore tbRoot = sf.createThumbnailService();
        tbRoot.setPixelsId(pix.getId());
        tbRoot.getThumbnail(64, 64);
    }

    @Test(groups = {"ticket:1801"},
          expectedExceptions = {ApiUsageException.class})
    public void testAdminViewsThumbnailsWithNoSettings() {
        loginNewUser();
        final ServiceFactory sf = this.factory;// new InternalServiceFactory();
        Pixels pix = makePixels();
        deleteRenderingSettings(pix);

        loginRootKeepGroup();
        ThumbnailStore tbRoot = sf.createThumbnailService();
        assertFalse(tbRoot.setPixelsId(pix.getId()));
        tbRoot.resetDefaults();
    }

    @Test(groups = {"ticket:1434","ticket:1769","shoola:ticket:1157"})
    public void testAdminViewsThumbnailsWithManualRdef() {
        loginNewUser();
        final ServiceFactory sf = this.factory;// new InternalServiceFactory();
        Pixels pix = makePixels();
        ThumbnailStore tbUser = sf.createThumbnailService();
        tbUser.setPixelsId(pix.getId());
        tbUser.getThumbnail(64, 64);
        //tbUser.resetDefaults();

        // Should only be one
        RenderingDef def = iQuery.findByQuery(
                "select rdef from RenderingDef rdef " +
                "where rdef.pixels.id = " + pix.getId(), null);

        loginRootKeepGroup();
        ThumbnailStore tbRoot = sf.createThumbnailService();
        assertTrue(tbRoot.setPixelsId(pix.getId()));
        tbRoot.setRenderingDefId(def.getId()); // Users rdef
        tbRoot.getThumbnail(64, 64);

        try {
            // tbRoot.resetDefaults();
            // fail("group-sec-vio");
        } catch (ReadOnlyAdminGroupSecurityViolation roagsv) {
            // ok.
        }
    }

    @Test(groups = {"ticket:1434","ticket:1769","shoola:ticket:1157","ticket:1801"})
    public void testAdminViewsThumbnailsViaInsight() {
        loginNewUser();
        final ServiceFactory sf = this.factory;// new InternalServiceFactory();
        Pixels pix = makePixels();
        ThumbnailStore tbUser = sf.createThumbnailService();
        tbUser.setPixelsId(pix.getId());
        tbUser.getThumbnailByLongestSideSet(64, Collections.singleton(pix.getId()));
        //tbUser.resetDefaults();

        loginRootKeepGroup();
        ThumbnailStore tbRoot = sf.createThumbnailService();
        tbUser.getThumbnailByLongestSideSet(64, Collections.singleton(pix.getId()));

        try {
            // tbRoot.resetDefaults();
            // fail("group-sec-vio");
        } catch (ReadOnlyAdminGroupSecurityViolation roagsv) {
            // ok.
        }
    }

    private long syntheticImage() {
        throw new UnsupportedOperationException();
    }

    private void deleteRenderingSettings(Pixels pix) {
        Parameters params = new Parameters();
        params.addId(pix.getId());
        RenderingDef settings = iQuery.findByQuery(
                "select rdef from RenderingDef as rdef " +
                "left outer join fetch rdef.waveRendering " +
                "where rdef.pixels.id = (:id)", params);
        params.addId(settings.getId());
        for (int i = 0; i < settings.sizeOfWaveRendering(); i++)
        {
            ChannelBinding channelBinding = settings.getChannelBinding(i);
            iUpdate.deleteObject(channelBinding);
        }
        iUpdate.deleteObject(settings);
    }
    
    private void makeDefaultGroupReadWrite(Experimenter experimenter)
    {
        ExperimenterGroup group = iAdmin.getDefaultGroup(experimenter.getId());
        group.getDetails().setPermissions(Permissions.GROUP_WRITEABLE);
        iAdmin.updateGroup(group);
    }
}