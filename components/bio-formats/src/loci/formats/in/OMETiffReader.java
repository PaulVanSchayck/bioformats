//
// OMETiffReader.java
//

/*
OME Bio-Formats package for reading and converting biological file formats.
Copyright (C) 2005-@year@ UW-Madison LOCI and Glencoe Software, Inc.

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package loci.formats.in;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Vector;

import ome.xml.model.primitives.NonNegativeInteger;
import ome.xml.model.primitives.PositiveInteger;

import loci.common.Location;
import loci.common.RandomAccessInputStream;
import loci.common.services.DependencyException;
import loci.common.services.ServiceException;
import loci.common.services.ServiceFactory;
import loci.formats.CoreMetadata;
import loci.formats.FormatException;
import loci.formats.FormatReader;
import loci.formats.FormatTools;
import loci.formats.IFormatReader;
import loci.formats.MetadataTools;
import loci.formats.MissingLibraryException;
import loci.formats.meta.IMetadata;
import loci.formats.meta.MetadataStore;
import loci.formats.ome.OMEXMLMetadata;
import loci.formats.services.OMEXMLService;
import loci.formats.services.OMEXMLServiceImpl;
import loci.formats.tiff.IFD;
import loci.formats.tiff.IFDList;
import loci.formats.tiff.PhotoInterp;
import loci.formats.tiff.TiffParser;

/**
 * OMETiffReader is the file format reader for
 * <a href="http://ome-xml.org/wiki/OmeTiff">OME-TIFF</a> files.
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="http://dev.loci.wisc.edu/trac/java/browser/trunk/components/bio-formats/src/loci/formats/in/OMETiffReader.java">Trac</a>,
 * <a href="http://dev.loci.wisc.edu/svn/java/trunk/components/bio-formats/src/loci/formats/in/OMETiffReader.java">SVN</a></dd></dl>
 */
public class OMETiffReader extends FormatReader {

  // -- Fields --

  /** Mapping from series and plane numbers to files and IFD entries. */
  protected OMETiffPlane[][] info; // dimensioned [numSeries][numPlanes]

  /** List of used files. */
  protected String[] used;

  private int lastPlane;
  private boolean hasSPW;

  private int[] tileWidth;
  private int[] tileHeight;

  private OMEXMLService service;

  // -- Constructor --

  /** Constructs a new OME-TIFF reader. */
  public OMETiffReader() {
    super("OME-TIFF", new String[] {"ome.tif", "ome.tiff"});
    suffixNecessary = false;
    suffixSufficient = false;
    domains = FormatTools.NON_GRAPHICS_DOMAINS;
    hasCompanionFiles = true;
  }

  // -- IFormatReader API methods --

  /* @see loci.formats.IFormatReader#isSingleFile(String) */
  public boolean isSingleFile(String id) throws FormatException, IOException {
    // parse and populate OME-XML metadata
    String fileName = new Location(id).getAbsoluteFile().getAbsolutePath();
    RandomAccessInputStream ras = new RandomAccessInputStream(fileName);
    TiffParser tp = new TiffParser(ras);
    IFD ifd = tp.getFirstIFD();
    long[] ifdOffsets = tp.getIFDOffsets();
    ras.close();
    String xml = ifd.getComment();

    if (service == null) setupService();
    OMEXMLMetadata meta;
    try {
      meta = service.createOMEXMLMetadata(xml);
    }
    catch (ServiceException se) {
      throw new FormatException(se);
    }

    if (meta.getRoot() == null) {
      throw new FormatException("Could not parse OME-XML from TIFF comment");
    }

    int nImages = 0;
    for (int i=0; i<meta.getImageCount(); i++) {
      int nChannels = meta.getChannelCount(i);
      if (nChannels == 0) nChannels = 1;
      int z = meta.getPixelsSizeZ(i).getValue().intValue();
      int t = meta.getPixelsSizeT(i).getValue().intValue();
      nImages += z * t * nChannels;
    }
    return nImages <= ifdOffsets.length;
  }

