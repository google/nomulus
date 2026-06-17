// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.module;

import com.google.common.flogger.FluentLogger;
import google.registry.request.RequestHandler;
import google.registry.util.SystemClock;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Security;
import java.time.Duration;
import java.time.Instant;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/** Base for Servlets that handle all requests to our modules. */
public class ServletBase extends HttpServlet {

  private final RequestHandler<?> requestHandler;
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final SystemClock clock = new SystemClock();

  public ServletBase(RequestHandler<?> requestHandler) {
    this.requestHandler = requestHandler;
  }

  @Override
  public void init() {
    Security.addProvider(new BouncyCastleProvider());
  }

  @Override
  public void service(HttpServletRequest req, HttpServletResponse rsp) throws IOException {
    logger.atInfo().log("Received %s request.", getClass().getSimpleName());
    Instant startTime = clock.now();
    try {
      requestHandler.handleRequest(req, rsp);
    } finally {
      logger.atInfo().log(
          "Finished %s request. Latency: %.3fs.",
          getClass().getSimpleName(), Duration.between(startTime, clock.now()).toMillis() / 1000d);
    }
  }
}
