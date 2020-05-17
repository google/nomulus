// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.util;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.joda.time.DateTimeConstants.MILLIS_PER_SECOND;
import static org.joda.time.DateTimeZone.UTC;

import java.util.Arrays;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import org.joda.time.DateTime;

/**
 * POSIX Tar Header.
 *
 * <p>This class represents the 512-byte header that precedes each file within a tar file archive.
 * It's in the POSIX ustar format which is equivalent to using the following GNU tar flags:
 * {@code tar --format=ustar}. It's called ustar because you're a star!
 *
 * <p><b>Warning:</b> This class is not a complete tar implementation. It also offers no
 * abstractions beyond the header format and has only been tested against very simple archives
 * created in the ustar format and the default format for gnu and bsd tar. If your goal is to be
 * able to read generic tar files, you should use a more mature tar implementation like
 * <a href="http://commons.apache.org/proper/commons-compress/">Apache Commons Compress</a>.
 *
 * <p>This class is only really useful in situations where the following statements are true:
 *
 * <ol>
 * <li>You want to <i>create</i> tar archives.
 * <li>You don't need to be able to read tar files from external sources.
 * <li>You don't want additional dependencies.
 * <li>You don't need fancy features like symbolic links.
 * <li>You want something that's a step above writing out the bytes by hand.
 * </ol>
 *
 * <p>To create a tar archive using this class, you must do the following: For each file in the
 * archive, output a header and the file contents ({@code null}-padded to the nearest 512-byte
 * boundary). Then output another 1024 {@code null} bytes to indicate end of archive.
 *
 * <p>The ustar tar header contains the following fields:
 *
 * <dl>
 * <dt>name<dd><i>[Offset: 0, Length: 100]</i><br>
 *     C String which we'll assume is UTF-8 (Offset: 0)
 * <dt>mode<dd><i>[Offset: 100, Length: 8]</i><br>
 *     ASCII 7-digit zero-padded octal file mode and {@code null} byte.
 * <dt>uid<dd><i>[Offset: 108, Length: 8]</i><br>
 *     ASCII 7-digit zero-padded octal UNIX user ID and {@code null} byte.
 * <dt>gid<dd><i>[Offset: 116, Length: 8]</i><br>
 *     ASCII 7-digit zero-padded octal UNIX group ID and {@code null} byte.
 * <dt>size<dd><i>[Offset: 124, Length: 12]</i><br>
 *     ASCII 11-digit zero-padded octal file size and {@code null} byte.
 * <dt>mtime<dd><i>[Offset: 136, Length: 12]</i><br>
 *     ASCII octal UNIX timestamp modified time and {@code null} byte.
 * <dt>chksum<dd><i>[Offset: 148, Length: 8]</i><br>
 *     ASCII octal sum of all header bytes where chksum are 0's.
 * <dt>typeflag<dd><i>[Offset: 156]</i><br>
 *     Always {@code '0'} (zero character) for regular type of file.
 * <dt>linkname<dd><i>[Offset: 157, Length: 100]</i><br>
 *     All {@code null} bytes because we don't support symbolic links.
 * <dt>magic<dd><i>[Offset: 257, Length: 6]</i><br>
 *     Always the C string "ustar".
 * <dt>version<dd><i>[Offset: 263, Length: 2]</i><br>
 *     Always "00" without a {@code null} or blank on GNU systems.
 * <dt>uname<dd><i>[Offset: 265, Length: 32]</i><br>
 *     The C string UNIX user name corresponding to {@code uid}.
 * <dt>gname<dd><i>[Offset: 297, Length: 32]</i><br>
 *     The C string UNIX group name corresponding to {@code gid}.
 * <dt>devmajor<dd><i>[Offset: 329, Length: 8]</i><br>
 *     Not supported; set to zero.
 * <dt>devminor<dd><i>[Offset: 337, Length: 8]</i><br>
 *     Not supported; set to zero.
 * <dt>prefix<dd><i>[Offset: 345, Length: 155]</i><br>
 *     Not supported; set to {@code null}.
 * </dl>
 *
 * @see <a href="http://www.gnu.org/software/tar/manual/html_node/Standard.html">Tar Standard</a>
 */
@Immutable
public final class PosixTarHeader {

