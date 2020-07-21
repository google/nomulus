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

package google.registry.tmch;

import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.google.appengine.api.urlfetch.HTTPRequest;
import com.google.appengine.api.urlfetch.HTTPResponse;
import com.google.appengine.api.urlfetch.URLFetchService;
import google.registry.testing.AppEngineRule;
import google.registry.testing.BouncyCastleProviderExtension;
import google.registry.testing.FakeClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Common code for unit tests of classes that extend {@link Marksdb}. */
@ExtendWith(MockitoExtension.class)
abstract class TmchActionTestCase {

  static final String MARKSDB_LOGIN_AND_PASSWORD = "lolcat:attack";
  static final String MARKSDB_URL = "http://127.0.0.1/love";

  @RegisterExtension
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  @RegisterExtension
  public final BouncyCastleProviderExtension bouncy = new BouncyCastleProviderExtension();

  @Mock URLFetchService fetchService;
  @Mock HTTPResponse httpResponse;
  @Captor ArgumentCaptor<HTTPRequest> httpRequest;

  final FakeClock clock = new FakeClock();
  final Marksdb marksdb = new Marksdb();

  @BeforeEach
  public void beforeEachTmchActionTestCase() throws Exception {
    marksdb.fetchService = fetchService;
    marksdb.tmchMarksdbUrl = MARKSDB_URL;
    marksdb.marksdbPublicKey = TmchData.loadPublicKey(TmchTestData.loadBytes("pubkey"));
    when(fetchService.fetch(any(HTTPRequest.class))).thenReturn(httpResponse);
    when(httpResponse.getResponseCode()).thenReturn(SC_OK);
  }
}
