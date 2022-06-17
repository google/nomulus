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

package google.registry.dns;

import static google.registry.dns.DnsConstants.DNS_PUBLISH_PUSH_QUEUE_NAME;
import static google.registry.dns.DnsConstants.DNS_PULL_QUEUE_NAME;
import static google.registry.request.RequestParameters.extractEnumParameter;
import static google.registry.request.RequestParameters.extractIntParameter;
import static google.registry.request.RequestParameters.extractRequiredParameter;
import static google.registry.request.RequestParameters.extractSetOfParameters;

import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import google.registry.dns.DnsConstants.TargetType;
import google.registry.dns.writer.DnsWriterZone;
import google.registry.request.Parameter;
import google.registry.request.RequestParameters;
import java.util.Set;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import org.joda.time.DateTime;

/** Dagger module for the dns package. */
@Module
public abstract class DnsModule {

  public static final String PARAM_DNS_WRITER = "dnsWriter";
  public static final String PARAM_LOCK_INDEX = "lockIndex";
  public static final String PARAM_NUM_PUBLISH_LOCKS = "numPublishLocks";
  public static final String PARAM_DOMAINS = "domains";
  public static final String PARAM_HOSTS = "hosts";
  public static final String PARAM_PUBLISH_TASK_ENQUEUED = "enqueued";
  public static final String PARAM_REFRESH_REQUEST_CREATED = "itemsCreated";

  @Binds
  @DnsWriterZone
  abstract String provideZoneName(@Parameter(RequestParameters.PARAM_TLD) String tld);

  /**
   * Provides a HashFunction used for generating sharded DNS publish queue tasks.
   *
   * <p>We use murmur3_32 because it isn't subject to change, and is fast (non-cryptographic, which
   * would be overkill in this situation.)
   */
  @Provides
  static HashFunction provideHashFunction() {
    return Hashing.murmur3_32();
  }

  @Provides
  @Named(DNS_PULL_QUEUE_NAME)
  static Queue provideDnsPullQueue() {
    return QueueFactory.getQueue(DNS_PULL_QUEUE_NAME);
  }

  @Provides
  @Named(DNS_PUBLISH_PUSH_QUEUE_NAME)
  static Queue provideDnsUpdatePushQueue() {
    return QueueFactory.getQueue(DNS_PUBLISH_PUSH_QUEUE_NAME);
  }

  @Provides
  @Parameter(PARAM_PUBLISH_TASK_ENQUEUED)
  static DateTime provideCreateTime(HttpServletRequest req) {
    return DateTime.parse(extractRequiredParameter(req, PARAM_PUBLISH_TASK_ENQUEUED));
  }

  @Provides
  @Parameter(PARAM_REFRESH_REQUEST_CREATED)
  static DateTime provideItemsCreateTime(HttpServletRequest req) {
    return DateTime.parse(extractRequiredParameter(req, PARAM_REFRESH_REQUEST_CREATED));
  }

  @Provides
  @Parameter(PARAM_DNS_WRITER)
  static String provideDnsWriter(HttpServletRequest req) {
    return extractRequiredParameter(req, PARAM_DNS_WRITER);
  }

  @Provides
  @Parameter(PARAM_LOCK_INDEX)
  static int provideLockIndex(HttpServletRequest req) {
    return extractIntParameter(req, PARAM_LOCK_INDEX);
  }

  @Provides
  @Parameter(PARAM_NUM_PUBLISH_LOCKS)
  static int provideMaxNumLocks(HttpServletRequest req) {
    return extractIntParameter(req, PARAM_NUM_PUBLISH_LOCKS);
  }

  @Provides
  @Parameter(PARAM_DOMAINS)
  static Set<String> provideDomains(HttpServletRequest req) {
    return extractSetOfParameters(req, PARAM_DOMAINS);
  }

  @Provides
  @Parameter(PARAM_HOSTS)
  static Set<String> provideHosts(HttpServletRequest req) {
    return extractSetOfParameters(req, PARAM_HOSTS);
  }

  @Provides
  @Parameter("domainOrHostName")
  static String provideName(HttpServletRequest req) {
    return extractRequiredParameter(req, "name");
  }

  @Provides
  @Parameter("type")
  static TargetType provideType(HttpServletRequest req) {
    return extractEnumParameter(req, TargetType.class, "type");
  }
}
