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

package google.registry.dns.writer.powerdns.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Server {
  @JsonProperty("type")
  private String type;

  @JsonProperty("id")
  private String id;

  @JsonProperty("daemon_type")
  private String daemonType;

  @JsonProperty("version")
  private String version;

  @JsonProperty("url")
  private String url;

  @JsonProperty("config_url")
  private String configUrl;

  @JsonProperty("zones_url")
  private String zonesUrl;

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getDaemonType() {
    return daemonType;
  }

  public void setDaemonType(String daemonType) {
    this.daemonType = daemonType;
  }

  public String getVersion() {
    return version;
  }

  public void setVersion(String version) {
    this.version = version;
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public String getConfigUrl() {
    return configUrl;
  }

  public void setConfigUrl(String configUrl) {
    this.configUrl = configUrl;
  }

  public String getZonesUrl() {
    return zonesUrl;
  }

  public void setZonesUrl(String zonesUrl) {
    this.zonesUrl = zonesUrl;
  }
}
