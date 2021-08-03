// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.batch;

import static com.google.common.truth.Truth.assertThat;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static org.apache.http.HttpStatus.SC_INTERNAL_SERVER_ERROR;
import static org.apache.http.HttpStatus.SC_OK;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import google.registry.beam.BeamActionTestBase;
import google.registry.config.RegistryEnvironment;
import google.registry.testing.FakeClock;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link WipeoutDatastoreAction}. */
class WipeOutDatastoreActionTest extends BeamActionTestBase {

  private final FakeClock clock = new FakeClock();

  @Test
  void run_projectNotAllowed() {
    try {
      RegistryEnvironment.SANDBOX.setup();
      WipeoutDatastoreAction action =
          new WipeoutDatastoreAction(
              "domain-registry-sandbox",
              "us-central1",
              "gs://some-bucket",
              clock,
              response,
              dataflow);
      action.run();
      assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
      verifyNoInteractions(dataflow);
    } finally {
      RegistryEnvironment.UNITTEST.setup();
    }
  }

  @Test
  void run_projectAllowed() throws Exception {
    WipeoutDatastoreAction action =
        new WipeoutDatastoreAction(
            "domain-registry-qa", "us-central1", "gs://some-bucket", clock, response, dataflow);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(launch, times(1)).execute();
    verifyNoMoreInteractions(launch);
  }

  @Test
  void run_failure() throws Exception {
    when(launch.execute()).thenThrow(new RuntimeException());
    WipeoutDatastoreAction action =
        new WipeoutDatastoreAction(
            "domain-registry-qa", "us-central1", "gs://some-bucket", clock, response, dataflow);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_INTERNAL_SERVER_ERROR);
    verify(launch, times(1)).execute();
    verifyNoMoreInteractions(launch);
  }
}
