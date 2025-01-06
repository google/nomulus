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

package google.registry.tmch;

import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.BouncyCastleProviderExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeUrlConnectionService;
import google.registry.testing.TestCacheExtension;
import java.net.HttpURLConnection;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.junit.jupiter.MockitoExtension;

/** Common code for unit tests of classes that extend {@link Marksdb}. */
@ExtendWith(MockitoExtension.class)
abstract class TmchActionTestCase {

  static final String MARKSDB_LOGIN_AND_PASSWORD = "lolcat:attack";
  static final String MARKSDB_URL = "http://127.0.0.1/love";

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  @RegisterExtension
  public final BouncyCastleProviderExtension bouncy = new BouncyCastleProviderExtension();

  @RegisterExtension
  public final TestCacheExtension testCacheExtension =
      new TestCacheExtension.Builder().withClaimsListCache(Duration.ofHours(6)).build();

  final FakeClock clock = new FakeClock();
  final Marksdb marksdb = new Marksdb();

  protected final HttpURLConnection httpUrlConnection = mock(HttpURLConnection.class);
  protected FakeUrlConnectionService urlConnectionService =
      new FakeUrlConnectionService(httpUrlConnection);

  @BeforeEach
  public void beforeEachTmchActionTestCase() throws Exception {
    marksdb.tmchMarksdbUrl = MARKSDB_URL;
    marksdb.marksdbPublicKey =
        TmchData.loadPublicKey(TmchTestData.loadBytes("crypto/marksdb-public-key-test.asc"));
    marksdb.urlConnectionService = urlConnectionService;
    when(httpUrlConnection.getResponseCode()).thenReturn(SC_OK);
  }
}
