// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package com.google.domain.registry.module.tools;

import com.google.common.base.Optional;
import com.google.domain.registry.request.RequestHandler;
import com.google.domain.registry.request.Route;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Tools-specific version of {@code RequestHandler}.
 *
 * @see com.google.domain.registry.request.RequestHandler
 */
public class ToolsRequestHandler extends RequestHandler<ToolsRequestComponent> {

  @Inject
  public ToolsRequestHandler(
      ToolsRequestComponent component,
      HttpServletResponse rsp,
      HttpServletRequest req,
      Optional<Route> route) {
    super(component, rsp, req, route);
  }
}