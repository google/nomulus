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

package google.registry.util;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.appengine.api.modules.ModulesService;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link AppEngineServiceUtilsImpl}. */
@RunWith(JUnit4.class)
public class AppEngineServiceUtilsImplTest {

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock private ModulesService modulesService;

  private AppEngineServiceUtils appEngineServiceUtils;

  @Before
  public void before() {
    appEngineServiceUtils = new AppEngineServiceUtilsImpl(modulesService);
    when(modulesService.getVersionHostname(anyString(), isNull()))
        .thenReturn("1234.servicename.projectid.appspot.fake");
    when(modulesService.getVersionHostname(anyString(), eq("2345")))
        .thenReturn("2345.servicename.projectid.appspot.fake");
  }

  @Test
  public void test_getServiceHostname_doesntIncludeVersionId() {
    assertThat(appEngineServiceUtils.getServiceHostname("servicename"))
        .isEqualTo("servicename.projectid.appspot.fake");
  }

  @Test
  public void test_getVersionHostname_doesIncludeVersionId() {
    assertThat(appEngineServiceUtils.getCurrentVersionHostname("servicename"))
        .isEqualTo("1234.servicename.projectid.appspot.fake");
  }

  @Test
  public void test_getVersionHostname_worksWithVersionId() {
    assertThat(appEngineServiceUtils.getVersionHostname("servicename", "2345"))
        .isEqualTo("2345.servicename.projectid.appspot.fake");
  }

  @Test
  public void test_getVersionHostname_throwsWhenVersionIdIsNull() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> appEngineServiceUtils.getVersionHostname("servicename", null));
    assertThat(thrown).hasMessageThat().isEqualTo("Must specify the version");
  }

  @Test
  public void test_setNumInstances_worksWithValidParameters() {
    appEngineServiceUtils.setNumInstances("service", "version", 10L);
    verify(modulesService, times(1)).setNumInstances("service", "version", 10L);
  }

  @Test
  public void test_setNumInstances_throwsWhenServiceIsNull() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> appEngineServiceUtils.setNumInstances(null, "version", 10L));
    assertThat(thrown).hasMessageThat().isEqualTo("Must specify the service");
  }

  @Test
  public void test_setNumInstances_throwsWhenVersionIsNull() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> appEngineServiceUtils.setNumInstances("service", null, 10L));
    assertThat(thrown).hasMessageThat().isEqualTo("Must specify the version");
  }

  @Test
  public void test_setNumInstances_throwsWhenNumInstancesIsInvalid() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> appEngineServiceUtils.setNumInstances("service", "version", -10L));
    assertThat(thrown).hasMessageThat().isEqualTo("Number of instances must be greater than 0");
  }

  @Test
  public void test_convertToSingleSubdomain_doesNothingWithoutServiceOrHostname() {
    assertThat(appEngineServiceUtils.convertToSingleSubdomain("projectid.appspot.com"))
        .isEqualTo("projectid.appspot.com");
  }

  @Test
  public void test_convertToSingleSubdomain_doesNothingWhenItCannotParseCorrectly() {
    assertThat(appEngineServiceUtils.convertToSingleSubdomain("garbage.notrealhost.example"))
        .isEqualTo("garbage.notrealhost.example");
  }

  @Test
  public void test_convertToSingleSubdomain_convertsWithServiceName() {
    assertThat(appEngineServiceUtils.convertToSingleSubdomain("service.projectid.appspot.com"))
        .isEqualTo("service-dot-projectid.appspot.com");
  }

  @Test
  public void test_convertToSingleSubdomain_convertsWithVersionAndServiceName() {
    assertThat(
            appEngineServiceUtils.convertToSingleSubdomain("version.service.projectid.appspot.com"))
        .isEqualTo("version-dot-service-dot-projectid.appspot.com");
  }

  @Test
  public void test_convertToSingleSubdomain_convertsWithInstanceAndVersionAndServiceName() {
    assertThat(
            appEngineServiceUtils.convertToSingleSubdomain(
                "instanceid.version.service.projectid.appspot.com"))
        .isEqualTo("instanceid-dot-version-dot-service-dot-projectid.appspot.com");
  }
}
