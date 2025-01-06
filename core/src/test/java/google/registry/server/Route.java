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

package google.registry.server;

import jakarta.servlet.http.HttpServlet;

/** Pair of servlet path and servlet instance object. */
public record Route(String path, Class<? extends HttpServlet> servletClass) {

  /** Creates a new route mapping between a path (may have wildcards) and a servlet. */
  public static Route route(String path, Class<? extends HttpServlet> servletClass) {
    return new Route(path, servletClass);
  }
}
