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
import static google.registry.testing.DatastoreHelper.createTlds;
import static google.registry.testing.DatastoreHelper.newRegistry;
import static google.registry.testing.DatastoreHelper.persistResource;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.MediaType;
import google.registry.model.registry.Registry.TldType;
import google.registry.tools.server.ListDomainsAction;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link ListDomainsCommand}.
 *
 * @see ListObjectsCommandTestCase
 */
public class ListDomainsCommandTest extends ListObjectsCommandTestCase<ListDomainsCommand> {

  @Override
  final String getTaskPath() {
    return ListDomainsAction.PATH;
  }

  @Override
  protected ImmutableMap<String, Object> getOtherParameters() {
    return ImmutableMap.of("tlds", "foo", "limit", Integer.MAX_VALUE);
  }

  @Test
  @MockitoSettings(strictness = Strictness.LENIENT)
  void test_tldsParamTooLong() {
    String tldsParam = "--tlds=foo,bar" + Strings.repeat(",baz", 300);
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommand(tldsParam));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Total length of TLDs is too long for URL parameter");
  }

  @Test
  void test_bothParamsSpecified() throws Exception {
    runCommand("--tlds=foo,bar", "--limit=100");
    verify(connection)
        .sendPostRequest(
            eq(getTaskPath()),
            eq(ImmutableMap.of("tlds", "foo,bar", "limit", 100)),
            eq(MediaType.PLAIN_TEXT_UTF_8),
            eq(new byte[0]));
  }

  @Test
  void test_defaultsToAllRealTlds() throws Exception {
    createTlds("tldone", "tldtwo");
    persistResource(newRegistry("fake", "FAKE").asBuilder().setTldType(TldType.TEST).build());
    runCommand();
    verify(connection)
        .sendPostRequest(
            eq(getTaskPath()),
            eq(ImmutableMap.of("tlds", "tldone,tldtwo", "limit", Integer.MAX_VALUE)),
            eq(MediaType.PLAIN_TEXT_UTF_8),
            eq(new byte[0]));
  }
}
