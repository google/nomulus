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

package google.registry.request;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.request.RequestParameters.extractBooleanParameter;
import static google.registry.request.RequestParameters.extractEnumParameter;
import static google.registry.request.RequestParameters.extractOptionalDatetimeParameter;
import static google.registry.request.RequestParameters.extractOptionalEnumParameter;
import static google.registry.request.RequestParameters.extractOptionalParameter;
import static google.registry.request.RequestParameters.extractRequiredDatetimeParameter;
import static google.registry.request.RequestParameters.extractRequiredParameter;
import static google.registry.request.RequestParameters.extractSetOfEnumParameters;
import static google.registry.request.RequestParameters.extractSetOfParameters;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import google.registry.request.HttpException.BadRequestException;
import javax.servlet.http.HttpServletRequest;
import org.joda.time.DateTime;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RequestParameters}. */
@RunWith(JUnit4.class)
public class RequestParametersTest {
  private final HttpServletRequest req = mock(HttpServletRequest.class);

  @Test
  public void testExtractRequiredParameter_valuePresent_returnsValue() {
    when(req.getParameter("spin")).thenReturn("bog");
    assertThat(extractRequiredParameter(req, "spin")).isEqualTo("bog");
  }

  @Test
  public void testExtractRequiredParameter_notPresent_throwsBadRequest() {
    BadRequestException thrown =
        assertThrows(BadRequestException.class, () -> extractRequiredParameter(req, "spin"));
    assertThat(thrown).hasMessageThat().contains("spin");
  }

  @Test
  public void testExtractRequiredParameter_empty_throwsBadRequest() {
    when(req.getParameter("spin")).thenReturn("");
    BadRequestException thrown =
        assertThrows(BadRequestException.class, () -> extractRequiredParameter(req, "spin"));
    assertThat(thrown).hasMessageThat().contains("spin");
  }

  @Test
  public void testExtractOptionalParameter_valuePresent_returnsValue() {
    when(req.getParameter("spin")).thenReturn("bog");
    assertThat(extractOptionalParameter(req, "spin")).hasValue("bog");
  }

  @Test
  public void testExtractOptionalParameter_notPresent_returnsEmpty() {
    assertThat(extractOptionalParameter(req, "spin")).isEmpty();
  }

  @Test
  public void testExtractOptionalParameter_empty_returnsEmpty() {
    when(req.getParameter("spin")).thenReturn("");
    assertThat(extractOptionalParameter(req, "spin")).isEmpty();
  }

  @Test
  public void testExtractSetOfParameters_notPresent_returnsEmpty() {
    assertThat(extractSetOfParameters(req, "spin")).isEmpty();
  }

  @Test
  public void testExtractSetOfParameters_empty_returnsEmpty() {
    when(req.getParameter("spin")).thenReturn("");
    assertThat(extractSetOfParameters(req, "spin")).isEmpty();
  }

  @Test
  public void testExtractSetOfParameters_oneValue_returnsValue() {
    when(req.getParameter("spin")).thenReturn("bog");
    assertThat(extractSetOfParameters(req, "spin")).containsExactly("bog");
  }

  @Test
  public void testExtractSetOfParameters_multipleValues_returnsAll() {
    when(req.getParameter("spin")).thenReturn("bog,gob");
    assertThat(extractSetOfParameters(req, "spin")).containsExactly("bog", "gob");
  }

  @Test
  public void testExtractSetOfParameters_multipleValuesWithEmpty_removesEmpty() {
    when(req.getParameter("spin")).thenReturn(",bog,,gob,");
    assertThat(extractSetOfParameters(req, "spin")).containsExactly("bog", "gob");
  }

  @Test
  public void testExtractSetOfParameters_multipleParameters_error() {
    when(req.getParameterValues("spin")).thenReturn(new String[]{"bog", "gob"});
    BadRequestException thrown =
        assertThrows(BadRequestException.class, () -> extractSetOfParameters(req, "spin"));
    assertThat(thrown).hasMessageThat().contains("spin");
  }

