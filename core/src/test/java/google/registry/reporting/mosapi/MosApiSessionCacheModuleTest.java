// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.reporting.mosapi;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;

import dagger.Provides;
import google.registry.privileges.secretmanager.SecretManagerClient;
import jakarta.inject.Singleton;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link MosApiSessionCacheModule}. */
public class MosApiSessionCacheModuleTest {
  @Test
  void testProviderMethod_returnsInstance() {
    // Mock the dependency
    SecretManagerClient mockClient = mock(SecretManagerClient.class);
    // Call the static provider method
    MosApiSessionCache cache = MosApiSessionCacheModule.provideMosApiSessionCache(mockClient);
    // Verify it returns a non-null instance
    assertThat(cache).isNotNull();
    assertThat(cache).isInstanceOf(MosApiSessionCache.class);
  }

  @Test
  void testProviderMethod_hasCorrectAnnotations() throws Exception {
    // This test ensures the Dagger annotations are present, which is important for
    // the injection framework.
    Method providerMethod =
        MosApiSessionCacheModule.class.getDeclaredMethod(
            "provideMosApiSessionCache", SecretManagerClient.class);

    assertThat(providerMethod.isAnnotationPresent(Provides.class)).isTrue();
    assertThat(providerMethod.isAnnotationPresent(Singleton.class)).isTrue();
  }
}
