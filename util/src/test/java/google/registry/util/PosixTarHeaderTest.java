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

import static com.google.common.io.BaseEncoding.base64;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.testing.EqualsTester;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import org.joda.time.DateTime;
import org.joda.time.format.ISODateTimeFormat;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link PosixTarHeader}. */
class PosixTarHeaderTest {

  @Test
  void testGnuTarBlob() throws Exception {
    // This data was generated as follows:
    //
    //   echo hello kitty >hello.xml
    //   tar --format=ustar -cf ~/hello.tar hello.xml
    //   head -c 1024 <hello.tar | base64
    //
    // As you can see, we're only going to bother with the first 1024 characters.
    byte[] gnuTarGeneratedData =
        base64()
            .decode(
                "aGVsbG8ueG1sAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADAwMDA2NDAAMDU0MTI2"
                    + "NgAwMDExNjEwADAwMDAwMDAwMDE0ADEyMjAyMzEwMzI0ADAxMjQ2MQAgMAAAAAAAAAAAAAAAAAAA"
                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB1c3RhcgAwMGphcnQAAAAAAAAAAAAAAAAAAAAA"
                    + "AAAAAAAAAAAAAAAAZW5nAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAwMDAwMDAwADAwMDAw"
                    + "MDAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABo"
                    + "ZWxsbyBraXR0eQoAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==");
    assertThat(gnuTarGeneratedData.length).isEqualTo(1024);

    // Now we have to replicate it.
    byte[] data = "hello kitty\n".getBytes(UTF_8);
    PosixTarHeader header =
        new PosixTarHeader.Builder()
            .setType(PosixTarHeader.Type.REGULAR)
            .setName("hello.xml")
            .setSize(data.length)
            .setMode(0640)
            // This timestamp should have been midnight but I think GNU tar might not understand
            // daylight savings time. Woe is me.
            .setMtime(DateTime.parse("2013-08-13T01:50:12Z"))
            .setUname("jart")
            .setGname("eng")
            .setUid(180918) // echo $UID
            .setGid(5000) // import grp; print grp.getgrnam('eng').gr_gid
            .build();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    output.write(header.getBytes());
    output.write(data);
    // Line up with the 512-byte block boundary.
    if (data.length % 512 != 0) {
      output.write(new byte[512 - data.length % 512]);
    }
    output.write(new byte[1024]); // Bunch of null bytes to indicate end of archive.
    byte[] tarData = output.toByteArray();
    assertThat(tarData.length % 512).isEqualTo(0);
    data = Arrays.copyOf(tarData, 1024);

    // From Wikipedia:
    // The checksum is calculated by taking the sum of the unsigned byte values of the header
    // record with the eight checksum bytes taken to be ascii spaces (decimal value 32). It is
    // stored as a six digit octal number with leading zeroes followed by a NUL and then a space.
    // Various implementations do not adhere to this format. For better compatibility, ignore
    // leading and trailing whitespace, and get the first six digits. In addition, some historic
    // tar implementations treated bytes as signed. Implementations typically calculate the
    // checksum both ways, and treat it as good if either the signed or unsigned sum matches the
    // included checksum.
    data[155] = ' ';

    // Compare everything in the arrays except for the checksum. That way we know what's causing
    // the checksum to fail.
    byte[] gnuTarGeneratedDataNoChksum = gnuTarGeneratedData.clone();
    Arrays.fill(gnuTarGeneratedDataNoChksum, 148, 148 + 8, (byte) 0);
    byte[] dataNoChksum = data.clone();
    Arrays.fill(dataNoChksum, 148, 148 + 8, (byte) 0);
    assertThat(dataNoChksum).isEqualTo(gnuTarGeneratedDataNoChksum);

    // Now do it again with the checksum.
    assertThat(data).isEqualTo(gnuTarGeneratedData);
  }

  @Test
  void testFields() {
    PosixTarHeader header =
        new PosixTarHeader.Builder()
            .setType(PosixTarHeader.Type.REGULAR)
            .setName("(◕‿◕).txt")
            .setSize(666)
            .setMode(0777)
            .setMtime(DateTime.parse("1984-12-18T04:20:00Z"))
            .setUname("everything i ever touched")
            .setGname("everything i ever had, has died")
            .setUid(180918)
            .setGid(5000)
            .build();
    assertThat(header.getType()).isEqualTo(PosixTarHeader.Type.REGULAR);
    assertThat(header.getName()).isEqualTo("(◕‿◕).txt");
    assertThat(header.getSize()).isEqualTo(666);
    assertThat(header.getMode()).isEqualTo(0777);
    assertThat(header.getUname()).isEqualTo("everything i ever touched");
    assertThat(header.getGname()).isEqualTo("everything i ever had, has died");
    assertThat(header.getUid()).isEqualTo(180918);
    assertThat(header.getGid()).isEqualTo(5000);
    assertThat(header.getMtime()).isEqualTo(DateTime.parse("1984-12-18T04:20:00Z"));
    assertThat(header.getMagic()).isEqualTo("ustar");
    assertThat(header.getVersion()).isEqualTo("00");
  }

