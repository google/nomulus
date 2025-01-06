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

package google.registry.testing;

import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import google.registry.request.UrlConnectionService;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** A fake {@link UrlConnectionService} with a mocked HTTP connection for testing. */
public class FakeUrlConnectionService implements UrlConnectionService {

  private final HttpURLConnection mockConnection;
  private final List<URL> connectedUrls = new ArrayList<>();

  public FakeUrlConnectionService(HttpURLConnection mockConnection) {
    this.mockConnection = mockConnection;
  }

  @Override
  public HttpURLConnection createConnection(URL url) {
    connectedUrls.add(url);
    when(mockConnection.getURL()).thenReturn(url);
    return mockConnection;
  }

  public ImmutableList<URL> getConnectedUrls() {
    return ImmutableList.copyOf(connectedUrls);
  }
}
