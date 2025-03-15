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

package google.registry.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.converters.IParameterSplitter;
import com.google.common.base.Ascii;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.request.Action.GaeService;
import google.registry.request.Action.GkeService;
import google.registry.request.Action.Service;
import jakarta.inject.Inject;
import java.util.List;

@Parameters(separators = " =", commandDescription = "Send an HTTP command to the nomulus server.")
class CurlCommand implements CommandWithConnection {
  private ServiceConnection connection;

  // HTTP Methods that are acceptable for use as values for --method.
  public enum Method {
    GET,
    POST
  }

  @Parameter(
      names = {"-X", "--request"},
      description = "HTTP method.  Must be either \"GET\" or \"POST\".")
  private Method method;

  @Parameter(
      names = {"-u", "--path"},
      description =
          "URL path to send the request to. (e.g. \"/_dr/foo?parm=val\").  Be careful "
              + "with the shell quoting.",
      required = true)
  private String path;

  @Parameter(
      names = {"-t", "--content-type"},
      converter = MediaTypeConverter.class,
      description =
          "Media type of the request body (for a POST request.  Must be combined with --body)")
  private MediaType mimeType = MediaType.PLAIN_TEXT_UTF_8;

  // TODO(b/112314048): Make this data flag friendlier (support escaping, convert to query args for
  // GET...)
  @Parameter(
      names = {"-d", "--data"},
      splitter = NoSplittingSplitter.class,
      description =
          "Body for a post request.  If specified, a POST request is sent.  If "
              + "absent, a GET request is sent.")
  private List<String> data;

  @Parameter(
      names = {"--service"},
      description = "Which service to connect to",
      required = true)
  private String serviceName;

  @Inject
  @Config("useGke")
  boolean useGke;

  @Inject
  @Config("useCanary")
  boolean useCanary;

  @Override
  public void setConnection(ServiceConnection connection) {
    this.connection = connection;
  }

  @Override
  public void run() throws Exception {
    if (method == null) {
      method = (data == null) ? Method.GET : Method.POST;
    } else if (method == Method.POST && data == null) {
      data = ImmutableList.of("");
    } else if (method == Method.GET && data != null) {
      throw new IllegalArgumentException("You may not specify a body for a get method.");
    }

    Service service =
        useGke
            ? GkeService.valueOf(Ascii.toUpperCase(serviceName))
            : GaeService.valueOf(Ascii.toUpperCase(serviceName));

    ServiceConnection connectionToService = connection.withService(service, useCanary);
    String response =
        (method == Method.GET)
            ? connectionToService.sendGetRequest(path, ImmutableMap.<String, String>of())
            : connectionToService.sendPostRequest(
                path,
                ImmutableMap.<String, String>of(),
                mimeType,
                Joiner.on("&").join(data).getBytes(UTF_8));
    System.out.println(response);
  }

  public static class MediaTypeConverter implements IStringConverter<MediaType> {
    @Override
    public MediaType convert(String mediaType) {
      List<String> parts = Splitter.on('/').splitToList(mediaType);
      checkArgument(parts.size() == 2, "invalid MediaType '%s'", mediaType);
      return MediaType.create(parts.get(0), parts.get(1)).withCharset(UTF_8);
    }
  }

  public static class NoSplittingSplitter implements IParameterSplitter {
    @Override
    public List<String> split(String value) {
      return ImmutableList.of(value);
    }
  }
}
