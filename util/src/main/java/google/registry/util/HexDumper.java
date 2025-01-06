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

package google.registry.util;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import javax.annotation.Nonnegative;
import javax.annotation.WillNotClose;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Hex Dump Utility.
 *
 * <p>This class takes binary data and prints it out in a way that humans can read. It's just like
 * the hex dump programs you remembered as a kid. There a column on the left for the address (or
 * offset) in decimal, the middle columns are each digit in hexadecimal, and the column on the
 * right is the ASCII representation where non-printable characters are represented by {@code '.'}.
 *
 * <p>It's easiest to generate a simple {@link String} by calling {@link #dumpHex(byte[])}, or you
 * can stream data with {@link #HexDumper(Writer, int, int)}.
 *
 * <p>Example output:
 * <pre>   {@code
 *   [222 bytes total]
 *   00000000  90 0d 03 00  08 03 35 58  61 46 fd f3  f3 73 01 88  ......5XaF...s..
 *   00000016  cd 04 00 03  08 00 37 05  02 52 09 3f  8d 30 1c 45  ......7..R.?.0.E
 *   00000032  72 69 63 20  45 63 68 69  64 6e 61 20  28 74 65 73  ric Echidna (tes
 *   00000048  74 20 6b 65  79 29 20 3c  65 72 69 63  40 62 6f 75  t key) <eric@bou
 *   00000064  6e 63 79 63  61 73 74 6c  65 2e 6f 72  67 3e 00 0a  ncycastle.org>..
 *   00000080  09 10 35 58  61 46 fd f3  f3 73 4b 5b  03 fe 2e 53  ..5XaF...sK[...S
 *   00000096  04 28 ab cb  35 3b e2 1b  63 91 65 3a  86 b9 fb 47  .(..5;..c.e:...G
 *   00000112  d5 4c 6a 21  50 f5 2e 39  76 aa d5 86  d7 96 3b 9a  .Lj!P..9v.....;.
 *   00000128  1a c3 6d c0  50 7f c6 25  9a 04 de 0f  1f 20 ae 70  ..m.P..%..... .p
 *   00000144  f9 77 c4 8b  bf ec 3c 2f  59 58 b8 47  81 6a 59 25  .w....</YX.G.jY%
 *   00000160  82 b0 ba e2  a9 43 94 aa  fc 92 2b b3  76 77 f5 ba  .....C....+.vw..
 *   00000176  5b 59 9a de  22 1c 79 06  88 d2 ba 97  51 e3 11 2e  [Y..".y.....Q...
 *   00000192  5b c0 c6 8c  34 4d a7 28  77 bf 11 27  e7 6c 8e 1c  [...4M.(w..'.l..
 *   00000208  b4 a6 66 18  8e 69 3c 18  b7 97 d5 34  9a bb        ..f..i<....4..
 *   }</pre>
 */
@NotThreadSafe
public final class HexDumper extends OutputStream {

  @Nonnegative
  public static final int DEFAULT_PER_LINE = 16;

  @Nonnegative
  public static final int DEFAULT_PER_GROUP = 4;

  private Writer upstream;

  @Nonnegative
  private final int perLine;

  @Nonnegative
  private final int perGroup;

  private long totalBytes;

  @Nonnegative
  private int lineCount;

  private StringBuilder line;

  private final char[] asciis;

  /**
   * Calls {@link #dumpHex(byte[], int, int)} with {@code perLine} set to
   * {@value #DEFAULT_PER_LINE} and {@code perGroup} set to {@value #DEFAULT_PER_GROUP}.
   */
  public static String dumpHex(byte[] data) {
    return dumpHex(data, DEFAULT_PER_LINE, DEFAULT_PER_GROUP);
  }

  /**
   * Convenience static method for generating a hex dump as a {@link String}.
   *
   * <p>This method adds an additional line to the beginning with the total number of bytes.
   *
   * @see #HexDumper(Writer, int, int)
   */
  public static String dumpHex(byte[] data, @Nonnegative int perLine, @Nonnegative int perGroup) {
    checkNotNull(data, "data");
    StringWriter writer = new StringWriter();
    writer.write(String.format("[%d bytes total]\n", data.length));
    try (HexDumper hexDump = new HexDumper(writer, perLine, perGroup)) {
      hexDump.write(data);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return writer.toString();
  }

  /**
   * Calls {@link #HexDumper(Writer, int, int)} with {@code perLine} set to
   * {@value #DEFAULT_PER_LINE} and {@code perGroup} set to {@value #DEFAULT_PER_GROUP}.
   */
  public HexDumper(@WillNotClose Writer writer) {
    this(writer, DEFAULT_PER_LINE, DEFAULT_PER_GROUP);
  }

  /**
   * Construct a new streaming {@link HexDumper} object.
   *
   * <p>The output is line-buffered so a single write call is made to {@code out} for each line.
   * This is done to avoid system call overhead {@code out} is a resource, and reduces the chance
   * of lines being broken by other threads writing to {@code out} (or its underlying resource) at
   * the same time.
   *
   * <p>This object will <i>not</i> close {@code out}. You must close <i>both</i> this object and
   * {@code out}, and this object must be closed <i>first</i>.
   *
   * @param out is the stream to which the hex dump text is written. It is <i>not</i> closed.
   * @param perLine determines how many hex characters to show on each line.
   * @param perGroup how many columns of hex digits should be grouped together. If this value is
   *     {@code > 0}, an extra space will be inserted after each Nth column for readability.
   *     Grouping can be disabled by setting this to {@code 0}.
   * @see #dumpHex(byte[])
   */
  public HexDumper(@WillNotClose Writer out, @Nonnegative int perLine, @Nonnegative int perGroup) {
    checkArgument(0 < perLine, "0 < perLine <= INT32_MAX");
    checkArgument(0 <= perGroup && perGroup < perLine, "0 <= perGroup < perLine");
    this.upstream = checkNotNull(out, "out");
    this.totalBytes = 0L;
    this.perLine = perLine;
    this.perGroup = perGroup;
    this.asciis = new char[perLine];
    this.line = newLine();
  }

  /** Initializes member variables at the beginning of a new line. */
  private StringBuilder newLine() {
    lineCount = 0;
    return new StringBuilder(String.format("%08d  ", totalBytes));
  }

  /**
   * Writes a single byte to the current line buffer, flushing if end of line.
   *
   * @throws IOException upon failure to write to upstream {@link Writer#write(String) writer}.
   * @throws IllegalStateException if this object has been {@link #close() closed}.
   */
  @Override
  public void write(int b) throws IOException {
    String flush = null;
    line.append(String.format("%02x ", (byte) b));
    asciis[lineCount] = b >= 32 && b <= 126 ? (char) b : '.';
    ++lineCount;
    ++totalBytes;
    if (lineCount == perLine) {
      line.append(' ');
      line.append(asciis);
      line.append('\n');
      flush = line.toString();
      line = newLine();
    } else {
      if (perGroup > 0 && lineCount % perGroup == 0) {
        line.append(' ');
      }
    }
    // Writing upstream is deferred until the end in order to *somewhat* maintain a correct
    // internal state in the event that an exception is thrown. This also avoids the need for
    // a try statement which usually makes code run slower.
    if (flush != null) {
      upstream.write(flush);
    }
  }

  /**
   * Writes partial line buffer (if any) to upstream {@link Writer}.
   *
   * @throws IOException upon failure to write to upstream {@link Writer#write(String) writer}.
   * @throws IllegalStateException if this object has been {@link #close() closed}.
   */
  @Override
  public void flush() throws IOException {
    if (line.length() > 0) {
      upstream.write(line.toString());
      line = new StringBuilder();
    }
  }

  /**
   * Writes out the final line (if incomplete) and invalidates this object.
   *
   * <p>This object must be closed <i>before</i> you close the upstream writer. Please note that
   * this method <i>does not</i> close upstream writer for you.
   *
   * <p>If you attempt to write to this object after calling this method,
   * {@link IllegalStateException} will be thrown. However, it's safe to call close multiple times,
   * as subsequent calls will be treated as a no-op.
   *
   * @throws IOException upon failure to write to upstream {@link Writer#write(String) writer}.
   */
  @Override
  public void close() throws IOException {
    if (lineCount > 0) {
      while (lineCount < perLine) {
        asciis[lineCount] = ' ';
        line.append("   ");
        ++lineCount;
        if (perGroup > 0 && lineCount % perGroup == 0 && lineCount != perLine) {
          line.append(' ');
        }
      }
      line.append(' ');
      line.append(asciis);
      line.append('\n');
      flush();
    }
  }
}
