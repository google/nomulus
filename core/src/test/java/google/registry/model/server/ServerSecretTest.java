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

package google.registry.model.server;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;

import google.registry.model.ofy.RequestCapturingAsyncDatastoreService;
import google.registry.testing.AppEngineRule;
import java.util.UUID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ServerSecret}. */
@RunWith(JUnit4.class)
public class ServerSecretTest {
  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  @Before
  public void before() {
    ServerSecret.resetCache();
  }

  @Test
  public void testGet_bootstrapping_savesSecretToDatastore() {
    ServerSecret secret = ServerSecret.get();
    assertThat(secret).isNotNull();
    assertThat(ofy().load().entity(new ServerSecret()).now()).isEqualTo(secret);
  }

  @Test
  public void testGet_existingSecret_returned() {
    ServerSecret secret = ServerSecret.create(123, 456);
    ofy().saveWithoutBackup().entity(secret).now();
    assertThat(ServerSecret.get()).isEqualTo(secret);
    assertThat(ofy().load().entity(new ServerSecret()).now()).isEqualTo(secret);
  }

  @Test
  public void testGet_cachedSecret_returnedWithoutDatastoreRead() {
    int numInitialReads = RequestCapturingAsyncDatastoreService.getReads().size();
    ServerSecret secret = ServerSecret.get();
    int numReads = RequestCapturingAsyncDatastoreService.getReads().size();
    assertThat(numReads).isGreaterThan(numInitialReads);
    assertThat(ServerSecret.get()).isEqualTo(secret);
    assertThat(RequestCapturingAsyncDatastoreService.getReads()).hasSize(numReads);
  }

  @Test
  public void testAsUuid() {
    UUID uuid = ServerSecret.create(123, 456).asUuid();
    assertThat(uuid.getMostSignificantBits()).isEqualTo(123);
    assertThat(uuid.getLeastSignificantBits()).isEqualTo(456);
  }

  @Test
  public void testAsBytes() {
    byte[] bytes = ServerSecret.create(123, 0x456).asBytes();
    assertThat(bytes)
        .isEqualTo(new byte[] {0, 0, 0, 0, 0, 0, 0, 123, 0, 0, 0, 0, 0, 0, 0x4, 0x56});
  }
}
