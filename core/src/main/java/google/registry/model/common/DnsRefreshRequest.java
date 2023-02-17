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

package google.registry.model.common;

import google.registry.dns.DnsConstants.TargetType;
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
public class DnsRefreshRequest {

  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Id
  long id;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  TargetType type;

  @Column(nullable = false)
  String name;

  @Column(nullable = false)
  String tld;

  @Column(nullable = false)
  DateTime requestTime;
}