  /* @see loci.formats.IFormatReader#isThisType(RandomAccessInputStream) */
  public boolean isThisType(RandomAccessInputStream stream) throws IOException {
    TiffParser tp = new TiffParser(stream);
    boolean validHeader = tp.isValidHeader();
    if (!validHeader) return false;
    // look for OME-XML in first IFD's comment
    IFD ifd = tp.getFirstIFD();
    if (ifd == null) return false;
    String comment = ifd.getComment();
    if (comment == null || comment.trim().length() == 0) return false;

    try {
      if (service == null) setupService();
      IMetadata meta = service.createOMEXMLMetadata(comment.trim());
      for (int i=0; i<meta.getImageCount(); i++) {
        MetadataTools.verifyMinimumPopulated(meta, i);
      }
      return true;
    }
    catch (ServiceException se) { }
    catch (NullPointerException e) { }
    catch (FormatException e) { }
    return false;
  }

  /* @see loci.formats.IFormatReader#getDomains() */
  public String[] getDomains() {
    FormatTools.assertId(currentId, true, 1);
    return hasSPW ? new String[] {FormatTools.HCS_DOMAIN} :
      FormatTools.NON_SPECIAL_DOMAINS;
  }

  /* @see loci.formats.IFormatReader#get8BitLookupTable() */
  public byte[][] get8BitLookupTable() throws FormatException, IOException {
    if (info[series][lastPlane] == null ||
      info[series][lastPlane].reader == null ||
      info[series][lastPlane].id == null)
    {
      return null;
    }
    info[series][lastPlane].reader.setId(info[series][lastPlane].id);
    return info[series][lastPlane].reader.get8BitLookupTable();
  }

  /* @see loci.formats.IFormatReader#get16BitLookupTable() */
  public short[][] get16BitLookupTable() throws FormatException, IOException {
    if (info[series][lastPlane] == null ||
      info[series][lastPlane].reader == null ||
      info[series][lastPlane].id == null)
    {
      return null;
    }
    info[series][lastPlane].reader.setId(info[series][lastPlane].id);
    return info[series][lastPlane].reader.get16BitLookupTable();
  }

  /*
   * @see loci.formats.IFormatReader#openBytes(int, byte[], int, int, int, int)
   */
  public byte[] openBytes(int no, byte[] buf, int x, int y, int w, int h)
    throws FormatException, IOException
  {
    FormatTools.checkPlaneParameters(this, no, buf.length, x, y, w, h);
    lastPlane = no;
    int i = info[series][no].ifd;
    MinimalTiffReader r = (MinimalTiffReader) info[series][no].reader;
    if (r.getCurrentFile() == null) {
      r.setId(info[series][no].id);
    }
    IFDList ifdList = r.getIFDs();
    if (i >= ifdList.size()) {
      LOGGER.warn("Error untangling IFDs; the OME-TIFF file may be malformed.");
      return buf;
    }
    IFD ifd = ifdList.get(i);
    RandomAccessInputStream s =
      new RandomAccessInputStream(info[series][no].id);
    TiffParser p = new TiffParser(s);
    p.getSamples(ifd, buf, x, y, w, h);
    s.close();
    return buf;
  }

  /* @see loci.formats.IFormatReader#getSeriesUsedFiles(boolean) */
  public String[] getSeriesUsedFiles(boolean noPixels) {
    FormatTools.assertId(currentId, true, 1);
    if (noPixels) return null;
    Vector<String> usedFiles = new Vector<String>();
    for (int i=0; i<info[series].length; i++) {
      if (!usedFiles.contains(info[series][i].id)) {
        usedFiles.add(info[series][i].id);
      }
    }
    return usedFiles.toArray(new String[usedFiles.size()]);
  }

  /* @see loci.formats.IFormatReader#fileGroupOption() */
  public int fileGroupOption(String id) {
    return FormatTools.MUST_GROUP;
  }

  /* @see loci.formats.IFormatReader#close(boolean) */
  public void close(boolean fileOnly) throws IOException {
    super.close(fileOnly);
    if (info != null) {
      for (OMETiffPlane[] dimension : info) {
        for (OMETiffPlane plane : dimension) {
          if (plane.reader != null) {
            try {
              plane.reader.close();
            }
            catch (Exception e) {
              LOGGER.error("Plane closure failure!", e);
            }
          }
        }
      }
    }
    if (!fileOnly) {
      info = null;
      used = null;
      lastPlane = 0;
      tileWidth = null;
      tileHeight = null;
    }
  }

  /* @see loci.formats.IFormatReader#getOptimalTileWidth() */
  public int getOptimalTileWidth() {
    FormatTools.assertId(currentId, true, 1);
    return tileWidth[getSeries()];
  }

  /* @see loci.formats.IFormatReader#getOptimalTileHeight() */
  public int getOptimalTileHeight() {
    FormatTools.assertId(currentId, true, 1);
    return tileHeight[getSeries()];
  }