  @Test
  public void testExtractSetOfEnumParameters_notPresent_returnsEmpty() {
    assertThat(extractSetOfEnumParameters(req, Club.class, "spin")).isEmpty();
  }

  @Test
  public void testExtractSetOfEnumParameters_empty_returnsEmpty() {
    when(req.getParameter("spin")).thenReturn("");
    assertThat(extractSetOfEnumParameters(req, Club.class, "spin")).isEmpty();
  }

  @Test
  public void testExtractSetOfEnumParameters_oneValue_returnsValue() {
    when(req.getParameter("spin")).thenReturn("DANCE");
    assertThat(extractSetOfEnumParameters(req, Club.class, "spin")).containsExactly(Club.DANCE);
  }

  @Test
  public void testExtractSetOfEnumParameters_multipleValues_returnsAll() {
    when(req.getParameter("spin")).thenReturn("DANCE,FLOOR");
    assertThat(extractSetOfEnumParameters(req, Club.class, "spin"))
        .containsExactly(Club.DANCE, Club.FLOOR);
  }

  @Test
  public void testExtractSetOfEnumParameters_multipleValuesWithEmpty_removesEmpty() {
    when(req.getParameter("spin")).thenReturn(",DANCE,,FLOOR,");
    assertThat(extractSetOfEnumParameters(req, Club.class, "spin"))
        .containsExactly(Club.DANCE, Club.FLOOR);
  }

  @Test
  public void testExtractSetOfEnumParameters_multipleValues_caseInsensitive() {
    when(req.getParameter("spin")).thenReturn("danCE,FlooR");
    assertThat(extractSetOfEnumParameters(req, Club.class, "spin"))
        .containsExactly(Club.DANCE, Club.FLOOR);
  }

  @Test
  public void testExtractSetOfEnumParameters_multipleParameters_error() {
    when(req.getParameterValues("spin")).thenReturn(new String[]{"DANCE", "FLOOR"});
    BadRequestException thrown =
        assertThrows(
            BadRequestException.class, () -> extractSetOfEnumParameters(req, Club.class, "spin"));
    assertThat(thrown).hasMessageThat().contains("spin");
  }

  @Test
  public void testExtractBooleanParameter_notPresent_returnsFalse() {
    assertThat(extractBooleanParameter(req, "love")).isFalse();
  }

  @Test
  public void testExtractBooleanParameter_presentWithoutValue_returnsTrue() {
    when(req.getParameterMap()).thenReturn(ImmutableMap.of("love", ""));
    assertThat(extractBooleanParameter(req, "love")).isTrue();
  }

  @Test
  public void testExtractBooleanParameter_empty_returnsTrue() {
    when(req.getParameterMap()).thenReturn(ImmutableMap.of("love", ""));
    when(req.getParameter("love")).thenReturn("");
    assertThat(extractBooleanParameter(req, "love")).isTrue();
  }

  @Test
  public void testExtractBooleanParameter_presentStringArbitrary_returnsTrue() {
    when(req.getParameterMap()).thenReturn(ImmutableMap.of("love", "lol"));
    when(req.getParameter("love")).thenReturn("lol");
    assertThat(extractBooleanParameter(req, "love")).isTrue();
  }

  @Test
  public void testExtractBooleanParameter_presentStringTrue_returnsTrue() {
    when(req.getParameterMap()).thenReturn(ImmutableMap.of("love", "true"));
    when(req.getParameter("love")).thenReturn("true");
    assertThat(extractBooleanParameter(req, "love")).isTrue();
  }

  @Test
  public void testExtractBooleanParameter_presentStringFalse_returnsFalse() {
    when(req.getParameterMap()).thenReturn(ImmutableMap.of("love", "false"));
    when(req.getParameter("love")).thenReturn("false");
    assertThat(extractBooleanParameter(req, "love")).isFalse();
  }

  @Test
  public void testExtractBooleanParameter_presentStringFalse_caseInsensitive() {
    when(req.getParameterMap()).thenReturn(ImmutableMap.of("love", "FaLsE"));
    when(req.getParameter("love")).thenReturn("FaLsE");
    assertThat(extractBooleanParameter(req, "love")).isFalse();
  }

