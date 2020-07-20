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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.request.Action.Service.BACKEND;
import static google.registry.request.Action.Service.DEFAULT;
import static google.registry.request.Action.Service.PUBAPI;
import static google.registry.request.Action.Service.TOOLS;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.net.MediaType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit tests for {@link CurlCommand}. */
class CurlCommandTest extends CommandTestCase<CurlCommand> {

  @Mock private AppEngineConnection connection;
  @Mock private AppEngineConnection connectionForService;

  @BeforeEach
  void beforeEach() {
    command.setConnection(connection);
    when(connection.withService(any())).thenReturn(connectionForService);
  }

  @Captor ArgumentCaptor<ImmutableMap<String, String>> urlParamCaptor;

  @Test
  void testGetInvocation() throws Exception {
    runCommand("--path=/foo/bar?a=1&b=2", "--service=TOOLS");
    verify(connection).withService(TOOLS);
    verifyNoMoreInteractions(connection);
    verify(connectionForService)
        .sendGetRequest(eq("/foo/bar?a=1&b=2"), eq(ImmutableMap.<String, String>of()));
  }

  @Test
  void testExplicitGetInvocation() throws Exception {
    runCommand("--path=/foo/bar?a=1&b=2", "--request=GET", "--service=BACKEND");
    verify(connection).withService(BACKEND);
    verifyNoMoreInteractions(connection);
    verify(connectionForService)
        .sendGetRequest(eq("/foo/bar?a=1&b=2"), eq(ImmutableMap.<String, String>of()));
  }

  @Test
  void testPostInvocation() throws Exception {
    runCommand("--path=/foo/bar?a=1&b=2", "--data=some data", "--service=DEFAULT");
    verify(connection).withService(DEFAULT);
    verifyNoMoreInteractions(connection);
    verify(connectionForService)
        .sendPostRequest(
            eq("/foo/bar?a=1&b=2"),
            eq(ImmutableMap.<String, String>of()),
            eq(MediaType.PLAIN_TEXT_UTF_8),
            eq("some data".getBytes(UTF_8)));
  }

  @Test
  void testPostInvocation_withContentType() throws Exception {
    runCommand(
        "--path=/foo/bar?a=1&b=2",
        "--data=some data",
        "--service=DEFAULT",
        "--content-type=application/json");
    verify(connection).withService(DEFAULT);
    verifyNoMoreInteractions(connection);
    verify(connectionForService)
        .sendPostRequest(
            eq("/foo/bar?a=1&b=2"),
            eq(ImmutableMap.<String, String>of()),
            eq(MediaType.JSON_UTF_8),
            eq("some data".getBytes(UTF_8)));
  }

  @Test
  @MockitoSettings(strictness = Strictness.LENIENT)
  void testPostInvocation_badContentType() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommand(
                "--path=/foo/bar?a=1&b=2",
                "--data=some data",
                "--service=DEFAULT",
                "--content-type=bad"));
    verifyNoMoreInteractions(connection);
    verifyNoMoreInteractions(connectionForService);
  }

  @Test
  void testMultiDataPost() throws Exception {
    runCommand(
        "--path=/foo/bar?a=1&b=2", "--data=first=100", "-d", "second=200", "--service=PUBAPI");
    verify(connection).withService(PUBAPI);
    verifyNoMoreInteractions(connection);
    verify(connectionForService)
        .sendPostRequest(
            eq("/foo/bar?a=1&b=2"),
            eq(ImmutableMap.<String, String>of()),
            eq(MediaType.PLAIN_TEXT_UTF_8),
            eq("first=100&second=200".getBytes(UTF_8)));
  }

  @Test
  void testDataDoesntSplit() throws Exception {
    runCommand(
        "--path=/foo/bar?a=1&b=2", "--data=one,two", "--service=PUBAPI");
    verify(connection).withService(PUBAPI);
    verifyNoMoreInteractions(connection);
    verify(connectionForService)
        .sendPostRequest(
            eq("/foo/bar?a=1&b=2"),
            eq(ImmutableMap.<String, String>of()),
            eq(MediaType.PLAIN_TEXT_UTF_8),
            eq("one,two".getBytes(UTF_8)));
  }

  @Test
  void testExplicitPostInvocation() throws Exception {
    runCommand("--path=/foo/bar?a=1&b=2", "--request=POST", "--service=TOOLS");
    verify(connection).withService(TOOLS);
    verifyNoMoreInteractions(connection);
    verify(connectionForService)
        .sendPostRequest(
            eq("/foo/bar?a=1&b=2"),
            eq(ImmutableMap.<String, String>of()),
            eq(MediaType.PLAIN_TEXT_UTF_8),
            eq("".getBytes(UTF_8)));
  }

  @Test
  @MockitoSettings(strictness = Strictness.LENIENT)
  void testGetWithBody() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommand(
                    "--path=/foo/bar?a=1&b=2",
                    "--request=GET",
                    "--data=inappropriate data",
                    "--service=TOOLS"));
    assertThat(thrown).hasMessageThat().contains("You may not specify a body for a get method.");
  }
}
