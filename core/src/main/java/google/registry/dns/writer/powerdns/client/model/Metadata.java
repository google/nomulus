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
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Metadata {

  public static Metadata createMetadata(String kind, List<String> metadata) {
    Metadata m = new Metadata();
    m.setKind(kind);
    m.setMetadata(metadata);
    return m;
  }

  @JsonProperty("kind")
  private String kind;

  @JsonProperty("metadata")
  private List<String> metadata;

  public String getKind() {
    return kind;
  }

  public void setKind(String kind) {
    this.kind = kind;
  }

  public List<String> getMetadata() {
    return metadata;
  }

  public void setMetadata(List<String> metadata) {
    this.metadata = metadata;
  }

  @Override
  public String toString() {
    return String.format("{kind:%s,metadata:%s}", kind, metadata);
  }
}
