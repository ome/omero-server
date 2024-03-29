/*
 *   $Id$
 *
 *   Copyright 2006-2016 University of Dundee. All rights reserved.
 *   Use is subject to license terms supplied in LICENSE.txt
 */

package ome.services;

import java.awt.Dimension;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ome.annotations.RolesAllowed;
import ome.api.IPixels;
import ome.api.IRepositoryInfo;
import ome.api.RawPixelsStore;
import ome.api.ServiceInterface;
import ome.conditions.ApiUsageException;
import ome.conditions.ResourceError;
import ome.conditions.RootException;
import ome.conditions.ValidationException;
import ome.io.nio.DimensionsOutOfBoundsException;
import ome.io.nio.PixelBuffer;
import ome.io.nio.PixelsService;
import ome.io.nio.RomioPixelBuffer;
import ome.io.nio.TileSizes;
import ome.model.core.Channel;
import ome.model.core.Pixels;
import ome.parameters.Parameters;
import ome.util.PixelData;
import ome.util.ShallowCopy;
import ome.util.SqlAction;
import omeis.providers.re.data.PlaneDef;
import omeis.providers.re.metadata.StatsFactory;

import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import com.google.common.collect.MapMaker;

/**
 * Implementation of the RawPixelsStore stateful service.
 *
 * @author <br>
 *         Josh Moore&nbsp;&nbsp;&nbsp;&nbsp; <a
 *         href="mailto:josh.moore@gmx.de"> josh.moore@gmx.de</a>
 * @version 3.0 <small> (<b>Internal version:</b> $Revision$ $Date: 2005/07/05
 *          16:13:52 $) </small>
 * @since OMERO3
 */