  // -- Internal FormatReader API methods --

  /* @see loci.formats.FormatReader#initFile(String) */
  protected void initFile(String id) throws FormatException, IOException {
    // normalize file name
    super.initFile(normalizeFilename(null, id));
    id = currentId;
    String dir = new File(id).getParent();

    // parse and populate OME-XML metadata
    String fileName = new Location(id).getAbsoluteFile().getAbsolutePath();
    RandomAccessInputStream ras = new RandomAccessInputStream(fileName);
    String xml;
    IFD firstIFD;
    try {
      TiffParser tp = new TiffParser(ras);
      firstIFD = tp.getFirstIFD();
      xml = firstIFD.getComment();
    }
    finally {
      ras.close();
    }

    if (service == null) setupService();
    OMEXMLMetadata meta;
    try {
      meta = service.createOMEXMLMetadata(xml);
    }
    catch (ServiceException se) {
      throw new FormatException(se);
    }

    hasSPW = meta.getPlateCount() > 0;

    for (int i=0; i<meta.getImageCount(); i++) {
      int sizeC = meta.getPixelsSizeC(i).getValue().intValue();
      service.removeChannels(meta, i, sizeC);
    }

    // TODO
    //Hashtable originalMetadata = meta.getOriginalMetadata();
    //if (originalMetadata != null) metadata = originalMetadata;

    LOGGER.trace(xml);

    if (meta.getRoot() == null) {
      throw new FormatException("Could not parse OME-XML from TIFF comment");
    }

    String currentUUID = meta.getUUID();
    service.convertMetadata(meta, metadataStore);

    // determine series count from Image and Pixels elements
    int seriesCount = meta.getImageCount();
    core = new CoreMetadata[seriesCount];
    for (int i=0; i<seriesCount; i++) {
      core[i] = new CoreMetadata();
    }
    info = new OMETiffPlane[seriesCount][];

    tileWidth = new int[seriesCount];
    tileHeight = new int[seriesCount];

    // compile list of file/UUID mappings
    Hashtable<String, String> files = new Hashtable<String, String>();
    boolean needSearch = false;
    for (int i=0; i<seriesCount; i++) {
      int tiffDataCount = meta.getTiffDataCount(i);
      for (int td=0; td<tiffDataCount; td++) {
        String uuid = null;
        try {
          uuid = meta.getUUIDValue(i, td);
        }
        catch (NullPointerException e) { }
        String filename = null;
        if (uuid == null) {
          // no UUID means that TiffData element refers to this file
          uuid = "";
          filename = id;
        }
        else {
          filename = meta.getUUIDFileName(i, td);
          if (!new Location(dir, filename).exists()) filename = null;
          if (filename == null) {
            if (uuid.equals(currentUUID) || currentUUID == null) {
              // UUID references this file
              filename = id;
            }
            else {
              // will need to search for this UUID
              filename = "";
              needSearch = true;
            }
          }
          else filename = normalizeFilename(dir, filename);
        }
        String existing = files.get(uuid);
        if (existing == null) files.put(uuid, filename);
        else if (!existing.equals(filename)) {
          throw new FormatException("Inconsistent UUID filenames");
        }
      }
    }

    // search for missing filenames
    if (needSearch) {
      Enumeration en = files.keys();
      while (en.hasMoreElements()) {
        String uuid = (String) en.nextElement();
        String filename = files.get(uuid);
        if (filename.equals("")) {
          // TODO search...
          // should scan only other .ome.tif files
          // to make this work with OME server may be a little tricky?
          throw new FormatException("Unmatched UUID: " + uuid);
        }
      }
    }

    // build list of used files
    Enumeration en = files.keys();
    int numUUIDs = files.size();
    HashSet fileSet = new HashSet(); // ensure no duplicate filenames
    for (int i=0; i<numUUIDs; i++) {
      String uuid = (String) en.nextElement();
      String filename = files.get(uuid);
      fileSet.add(filename);
    }
    used = new String[fileSet.size()];
    Iterator iter = fileSet.iterator();
    for (int i=0; i<used.length; i++) used[i] = (String) iter.next();

    // process TiffData elements
    Hashtable<String, IFormatReader> readers =
      new Hashtable<String, IFormatReader>();
    for (int i=0; i<seriesCount; i++) {
      int s = i;
      LOGGER.debug("Image[{}] {", i);
      LOGGER.debug("  id = {}", meta.getImageID(i));

      String order = meta.getPixelsDimensionOrder(i).toString();

      PositiveInteger samplesPerPixel = null;
      if (meta.getChannelCount(i) > 0) {
        samplesPerPixel = meta.getChannelSamplesPerPixel(i, 0);
      }
      int samples = samplesPerPixel == null ?  -1 : samplesPerPixel.getValue();
      int tiffSamples = firstIFD.getSamplesPerPixel();
      if (samples != tiffSamples) {
        LOGGER.warn("SamplesPerPixel mismatch: OME={}, TIFF={}",
          samples, tiffSamples);
        samples = tiffSamples;
      }

      int effSizeC = meta.getPixelsSizeC(i).getValue().intValue() / samples;
      if (effSizeC == 0) effSizeC = 1;
      if (effSizeC * samples != meta.getPixelsSizeC(i).getValue().intValue()) {
        effSizeC = meta.getPixelsSizeC(i).getValue().intValue();
      }
      int sizeT = meta.getPixelsSizeT(i).getValue().intValue();
      int sizeZ = meta.getPixelsSizeZ(i).getValue().intValue();
      int num = effSizeC * sizeT * sizeZ;

      OMETiffPlane[] planes = new OMETiffPlane[num];
      for (int no=0; no<num; no++) planes[no] = new OMETiffPlane();

      int tiffDataCount = meta.getTiffDataCount(i);
      boolean zOneIndexed = false;
      boolean cOneIndexed = false;
      boolean tOneIndexed = false;

      // pre-scan TiffData indices to see if any of them are indexed from 1

      for (int td=0; td<tiffDataCount; td++) {
        NonNegativeInteger firstC = meta.getTiffDataFirstC(i, td);
        NonNegativeInteger firstT = meta.getTiffDataFirstT(i, td);
        NonNegativeInteger firstZ = meta.getTiffDataFirstZ(i, td);
        int c = firstC == null ? 0 : firstC.getValue();
        int t = firstT == null ? 0 : firstT.getValue();
        int z = firstZ == null ? 0 : firstZ.getValue();

        if (c >= effSizeC) cOneIndexed = true;
        if (z >= sizeZ) zOneIndexed = true;
        if (t >= sizeT) tOneIndexed = true;
      }

      for (int td=0; td<tiffDataCount; td++) {
        LOGGER.debug("    TiffData[{}] {", td);
        // extract TiffData parameters
        String filename = null;
        String uuid = null;
        try {
          filename = meta.getUUIDFileName(i, td);
        } catch (NullPointerException e) {
          LOGGER.debug("Ignoring null UUID object when retrieving filename.");
        }
        try {
          uuid = meta.getUUIDValue(i, td);
        } catch (NullPointerException e) {
          LOGGER.debug("Ignoring null UUID object when retrieving value.");
        }
        NonNegativeInteger tdIFD = meta.getTiffDataIFD(i, td);
        int ifd = tdIFD == null ? 0 : tdIFD.getValue();
        NonNegativeInteger numPlanes = meta.getTiffDataPlaneCount(i, td);
        NonNegativeInteger firstC = meta.getTiffDataFirstC(i, td);
        NonNegativeInteger firstT = meta.getTiffDataFirstT(i, td);
        NonNegativeInteger firstZ = meta.getTiffDataFirstZ(i, td);
        int c = firstC == null ? 0 : firstC.getValue();
        int t = firstT == null ? 0 : firstT.getValue();
        int z = firstZ == null ? 0 : firstZ.getValue();

        // NB: some writers index FirstC, FirstZ and FirstT from 1
        if (cOneIndexed) c--;
        if (zOneIndexed) z--;
        if (tOneIndexed) t--;

        int index = FormatTools.getIndex(order,
          sizeZ, effSizeC, sizeT, num, z, c, t);
        int count = numPlanes == null ? 1 : numPlanes.getValue();
        if (count == 0) {
          core[s] = null;
          break;
        }

        // get reader object for this filename
        if (filename == null) {
          if (uuid == null) filename = id;
          else filename = files.get(uuid);
        }
        else filename = normalizeFilename(dir, filename);
        IFormatReader r = readers.get(filename);
        if (r == null) {
          r = new MinimalTiffReader();
          readers.put(filename, r);
        }

        Location file = new Location(filename);
        if (!file.exists()) {
          // if this is an absolute file name, try using a relative name
          // old versions of OMETiffWriter wrote an absolute path to
          // UUID.FileName, which causes problems if the file is moved to
          // a different directory
          filename =
            filename.substring(filename.lastIndexOf(File.separator) + 1);
          filename = dir + File.separator + filename;

          if (!new Location(filename).exists()) {
            filename = currentId;
          }
        }

        // populate plane index -> IFD mapping
        for (int q=0; q<count; q++) {
          int no = index + q;
          planes[no].reader = r;
          planes[no].id = filename;
          planes[no].ifd = ifd + q;
          planes[no].certain = true;
          LOGGER.debug("      Plane[{}]: file={}, IFD={}",
            new Object[] {no, planes[no].id, planes[no].ifd});
        }
        if (numPlanes == null) {
          // unknown number of planes; fill down
          for (int no=index+1; no<num; no++) {
            if (planes[no].certain) break;
            planes[no].reader = r;
            planes[no].id = filename;
            planes[no].ifd = planes[no - 1].ifd + 1;
            LOGGER.debug("      Plane[{}]: FILLED", no);
          }
        }
        else {
          // known number of planes; clear anything subsequently filled
          for (int no=index+count; no<num; no++) {
            if (planes[no].certain) break;
            planes[no].reader = null;
            planes[no].id = null;
            planes[no].ifd = -1;
            LOGGER.debug("      Plane[{}]: CLEARED", no);
          }
        }
        LOGGER.debug("    }");
      }

      if (core[s] == null) continue;

      // verify that all planes are available
      LOGGER.debug("    --------------------------------");
      for (int no=0; no<num; no++) {
        LOGGER.debug("    Plane[{}]: file={}, IFD={}",
          new Object[] {no, planes[no].id, planes[no].ifd});
        if (planes[no].reader == null) {
          LOGGER.warn("Image ID '{}': missing plane #{}.  " +
            "Using TiffReader to determine the number of planes.",
            meta.getImageID(i), no);
          TiffReader r = new TiffReader();
          r.setId(currentId);
          try {
            planes = new OMETiffPlane[r.getImageCount()];
            for (int plane=0; plane<planes.length; plane++) {
              planes[plane] = new OMETiffPlane();
              planes[plane].id = currentId;
              planes[plane].reader = r;
              planes[plane].ifd = plane;
            }
            num = planes.length;
          }
          finally {
            r.close();
          }
        }
      }
      LOGGER.debug("  }");

      // populate core metadata
      info[s] = planes;
      try {
        info[s][0].reader.setId(info[s][0].id);
        tileWidth[s] = info[s][0].reader.getOptimalTileWidth();
        tileHeight[s] = info[s][0].reader.getOptimalTileHeight();

        core[s].sizeX = meta.getPixelsSizeX(i).getValue().intValue();
        int tiffWidth = (int) firstIFD.getImageWidth();
        if (core[s].sizeX != tiffWidth) {
          LOGGER.warn("SizeX mismatch: OME={}, TIFF={}",
            core[s].sizeX, tiffWidth);
        }
        core[s].sizeY = meta.getPixelsSizeY(i).getValue().intValue();
        int tiffHeight = (int) firstIFD.getImageLength();
        if (core[s].sizeY != tiffHeight) {
          LOGGER.warn("SizeY mismatch: OME={}, TIFF={}",
            core[s].sizeY, tiffHeight);
        }
        core[s].sizeZ = meta.getPixelsSizeZ(i).getValue().intValue();
        core[s].sizeC = meta.getPixelsSizeC(i).getValue().intValue();
        core[s].sizeT = meta.getPixelsSizeT(i).getValue().intValue();
        core[s].pixelType = FormatTools.pixelTypeFromString(
          meta.getPixelsType(i).toString());
        int tiffPixelType = firstIFD.getPixelType();
        if (core[s].pixelType != tiffPixelType) {
          LOGGER.warn("PixelType mismatch: OME={}, TIFF={}",
            core[s].pixelType, tiffPixelType);
          core[s].pixelType = tiffPixelType;
        }
        core[s].imageCount = num;
        core[s].dimensionOrder = meta.getPixelsDimensionOrder(i).toString();

        core[s].orderCertain = true;
        PhotoInterp photo = firstIFD.getPhotometricInterpretation();
        core[s].rgb = samples > 1 || photo == PhotoInterp.RGB;
        if ((samples != core[s].sizeC && (samples % core[s].sizeC) != 0 &&
          (core[s].sizeC % samples) != 0) || core[s].sizeC == 1)
        {
          core[s].sizeC *= samples;
        }

        if (core[s].sizeZ * core[s].sizeT * core[s].sizeC >
          core[s].imageCount && !core[s].rgb)
        {
          if (core[s].sizeZ == core[s].imageCount) {
            core[s].sizeT = 1;
            core[s].sizeC = 1;
          }
          else if (core[s].sizeT == core[s].imageCount) {
            core[s].sizeZ = 1;
            core[s].sizeC = 1;
          }
          else if (core[s].sizeC == core[s].imageCount) {
            core[s].sizeT = 1;
            core[s].sizeZ = 1;
          }
        }

        if (meta.getPixelsBinDataCount(i) > 1) {
          LOGGER.warn("OME-TIFF Pixels element contains BinData elements! " +
                      "Ignoring.");
        }
        core[s].littleEndian = firstIFD.isLittleEndian();
        core[s].interleaved = false;
        core[s].indexed = photo == PhotoInterp.RGB_PALETTE &&
          firstIFD.getIFDValue(IFD.COLOR_MAP) != null;
        if (core[s].indexed) {
          core[s].rgb = false;
        }
        core[s].falseColor = true;
        core[s].metadataComplete = true;
      }
      catch (NullPointerException exc) {
        throw new FormatException("Incomplete Pixels metadata", exc);
      }
    }

    // remove null CoreMetadata entries

    Vector<CoreMetadata> series = new Vector<CoreMetadata>();
    Vector<OMETiffPlane[]> planeInfo = new Vector<OMETiffPlane[]>();
    for (int i=0; i<core.length; i++) {
      if (core[i] != null) {
        series.add(core[i]);
        planeInfo.add(info[i]);
      }
    }
    core = series.toArray(new CoreMetadata[series.size()]);
    info = planeInfo.toArray(new OMETiffPlane[0][0]);

    MetadataTools.populatePixels(metadataStore, this, false, false);
    metadataStore = getMetadataStoreForConversion();
  }