  /** Type of file stored in the archive. Only normal files and directories are supported. */
  public enum Type {
    /** A regular file. This can't be a symbolic link or anything interesting. */
    REGULAR,

    /** A directory AKA folder. */
    DIRECTORY,

    /** This indicates we read a file from an archive with an unsupported type. */
    UNSUPPORTED
  }

  public static final int HEADER_LENGTH = 512;

  private final byte[] header;

  /**
   * Create a new header from the bytes of an existing tar file header (Safe).
   *
   * <p>This routine validates the checksum to ensure the data header is correct and supported.
   *
   * @param header the existing and assumed correct header. Value is defensively copied.
   * @throws IllegalArgumentException if header isn't ustar or has a bad checksum.
   * @throws NullPointerException if {@code header} is {@code null}.
   */
  public static PosixTarHeader from(byte[] header) {
    checkNotNull(header, "header");
    checkArgument(header.length == HEADER_LENGTH,
        "POSIX tar header length should be %s but was %s", HEADER_LENGTH, header.length);
    PosixTarHeader res = new PosixTarHeader(header.clone());
    checkArgument(res.getMagic().equals("ustar"),
        "Not a POSIX tar ustar header.");
    String version = res.getVersion();
    checkArgument(version.isEmpty() || version.equals("00"),
        "Only POSIX tar ustar version 00 and the GNU variant supported.");
    checkArgument(res.getChksum() == checksum(header),
        "POSIX tar header chksum invalid.");
    return res;
  }

  /** Constructs a new instance (Unsafe). */
  PosixTarHeader(byte[] header) {
    this.header = header;
  }

  /** Returns 512-byte tar header (safe copy). */
  public byte[] getBytes() {
    return header.clone();
  }

  /** Returns the filename. */
  public String getName() {
    return extractField(0, 100);
  }

  /** Returns the octal UNIX mode aka permissions. */
  public int getMode() {
    return Integer.parseInt(extractField(100, 8).trim(), 8);
  }

  /** Returns the UNIX owner/user id. */
  public int getUid() {
    return Integer.parseInt(extractField(108, 8).trim(), 8);
  }

  /** Returns the UNIX group id. */
  public int getGid() {
    return Integer.parseInt(extractField(116, 8).trim(), 8);
  }

  /** Returns the file size in bytes. */
  public int getSize() {
    return Integer.parseInt(extractField(124, 12).trim(), 8);
  }

  /** Returns the modified time as a UTC {@link DateTime} object. */
  public DateTime getMtime() {
    return new DateTime(Long.parseLong(extractField(136, 12).trim(), 8) * MILLIS_PER_SECOND, UTC);
  }

  /** Returns the checksum value stored in the . */
  public int getChksum() {
    return Integer.parseInt(extractField(148, 8).trim(), 8);
  }

  /** Returns the {@link Type} of file. */
  public Type getType() {
    switch (header[156]) {
      case '\0':
      case '0':
        return Type.REGULAR;
      case '5':
        return Type.DIRECTORY;
      default:
        return Type.UNSUPPORTED;
    }
  }

  /**
   * Returns the UNIX symbolic link name.
   *
   * <p>This feature is unsupported but the getter is included for completeness.
   */
  public String getLinkName() {
    return extractField(157, 100);
  }

  /** Returns the {@code magic} field. Only {@code "ustar"} is supported. */
  public String getMagic() {
    return extractField(257, 6).trim();
  }

  /** Returns the {@code magic} field. Only {@code "00"} is supported. */
  public String getVersion() {
    return extractField(263, 2).trim();
  }

  /** Returns the UNIX user name (or owner) of the file. */
  public String getUname() {
    return extractField(265, 32).trim();
  }

  /** Returns the UNIX group name associated with the file. */
  public String getGname() {
    return extractField(297, 32).trim();
  }

  /**
   * Returns the {@code devmajor} field.
   *
   * <p>This feature is unsupported but the getter is included for completeness.
   */
  public String getDevMajor() {
    return extractField(329, 8);
  }

  /**
   * Returns the {@code devminor} field.
   *
   * <p>This feature is unsupported but the getter is included for completeness.
   */
  public String getDevMinor() {
    return extractField(337, 8);
  }

