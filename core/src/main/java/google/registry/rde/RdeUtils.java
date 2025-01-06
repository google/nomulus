// Copyright 2025 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.rde;

import static google.registry.util.HexDumper.dumpHex;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.Ordering;
import com.google.common.io.BaseEncoding;
import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import google.registry.gcs.GcsUtils;
import google.registry.request.HttpException.NoContentException;
import google.registry.xjc.rde.XjcRdeRrType;
import google.registry.xml.XmlException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.joda.time.DateTime;
import org.joda.time.ReadableInstant;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

/** Helper methods for RDE. */
public final class RdeUtils {

  /** Number of bytes in head of XML deposit that will contain the information we want. */
  private static final int PEEK_SIZE = 2048;

  /** Regular expression for extracting creation timestamp from a raw XML deposit. */
  private static final Pattern WATERMARK_PATTERN = Pattern.compile("[<:]watermark>\\s*([^<\\s]+)");

  /** Standard ISO date/time formatter without milliseconds. Used for watermarks. */
  private static final DateTimeFormatter DATETIME_FORMATTER =
      ISODateTimeFormat.dateTimeNoMillis().withZoneUTC();

  /**
   * Look at some bytes from {@code xmlInput} to ensure it appears to be a FULL XML deposit and
   * then use a regular expression to extract the watermark timestamp which is returned.
   */
  public static DateTime peekWatermark(BufferedInputStream xmlInput)
      throws IOException, XmlException {
    xmlInput.mark(PEEK_SIZE);
    byte[] peek = new byte[PEEK_SIZE];
    if (xmlInput.read(peek) != PEEK_SIZE) {
      throw new IOException(String.format("Failed to peek %,d bytes on input file", PEEK_SIZE));
    }
    xmlInput.reset();
    String peekStr = new String(peek, UTF_8);
    if (!peekStr.contains("urn:ietf:params:xml:ns:rde-1.0")) {
      throw new XmlException(String.format(
          "Does not appear to be an XML RDE deposit\n%s", dumpHex(peek)));
    }
    if (!peekStr.contains("type=\"FULL\"")) {
      throw new XmlException("Only FULL XML RDE deposits suppported at this time");
    }
    Matcher watermarkMatcher = WATERMARK_PATTERN.matcher(peekStr);
    if (!watermarkMatcher.find()) {
      throw new XmlException("Could not find RDE watermark in XML");
    }
    return DATETIME_FORMATTER.parseDateTime(watermarkMatcher.group(1));
  }

  /** Find the most recent folder in the given GCS bucket for the given watermark. */
  public static String findMostRecentPrefixForWatermark(
      DateTime watermark, String bucket, String tld, GcsUtils gcsUtils) throws NoContentException {
    // The prefix is always in the format of: rde-2022-02-21t00-00-00z-2022-02-21t00-07-33z, where
    // the first datetime is the watermark and the second one is the time when the RDE beam job
    // launched. We search for the latest folder that starts with "rde-[watermark]".
    String partialPrefix = String.format("rde-%s", watermark.toString("yyyy-MM-dd't'HH-mm-ss'z'"));
    String latestFilenameSuffix = null;
    try {
      latestFilenameSuffix =
          gcsUtils.listFolderObjects(bucket, partialPrefix).stream()
              .max(Ordering.natural())
              .orElse(null);
    } catch (IOException e) {
      throw new NoContentException(
          String.format(
              "Error reading folders starting with %s in bucket %s", partialPrefix, bucket));
    }
    if (latestFilenameSuffix == null) {
      throw new NoContentException(
          String.format("RDE deposit for TLD %s on %s does not exist", tld, watermark));
    }
    int firstSlashPosition = latestFilenameSuffix.indexOf('/');
    return partialPrefix + latestFilenameSuffix.substring(0, firstSlashPosition + 1);
  }

  /**
   * Generates an ID matching the regex {@code \w{1,13}} from a millisecond timestamp.
   *
   * <p>This routine works by turning the number of UTC milliseconds from the UNIX epoch into a
   * big-endian byte-array which is then converted to a base32 string without padding that's no
   * longer than 13 chars because {@code 13 = Ceiling[Log[32, 2^64]]}. How lucky!
   */
  public static String timestampToId(ReadableInstant timestamp) {
    byte[] bytes = ByteBuffer.allocate(8).putLong(timestamp.getMillis()).array();
    return BaseEncoding.base32().omitPadding().encode(bytes);
  }

  static XjcRdeRrType makeXjcRdeRrType(String registrarId) {
    XjcRdeRrType bean = new XjcRdeRrType();
    bean.setValue(registrarId);
    return bean;
  }

  private RdeUtils() {}
}
