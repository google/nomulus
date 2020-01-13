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

package google.registry.flows.host;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.flows.host.HostFlowUtils.validateHostName;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Strings;
import google.registry.flows.host.HostFlowUtils.HostNameNotLowerCaseException;
import google.registry.flows.host.HostFlowUtils.HostNameNotNormalizedException;
import google.registry.flows.host.HostFlowUtils.HostNameNotPunyCodedException;
import google.registry.flows.host.HostFlowUtils.HostNameTooLongException;
import google.registry.flows.host.HostFlowUtils.HostNameTooShallowException;
import google.registry.flows.host.HostFlowUtils.InvalidHostNameException;
import google.registry.testing.AppEngineRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link HostFlowUtils}. */
@RunWith(JUnit4.class)
public class HostFlowUtilsTest {

  @Rule public final AppEngineRule appEngine = AppEngineRule.builder().withDatastore().build();

  @Test
  public void test_validExternalHostName_validates() throws Exception {
    assertThat(validateHostName("host.example.com").toString()).isEqualTo("host.example.com");
  }

  @Test
  public void test_validExternalHostNameOnRegistrySuffixList_validates() throws Exception {
    assertThat(validateHostName("host.blogspot.com").toString()).isEqualTo("host.blogspot.com");
  }

  @Test
  public void test_validExternalHostNameOnRegistrySuffixList_multipartTLD_validates()
      throws Exception {
    assertThat(validateHostName("ns1.host.co.uk").toString()).isEqualTo("ns1.host.co.uk");
  }

  @Test
  public void test_validExternalHostNameOnRegistrySuffixList_multipartTLD_tooShallow() {
    assertThrows(
        HostNameTooShallowException.class, () -> validateHostName("host.co.uk").toString());
  }

  @Test
  public void test_validateHostName_hostNameTooLong() {
    assertThrows(
        HostNameTooLongException.class,
        () -> validateHostName(Strings.repeat("na", 200) + ".wat.man"));
  }

  @Test
  public void test_validateHostName_hostNameNotLowerCase() {
    assertThrows(HostNameNotLowerCaseException.class, () -> validateHostName("NA.CAPS.TLD"));
  }

  @Test
  public void test_validateHostName_hostNameNotPunyCoded() {
    assertThrows(
        HostNameNotPunyCodedException.class, () -> validateHostName("motörhead.death.metal"));
  }

  @Test
  public void test_validateHostName_hostNameNotNormalized() {
    assertThrows(HostNameNotNormalizedException.class, () -> validateHostName("root.node.yeah."));
  }

  @Test
  public void test_validateHostName_hostNameHasLeadingHyphen() {
    assertThrows(InvalidHostNameException.class, () -> validateHostName("-giga.mega.tld"));
  }

  @Test
  public void test_validateHostName_hostNameTooShallow() {
    assertThrows(HostNameTooShallowException.class, () -> validateHostName("domain.tld"));
  }
}
