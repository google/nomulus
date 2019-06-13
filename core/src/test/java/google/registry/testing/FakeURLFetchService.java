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

package google.registry.testing;

import com.google.appengine.api.urlfetch.HTTPRequest;
import com.google.appengine.api.urlfetch.HTTPResponse;
import com.google.appengine.api.urlfetch.URLFetchService;
import com.google.common.collect.ImmutableList;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * A fake {@link URLFetchService} that serves constructed {@link HTTPResponse} objects from
 * a simple {@link Map} ({@link URL} to {@link HTTPResponse}) lookup.
 */
public class FakeURLFetchService extends ForwardingURLFetchService {

  private Map<URL, HTTPResponse> backingMap;

  public FakeURLFetchService(Map<URL, HTTPResponse> backingMap) {
    this.backingMap = backingMap;
  }

  @Override
  public HTTPResponse fetch(HTTPRequest request) {
    URL requestURL = request.getURL();
    if (backingMap.containsKey(requestURL)) {
      return backingMap.get(requestURL);
    } else {
      return new HTTPResponse(HttpURLConnection.HTTP_NOT_FOUND, null, null, ImmutableList.of());
    }
  }
}
