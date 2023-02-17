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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import google.registry.dns.DnsConstants.TargetType;
import google.registry.dns.PublishDnsUpdatesAction;
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
@Table(indexes = {@Index(columnList = "requestTime"), @Index(columnList = "processTime")})
public class DnsRefreshRequest extends ImmutableObject {

  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Id
  @SuppressWarnings("unused")
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

  @Column(nullable = false)
  private DateTime processTime;

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

  /**
   * The time at which the entity is processed.
   *
   * <p>Note that "processed" means that it is read, not necessarily that the DNS requests is
   * processed successfully. The subsequent steps to bundle requests together and enqueue them in a
   * Cloud Tasks queue for {@link PublishDnsUpdatesAction} to process can still fail.
   *
   * <p>This value allows us to control if a row is just recently read and should be skipped, should
   * there are concurrent reads that all attempt to read the rows with oldest {@link #requestTime}.
   */
  public DateTime getProcessTime() {
    return processTime;
  }

  protected DnsRefreshRequest() {}

  private DnsRefreshRequest(
      TargetType type, String name, String tld, DateTime requestTime, DateTime processTime) {
    checkNotNull(type, "Target type cannot be null");
    checkNotNull(name, "Domain/host name cannot be null");
    checkNotNull(tld, "TLD cannot be null");
    checkNotNull(requestTime, "Request time cannot be null");
    checkNotNull(processTime, "Process time cannot be null");
    this.type = type;
    this.name = name;
    this.tld = tld;
    this.requestTime = requestTime;
    this.processTime = processTime;
  }

  public DnsRefreshRequest(TargetType type, String name, String tld, DateTime requestTime) {
    this(type, name, tld, requestTime, START_OF_TIME);
  }

  public DnsRefreshRequest updateProcessTime(DateTime processTime) {
    checkArgument(
        processTime.isAfter(getRequestTime()),
        "Process time %s must be later than request time %s",
        processTime,
        getRequestTime());
    checkArgument(
        processTime.isAfter(getProcessTime()),
        "New process time %s must be later than the old one %s",
        processTime,
        getProcessTime());
    return new DnsRefreshRequest(getType(), getName(), getTld(), getRequestTime(), processTime);
  }
}
