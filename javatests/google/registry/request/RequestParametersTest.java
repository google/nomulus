// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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
import static google.registry.request.RequestParameters.extractBooleanParameter;
import static google.registry.request.RequestParameters.extractEnumParameter;
import static google.registry.request.RequestParameters.extractOptionalDatetimeParameter;
import static google.registry.request.RequestParameters.extractOptionalParameter;
import static google.registry.request.RequestParameters.extractRequiredDatetimeParameter;
import static google.registry.request.RequestParameters.extractRequiredParameter;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import google.registry.request.HttpException.BadRequestException;
import google.registry.testing.ExceptionRule;
import javax.servlet.http.HttpServletRequest;
import org.joda.time.DateTime;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

/** Unit tests for {@link RequestParameters}. */
@RunWith(MockitoJUnitRunner.class)
public class RequestParametersTest {

  @Rule
  public final ExceptionRule thrown = new ExceptionRule();

  @Mock
  private HttpServletRequest req;

  @Test
  public void testExtractRequiredParameter_valuePresent_returnsValue() throws Exception {
    when(req.getParameter("spin")).thenReturn("bog");
    assertThat(extractRequiredParameter(req, "spin")).isEqualTo("bog");
  }

  @Test
  public void testExtractRequiredParameter_notPresent_throwsBadRequest() throws Exception {
    thrown.expect(BadRequestException.class, "spin");
    extractRequiredParameter(req, "spin");
  }

  @Test
  public void testExtractRequiredParameter_empty_throwsBadRequest() throws Exception {
    when(req.getParameter("spin")).thenReturn("");
    thrown.expect(BadRequestException.class, "spin");
    extractRequiredParameter(req, "spin");
  }

  @Test
  public void testExtractOptionalParameter_valuePresent_returnsValue() throws Exception {
    when(req.getParameter("spin")).thenReturn("bog");
    assertThat(extractOptionalParameter(req, "spin")).hasValue("bog");
  }

  @Test
  public void testExtractOptionalParameter_notPresent_returnsAbsent() throws Exception {
    assertThat(extractOptionalParameter(req, "spin")).isAbsent();
  }

  @Test
  public void testExtractOptionalParameter_empty_returnsAbsent() throws Exception {
    when(req.getParameter("spin")).thenReturn("");
    assertThat(extractOptionalParameter(req, "spin")).isAbsent();
  }

  @Test
  public void testExtractBooleanParameter_notPresent_returnsFalse() throws Exception {
    assertThat(extractBooleanParameter(req, "love")).isFalse();
  }

  @Test
  public void testExtractBooleanParameter_presentWithoutValue_returnsTrue() throws Exception {
    when(req.getParameterMap()).thenReturn(ImmutableMap.of("love", ""));
    assertThat(extractBooleanParameter(req, "love")).isTrue();
  }

  @Test
  public void testExtractBooleanParameter_empty_returnsTrue() throws Exception {
    when(req.getParameterMap()).thenReturn(ImmutableMap.of("love", ""));
    when(req.getParameter("love")).thenReturn("");
    assertThat(extractBooleanParameter(req, "love")).isTrue();
  }

  @Test
  public void testExtractBooleanParameter_presentStringArbitrary_returnsTrue() throws Exception {
    when(req.getParameterMap()).thenReturn(ImmutableMap.of("love", "lol"));
    when(req.getParameter("love")).thenReturn("lol");
    assertThat(extractBooleanParameter(req, "love")).isTrue();
  }

  @Test
  public void testExtractBooleanParameter_presentStringTrue_returnsTrue() throws Exception {
    when(req.getParameterMap()).thenReturn(ImmutableMap.of("love", "true"));
    when(req.getParameter("love")).thenReturn("true");
    assertThat(extractBooleanParameter(req, "love")).isTrue();
  }

  @Test
  public void testExtractBooleanParameter_presentStringFalse_returnsFalse() throws Exception {
    when(req.getParameterMap()).thenReturn(ImmutableMap.of("love", "false"));
    when(req.getParameter("love")).thenReturn("false");
    assertThat(extractBooleanParameter(req, "love")).isFalse();
  }

  @Test
  public void testExtractBooleanParameter_presentStringFalse_caseInsensitive() throws Exception {
    when(req.getParameterMap()).thenReturn(ImmutableMap.of("love", "FaLsE"));
    when(req.getParameter("love")).thenReturn("FaLsE");
    assertThat(extractBooleanParameter(req, "love")).isFalse();
  }

  enum Club { DANCE, FLOOR }

  @Test
  public void testExtractEnumValue_correctValue_works() throws Exception {
    when(req.getParameter("spin")).thenReturn("DANCE");
    assertThat(extractEnumParameter(req, Club.class, "spin")).isEqualTo(Club.DANCE);
  }

  @Test
  public void testExtractEnumValue_weirdCasing_isCaseInsensitive() throws Exception {
    when(req.getParameter("spin")).thenReturn("DaNcE");
    assertThat(extractEnumParameter(req, Club.class, "spin")).isEqualTo(Club.DANCE);
  }

  @Test
  public void testExtractEnumValue_nonExistentValue_throwsBadRequest() throws Exception {
    when(req.getParameter("spin")).thenReturn("sing");
    thrown.expect(BadRequestException.class, "spin");
    extractEnumParameter(req, Club.class, "spin");
  }

  @Test
  public void testExtractRequiredDatetimeParameter_correctValue_works() throws Exception {
    when(req.getParameter("timeParam")).thenReturn("2015-08-27T13:25:34.123Z");
    assertThat(extractRequiredDatetimeParameter(req, "timeParam"))
        .isEqualTo(DateTime.parse("2015-08-27T13:25:34.123Z"));
  }

  @Test
  public void testExtractRequiredDatetimeParameter_badValue_throwsBadRequest() throws Exception {
    when(req.getParameter("timeParam")).thenReturn("Tuesday at three o'clock");
    thrown.expect(BadRequestException.class, "timeParam");
    extractRequiredDatetimeParameter(req, "timeParam");
  }

  @Test
  public void testExtractOptionalDatetimeParameter_correctValue_works() throws Exception {
    when(req.getParameter("timeParam")).thenReturn("2015-08-27T13:25:34.123Z");
    assertThat(extractOptionalDatetimeParameter(req, "timeParam"))
        .hasValue(DateTime.parse("2015-08-27T13:25:34.123Z"));
  }

  @Test
  public void testExtractOptionalDatetimeParameter_badValue_throwsBadRequest() throws Exception {
    when(req.getParameter("timeParam")).thenReturn("Tuesday at three o'clock");
    thrown.expect(BadRequestException.class, "timeParam");
    extractOptionalDatetimeParameter(req, "timeParam");
  }

  @Test
  public void testExtractOptionalDatetimeParameter_empty_returnsAbsent() throws Exception {
    when(req.getParameter("timeParam")).thenReturn("");
    assertThat(extractOptionalDatetimeParameter(req, "timeParam")).isAbsent();
  }

  @Test
  public void testExtractRequiredDatetimeParameter_noValue_throwsBadRequest() throws Exception {
    thrown.expect(BadRequestException.class, "timeParam");
    extractRequiredDatetimeParameter(req, "timeParam");
  }
}
