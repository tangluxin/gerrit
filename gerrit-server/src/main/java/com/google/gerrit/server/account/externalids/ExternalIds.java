// Copyright (C) 2016 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.account.externalids;

import static java.util.stream.Collectors.toSet;

import com.google.gerrit.common.Nullable;
import com.google.gerrit.reviewdb.client.Account;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.Set;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.ObjectId;

/**
 * Class to access external IDs.
 *
 * <p>The external IDs are either read from NoteDb or retrieved from the cache.
 */
@Singleton
public class ExternalIds {
  private final ExternalIdReader externalIdReader;
  private final ExternalIdCache externalIdCache;

  @Inject
  public ExternalIds(ExternalIdReader externalIdReader, ExternalIdCache externalIdCache) {
    this.externalIdReader = externalIdReader;
    this.externalIdCache = externalIdCache;
  }

  /** Returns all external IDs. */
  public Set<ExternalId> all() throws IOException {
    return externalIdReader.all();
  }

  /** Returns all external IDs from the specified revision of the refs/meta/external-ids branch. */
  public Set<ExternalId> all(ObjectId rev) throws IOException {
    return externalIdReader.all(rev);
  }

  /** Returns the specified external ID. */
  @Nullable
  public ExternalId get(ExternalId.Key key) throws IOException, ConfigInvalidException {
    return externalIdReader.get(key);
  }

  /** Returns the specified external ID from the given revision. */
  @Nullable
  public ExternalId get(ExternalId.Key key, ObjectId rev)
      throws IOException, ConfigInvalidException {
    return externalIdReader.get(key, rev);
  }

  /** Returns the external IDs of the specified account. */
  public Set<ExternalId> byAccount(Account.Id accountId) throws IOException {
    return externalIdCache.byAccount(accountId);
  }

  /** Returns the external IDs of the specified account that have the given scheme. */
  public Set<ExternalId> byAccount(Account.Id accountId, String scheme) throws IOException {
    return byAccount(accountId).stream().filter(e -> e.key().isScheme(scheme)).collect(toSet());
  }

  public Set<ExternalId> byEmail(String email) throws IOException {
    return externalIdCache.byEmail(email);
  }
}
