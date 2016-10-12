package google.registry.model.registry.label;

import static com.google.common.collect.Iterables.partition;
import static google.registry.model.ofy.ObjectifyService.allocateId;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.base.Optional;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.VoidWork;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Ignore;
import com.googlecode.objectify.annotation.OnLoad;
import com.googlecode.objectify.annotation.Parent;
import com.googlecode.objectify.cmd.Query;
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.VirtualEntity;
import google.registry.model.registry.Registry;
import org.joda.money.Money;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public abstract class BasePremiumList<T extends Comparable<?>, D extends DomainLabelEntry<T, ?>>
    extends BaseDomainLabelList<T, D> {
  /** The number of premium list entry entities that are created and deleted per batch. */
  static final int TRANSACTION_BATCH_SIZE = 200;

  Key<PremiumListRevision> revisionKey;

  @Override
  public boolean refersToKey(Registry registry, Key<? extends BaseDomainLabelList<?, ?>> key) {
    return Objects.equals(registry.getPremiumList(), key);
  }

  @Ignore Map<String, D> premiumListMap;

  @OnLoad
  private void loadPremiumListMap() {
    try {
      ImmutableMap.Builder<String, D> entriesMap = new ImmutableMap.Builder<>();
      if (revisionKey != null) {
        for (D entry : loadEntriesForCurrentRevision()) {
          entriesMap.put(entry.getLabel(), entry);
        }
      }
      premiumListMap = entriesMap.build();
    } catch (Exception e) {
      throw new RuntimeException("Could not retrieve entries for premium list " + name, e);
    }
  }

  /** Deletes the PremiumList and all of its child entities. */
  public void delete() {
    ofy()
        .transactNew(
            new VoidWork() {
              @Override
              public void vrun() {
                ofy().delete().entity(BasePremiumList.this);
              }
            });
    deleteEntries();
    getCache().invalidate(name);
  }

  void deleteEntries() {
    if (revisionKey == null) {
      return;
    }
    for (final List<Key<D>> batch :
        partition(loadEntriesForCurrentRevision().keys(), TRANSACTION_BATCH_SIZE)) {
      ofy()
          .transactNew(
              new VoidWork() {
                @Override
                public void vrun() {
                  ofy().delete().keys(batch);
                }
              });
    }
  }

  public Map<String, D> getPremiumListEntries() {
    return nullToEmptyImmutableCopy(premiumListMap);
  }

  public Key<PremiumListRevision> getRevisionKey() {
    return revisionKey;
  }

  /**
   * Gets the premium price for the specified label in the current PremiumList, or returns
   * Optional.absent if there is no premium price.
   */
  public abstract Optional<Money> getPremiumPrice(String label);

  protected abstract LoadingCache<String, ?> getCache();

  protected abstract Query<D> loadEntriesForCurrentRevision();

  /** Virtual parent entity for premium list entry entities associated with a single revision. */
  @Entity
  @VirtualEntity
  public static class PremiumListRevision extends ImmutableObject {
    @Parent Key<BasePremiumList> parent;

    @Id long revisionId;

    static Key<BasePremiumList.PremiumListRevision> createKey(BasePremiumList parent) {
      BasePremiumList.PremiumListRevision revision = new BasePremiumList.PremiumListRevision();
      revision.parent = Key.create(parent);
      revision.revisionId = allocateId();
      return Key.create(revision);
    }
  }
}
