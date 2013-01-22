/*
 * #%L
 * OME SCIFIO package for reading and converting scientific file formats.
 * %%
 * Copyright (C) 2005 - 2012 Open Microscopy Environment:
 *   - Board of Regents of the University of Wisconsin-Madison
 *   - Glencoe Software, Inc.
 *   - University of Dundee
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * 
 * The views and conclusions contained in the software and documentation are
 * those of the authors and should not be interpreted as representing official
 * policies, either expressed or implied, of any organization.
 * #L%
 */

package loci.formats.services;

import java.io.IOException;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import loci.common.ByteArrayHandle;
import loci.common.DataTools;
import loci.common.RandomAccessInputStream;
import loci.common.Region;
import loci.common.services.DependencyException;
import loci.common.services.Service;
import loci.common.services.ServiceException;

import org.libjpegturbo.turbojpeg.TJ;
import org.libjpegturbo.turbojpeg.TJDecompressor;

import org.scijava.nativelib.NativeLibraryUtil;

/**
 * Based upon the NDPI to OME-TIFF converter by Matthias Baldauf:
 *
 * http://matthias-baldauf.at/software/ndpi_converter/
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="http://trac.openmicroscopy.org.uk/ome/browser/bioformats.git/components/bio-formats/src/loci/formats/services/JPEGTurboServiceImpl.java">Trac</a>,
 * <a href="http://git.openmicroscopy.org/?p=bioformats.git;a=blob;f=components/bio-formats/src/loci/formats/services/JPEGTurboServiceImpl.java;hb=HEAD">Gitweb</a></dd></dl>
 *
 * @author Melissa Linkert <melissa at glencoesoftware.com>
 */
public class JPEGTurboServiceImpl implements JPEGTurboService {

  // -- Constants --

  private static final String NATIVE_LIB_CLASS =
    "org.scijava.nativelib.NativeLibraryUtil";

  private static final int SOF0 = 0xffc0;

  private static final int DRI = 0xffdd;
  private static final int SOS = 0xffda;

  private static final int RST0 = 0xffd0;
  private static final int RST7 = 0xffd7;

  private static final int EOI = 0xffd9;

  // -- Fields --

  private Logger logger;
  private int imageWidth;
  private int imageHeight;
  private long offset;
  private RandomAccessInputStream in;

  private int restartInterval = 1;
  private long sos;
  private long imageDimensions;

  private int tileDim;
  private int xTiles;
  private int yTiles;

  private ArrayList<Long> restartMarkers = new ArrayList<Long>();

  private byte[] header;

  // -- Constructor --

  public JPEGTurboServiceImpl() {
    logger = Logger.getLogger(NATIVE_LIB_CLASS);
    logger.setLevel(Level.SEVERE);
    NativeLibraryUtil.loadNativeLibrary(TJ.class, "turbojpeg");
  }

  // -- JPEGTurboService API methods --

  public void setRestartMarkers(long[] markers) {
    restartMarkers.clear();
    if (markers != null) {
      for (long marker : markers) {
        restartMarkers.add(marker);
      }
    }
  }

  public long[] getRestartMarkers() {
    long[] markers = new long[restartMarkers.size()];
    for (int i=0; i<markers.length; i++) {
      markers[i] = restartMarkers.get(i);
    }
    return markers;
  }

  public void initialize(RandomAccessInputStream jpeg, int width, int height)
    throws ServiceException, IOException
  {
    in = jpeg;
    imageWidth = width;
    imageHeight = height;
    offset = jpeg.getFilePointer();

    in.skipBytes(2);
    int marker = in.readShort() & 0xffff;

    boolean inImage = false;
    while (marker != EOI && in.getFilePointer() + 2 < in.length()) {
      int length = in.readShort() & 0xffff;
      long end = in.getFilePointer() + length - 2;

      if (marker == DRI) {
        restartInterval = in.readShort() & 0xffff;
      }
      else if (marker == SOF0) {
        imageDimensions = in.getFilePointer() + 1;
      }
      else if (marker == SOS) {
        sos = end;
        inImage = true;
        if (restartMarkers.size() == 0) {
          restartMarkers.add(sos);
        }
        else {
          long diff = sos - restartMarkers.get(0);
          for (int i=0; i<restartMarkers.size(); i++) {
            long original = restartMarkers.get(i);
            original += diff;
            restartMarkers.set(i, original);
          }
          break;
        }
      }
      else if (marker >= RST0 && marker <= RST7) {
        restartMarkers.add(in.getFilePointer() - 2);
        in.skipBytes(restartInterval * 2);
      }

      if (end < in.length() && !inImage) {
        in.seek(end);
      }
      else if (inImage) {
        in.seek(in.getFilePointer() - 3);
      }
      marker = in.readShort() & 0xffff;
    }

    tileDim = restartInterval * 8;

    xTiles = imageWidth / tileDim;
    yTiles = imageHeight / tileDim;

    if (xTiles * tileDim != imageWidth) {
      xTiles++;
    }
    if (yTiles * tileDim != imageHeight) {
      yTiles++;
    }
  }

