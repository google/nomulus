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

package google.registry.tools;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.xml.XmlTestUtils.assertXmlEquals;
import static google.registry.xml.XmlTransformer.prettyPrint;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.MediaType;
import google.registry.tools.server.ToolsTestData;
import java.net.URLDecoder;
import java.util.Map;
import org.mockito.ArgumentCaptor;

/**
 * Class for verifying EPP commands sent to the server via the tool endpoint.
 *
 * <p>Provides its own (mock) {@link AppEngineConnection} that will be monitored for EPP
 * transmission. This Connection needs to be registered with the tool endpoint - something like
 * this:
 *
 * <pre>{@code
 * SomeToolCommand command = ...;
 * EppToolVerifier eppToolVerifier = EppToolVerifier.create(command);
 * // run command...
 * eppToolVerifier.expectClientId("SomeClientId").verifySent("some_epp_file.xml");
 * }</pre>
 */
public class EppToolVerifier {

  private final AppEngineConnection connection = mock(AppEngineConnection.class);

  private String registrarId;
  private boolean superuser;
  private boolean dryRun;
  private ImmutableList<byte[]> capturedParams;
  private int paramIndex;

  private EppToolVerifier() {}

  /** Creates an EppToolVerifier that monitors EPPs sent by the given command. */
  public static EppToolVerifier create(EppToolCommand command) {
    EppToolVerifier eppToolVerifier = new EppToolVerifier();
    command.setConnection(eppToolVerifier.getConnection());
    return eppToolVerifier;
  }

  /**
   * Sets the expected registrarId for any following verifySent command.
   *
   * <p>Must be called at least once before any {@link #verifySent} calls.
   */
  EppToolVerifier expectRegistrarId(String registrarId) {
    this.registrarId = registrarId;
    return this;
  }

  /**
   * Declares that any following verifySent command expects the "superuser" flag to be set.
   *
   * <p>If not called, {@link #verifySent} will expect the "superuser" flag to be false.
   */
  EppToolVerifier expectSuperuser() {
    this.superuser = true;
    return this;
  }

  /**
   * Declares that any following verifySent command expects the "dryRun" flag to be set.
   *
   * <p>If not called, {@link #verifySent} will expect the "dryRun" flag to be false.
   */
  EppToolVerifier expectDryRun() {
    this.dryRun = true;
    return this;
  }

  /**
   * Tests that the expected EPP was sent.
   *
   * <p>The expected EPP must have the correct "clientId", "dryRun" and "superuser" flags as set by
   * the various "expect*" calls.
   *
   * <p>The expected EPP's content is checked against the given file.
   *
   * <p>If multiple EPPs are expected, the verifySent* call order must match the actual EPP order.
   *
   * @param expectedXmlFile the name of the file holding the expected content of the EPP. The file
   *     resides in the tools/server/testdata directory.
   */
  public EppToolVerifier verifySent(String expectedXmlFile) throws Exception {
    return verifySentContents(ToolsTestData.loadFile(expectedXmlFile));
  }

  /**
   * Tests that the expected EPP was sent, with the given substitutions.
   *
   * <p>The expected EPP must have the correct "clientId", "dryRun" and "superuser" flags as set by
   * the various "expect*" calls.
   *
   * <p>The expected EPP's content is checked against the given file after the given substitutions
   * have been applied.
   *
   * <p>If multiple EPPs are expected, the verifySent* call order must match the EPP order.
   *
   * @param expectedXmlFile the name of the file holding the expected content of the EPP. The file
   *     resides in the tools/server/testdata directory.
   * @param substitutions a list of substitutions to apply on the expectedXmlFile
   */
  public EppToolVerifier verifySent(String expectedXmlFile, Map<String, String> substitutions)
      throws Exception {
    return verifySentContents(ToolsTestData.loadFile(expectedXmlFile, substitutions));
  }

  /**
   * Tests an EPP was sent, without checking the contents.
   *
   * <p>The expected EPP are not check for its content or any of the "contentId" / "superuser" /
   * "dryRun" flags - only that it exists.
   *
   * <p>If multiple EPPs are expected, the verifySent* call order must match the EPP order.
   */
  EppToolVerifier verifySentAny() throws Exception {
    setArgumentsIfNeeded();
    paramIndex++;
    assertThat(capturedParams.size()).isAtLeast(paramIndex);
    return this;
  }

  /**
   * Test that no more EPPs were sent, after any that were expected in previous "verifySent" calls.
   */
  void verifyNoMoreSent() throws Exception {
    setArgumentsIfNeeded();
    assertThat(
            capturedParams
                .stream()
                .skip(paramIndex)
                .map(bytes -> new String(bytes, UTF_8))
                .toArray())
        .isEmpty();
  }

  private void setArgumentsIfNeeded() throws Exception {
    if (capturedParams != null) {
      return;
    }
    ArgumentCaptor<byte[]> params = ArgumentCaptor.forClass(byte[].class);
    verify(connection, atLeast(0))
        .sendPostRequest(
            eq("/_dr/epptool"), eq(ImmutableMap.of()), eq(MediaType.FORM_DATA), params.capture());
    capturedParams = ImmutableList.copyOf(params.getAllValues());
    paramIndex = 0;
  }

  private String bytesToXml(byte[] bytes) throws Exception {
    checkState(registrarId != null, "expectClientId must be called before any verifySent command");
    Map<String, String> map =
        Splitter.on('&')
        .withKeyValueSeparator('=')
        .split(new String(bytes, UTF_8));
    assertThat(map).hasSize(4);
    assertThat(map).containsEntry("dryRun", Boolean.toString(dryRun));
    assertThat(map).containsEntry("clientId", registrarId);
    assertThat(map).containsEntry("superuser", Boolean.toString(superuser));
    return URLDecoder.decode(map.get("xml"), UTF_8.toString());
  }

  private EppToolVerifier verifySentContents(String expectedXmlContent) throws Exception {
    setArgumentsIfNeeded();
    assertThat(capturedParams.size()).isGreaterThan(paramIndex);
    assertXmlEquals(expectedXmlContent, prettyPrint(bytesToXml(capturedParams.get(paramIndex))));
    paramIndex++;
    return this;
  }

  /** Returns the (mock) Connection that is being monitored by this verifier. */
  private AppEngineConnection getConnection() {
    return connection;
  }
}
