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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.CharSink;
import com.google.common.io.Files;
import com.google.common.net.MediaType;
import java.io.File;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Tests fir {@link BulkDomainTransferCommand}. */
public class BulkDomainTransferCommandTest extends CommandTestCase<BulkDomainTransferCommand> {

  private ServiceConnection connection;

  @BeforeEach
  void beforeEach() {
    connection = mock(ServiceConnection.class);
    command.setConnection(connection);
  }

  @Test
  void testSuccess_validParametersSent() throws Exception {
    runCommandForced(
        "--gaining_registrar_id", "NewRegistrar",
        "--losing_registrar_id", "TheRegistrar",
        "--reason", "someReason",
        "--domains", "foo.tld,bar.tld");
    assertInStdout("Sending batch of 2 domains");
    verify(connection)
        .sendPostRequest(
            "/_dr/task/bulkDomainTransfer",
            ImmutableMap.of(
                "gainingRegistrarId",
                "NewRegistrar",
                "losingRegistrarId",
                "TheRegistrar",
                "requestedByRegistrar",
                false,
                "reason",
                "someReason"),
            MediaType.PLAIN_TEXT_UTF_8,
            "[\"foo.tld\",\"bar.tld\"]".getBytes(UTF_8));
  }

  @Test
  void testSuccess_fileInBatches() throws Exception {
    File domainNamesFile = tmpDir.resolve("domain_names.txt").toFile();
    CharSink sink = Files.asCharSink(domainNamesFile, UTF_8);
    sink.writeLines(IntStream.range(0, 1003).mapToObj(i -> String.format("foo%d.tld", i)));
    runCommandForced(
        "--gaining_registrar_id", "NewRegistrar",
        "--losing_registrar_id", "TheRegistrar",
        "--reason", "someReason",
        "--domain_names_file", domainNamesFile.getPath());
    assertInStdout("Sending batch of 1000 domains");
    assertInStdout("Sending batch of 3 domains");
    ArgumentCaptor<byte[]> listCaptor = ArgumentCaptor.forClass(byte[].class);
    verify(connection, times(2))
        .sendPostRequest(
            eq("/_dr/task/bulkDomainTransfer"),
            eq(
                ImmutableMap.of(
                    "gainingRegistrarId",
                    "NewRegistrar",
                    "losingRegistrarId",
                    "TheRegistrar",
                    "requestedByRegistrar",
                    false,
                    "reason",
                    "someReason")),
            eq(MediaType.PLAIN_TEXT_UTF_8),
            listCaptor.capture());
    assertThat(listCaptor.getValue())
        .isEqualTo("[\"foo1000.tld\",\"foo1001.tld\",\"foo1002.tld\"]".getBytes(UTF_8));
  }

  @Test
  void testFailure_badGaining() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    runCommandForced(
                        "--gaining_registrar_id", "Bad",
                        "--losing_registrar_id", "TheRegistrar",
                        "--reason", "someReason",
                        "--domains", "foo.tld,baz.tld")))
        .hasMessageThat()
        .isEqualTo("Gaining registrar Bad doesn't exist");
  }

  @Test
  void testFailure_badLosing() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    runCommandForced(
                        "--gaining_registrar_id", "NewRegistrar",
                        "--losing_registrar_id", "Bad",
                        "--reason", "someReason",
                        "--domains", "foo.tld,baz.tld")))
        .hasMessageThat()
        .isEqualTo("Losing registrar Bad doesn't exist");
  }

  @Test
  void testFailure_noDomainsSpecified() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    runCommandForced(
                        "--gaining_registrar_id", "NewRegistrar",
                        "--losing_registrar_id", "TheRegistrar",
                        "--reason", "someReason")))
        .hasMessageThat()
        .isEqualTo(
            "Must specify exactly one input method, either --domains or --domain_names_file");
  }

  @Test
  void testFailure_bothDomainMethodsSpecified() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    runCommandForced(
                        "--gaining_registrar_id",
                        "NewRegistrar",
                        "--losing_registrar_id",
                        "TheRegistrar",
                        "--reason",
                        "someReason",
                        "--domains",
                        "foo.tld,baz.tld",
                        "--domain_names_file",
                        "foo.txt")))
        .hasMessageThat()
        .isEqualTo(
            "Must specify exactly one input method, either --domains or --domain_names_file");
  }
}