@Transactional(readOnly = true)
public class RawPixelsBean extends AbstractStatefulBean implements
        RawPixelsStore {
    /** The logger for this particular class */
    private static Logger log = LoggerFactory.getLogger(RawPixelsBean.class);

    private static final long serialVersionUID = -6640632220587930165L;

    /** The default bin size used for histograms */
    private static final int DEFAULT_HISTOGRAM_BINSIZE = 256;
    
    private Long id;

    private transient Long reset = null;

    private transient Pixels pixelsInstance;

    private transient PixelBuffer buffer;

    private transient PixelsService dataService;

    private transient IPixels metadataService;

    /** the disk space checking service */
    private transient IRepositoryInfo iRepositoryInfo;

    /** is file service checking for disk overflow */
    private transient boolean diskSpaceChecking;

    /** A copy buffer for the pixel retrieval. */
    private transient byte[] readBuffer;
    
    /** Pixels set cache. */
    private transient Map<Long, Pixels> pixelsCache;

    /** SQL action instance for this class. */
    private transient SqlAction sql;

    /** TileSizes */
    private TileSizes tileSizes;

    /**
     * default constructor
     */
    public RawPixelsBean() {
    }

    /**
     * overridden to allow Spring to set boolean
     * 
     * @param checking
     */
    public RawPixelsBean(boolean checking, String omeroDataDir) {
        this.diskSpaceChecking = checking;
        //omeroDataDir is no longer used by this class
    }

    public void setTileSizes(TileSizes tileSizes) {
        this.tileSizes = tileSizes;
    }

    public synchronized Class<? extends ServiceInterface> getServiceInterface() {
        return RawPixelsStore.class;
    }

    public synchronized final void setPixelsMetadata(IPixels metaService) {
        getBeanHelper().throwIfAlreadySet(this.metadataService, metaService);
        metadataService = metaService;
    }

    public synchronized final void setPixelsData(PixelsService dataService) {
        getBeanHelper().throwIfAlreadySet(this.dataService, dataService);
        this.dataService = dataService;
    }

    /**
     * Disk Space Usage service Bean injector
     * 
     * @param iRepositoryInfo
     *            an <code>IRepositoryInfo</code>
     */
    public synchronized final void setIRepositoryInfo(IRepositoryInfo iRepositoryInfo) {
        getBeanHelper()
                .throwIfAlreadySet(this.iRepositoryInfo, iRepositoryInfo);
        this.iRepositoryInfo = iRepositoryInfo;
    }

    /**
     * SQL action Bean injector
     * @param sql a <code>SqlAction</code>
     */
    public synchronized final void setSqlAction(SqlAction sql) {
        getBeanHelper().throwIfAlreadySet(this.sql, sql);
        this.sql = sql;
    }

    // ~ Lifecycle methods
    // =========================================================================

    // See documentation on JobBean#passivate
    @RolesAllowed("user")
    @Transactional(readOnly = true)    
    public synchronized void passivate() {
	// Nothing necessary
    }

    // See documentation on JobBean#activate
    @RolesAllowed("user")
    @Transactional(readOnly = true)    
    public synchronized void activate() {
        if (id != null) {
            reset = id;
            id = null;
        }
    }

    @RolesAllowed("user")
    @Transactional(readOnly = false)
    public synchronized Pixels save() {
        if (isModified()) {
            Long id = (pixelsInstance == null) ? null : pixelsInstance.getId();
            if (id == null) {
                return null;
            }

            try {
                byte[] hash = buffer.calculateMessageDigest();
                pixelsInstance.setSha1(Hex.encodeHexString(hash));

            } catch (RuntimeException re) {
                // ticket:3140
                if (re.getCause() instanceof FileNotFoundException) {
                    String msg = "Cannot find path. Deleted? " + buffer;
                    log.warn(msg);
                    clean(); // Prevent a second exception on close.
                    throw new ResourceError(msg);
                }
                throw re;
            } catch (IOException e) {
                log.warn("calculateMessageDigest failed on " + buffer, e);
                throw new ResourceError(e.getMessage());
            }

            iUpdate.flush();
            modified = false;
            return new ShallowCopy().copy(pixelsInstance);
        }
        return null;
    }

    @RolesAllowed("user")
    @Transactional(readOnly = false)
    public synchronized void close() {
        try {
            save();
        } catch (RootException root) {
            // ticket:3140
            // if one of our exceptions, then just rethrow
            throw root;
        } catch (RuntimeException re) {
            Long id = (pixelsInstance == null ? null : pixelsInstance.getId());
            log.error("Failed to update pixels: " + id, re);
        } finally {
            clean();
        }
    }

    public synchronized void clean() {
        dataService = null;
        pixelsInstance = null;
        try {
            closePixelBuffer();
        } finally {
            buffer = null;
            readBuffer = null;
            pixelsCache = null;
        }
    }

    /**
     * Close the active pixel buffer, cleaning up any potential messes left by
     * the pixel buffer itself.
     */
    private synchronized void closePixelBuffer() {
        try {
            if (buffer != null) {
                buffer.close();
            }
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("Buffer could not be closed successfully.", e);
            }
            throw new ResourceError(e.getMessage()
                    + " Please check server log.");
        }
    }

    @RolesAllowed("user")
    public synchronized void setPixelsId(long pixelsId, boolean bypassOriginalFile) {
        if (id == null || id.longValue() != pixelsId) {
            id = new Long(pixelsId);
            pixelsInstance = null;
            closePixelBuffer();
            buffer = null;
            reset = null;

            if (pixelsCache != null && pixelsCache.containsKey(pixelsId))
            {
            	pixelsInstance = pixelsCache.get(pixelsId);
            }
            else
            {
            	pixelsInstance = iQuery.findByQuery(
            			"select p from Pixels as p " +
            			"join fetch p.pixelsType "+ 
            			"left outer join fetch p.channels as c " +
            			"left outer join fetch c.logicalChannel as lc " +
            			"left outer join fetch c.statsInfo " +
            			"where p.id = :id",
            			new Parameters().addId(id));
            }

            if (pixelsInstance == null)
            {
                throw new ValidationException("Cannot read pixels id=" + id);
            }

            try {
                buffer = dataService.getPixelBuffer(pixelsInstance, true);
            } catch (RuntimeException re) {
                // Rolling back to let the next setPixelsId try again
                // since this is most likely our MissingPyramidException.
                // If it's anything more serious, then the instance
                // should most likely be closed.
                id = null;
                throw re;
            }
        }
    }

    @RolesAllowed("user")
    public synchronized long getPixelsId() {
        errorIfNotLoaded();

        return id.longValue();
    }

    @RolesAllowed("user")
    public String getPixelsPath() {
        errorIfNotLoaded();

        return buffer.getPath();
    }
    
    @RolesAllowed("user")
    public synchronized void prepare(Set<Long> pixelsIds)
    {
    	pixelsCache = new MapMaker().makeMap();
    	List<Pixels> pixelsList = iQuery.findAllByQuery(
    			"select p from Pixels as p join fetch p.pixelsType " +
        		"where p.id in (:ids)", new Parameters().addIds(pixelsIds));
    	for (Pixels pixels : pixelsList)
    	{
    		pixelsCache.put(pixels.getId(), pixels);
    	}
    }

    private synchronized void errorIfNotLoaded() {
        // If we're not loaded because of passivation, then load.
        if (reset != null) {
            id = null;
            setPixelsId(reset.longValue(), false);
            reset = null;
        }
        if (buffer == null) {
            throw new ApiUsageException(
                    "This RawPixelsStore has not been properly initialized.\n"
                            + "Please set the pixels id before executing any other methods.\n");
        }
    }

    // ~ Delegation
    // =========================================================================

    @RolesAllowed("user")
    public synchronized byte[] calculateMessageDigest() {
        errorIfNotLoaded();

        try {
            return buffer.calculateMessageDigest();
        } catch (Exception e) {
            handleException(e);
        }
        return null;
    }

    @RolesAllowed("user")
    public synchronized byte[] getHypercube(List<Integer> offset, List<Integer> size, List<Integer> step) {
        errorIfNotLoaded();

        int cubeSize = RomioPixelBuffer.safeLongToInteger(
                buffer.getHypercubeSize(offset, size, step));
        if (readBuffer == null || readBuffer.length != cubeSize) {
            readBuffer = new byte[cubeSize];
        }
        try {
            readBuffer = buffer.getHypercubeDirect(offset, size, step,
                    readBuffer);
        } catch (Exception e) {
            handleException(e);
        }
        return readBuffer;
    }

    @RolesAllowed("user")
    public synchronized byte[] getPlaneRegion(int z, int c, int t, int count, int offset) {
        errorIfNotLoaded();

        int size = RomioPixelBuffer.safeLongToInteger(
                buffer.getByteWidth() * (long) count);
        if (readBuffer == null || readBuffer.length != size) {
            readBuffer = new byte[size];
        }
        try {
            readBuffer = buffer.getPlaneRegionDirect(z, c, t, count, offset,
                    readBuffer);
        } catch (Exception e) {
            handleException(e);
        }
        return readBuffer;
    }

    @RolesAllowed("user")
    public synchronized byte[] getPlane(int arg0, int arg1, int arg2) {
        errorIfNotLoaded();

        int size = RomioPixelBuffer.safeLongToInteger(buffer.getPlaneSize());
        if (readBuffer == null || readBuffer.length != size) {
            readBuffer = new byte[size];
        }
        try {
            readBuffer = buffer.getPlaneDirect(arg0, arg1, arg2, readBuffer);
        } catch (Exception e) {
            handleException(e);
        }
        return readBuffer;
    }

    @RolesAllowed("user")
    public synchronized long getPlaneOffset(int arg0, int arg1, int arg2) {
        errorIfNotLoaded();

        try {
            return buffer.getPlaneOffset(arg0, arg1, arg2);
        } catch (Exception e) {
            handleException(e);
        }
        return -1;
    }

    /*
     * @inheritDoc
     * @see ome.io.nio.PixelBuffer#getPlaneSize()
     */
    @RolesAllowed("user")
    public synchronized long getPlaneSize() {
        errorIfNotLoaded();

        return buffer.getPlaneSize();
    }

    @RolesAllowed("user")
    public synchronized byte[] getRegion(int arg0, long arg1) {
        errorIfNotLoaded();

        PixelData pd = null;
        byte[] bytes = null;

        try {
            pd = buffer.getRegion(arg0, arg1);
            bytes = bufferAsByteArrayWithExceptionIfNull(pd.getData());
        } catch (Exception e) {
            handleException(e);
        } finally {
            if (pd != null) {
                pd.dispose();
            }
        }
        return bytes;
    }

    @RolesAllowed("user")
    public synchronized byte[] getRow(int arg0, int arg1, int arg2, int arg3) {
        errorIfNotLoaded();

        int size = buffer.getRowSize();
        if (readBuffer == null || readBuffer.length != size) {
            readBuffer = new byte[size];
        }
        try {
            readBuffer = buffer
                    .getRowDirect(arg0, arg1, arg2, arg3, readBuffer);
        } catch (Exception e) {
            handleException(e);
        }
        return readBuffer;
    }
    
    @RolesAllowed("user")
    public synchronized byte[] getCol(int arg0, int arg1, int arg2, int arg3) {
        errorIfNotLoaded();

        int size = buffer.getColSize();
        if (readBuffer == null || readBuffer.length != size) {
            readBuffer = new byte[size];
        }
        try {
            readBuffer = buffer
                    .getColDirect(arg0, arg1, arg2, arg3, readBuffer);
        } catch (Exception e) {
            handleException(e);
        }
        return readBuffer;
    }

    @RolesAllowed("user")
    public synchronized long getRowOffset(int arg0, int arg1, int arg2, int arg3) {
        errorIfNotLoaded();

        try {
            return buffer.getRowOffset(arg0, arg1, arg2, arg3);
        } catch (Exception e) {
            handleException(e);
        }
        return -1;
    }

    @RolesAllowed("user")
    public synchronized int getRowSize() {
        errorIfNotLoaded();

        return buffer.getRowSize();
    }

    @RolesAllowed("user")
    public synchronized byte[] getStack(int arg0, int arg1) {
        errorIfNotLoaded();

        int size = RomioPixelBuffer.safeLongToInteger(buffer.getStackSize());
        if (readBuffer == null || readBuffer.length != size) {
            readBuffer = new byte[size];
        }
        try {
            readBuffer = buffer.getStackDirect(arg0, arg1, readBuffer);
        } catch (Exception e) {
            handleException(e);
        }
        return readBuffer;
    }

    @RolesAllowed("user")
    public synchronized long getStackOffset(int arg0, int arg1) {
        errorIfNotLoaded();

        try {
            return buffer.getStackOffset(arg0, arg1);
        } catch (Exception e) {
            handleException(e);
        }
        return -1;
    }

    @RolesAllowed("user")
    public synchronized long getStackSize() {
        errorIfNotLoaded();

        return buffer.getStackSize();
    }

    @RolesAllowed("user")
    public synchronized byte[] getTimepoint(int arg0) {
        errorIfNotLoaded();

        int size = RomioPixelBuffer.safeLongToInteger(
                buffer.getTimepointSize());
        if (readBuffer == null || readBuffer.length != size) {
            readBuffer = new byte[size];
        }
        try {
            readBuffer = buffer.getTimepointDirect(arg0, readBuffer);
        } catch (Exception e) {
            handleException(e);
        }
        return readBuffer;
    }

    @RolesAllowed("user")
    public synchronized long getTimepointOffset(int arg0) {
        errorIfNotLoaded();

        try {
            return buffer.getTimepointOffset(arg0);
        } catch (Exception e) {
            handleException(e);
        }
        return -1;
    }

    @RolesAllowed("user")
    public synchronized long getTimepointSize() {
        errorIfNotLoaded();

        return buffer.getTimepointSize();
    }

    @RolesAllowed("user")
    public synchronized long getTotalSize() {
        errorIfNotLoaded();

        return buffer.getTotalSize();
    }

    @RolesAllowed("user")
    public synchronized int getByteWidth() {
        errorIfNotLoaded();

        return buffer.getByteWidth();
    }

    @RolesAllowed("user")
    public synchronized boolean isSigned() {
        errorIfNotLoaded();

        return buffer.isSigned();
    }

    @RolesAllowed("user")
    public synchronized boolean isFloat() {
        errorIfNotLoaded();

        return buffer.isFloat();
    }

    @RolesAllowed("user")
    public synchronized void setPlane(byte[] arg0, int arg1, int arg2, int arg3) {
        errorIfNotLoaded();

        if (diskSpaceChecking) {
            iRepositoryInfo.sanityCheckRepository();
        }

        try {
            buffer.setPlane(arg0, arg1, arg2, arg3);
            modified();
        } catch (Exception e) {
            handleException(e);
        }
    }

    @RolesAllowed("user")
    public synchronized void setRegion(int arg0, long arg1, byte[] arg2) {
        errorIfNotLoaded();

        if (diskSpaceChecking) {
            iRepositoryInfo.sanityCheckRepository();
        }

        try {
            buffer.setRegion(arg0, arg1, arg2);
            modified();
        } catch (Exception e) {
            handleException(e);
        }
    }

    @RolesAllowed("user")
    public synchronized void setRow(byte[] arg0, int arg1, int arg2, int arg3, int arg4) {
        errorIfNotLoaded();

        if (diskSpaceChecking) {
            iRepositoryInfo.sanityCheckRepository();
        }

        try {
            ByteBuffer buf = ByteBuffer.wrap(arg0);
            buffer.setRow(buf, arg1, arg2, arg3, arg4);
            modified();
        } catch (Exception e) {
            handleException(e);
        }
    }

    @RolesAllowed("user")
    public synchronized void setStack(byte[] arg0, int arg1, int arg2, int arg3) {
        errorIfNotLoaded();

        if (diskSpaceChecking) {
            iRepositoryInfo.sanityCheckRepository();
        }

        try {
            buffer.setStack(arg0, arg1, arg2, arg3);
            modified();
        } catch (Exception e) {
            handleException(e);
        }
    }

    @RolesAllowed("user")
    public synchronized void setTimepoint(byte[] arg0, int arg1) {
        errorIfNotLoaded();

        if (diskSpaceChecking) {
            iRepositoryInfo.sanityCheckRepository();
        }

        try {
            buffer.setTimepoint(arg0, arg1);
            modified();
        } catch (Exception e) {
            handleException(e);
        }
    }
    
    @RolesAllowed("user")
    public synchronized Map<Integer, int[]> getHistogram(int[] channels,
            int binCount, boolean globalRange, PlaneDef plane) {
        errorIfNotLoaded();

        //Find resolution level closest to max plane size without
        //exceeding it
        int resolutionLevel = -1;
        for (int i = 0; i < buffer.getResolutionLevels(); i++) {
            //If there's only 1 resolution level, we may have a
            //RomioPixelBuffer, which doesn't support setResolutionLevel,
            //so just check the size of the buffer
            //and use it if it's small enough
            if (buffer.getResolutionLevels() > 1) {
                buffer.setResolutionLevel(i);
            }
            if (buffer.getSizeX() > tileSizes.getMaxPlaneWidth() ||
                    buffer.getSizeY() > tileSizes.getMaxPlaneHeight()) {
                break;
            }
            resolutionLevel = i;
        }
        if (resolutionLevel < 0) {
            //No resolution levels exist smaller than max plane size
            throw new ApiUsageException("All resolution levels larger "
                    + "than max plane size");
        }
        if (buffer.getResolutionLevels() > 1) {
            buffer.setResolutionLevel(resolutionLevel);
        }

        if (binCount <= 0)
            binCount = DEFAULT_HISTOGRAM_BINSIZE;

        int imgWidth = buffer.getSizeX();

        int z = (plane != null && plane.getZ() >= 0) ? plane.getZ() : 0;
        int t = (plane != null && plane.getT() >= 0) ? plane.getT() : 0;
        int x = (plane != null && plane.getRegion() != null && plane
                .getRegion().getX() >= 0) ? plane.getRegion().getX() : 0;
        int y = (plane != null && plane.getRegion() != null && plane
                .getRegion().getY() >= 0) ? plane.getRegion().getY() : 0;
        int w = (plane != null && plane.getRegion() != null && plane
                .getRegion().getWidth() > 0) ? plane.getRegion().getWidth()
                : imgWidth;
        int h = (plane != null && plane.getRegion() != null && plane
                .getRegion().getHeight() > 0) ? plane.getRegion().getHeight()
                : buffer.getSizeY();

        Map<Integer, int[]> result = new HashMap<Integer, int[]>();

        try {
            for (int ch : channels) {
                Channel channel = pixelsInstance.getChannel(ch);
                if (channel == null)
                    continue;
                PixelData px = buffer.getPlane(z, ch, t);
                int[] data = new int[binCount];

                double[] minmax = determineHistogramMinMax(px, channel,
                        globalRange);
                double min = minmax[0];
                double max = minmax[1];

                double range = max - min;
                double binRange = range / binCount;
                for (int i = 0; i < px.size(); i++) {
                    int pxx = i % imgWidth;
                    int pxy = i / imgWidth;
                    if (pxx >= x && pxx < (x + w) && pxy >= y && pxy < (y + h)) {
                        if (px.getPixelValue(i) < min || px.getPixelValue(i) > max) {
                            continue;
                        } else {
                            int bin = (int) ((px.getPixelValue(i) - min) / binRange);
                            // Handle values exactly at the last edge
                            if (bin == binCount) {
                                bin--;
                            }
                            data[bin]++;
                        }
                    }
                }
                result.put(ch, data);
            }
        } catch (Exception e) {
            handleException(e);
        }

        return result;
    }

    @RolesAllowed("user")
    public synchronized Map<Integer, double[]> findMinMax(int[] channels) {
        Map<Integer, double[]> result = new HashMap<Integer, double[]>();
        
        if (requiresPixelsPyramid())
            return result;

        try {
            for (int ch : channels) {
                Channel channel = pixelsInstance.getChannel(ch);
                if (channel == null)
                    continue;
                int z = buffer.getSizeZ() > 1 ? (buffer.getSizeZ() - 1) / 2 : 0;
                int t = buffer.getSizeT() > 1 ? (buffer.getSizeT() - 1) / 2 : 0;
                PixelData px = buffer.getPlane(z, ch, t);
                double[] minmax = determineHistogramMinMax(px, channel, false);
                result.put(ch, minmax);
            }
        } catch (IOException e) {
            handleException(e);
        }
        return result;
    }
    
    // ~ Helpers
    // =========================================================================
    
    /**
     * Get the minimum and maximum value to use for the histogram. If useGlobal
     * is <code>true</code> and the channel has stats calculated the global
     * minimum and maximum will be used, otherwise the minimum and maximum value
     * of the plane will be used.
     * 
     * @param px
     *            The {@link PixelData}
     * @param channel
     *            The {@link Channel}
     * @param useGlobal
     *            Try to use the global minimum/maximum
     * @return See above
     */
    private double[] determineHistogramMinMax(PixelData px, Channel channel,
            boolean useGlobal) {
        double min, max;

        if (useGlobal && channel != null && channel.getStatsInfo() != null) {
            min = channel.getStatsInfo().getGlobalMin();
            max = channel.getStatsInfo().getGlobalMax();
            // if max == 1.0 the global min/max probably has not been
            // calculated; fall back to plane min/max
            if (max != 1.0)
                return new double[] { min, max };
        }

        StatsFactory sf = new StatsFactory();
        double[] pixelMinMax = sf.initPixelsRange(channel.getPixels());

        min = pixelMinMax[1];
        max = pixelMinMax[0];

        for (int i = 0; i < px.size(); i++) {
            min = Math.min(min, px.getPixelValue(i));
            max = Math.max(max, px.getPixelValue(i));
        }

        return new double[] { min, max };
    }
    
    private synchronized byte[] bufferAsByteArrayWithExceptionIfNull(ByteBuffer buffer) {
        byte[] b = new byte[buffer.capacity()];
        buffer.get(b, 0, buffer.capacity());
        return b;
    }

    private synchronized void handleException(Exception e) {

        if (e instanceof RootException) {
            throw (RootException) e; // Allow our own exceptions.
        }

        if (log.isDebugEnabled()) {
            log.debug("Error handling pixels.", e);
        }

        if (e instanceof IOException) {
            throw new ResourceError(e.getMessage());
        }
        if (e instanceof DimensionsOutOfBoundsException) {
            throw new ApiUsageException(e.getMessage());
        }
        if (e instanceof BufferUnderflowException) {
            throw new ResourceError("BufferUnderflowException: " + e.getMessage());
        }
        if (e instanceof BufferOverflowException) {
            throw new ResourceError("BufferOverflowException: " + e.getMessage());
        }
        
        // Fallthrough
        if (e instanceof RuntimeException) {
            throw (RuntimeException) e; // No reason to wrap if Runtime
        }
        throw new RuntimeException(e);
    }

    public synchronized boolean isDiskSpaceChecking() {
        return diskSpaceChecking;
    }

    public synchronized void setDiskSpaceChecking(boolean diskSpaceChecking) {
        this.diskSpaceChecking = diskSpaceChecking;
    }

    /* (non-Javadoc)
     * @see ome.api.RawPixelsStore#getResolutionLevels()
     */
    @RolesAllowed("user")
    public synchronized int getResolutionLevels()
    {
        errorIfNotLoaded();
        return buffer.getResolutionLevels();
    }

    @RolesAllowed("user")
    public synchronized List<List<Integer>> getResolutionDescriptions()
    {
        errorIfNotLoaded();
        return buffer.getResolutionDescriptions();
    }

    /* (non-Javadoc)
     * @see ome.api.RawPixelsStore#getTileSize()
     */
    @RolesAllowed("user")
    public synchronized int[] getTileSize()
    {
        errorIfNotLoaded();
        Dimension tileSize = buffer.getTileSize();
        return new int[] { (int) tileSize.getWidth(),
                           (int) tileSize.getHeight() };
    }

    /* (non-Javadoc)
     * @see ome.api.RawPixelsStore#requiresPixelsPyramid()
     */
    @RolesAllowed("user")
    public synchronized boolean requiresPixelsPyramid()
    {
        errorIfNotLoaded();
        return dataService.requiresPixelsPyramid(pixelsInstance);
    }

    /* (non-Javadoc)
     * @see ome.api.RawPixelsStore#getResolutionLevel()
     */
    @RolesAllowed("user")
    public synchronized int getResolutionLevel()
    {
        errorIfNotLoaded();
        return buffer.getResolutionLevel();
    }

    /* (non-Javadoc)
     * @see ome.api.RawPixelsStore#setResolutionLevel(int)
     */
    @RolesAllowed("user")
    public synchronized void setResolutionLevel(int resolutionLevel)
    {
        errorIfNotLoaded();
        buffer.setResolutionLevel(resolutionLevel);
    }

    /* (non-Javadoc)
     * @see ome.api.RawPixelsStore#getTile(int, int, int, int, int, int, int)
     */
    @RolesAllowed("user")
    public synchronized byte[] getTile(int z, int c, int t, int x, int y, int w, int h)
    {
        errorIfNotLoaded();

        int size = RomioPixelBuffer.safeLongToInteger(
                (long) w * (long) h * buffer.getByteWidth());
        if (readBuffer == null || readBuffer.length != size) {
            readBuffer = new byte[size];
        }
        try {
            readBuffer = buffer.getTileDirect(z, c, t, x, y, w, h, readBuffer);
        } catch (Exception e) {
            handleException(e);
        }
        return readBuffer;
    }

    /* (non-Javadoc)
     * @see ome.api.RawPixelsStore#setTile(byte[], int, int, int, int, int, int, int)
     */
    @RolesAllowed("user")
    public synchronized void setTile(byte[] data, int z, int c, int t, int x, int y,
            int w, int h)
    {
        errorIfNotLoaded();

        if (diskSpaceChecking) {
            iRepositoryInfo.sanityCheckRepository();
        }

        try {
            buffer.setTile(data, z, c, t, x, y, w, h);
            modified();
        } catch (Exception e) {
            handleException(e);
        }
    }

}
