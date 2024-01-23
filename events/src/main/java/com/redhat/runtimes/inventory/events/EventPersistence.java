/* Copyright (C) Red Hat 2024 */
package com.redhat.runtimes.inventory.events;

import static com.redhat.runtimes.inventory.events.Utils.instanceOf;

import com.redhat.runtimes.inventory.models.EapInstance;
import com.redhat.runtimes.inventory.models.InsightsMessage;
import com.redhat.runtimes.inventory.models.JvmInstance;
import com.redhat.runtimes.inventory.models.UpdateInstance;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class EventPersistence {

  @Inject EntityManager entityManager;

  @Transactional
  public void processMessage(ArchiveAnnouncement announce, String json) {
    // Needs to be visible in the catch block
    JvmInstance inst;
    InsightsMessage msg = instanceOf(announce, json);

    if (msg instanceof EapInstance) {
      inst = (EapInstance) msg;
    } else if (msg instanceof JvmInstance) {
      inst = (JvmInstance) msg;
    } else if (msg instanceof UpdateInstance update) {
      var linkingHash = update.getLinkingHash();
      var maybeInst = getInstanceFromHash(linkingHash);
      if (maybeInst.isPresent()) {
        inst = maybeInst.get();
        var newJars = update.getUpdates();
        inst.getJarHashes().addAll(newJars);
      } else {
        throw new IllegalStateException(
            "Update message seen for non-existent hash: " + linkingHash);
      }
    } else {
      // Can't happen, but just in case
      throw new IllegalStateException("Message seen that is neither a new instance or an update");
    }

    Log.debugf("About to persist: %s", inst);
    entityManager.persist(inst);
  }

  Optional<JvmInstance> getInstanceFromHash(String linkingHash) {
    List<JvmInstance> instances =
        entityManager
            .createQuery("SELECT ri from JvmInstance ri where ri.linkingHash = ?1")
            .setParameter(1, linkingHash)
            .getResultList();
    if (instances.size() > 1) {
      throw new IllegalStateException(
          "Multiple instances found matching linking hash: " + linkingHash);
    } else if (instances.size() == 0) {
      return Optional.empty();
    }
    return Optional.of(instances.get(0));
  }
}