  enum Club { DANCE, FLOOR }

  @Test
  public void testExtractEnumValue_correctValue_works() {
    when(req.getParameter("spin")).thenReturn("DANCE");
    assertThat(extractEnumParameter(req, Club.class, "spin")).isEqualTo(Club.DANCE);
  }

  @Test
  public void testExtractEnumValue_weirdCasing_isCaseInsensitive() {
    when(req.getParameter("spin")).thenReturn("DaNcE");
    assertThat(extractEnumParameter(req, Club.class, "spin")).isEqualTo(Club.DANCE);
  }

  @Test
  public void testExtractEnumValue_nonExistentValue_throwsBadRequest() {
    when(req.getParameter("spin")).thenReturn("sing");
    BadRequestException thrown =
        assertThrows(
            BadRequestException.class, () -> extractEnumParameter(req, Club.class, "spin"));
    assertThat(thrown).hasMessageThat().contains("spin");
  }

  @Test
  public void testOptionalExtractEnumValue_givenValue_returnsValue() {
    when(req.getParameter("spin")).thenReturn("DANCE");
    assertThat(extractOptionalEnumParameter(req, Club.class, "spin")).hasValue(Club.DANCE);
  }

  @Test
  public void testOptionalExtractEnumValue_noValue_returnsEmpty() {
    when(req.getParameter("spin")).thenReturn("");
    assertThat(extractOptionalEnumParameter(req, Club.class, "spin")).isEmpty();
  }

  @Test
  public void testOptionalExtractEnumValue_nonExistentValue_throwsBadRequest() {
    when(req.getParameter("spin")).thenReturn("sing");
    BadRequestException thrown =
        assertThrows(
            BadRequestException.class, () -> extractOptionalEnumParameter(req, Club.class, "spin"));
    assertThat(thrown).hasMessageThat().contains("spin");
  }

  @Test
  public void testExtractRequiredDatetimeParameter_correctValue_works() {
    when(req.getParameter("timeParam")).thenReturn("2015-08-27T13:25:34.123Z");
    assertThat(extractRequiredDatetimeParameter(req, "timeParam"))
        .isEqualTo(DateTime.parse("2015-08-27T13:25:34.123Z"));
  }

  @Test
  public void testExtractRequiredDatetimeParameter_badValue_throwsBadRequest() {
    when(req.getParameter("timeParam")).thenReturn("Tuesday at three o'clock");
    BadRequestException thrown =
        assertThrows(
            BadRequestException.class, () -> extractRequiredDatetimeParameter(req, "timeParam"));
    assertThat(thrown).hasMessageThat().contains("timeParam");
  }

  @Test
  public void testExtractOptionalDatetimeParameter_correctValue_works() {
    when(req.getParameter("timeParam")).thenReturn("2015-08-27T13:25:34.123Z");
    assertThat(extractOptionalDatetimeParameter(req, "timeParam"))
        .hasValue(DateTime.parse("2015-08-27T13:25:34.123Z"));
  }

  @Test
  public void testExtractOptionalDatetimeParameter_badValue_throwsBadRequest() {
    when(req.getParameter("timeParam")).thenReturn("Tuesday at three o'clock");
    BadRequestException thrown =
        assertThrows(
            BadRequestException.class, () -> extractOptionalDatetimeParameter(req, "timeParam"));
    assertThat(thrown).hasMessageThat().contains("timeParam");
  }

  @Test
  public void testExtractOptionalDatetimeParameter_empty_returnsEmpty() {
    when(req.getParameter("timeParam")).thenReturn("");
    assertThat(extractOptionalDatetimeParameter(req, "timeParam")).isEmpty();
  }

  @Test
  public void testExtractRequiredDatetimeParameter_noValue_throwsBadRequest() {
    BadRequestException thrown =
        assertThrows(
            BadRequestException.class, () -> extractRequiredDatetimeParameter(req, "timeParam"));
    assertThat(thrown).hasMessageThat().contains("timeParam");
  }
}
