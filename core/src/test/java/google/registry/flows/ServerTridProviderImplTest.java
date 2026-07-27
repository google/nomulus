// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.flows;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.common.FeatureFlag.FeatureName.USE_RANDOM_SERVER_TRID;
import static google.registry.model.common.FeatureFlag.FeatureStatus.ACTIVE;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_INSTANT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.common.FeatureFlag;
import google.registry.model.common.FeatureFlag.FeatureStatus;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ServerTridProviderImpl}. */
class ServerTridProviderImplTest {

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  @AfterEach
  void tearDown() {
    ServerTridProviderImpl.secureRandom.remove();
  }

  @Test
  void testCreateServerTrid_flagInactive_generatesLegacyFormat() {
    ServerTridProviderImpl provider = new ServerTridProviderImpl();
    String trid1 = provider.createServerTrid();
    String trid2 = provider.createServerTrid();

    assertThat(trid1).contains("-");
    assertThat(trid2).contains("-");
    assertThat(trid1).isNotEqualTo(trid2);
  }

  @Test
  void testCreateServerTrid_flagActive_generatesCorrectFormat() {
    persistResource(
        new FeatureFlag.Builder()
            .setFeatureName(USE_RANDOM_SERVER_TRID)
            .setStatusMap(
                ImmutableSortedMap.<Instant, FeatureStatus>naturalOrder()
                    .put(START_INSTANT, ACTIVE)
                    .build())
            .build());

    SecureRandom mockSecureRandom = mock(SecureRandom.class);

    // Mock secureRandom to return a deterministic sequence of bytes: 0, 1, 2, ..., 23
    doAnswer(
            invocation -> {
              byte[] bytes = invocation.getArgument(0);
              for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) i;
              }
              return null;
            })
        .when(mockSecureRandom)
        .nextBytes(any(byte[].class));

    ServerTridProviderImpl.secureRandom.set(mockSecureRandom);
    ServerTridProviderImpl provider = new ServerTridProviderImpl();
    String trid = provider.createServerTrid();

    String expectedTrid = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYX";

    Pattern tridPattern = Pattern.compile("^[A-Za-z0-9_-]{32}$");
    assertThat(trid).matches(tridPattern);
    assertThat(trid.length()).isAtMost(64);
    assertThat(trid).isEqualTo(expectedTrid);
  }

  @Test
  void testCreateServerTrid_flagActive_withMaxByteValues() {
    persistResource(
        new FeatureFlag.Builder()
            .setFeatureName(USE_RANDOM_SERVER_TRID)
            .setStatusMap(
                ImmutableSortedMap.<Instant, FeatureStatus>naturalOrder()
                    .put(START_INSTANT, ACTIVE)
                    .build())
            .build());

    SecureRandom mockSecureRandom = mock(SecureRandom.class);

    // Mock secureRandom to return all 0xFF bytes
    doAnswer(
            invocation -> {
              byte[] bytes = invocation.getArgument(0);
              for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) 0xFF;
              }
              return null;
            })
        .when(mockSecureRandom)
        .nextBytes(any(byte[].class));

    ServerTridProviderImpl.secureRandom.set(mockSecureRandom);
    ServerTridProviderImpl provider = new ServerTridProviderImpl();
    String trid = provider.createServerTrid();

    String expectedTrid = "________________________________";
    assertThat(trid).isEqualTo(expectedTrid);
  }

  @Test
  void testCreateServerTrid_flagActive_realInitializationWorks() {
    persistResource(
        new FeatureFlag.Builder()
            .setFeatureName(USE_RANDOM_SERVER_TRID)
            .setStatusMap(
                ImmutableSortedMap.<Instant, FeatureStatus>naturalOrder()
                    .put(START_INSTANT, ACTIVE)
                    .build())
            .build());

    ServerTridProviderImpl provider = new ServerTridProviderImpl();
    String trid1 = provider.createServerTrid();
    String trid2 = provider.createServerTrid();

    Pattern tridPattern = Pattern.compile("^[A-Za-z0-9_-]{32}$");
    assertThat(trid1).matches(tridPattern);
    assertThat(trid2).matches(tridPattern);
    assertThat(trid1).isNotEqualTo(trid2);
  }
}
