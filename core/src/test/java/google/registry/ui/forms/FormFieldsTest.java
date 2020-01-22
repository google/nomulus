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

package google.registry.ui.forms;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThrows;

import com.google.common.testing.NullPointerTester;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link FormFields}. */
@RunWith(JUnit4.class)
public class FormFieldsTest {

  @Test
  public void testXsToken_collapsesAndTrimsWhitespace() {
    assertThat(FormFields.XS_TOKEN.convert("  hello \r\n\t there\n")).hasValue("hello there");
  }

  @Test
  public void testXsNormalizedString_extraSpaces_doesntCare() {
    assertThat(FormFields.XS_NORMALIZED_STRING.convert("hello  there")).hasValue("hello  there");
  }

  @Test
  public void testXsNormalizedString_sideSpaces_doesntCare() {
    assertThat(FormFields.XS_NORMALIZED_STRING.convert(" hello there ")).hasValue(" hello there ");
  }

  @Test
  public void testXsNormalizedString_containsNonSpaceWhitespace_fails() {
    FormFieldException thrown =
        assertThrows(
            FormFieldException.class,
            () -> FormFields.XS_NORMALIZED_STRING.convert("  hello \r\n\t there\n"));
    assertThat(
        thrown,
        equalTo(
            new FormFieldException("Must not contain tabs or multiple lines.")
                .propagate("xsNormalizedString")));
  }

  @Test
  public void testXsEppE164PhoneNumber_nanpaNumber_validates() {
    assertThat(FormFields.XS_NORMALIZED_STRING.convert("+1.2125650000")).hasValue("+1.2125650000");
  }

  @Test
  public void testXsEppE164PhoneNumber_londonNumber_validates() {
    assertThat(FormFields.XS_NORMALIZED_STRING.convert("+44.2011112222"))
        .hasValue("+44.2011112222");
  }

  @Test
  public void testXsEppE164PhoneNumber_localizedNumber_fails() {
    FormFieldException thrown =
        assertThrows(
            FormFieldException.class, () -> FormFields.PHONE_NUMBER.convert("(212) 565-0000"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Must be a valid +E.164 phone number, e.g. +1.2125650000");
  }

  @Test
  public void testXsEppE164PhoneNumber_appliesXsTokenTransform() {
    assertThat(FormFields.PHONE_NUMBER.convert("  +1.2125650000  \r")).hasValue("+1.2125650000");
  }

  @Test
  public void testXsEppRoid_correctSyntax_validates() {
    assertThat(FormFields.ROID.convert("SH8013-REP")).hasValue("SH8013-REP");
  }

  @Test
  public void testXsEppRoid_lowerCase_validates() {
    assertThat(FormFields.ROID.convert("sh8013-rep")).hasValue("sh8013-rep");
  }

  @Test
  public void testXsEppRoid_missingHyphen_fails() {
    FormFieldException thrown =
        assertThrows(FormFieldException.class, () -> FormFields.ROID.convert("SH8013REP"));
    assertThat(thrown).hasMessageThat().contains("Please enter a valid EPP ROID, e.g. SH8013-REP");
  }

  @Test
  public void testXsEppRoid_appliesXsTokenTransform() {
    assertThat(FormFields.ROID.convert("\n  FOO-BAR  \r")).hasValue("FOO-BAR");
  }

  @Test
  public void testNullness() {
    new NullPointerTester().testAllPublicStaticMethods(FormFields.class);
  }
}
