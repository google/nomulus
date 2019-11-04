// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ui.server.registrar;

import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.model.transaction.TransactionManagerFactory.tm;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;
import static google.registry.ui.server.SoyTemplateUtils.CSS_RENAMING_MAP_SUPPLIER;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.template.soy.tofu.SoyTofu;
import com.googlecode.objectify.Key;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.registry.Registry;
import google.registry.model.registry.RegistryLockDao;
import google.registry.model.reporting.HistoryEntry;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.schema.domain.RegistryLock;
import google.registry.ui.server.SoyTemplateUtils;
import google.registry.ui.soy.registrar.RegistryLockVerificationSoyInfo;
import google.registry.util.Clock;
import google.registry.util.DateTimeUtils;
import java.util.HashMap;
import javax.inject.Inject;

/** Action that allows for verification of registry lock / unlock requests */
@Action(
    service = Action.Service.DEFAULT,
    path = RegistryLockVerifyAction.PATH,
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public final class RegistryLockVerifyAction extends ConsoleAction {

  public static final String PATH = "/registry-lock-verify";

  private static final Supplier<SoyTofu> TOFU_SUPPLIER =
      SoyTemplateUtils.createTofuSupplier(
          google.registry.ui.soy.ConsoleSoyInfo.getInstance(),
          google.registry.ui.soy.AnalyticsSoyInfo.getInstance(),
          google.registry.ui.soy.registrar.RegistryLockVerificationSoyInfo.getInstance());

  private final Clock clock;
  @VisibleForTesting String lockVerificationCode;

  @Inject
  public RegistryLockVerifyAction(
      Clock clock, @Parameter("lockVerificationCode") String lockVerificationCode) {
    this.clock = clock;
    this.lockVerificationCode = lockVerificationCode;
  }

  @Override
  public void runAfterLogin(HashMap<String, Object> data) {
    try {
      verifyAndApplyLock(data);
      data.put("success", true);
    } catch (Throwable t) {
      data.put("success", false);
      data.put("errorMessage", Throwables.getRootCause(t).getMessage());
    }
    response.setPayload(
        TOFU_SUPPLIER
            .get()
            .newRenderer(RegistryLockVerificationSoyInfo.VERIFICATION_PAGE)
            .setCssRenamingMap(CSS_RENAMING_MAP_SUPPLIER.get())
            .setData(data)
            .render());
  }

  @Override
  public String getPath() {
    return PATH;
  }

  private void verifyAndApplyLock(HashMap<String, Object> data) {
    jpaTm()
        .transact(
            () -> {
              RegistryLock lock =
                  RegistryLockDao.getByVerificationCode(lockVerificationCode)
                      .orElseThrow(() -> new IllegalArgumentException("Unknown verification code"));
              data.put("isLock", lock.getAction().equals(RegistryLock.Action.LOCK));
              data.put("fullyQualifiedDomainName", lock.getDomainName());
              verifyLock(lock);
              RegistryLockDao.save(lock.asBuilder().setCompletionTimestamp(clock.nowUtc()).build());
              tm().transact(() -> applyLockStatuses(lock));
            });
  }

  private void verifyLock(RegistryLock lock) {
    if (lock.getCompletionTimestamp().isPresent()) {
      throw new IllegalStateException("This lock / unlock has already been verified");
    }
    if (!DateTimeUtils.isAtOrAfter(lock.getCreationTimestamp().plusHours(1), clock.nowUtc())) {
      throw new IllegalStateException("The pending lock has expired; please try again");
    }
    if (lock.isSuperuser() && !authResult.userAuthInfo().get().isUserAdmin()) {
      throw new IllegalStateException("Non-admin user cannot verify admin lock");
    }
  }

  private void applyLockStatuses(RegistryLock lock) {
    DomainBase domain =
        loadByForeignKey(DomainBase.class, lock.getDomainName(), clock.nowUtc())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        String.format("Domain %s does not exist", lock.getDomainName())));
    verifyDomainState(domain, lock);
    DomainBase.Builder domainBuilder = domain.asBuilder();
    if (lock.getAction().equals(RegistryLock.Action.LOCK)) {
      domainBuilder.setStatusValues(
          ImmutableSet.<StatusValue>builder()
              .addAll(domain.getStatusValues())
              .addAll(REGISTRY_LOCK_STATUSES)
              .build());
    } else {
      domainBuilder.setStatusValues(
          ImmutableSet.copyOf(Sets.difference(domain.getStatusValues(), REGISTRY_LOCK_STATUSES)));
    }
    saveEntities(domainBuilder.build(), lock);
  }

  private void saveEntities(DomainBase domain, RegistryLock lock) {
    String reason = lock.getAction().equals(RegistryLock.Action.LOCK) ? "lock" : "unlock";
    HistoryEntry historyEntry =
        new HistoryEntry.Builder()
            .setClientId(domain.getCurrentSponsorClientId())
            .setBySuperuser(lock.isSuperuser())
            .setRequestedByRegistrar(!lock.isSuperuser())
            .setType(HistoryEntry.Type.DOMAIN_UPDATE)
            .setModificationTime(clock.nowUtc())
            .setParent(Key.create(domain))
            .setReason(reason)
            .build();
    ofy().save().entities(domain, historyEntry);
    if (!lock.isSuperuser()) { // admin actions shouldn't affect billing
      BillingEvent.OneTime oneTime =
          new BillingEvent.OneTime.Builder()
              .setReason(Reason.SERVER_STATUS)
              .setTargetId(domain.getForeignKey())
              .setClientId(domain.getCurrentSponsorClientId())
              .setCost(Registry.get(domain.getTld()).getServerStatusChangeCost())
              .setEventTime(clock.nowUtc())
              .setBillingTime(clock.nowUtc())
              .setParent(historyEntry)
              .build();
      ofy().save().entity(oneTime);
    }
  }

  private void verifyDomainState(DomainBase domain, RegistryLock lock) {
    if (lock.getAction().equals(RegistryLock.Action.LOCK)
        && domain.getStatusValues().containsAll(REGISTRY_LOCK_STATUSES)) {
      // lock is valid as long as any of the statuses are not there
      throw new IllegalStateException("Domain already locked");
    } else if (lock.getAction().equals(RegistryLock.Action.UNLOCK)
        && Sets.intersection(domain.getStatusValues(), REGISTRY_LOCK_STATUSES).isEmpty()) {
      // unlock is valid as long as any of the statuses are there
      throw new IllegalStateException("Domain already unlocked");
    }
  }
}
