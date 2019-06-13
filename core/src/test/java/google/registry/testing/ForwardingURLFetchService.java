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
import com.google.common.util.concurrent.Futures;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.Future;

/**
 * An implementation of the {@link URLFetchService} interface that forwards all requests through
 * a synchronous fetch call.
 */
public abstract class ForwardingURLFetchService implements URLFetchService {

  @Override
  public HTTPResponse fetch(URL url) throws IOException {
    return fetch(new HTTPRequest(url)); // Defaults to HTTPMethod.GET
  }

  @Override
  public Future<HTTPResponse> fetchAsync(URL url) {
    return fetchAsync(new HTTPRequest(url)); // Defaults to HTTPMethod.GET
  }

  @Override
  public Future<HTTPResponse> fetchAsync(HTTPRequest request) {
    try {
      return Futures.immediateFuture(fetch(request));
    } catch (Exception e) {
      return Futures.immediateFailedFuture(e);
    }
  }
}
