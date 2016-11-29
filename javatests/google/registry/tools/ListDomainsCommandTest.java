// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import google.registry.tools.server.ListDomainsAction;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

/**
 * Unit tests for {@link ListDomainsCommand}.
 *
 * @see ListObjectsCommandTestCase
 */
@RunWith(MockitoJUnitRunner.class)
public class ListDomainsCommandTest extends ListObjectsCommandTestCase<ListDomainsCommand> {

  @Override
  final String getTaskPath() {
    return ListDomainsAction.PATH;
  }

  @Override
  protected List<String> getTlds() {
    return ImmutableList.of("foo");
  }

  @Test
  public void test_tldsParamTooLong() throws Exception {
    String tldsParam = "--tld=foo,bar" + Strings.repeat(",baz", 300);
    thrown.expect(
        IllegalArgumentException.class, "Total length of TLDs is too long for URL parameter");
    runCommand(tldsParam);
  }
}