  @Test
  void testFieldsSomeMoar() {
    PosixTarHeader header =
        new PosixTarHeader.Builder()
            .setType(PosixTarHeader.Type.DIRECTORY)
            .setName("Black lung full of fumes, choke on memories")
            .setSize(1024 * 1024 * 1024)
            .setMode(31337)
            .setMtime(DateTime.parse("2020-12-18T04:20:00Z"))
            .setUname("every street i ever walked")
            .setGname("every home i ever had, is lost")
            .setUid(0)
            .setGid(31337)
            .build();
    assertThat(header.getType()).isEqualTo(PosixTarHeader.Type.DIRECTORY);
    assertThat(header.getName()).isEqualTo("Black lung full of fumes, choke on memories");
    assertThat(header.getSize()).isEqualTo(1024 * 1024 * 1024);
    assertThat(header.getMode()).isEqualTo(31337);
    assertThat(header.getUname()).isEqualTo("every street i ever walked");
    assertThat(header.getGname()).isEqualTo("every home i ever had, is lost");
    assertThat(header.getUid()).isEqualTo(0);
    assertThat(header.getGid()).isEqualTo(31337);
    assertThat(header.getMtime()).isEqualTo(DateTime.parse("2020-12-18T04:20:00Z"));
  }

  @Test
  void testLoad() {
    PosixTarHeader header =
        new PosixTarHeader.Builder()
            .setType(PosixTarHeader.Type.REGULAR)
            .setName("(◕‿◕).txt")
            .setSize(31337)
            .setMode(0777)
            .setMtime(DateTime.parse("1984-12-18T04:20:00Z"))
            .setUname("everything i ever touched")
            .setGname("everything i ever had, has died")
            .setUid(180918)
            .setGid(5000)
            .build();
    header = PosixTarHeader.from(header.getBytes()); // <-- Pay attention to this line.
    assertThat(header.getType()).isEqualTo(PosixTarHeader.Type.REGULAR);
    assertThat(header.getName()).isEqualTo("(◕‿◕).txt");
    assertThat(header.getSize()).isEqualTo(31337);
    assertThat(header.getMode()).isEqualTo(0777);
    assertThat(header.getUname()).isEqualTo("everything i ever touched");
    assertThat(header.getGname()).isEqualTo("everything i ever had, has died");
    assertThat(header.getUid()).isEqualTo(180918);
    assertThat(header.getGid()).isEqualTo(5000);
    assertThat(header.getMtime()).isEqualTo(DateTime.parse("1984-12-18T04:20:00Z"));
    assertThat(header.getMagic()).isEqualTo("ustar");
    assertThat(header.getVersion()).isEqualTo("00");
  }

