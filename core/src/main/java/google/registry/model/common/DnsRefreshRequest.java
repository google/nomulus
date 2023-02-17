// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.common;

import google.registry.dns.DnsConstants.TargetType;
import google.registry.model.ImmutableObject;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import org.joda.time.DateTime;

@Entity
@Table(indexes = @Index(columnList = "requestTime"))
public class DnsRefreshRequest extends ImmutableObject {

  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Id
  private long id;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  private TargetType type;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String tld;

  @Column(nullable = false)
  private DateTime requestTime;

  public TargetType getType() {
    return type;
  }

  public String getName() {
    return name;
  }

  public String getTld() {
    return tld;
  }

  public DateTime getRequestTime() {
    return requestTime;
  }

  private DnsRefreshRequest() {}

  public DnsRefreshRequest(TargetType type, String name, String tld, DateTime requestTime) {
    this.type = type;
    this.name = name;
    this.tld = tld;
    this.requestTime = requestTime;
  }
}
