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

package google.registry.export.sheet;

import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeLockHandler;
import google.registry.testing.FakeResponse;
import java.util.Optional;
import javax.annotation.Nullable;
import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link SyncRegistrarsSheetAction}. */
@RunWith(JUnit4.class)
public class SyncRegistrarsSheetActionTest {

  @Rule
  public final AppEngineRule appEngine =
      AppEngineRule.builder().withDatastoreAndCloudSql().withTaskQueue().build();

  private final FakeResponse response = new FakeResponse();
  private final SyncRegistrarsSheet syncRegistrarsSheet = mock(SyncRegistrarsSheet.class);

  private SyncRegistrarsSheetAction action;

  private void runAction(@Nullable String idConfig, @Nullable String idParam) {
    action.idConfig = Optional.ofNullable(idConfig);
    action.idParam = Optional.ofNullable(idParam);
    action.run();
  }

  @Before
  public void setUp() {
    action = new SyncRegistrarsSheetAction();
    action.response = response;
    action.syncRegistrarsSheet = syncRegistrarsSheet;
    action.timeout = Duration.standardHours(1);
    action.lockHandler = new FakeLockHandler(true);
  }

  @Test
  public void testPost_withoutParamsOrSystemProperty_dropsTask() {
    runAction(null, null);
    assertThat(response.getPayload()).startsWith("MISSINGNO");
    verifyNoInteractions(syncRegistrarsSheet);
  }

  @Test
  public void testPost_withoutParams_runsSyncWithDefaultIdAndChecksIfModified() throws Exception {
    when(syncRegistrarsSheet.wereRegistrarsModified()).thenReturn(true);
    runAction("jazz", null);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload()).startsWith("OK");
    verify(syncRegistrarsSheet).wereRegistrarsModified();
    verify(syncRegistrarsSheet).run(eq("jazz"));
    verifyNoMoreInteractions(syncRegistrarsSheet);
  }

  @Test
  public void testPost_noModificationsToRegistrarEntities_doesNothing() {
    when(syncRegistrarsSheet.wereRegistrarsModified()).thenReturn(false);
    runAction("NewRegistrar", null);
    assertThat(response.getPayload()).startsWith("NOTMODIFIED");
    verify(syncRegistrarsSheet).wereRegistrarsModified();
    verifyNoMoreInteractions(syncRegistrarsSheet);
  }

  @Test
  public void testPost_overrideId_runsSyncWithCustomIdAndDoesNotCheckModified() throws Exception {
    runAction(null, "foobar");
    assertThat(response.getPayload()).startsWith("OK");
    verify(syncRegistrarsSheet).run(eq("foobar"));
    verifyNoMoreInteractions(syncRegistrarsSheet);
  }

  @Test
  public void testPost_failToAquireLock_servletDoesNothingAndReturns() {
    action.lockHandler = new FakeLockHandler(false);
    runAction(null, "foobar");
    assertThat(response.getPayload()).startsWith("LOCKED");
    verifyNoInteractions(syncRegistrarsSheet);
  }
}