  @Test
  void testBadChecksum() {
    PosixTarHeader header =
        new PosixTarHeader.Builder().setName("(◕‿◕).txt").setSize(31337).build();
    byte[] bytes = header.getBytes();
    bytes[150] = '0';
    bytes[151] = '0';
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> PosixTarHeader.from(bytes));
    assertThat(thrown).hasMessageThat().contains("chksum invalid");
  }

  @Test
  void testHashEquals() {
    new EqualsTester()
        .addEqualityGroup(
            new PosixTarHeader.Builder()
                .setName("(◕‿◕).txt")
                .setSize(123)
                .setMtime(DateTime.parse("1984-12-18TZ"))
                .setUid(1234)
                .setGid(456)
                .setUname("jart")
                .setGname("wheel")
                .build(),
            new PosixTarHeader.Builder()
                .setName("(◕‿◕).txt")
                .setSize(123)
                .setMtime(DateTime.parse("1984-12-18TZ"))
                .setUid(1234)
                .setGid(456)
                .setUname("jart")
                .setGname("wheel")
                .build())
        .addEqualityGroup(
            new PosixTarHeader.Builder()
                .setName("(•︵•).txt") // Awwwww! It looks so sad...
                .setSize(123)
                .build())
        .testEquals();
  }

  @Test
  void testReadBsdTarFormatUstar() throws Exception {
    // $ tar --version
    // bsdtar 2.8.3 - libarchive 2.8.3

    // echo no rain can wash away my tears >liketears
    // echo no wind can soothe my pain >inrain
    // chmod 0600 liketears inrain
    // tar --format=ustar -c liketears inrain | gzip | base64
    InputStream input =
        new GZIPInputStream(
            new ByteArrayInputStream(
                base64()
                    .decode(
                        "H4sIAM17DVIAA+3T0QqCMBTGca97ivMIx03n84waaNkMNcS3T4OCbuymFcH/dzc22Dd2vrY5h"
                            + "TH4fsjSUVWnKllZ5MY5Wde6rvXBVpIbo9ZUpnKFaG7VlZlowkxP12H0/RLl6Ptx69xUh"
                            + "9Bu7L8+Sj4bMp3YSe+bKHsfZfJDLX7ys5xnuQ/F7tfxkFgT1+9Pe8f7/ttn/12hS/+NL"
                            + "Sr6/w1L/6cmHu79H7purMNa/ssyE3QfAAAAAAAAAAAAAADgH9wAqAJg4gAoAAA=")));

    PosixTarHeader header;
    byte[] block = new byte[512];
    String likeTears = "no rain can wash away my tears\n";
    String inRain = "no wind can soothe my pain\n";

    assertThat(input.read(block)).isEqualTo(512);
    header = PosixTarHeader.from(block);
    assertThat(header.getName()).isEqualTo("liketears");
    assertThat(header.getSize()).isEqualTo(likeTears.length());
    assertThat(header.getMode()).isEqualTo(0600);
    assertThat(header.getUname()).isEqualTo("jart");
    assertThat(header.getGname()).isEqualTo("wheel");
    assertThat(header.getUid()).isEqualTo(180918);
    assertThat(header.getGid()).isEqualTo(0);
    assertThat(header.getMtime().toString(ISODateTimeFormat.date())).isEqualTo("2013-08-16");

    assertThat(input.read(block)).isEqualTo(512);
    assertThat(new String(block, 0, likeTears.length(), UTF_8)).isEqualTo(likeTears);

    assertThat(input.read(block)).isEqualTo(512);
    header = PosixTarHeader.from(block);
    assertThat(header.getName()).isEqualTo("inrain");
    assertThat(header.getSize()).isEqualTo(inRain.length());
    assertThat(header.getMode()).isEqualTo(0600);
    assertThat(header.getUname()).isEqualTo("jart");
    assertThat(header.getGname()).isEqualTo("wheel");
    assertThat(header.getUid()).isEqualTo(180918);
    assertThat(header.getGid()).isEqualTo(0);
    assertThat(header.getMtime().toString(ISODateTimeFormat.date())).isEqualTo("2013-08-16");

    assertThat(input.read(block)).isEqualTo(512);
    assertThat(new String(block, 0, inRain.length(), UTF_8)).isEqualTo(inRain);

    assertThat(input.read(block)).isEqualTo(512);
    assertWithMessage("End of archive marker corrupt").that(block).isEqualTo(new byte[512]);
    assertThat(input.read(block)).isEqualTo(512);
    assertWithMessage("End of archive marker corrupt").that(block).isEqualTo(new byte[512]);
  }

  @Test
  void testReadBsdTarFormatDefault() throws Exception {
    // $ tar --version
    // bsdtar 2.8.3 - libarchive 2.8.3

    // echo no rain can wash away my tears >liketears
    // echo no wind can soothe my pain >inrain
    // chmod 0600 liketears inrain
    // tar -c liketears inrain | gzip | base64
    InputStream input =
        new GZIPInputStream(
            new ByteArrayInputStream(
                base64()
                    .decode(
                        "H4sIAM17DVIAA+3T0QqCMBTGca97ivMIx03n84waaNkMNcS3T4OCbuymFcH/dzc22Dd2vrY5h"
                            + "TH4fsjSUVWnKllZ5MY5Wde6rvXBVpIbo9ZUpnKFaG7VlZlowkxP12H0/RLl6Ptx69xUh"
                            + "9Bu7L8+Sj4bMp3YSe+bKHsfZfJDLX7ys5xnuQ/F7tfxkFgT1+9Pe8f7/ttn/12hS/+NL"
                            + "Sr6/w1L/6cmHu79H7purMNa/ssyE3QfAAAAAAAAAAAAAADgH9wAqAJg4gAoAAA=")));

    PosixTarHeader header;
    byte[] block = new byte[512];
    String likeTears = "no rain can wash away my tears\n";
    String inRain = "no wind can soothe my pain\n";

    assertThat(input.read(block)).isEqualTo(512);
    header = PosixTarHeader.from(block);
    assertThat(header.getName()).isEqualTo("liketears");
    assertThat(header.getSize()).isEqualTo(likeTears.length());
    assertThat(header.getMode()).isEqualTo(0600);
    assertThat(header.getUname()).isEqualTo("jart");
    assertThat(header.getGname()).isEqualTo("wheel");
    assertThat(header.getUid()).isEqualTo(180918);
    assertThat(header.getGid()).isEqualTo(0);
    assertThat(header.getMtime().toString(ISODateTimeFormat.date())).isEqualTo("2013-08-16");

    assertThat(input.read(block)).isEqualTo(512);
    assertThat(new String(block, 0, likeTears.length(), UTF_8)).isEqualTo(likeTears);

    assertThat(input.read(block)).isEqualTo(512);
    header = PosixTarHeader.from(block);
    assertThat(header.getName()).isEqualTo("inrain");
    assertThat(header.getSize()).isEqualTo(inRain.length());
    assertThat(header.getMode()).isEqualTo(0600);
    assertThat(header.getUname()).isEqualTo("jart");
    assertThat(header.getGname()).isEqualTo("wheel");
    assertThat(header.getUid()).isEqualTo(180918);
    assertThat(header.getGid()).isEqualTo(0);
    assertThat(header.getMtime().toString(ISODateTimeFormat.date())).isEqualTo("2013-08-16");

    assertThat(input.read(block)).isEqualTo(512);
    assertThat(new String(block, 0, inRain.length(), UTF_8)).isEqualTo(inRain);

    assertThat(input.read(block)).isEqualTo(512);
    assertWithMessage("End of archive marker corrupt").that(block).isEqualTo(new byte[512]);
    assertThat(input.read(block)).isEqualTo(512);
    assertWithMessage("End of archive marker corrupt").that(block).isEqualTo(new byte[512]);
  }

  @Test
  void testReadGnuTarFormatDefault() throws Exception {
    // $ tar --version
    // tar (GNU tar) 1.26

    // echo no rain can wash away my tears >liketears
    // echo no wind can soothe my pain >inrain
    // chmod 0600 liketears inrain
    // tar -c liketears inrain | gzip | base64
    InputStream input =
        new GZIPInputStream(
            new ByteArrayInputStream(
                base64()
                    .decode(
                        "H4sIAOB8DVIAA+3TTQ6DIBCGYdY9BUcYUPE8pDWV/mCjNsbbF01jurIr25i8z4ZACAxhvlu4V"
                            + "n3l205tRxInoqTIjXUuzY1xRub1WVYqY61ktnSmyJUYWzhRWjasafHset+mUi6+7df2V"
                            + "fG8es77Kcu4E7HRrQ9RH33Ug+9q7Qc/6vuo56Y4/Ls8bCzE6fu3veN7/rOP/Lsp/1Jk5"
                            + "P8XUv6HEE9z/rum6etqCv8j9QTZBwAAAAAAAAAAAAAA2IMXm3pYMgAoAAA=")));

    PosixTarHeader header;
    byte[] block = new byte[512];
    String likeTears = "no rain can wash away my tears\n";
    String inRain = "no wind can soothe my pain\n";

    assertThat(input.read(block)).isEqualTo(512);
    header = PosixTarHeader.from(block);
    assertThat(header.getName()).isEqualTo("liketears");
    assertThat(header.getSize()).isEqualTo(likeTears.length());
    assertThat(header.getMode()).isEqualTo(0600);
    assertThat(header.getUname()).isEqualTo("jart");
    assertThat(header.getGname()).isEqualTo("eng");
    assertThat(header.getUid()).isEqualTo(180918);
    assertThat(header.getGid()).isEqualTo(5000);
    assertThat(header.getMtime().toString(ISODateTimeFormat.date())).isEqualTo("2013-08-16");

    assertThat(input.read(block)).isEqualTo(512);
    assertThat(new String(block, 0, likeTears.length(), UTF_8)).isEqualTo(likeTears);

    assertThat(input.read(block)).isEqualTo(512);
    header = PosixTarHeader.from(block);
    assertThat(header.getName()).isEqualTo("inrain");
    assertThat(header.getSize()).isEqualTo(inRain.length());
    assertThat(header.getMode()).isEqualTo(0600);
    assertThat(header.getUname()).isEqualTo("jart");
    assertThat(header.getGname()).isEqualTo("eng");
    assertThat(header.getUid()).isEqualTo(180918);
    assertThat(header.getGid()).isEqualTo(5000);
    assertThat(header.getMtime().toString(ISODateTimeFormat.date())).isEqualTo("2013-08-16");

    assertThat(input.read(block)).isEqualTo(512);
    assertThat(new String(block, 0, inRain.length(), UTF_8)).isEqualTo(inRain);

    assertThat(input.read(block)).isEqualTo(512);
    assertWithMessage("End of archive marker corrupt").that(block).isEqualTo(new byte[512]);
    assertThat(input.read(block)).isEqualTo(512);
    assertWithMessage("End of archive marker corrupt").that(block).isEqualTo(new byte[512]);
  }

  @Test
  void testReadGnuTarFormatUstar() throws Exception {
    // $ tar --version
    // tar (GNU tar) 1.26

    // echo no rain can wash away my tears >liketears
    // echo no wind can soothe my pain >inrain
    // chmod 0600 liketears inrain
    // tar --format=ustar -c liketears inrain | gzip | base64
    InputStream input =
        new GZIPInputStream(
            new ByteArrayInputStream(
                base64()
                    .decode(
                        "H4sIAOB8DVIAA+3TTQ6DIBCGYdY9BUcYUPE8pDWV/mCjNsbbF01jurIr25i8z4ZACAxhvlu4V"
                            + "n3l205tRxInoqTIjXUuzY1xRub1WVYqY61ktnSmyJUYWzhRWjasafHset+mUi6+7df2V"
                            + "fG8es77Kcu4E7HRrQ9RH33Ug+9q7Qc/6vuo56Y4/Ls8bCzE6fu3veN7/rOP/Lsp/1Jk5"
                            + "P8XUv6HEE9z/rum6etqCv8j9QTZBwAAAAAAAAAAAAAA2IMXm3pYMgAoAAA=")));

    PosixTarHeader header;
    byte[] block = new byte[512];
    String likeTears = "no rain can wash away my tears\n";
    String inRain = "no wind can soothe my pain\n";

    assertThat(input.read(block)).isEqualTo(512);
    header = PosixTarHeader.from(block);
    assertThat(header.getType()).isEqualTo(PosixTarHeader.Type.REGULAR);
    assertThat(header.getName()).isEqualTo("liketears");
    assertThat(header.getSize()).isEqualTo(likeTears.length());
    assertThat(header.getMode()).isEqualTo(0600);
    assertThat(header.getUname()).isEqualTo("jart");
    assertThat(header.getGname()).isEqualTo("eng");
    assertThat(header.getUid()).isEqualTo(180918);
    assertThat(header.getGid()).isEqualTo(5000);
    assertThat(header.getMtime().toString(ISODateTimeFormat.date())).isEqualTo("2013-08-16");

    assertThat(input.read(block)).isEqualTo(512);
    assertThat(new String(block, 0, likeTears.length(), UTF_8)).isEqualTo(likeTears);

    assertThat(input.read(block)).isEqualTo(512);
    header = PosixTarHeader.from(block);
    assertThat(header.getType()).isEqualTo(PosixTarHeader.Type.REGULAR);
    assertThat(header.getName()).isEqualTo("inrain");
    assertThat(header.getSize()).isEqualTo(inRain.length());
    assertThat(header.getMode()).isEqualTo(0600);
    assertThat(header.getUname()).isEqualTo("jart");
    assertThat(header.getGname()).isEqualTo("eng");
    assertThat(header.getUid()).isEqualTo(180918);
    assertThat(header.getGid()).isEqualTo(5000);
    assertThat(header.getMtime().toString(ISODateTimeFormat.date())).isEqualTo("2013-08-16");

    assertThat(input.read(block)).isEqualTo(512);
    assertThat(new String(block, 0, inRain.length(), UTF_8)).isEqualTo(inRain);

    assertThat(input.read(block)).isEqualTo(512);
    assertWithMessage("End of archive marker corrupt").that(block).isEqualTo(new byte[512]);
    assertThat(input.read(block)).isEqualTo(512);
    assertWithMessage("End of archive marker corrupt").that(block).isEqualTo(new byte[512]);
  }
}