  // -- OMETiffReader API methods --

  /**
   * Returns a MetadataStore that is populated in such a way as to
   * produce valid OME-XML.  The returned MetadataStore cannot be used
   * by an IFormatWriter, as it will not contain the required
   * BinData.BigEndian attributes.
   */
  public MetadataStore getMetadataStoreForDisplay() {
    MetadataStore store = getMetadataStore();
    if (service.isOMEXMLMetadata(store)) {
      service.removeBinData((OMEXMLMetadata) store);
      for (int i=0; i<getSeriesCount(); i++) {
        if (((OMEXMLMetadata) store).getTiffDataCount(i) == 0) {
          service.addMetadataOnly((OMEXMLMetadata) store, i);
        }
      }
    }
    return store;
  }

  /**
   * Returns a MetadataStore that is populated in such a way as to be
   * usable by an IFormatWriter.  Any OME-XML generated from this
   * MetadataStore is <em>very unlikely</em> to be valid, as more than
   * likely both BinData and TiffData element will be present.
   */
  public MetadataStore getMetadataStoreForConversion() {
    MetadataStore store = getMetadataStore();
    int realSeries = getSeries();
    for (int i=0; i<getSeriesCount(); i++) {
      setSeries(i);
      store.setPixelsBinDataBigEndian(new Boolean(!isLittleEndian()), i, 0);
    }
    setSeries(realSeries);
    return store;
  }

  // -- Helper methods --

  private String normalizeFilename(String dir, String name) {
     File file = new File(dir, name);
     if (file.exists()) return file.getAbsolutePath();
     return new Location(name).getAbsolutePath();
  }

  private void setupService() throws FormatException {
    try {
      ServiceFactory factory = new ServiceFactory();
      service = factory.getInstance(OMEXMLService.class);
    }
    catch (DependencyException de) {
      throw new MissingLibraryException(OMEXMLServiceImpl.NO_OME_XML_MSG, de);
    }
  }

  // -- Helper classes --

  /** Structure containing details on where to find a particular image plane. */
  private class OMETiffPlane {
    /** Reader to use for accessing this plane. */
    public IFormatReader reader;
    /** File containing this plane. */
    public String id;
    /** IFD number of this plane. */
    public int ifd = -1;
    /** Certainty flag, for dealing with unspecified NumPlanes. */
    public boolean certain = false;
  }

}
