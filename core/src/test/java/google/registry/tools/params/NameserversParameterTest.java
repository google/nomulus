// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools.params;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.tools.params.NameserversParameter.splitNameservers;
import static org.junit.Assert.assertThrows;

import com.beust.jcommander.ParameterException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link NameserversParameter}. */
@RunWith(JUnit4.class)
public class NameserversParameterTest {

  private final NameserversParameter instance = new NameserversParameter();

  @Test
  public void testConvert_singleBasicNameserver() {
    assertThat(instance.convert("ns11.goes.to")).containsExactly("ns11.goes.to");
  }

  @Test
  public void testConvert_multipleBasicNameservers() {
    assertThat(instance.convert("ns1.tim.buktu, ns2.tim.buktu, ns3.tim.buktu"))
        .containsExactly("ns1.tim.buktu", "ns2.tim.buktu", "ns3.tim.buktu");
  }

  @Test
  public void testConvert_kitchenSink() {
    assertThat(instance.convert(" ns1.foo.bar, ,ns2.foo.bar,, ns[1-3].range.baz "))
        .containsExactly(
            "ns1.foo.bar", "ns2.foo.bar", "ns1.range.baz", "ns2.range.baz", "ns3.range.baz");
  }

  @Test
  public void testConvert_invalid() {
    assertThat(instance.convert("ns1.foo.bar,ns2.foo.baz,ns3.foo.bar"))
        .containsExactly("ns1.foo.bar", "ns3.foo.bar", "ns2.foo.baz");
  }

  @Test
  public void testValidate_sillyString_throws() {
    ParameterException thrown =
        assertThrows(ParameterException.class, () -> instance.validate("nameservers", "[[ns]]"));
    assertThat(thrown).hasMessageThat().contains("Must be a comma-delimited list of nameservers");
    assertThat(thrown).hasCauseThat().hasMessageThat().contains("Could not parse square brackets");
  }

  @Test
  public void testConvert_empty_returnsEmpty() {
    assertThat(instance.convert("")).isEmpty();
  }

  @Test
  public void testConvert_nullString_returnsNull() {
    assertThat(instance.convert(null)).isEmpty();
  }

  @Test
  public void testSplitNameservers_noopWithNoBrackets() {
    assertThat(splitNameservers("ns9.fake.example")).containsExactly("ns9.fake.example");
  }

  @Test
  public void testSplitNameservers_worksWithBrackets() {
    assertThat(splitNameservers("ns[1-4].zan.zibar"))
        .containsExactly("ns1.zan.zibar", "ns2.zan.zibar", "ns3.zan.zibar", "ns4.zan.zibar");
  }

  @Test
  public void testSplitNameservers_worksWithBrackets_soloRange() {
    assertThat(splitNameservers("ns[1-1].zan.zibar")).containsExactly("ns1.zan.zibar");
  }

  @Test
  public void testSplitNameservers_throwsOnInvalidRange() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> splitNameservers("ns[9-2].foo.bar"));
    assertThat(thrown).hasMessageThat().isEqualTo("Number range [9-2] is invalid");
  }

  @Test
  public void testSplitNameservers_throwsOnInvalidHostname_missingPrefix() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> splitNameservers("[1-4].foo.bar"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Could not parse square brackets in [1-4].foo.bar");
  }

  @Test
  public void testSplitNameservers_throwsOnInvalidHostname_missingDomainName() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> splitNameservers("this.is.ns[1-5]"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Could not parse square brackets in this.is.ns[1-5]");
  }

  @Test
  public void testSplitNameservers_throwsOnInvalidRangeSyntax() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> splitNameservers("ns[1-4[.foo.bar"));
    assertThat(thrown).hasMessageThat().contains("Could not parse square brackets");
  }
}