  /**
   * Returns the {@code prefix} field.
   *
   * <p>This feature is unsupported but the getter is included for completeness.
   */
  public String getPrefix() {
    return extractField(345, 155);
  }

  /**
   * Extracts a C string field. This routine is lenient when it comes to the terminating
   * {@code null} byte. If it's not present, it'll be assumed that the string length is the
   * size of the field. The encoding is always assumed to be UTF-8.
   */
  private String extractField(int offset, int max) {
    return new String(header, offset, extractFieldLength(offset, max), UTF_8);
  }

  /** Returns length of C string in field, or {@code max} if there's no {@code null} byte. */
  private int extractFieldLength(int offset, int max) {
    for (int n = 0; n < max; ++n) {
      if (header[offset + n] == '\0') {
        return n;
      }
    }
    return max;
  }

  /** @see Arrays#hashCode(byte[]) */
  @Override
  public int hashCode() {
    return Arrays.hashCode(header);
  }

  /** @see Arrays#equals(byte[], byte[]) */
  @Override
  public boolean equals(@Nullable Object rhs) {
    return rhs == this
        || (rhs != null
            && getClass() == rhs.getClass()
            && Arrays.equals(header, ((PosixTarHeader) rhs).header));
  }

  /** @see Arrays#toString(byte[]) */
  @Override
  public String toString() {
    return Arrays.toString(header);
  }

  /** Simple checksum algorithm specified by tar. */
  static int checksum(byte[] bytes) {
    int sum = 0;
    for (int n = 0; n < 148; ++n) {
      sum += bytes[n];
    }
    sum += ' ' * 8;  // We pretend the chksum field stores spaces.
    for (int n = 148 + 8; n < bytes.length; ++n) {
      sum += bytes[n];
    }
    return sum;
  }

  /**
   * Builder for {@link PosixTarHeader}.
   *
   * <p>The following fields are required:<ul>
   * <li>{@link #setName(String)}
   * <li>{@link #setSize(long)}</ul>
   *
   * <p>{@link #build()} may be called multiple times. With the exception of the required fields
   * listed above, fields will retain the values. This is useful if you want to construct many
   * file headers with the same value for certain certain fields (e.g. uid, gid, uname, gname)
   * but don't want to have to call their setters repeatedly.
   */
  public static class Builder {

    private static final int DEFAULT_MODE = 0640;
    private static final int DEFAULT_UID = 0;
    private static final int DEFAULT_GID = 0;
    private static final String DEFAULT_UNAME = "root";
    private static final String DEFAULT_GNAME = "wheel";
    private static final Type DEFAULT_TYPE = Type.REGULAR;

    private final byte[] header = new byte[HEADER_LENGTH];
    private boolean hasName = false;
    private boolean hasSize = false;

    public Builder() {
      setMode(DEFAULT_MODE);
      setUid(DEFAULT_UID);
      setGid(DEFAULT_GID);
      setMtime(new DateTime(UTC));
      setType(DEFAULT_TYPE);
      setMagic();
      setVersion();
      setUname(DEFAULT_UNAME);
      setGname(DEFAULT_GNAME);
      setField("devmajor", 329, 8, "0000000");  // I have no clue what this is.
      setField("devminor", 337, 8, "0000000");  // I have no clue what this is.
    }

    /**
     * Sets the file name. (Required)
     *
     * @param name must be {@code <100} characters in length.
     */
    public Builder setName(String name) {
      checkArgument(!isNullOrEmpty(name), "name");
      setField("name", 0, 100, name);
      hasName = true;
      return this;
    }

    /**
     * Sets the UNIX file mode aka permissions. By default this is {@value #DEFAULT_MODE}.
     *
     * @param mode <u>This value is octal</u>. Just in case you were wondering, {@code 416} is the
     *        decimal representation of {@code 0640}. If that number doesn't look familiar to you,
     *        search Google for "chmod". The value must be {@code >=0} and {@code <8^7}.
     */
    public Builder setMode(int mode) {
      checkArgument(0 <= mode && mode <= 07777777,
          "Tar mode out of range: %s", mode);
      setField("mode", 100, 8, String.format("%07o", mode));
      return this;
    }