  public byte[] getTile(byte[] buf, int xCoordinate, int yCoordinate,
    int width, int height)
    throws IOException
  {
    Region image = new Region(xCoordinate, yCoordinate, width, height);

    int bufX = 0;
    int bufY = 0;

    int outputRowLen = width * 3;

    Region intersection = null;
    Region tileBoundary = new Region(0, 0, 0, 0);
    byte[] tile = null;
    for (int row=0; row<yTiles; row++) {
      tileBoundary.height = row < yTiles - 1 ? tileDim : imageHeight % tileDim;
      tileBoundary.y = row * tileDim;
      for (int col=0; col<xTiles; col++) {
        tileBoundary.x = col * tileDim;
        tileBoundary.width = col < xTiles - 1 ? tileDim : imageWidth % tileDim;
        if (tileBoundary.intersects(image)) {
          intersection = image.intersection(tileBoundary);
          tile = getTile(col, row);

          int rowLen =
            3 * (int) Math.min(tileBoundary.width, intersection.width);
          int outputOffset = bufY * outputRowLen + bufX;
          int intersectionX = 0;

          if (tileBoundary.x < image.x) {
            intersectionX = image.x - tileBoundary.x;
          }

          for (int trow=0; trow<intersection.height; trow++) {
            int realRow = trow + intersection.y - tileBoundary.y;
            int inputOffset =
              3 * (realRow * tileBoundary.width + intersectionX);
            System.arraycopy(tile, inputOffset, buf, outputOffset, rowLen);
            outputOffset += outputRowLen;
          }
          bufX += rowLen;
        }
      }
      if (intersection != null) {
        bufX = 0;
        bufY += intersection.height;
      }
      if (bufY >= height) {
        break;
      }
    }

    return buf;
  }

  public byte[] getTile(int tileX, int tileY) throws IOException {
    if (header == null) {
      header = getFixedHeader();
    }

    int dataLength = header.length + 2;

    int start = tileX + (tileY * xTiles * restartInterval);
    for (int row=0; row<restartInterval; row++) {
      int end = start + 1;

      if (end < restartMarkers.size()) {
        long startOffset = restartMarkers.get(start);
        long endOffset = restartMarkers.get(end);

        dataLength += (int) (endOffset - startOffset);
      }
      start += xTiles;
    }

    byte[] data = new byte[dataLength];

    int offset = 0;
    System.arraycopy(header, 0, data, offset, header.length);
    offset += header.length;

    start = tileX + (tileY * xTiles * restartInterval);
    for (int row=0; row<restartInterval; row++) {
      int end = start + 1;

      if (end < restartMarkers.size()) {
        long startOffset = restartMarkers.get(start);
        long endOffset = restartMarkers.get(end);

        in.seek(startOffset);
        in.read(data, offset, (int) (endOffset - startOffset - 2));
        offset += (int) (endOffset - startOffset - 2);

        DataTools.unpackBytes(0xffd0 + (row % 8), data, offset, 2, false);
        offset += 2;
      }
      start += xTiles;
    }

    DataTools.unpackBytes(EOI, data, offset, 2, false);

    // and here we actually decompress it...

    try {
      int pixelType = TJ.PF_RGB;
      int pixelSize = TJ.getPixelSize(pixelType);

      TJDecompressor decoder = new TJDecompressor(data);
      byte[] decompressed = decoder.decompress(tileDim, tileDim * pixelSize,
        tileDim, pixelType, pixelType);
      data = null;
      decoder.close();
      return decompressed;
    }
    catch (Exception e) {
      throw new IOException("", e);
    }
  }

  public void close() throws IOException {
    logger = null;
    imageWidth = 0;
    imageHeight = 0;
    in = null;
    offset = 0;
    restartMarkers.clear();
    restartInterval = 1;
    sos = 0;
    imageDimensions = 0;
    tileDim = 0;
    xTiles = 0;
    yTiles = 0;
    header = null;
  }

  // -- Helper methods --

  private byte[] getFixedHeader() throws IOException {
    in.seek(offset);

    byte[] header = new byte[(int) (sos - offset)];
    in.read(header);

    int index = (int) (imageDimensions - offset);
    DataTools.unpackBytes(tileDim, header, index, 2, false);
    DataTools.unpackBytes(tileDim, header, index + 2, 2, false);

    return header;
  }

}