    /**
     * Sets the owner's UNIX user ID. By default this is {@value #DEFAULT_UID}.
     *
     * @param uid must be {@code >=0} and {@code <8^7}.
     * @see #setUname(String)
     */
    public Builder setUid(int uid) {
      checkArgument(0 <= uid && uid <= 07777777,
          "Tar uid out of range: %s", uid);
      setField("uid", 108, 8, String.format("%07o", uid));
      return this;
    }

    /**
     * Sets the UNIX group ID. By default this is {@value #DEFAULT_GID}.
     *
     * @param gid must be {@code >=0} and {@code <8^7}.
     * @see #setGname(String)
     */
    public Builder setGid(int gid) {
      checkArgument(0 <= gid && gid <= 07777777,
          "Tar gid out of range: %s", gid);
      setField("gid", 116, 8, String.format("%07o", gid));
      return this;
    }

    /**
     * Sets the file size. (Required)
     *
     * <p>This value must be known in advance. There's no such thing as a streaming tar archive.
     *
     * @param size must be {@code >=0} and {@code <8^11} which places an eight gigabyte limit.
     */
    public Builder setSize(long size) {
      checkArgument(0 <= size && size <= 077777777777L,
          "Tar size out of range: %s", size);
      setField("size", 124, 12, String.format("%011o", size));
      hasSize = true;
      return this;
    }

    /**
     * Sets the modified time of the file. By default, this is the time the builder object was
     * constructed.
     *
     * <p>The modified time is always stored as a UNIX timestamp which is seconds since the UNIX
     * epoch in UTC time. Because {@link DateTime} has millisecond precision, it gets rounded down
     * (floor) to the second.
     */
    public Builder setMtime(DateTime mtime) {
      checkNotNull(mtime, "mtime");
      setField("mtime", 136, 12, String.format("%011o", mtime.getMillis() / MILLIS_PER_SECOND));
      return this;
    }

    private void setChksum() {
      setField("chksum", 148, 8, String.format("%06o", checksum(header)));
    }

    /**
     * Sets the file {@link Type}. By default this is {@link Type#REGULAR}.
     */
    public Builder setType(Type type) {
      switch (type) {
        case REGULAR:
          header[156] = '0';
          break;
        case DIRECTORY:
          header[156] = '5';
          break;
        default:
          throw new UnsupportedOperationException();
      }
      return this;
    }

    /**
     * Sets the UNIX owner of the file. By default this is {@value #DEFAULT_UNAME}.
     *
     * @param uname must be {@code <32} characters in length.
     * @see #setUid(int)
     * @see #setGname(String)
     */
    public Builder setUname(String uname) {
      checkArgument(!isNullOrEmpty(uname), "uname");
      setField("uname", 265, 32, uname);
      return this;
    }

    /**
     * Sets the UNIX group of the file. By default this is {@value #DEFAULT_GNAME}.
     *
     * @param gname must be {@code <32} characters in length.
     * @see #setGid(int)
     * @see #setUname(String)
     */
    public Builder setGname(String gname) {
      checkArgument(!isNullOrEmpty(gname), "gname");
      setField("gname", 297, 32, gname);
      return this;
    }

    private void setMagic() {
      setField("magic", 257, 6, "ustar");
    }

    private void setVersion() {
      header[263] = '0';
      header[264] = '0';
    }

    /**
     * Returns a new immutable {@link PosixTarHeader} instance.
     *
     * <p>It's safe to save a reference to the builder instance and call this method multiple times
     * because the header data is copied into the resulting object.
     *
     * @throws IllegalStateException if you forgot to call required setters.
     */
    public PosixTarHeader build() {
      checkState(hasName, "name not set");
      checkState(hasSize, "size not set");
      hasName = false;
      hasSize = false;
      setChksum();  // Calculate the checksum last.
      return new PosixTarHeader(header.clone());
    }

    private void setField(String fieldName, int offset, int max, String data) {
      byte[] bytes = (data + "\0").getBytes(UTF_8);
      checkArgument(bytes.length <= max,
          "%s field exceeds max length of %s: %s", fieldName, max - 1, data);
      System.arraycopy(bytes, 0, header, offset, bytes.length);
      Arrays.fill(header, offset + bytes.length, offset + max, (byte) 0);
    }
  }
}
