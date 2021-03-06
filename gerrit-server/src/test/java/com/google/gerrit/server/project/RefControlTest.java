// Copyright (C) 2010 The Android Open Source Project
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

package com.google.gerrit.server.project;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.common.data.Permission.EDIT_TOPIC_NAME;
import static com.google.gerrit.common.data.Permission.LABEL;
import static com.google.gerrit.common.data.Permission.OWNER;
import static com.google.gerrit.common.data.Permission.PUSH;
import static com.google.gerrit.common.data.Permission.READ;
import static com.google.gerrit.common.data.Permission.SUBMIT;
import static com.google.gerrit.server.group.SystemGroupBackend.ANONYMOUS_USERS;
import static com.google.gerrit.server.group.SystemGroupBackend.CHANGE_OWNER;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;
import static com.google.gerrit.server.project.Util.ADMIN;
import static com.google.gerrit.server.project.Util.DEVS;
import static com.google.gerrit.server.project.Util.allow;
import static com.google.gerrit.server.project.Util.block;
import static com.google.gerrit.server.project.Util.deny;
import static com.google.gerrit.server.project.Util.doNotInherit;
import static com.google.gerrit.testutil.InMemoryRepositoryManager.newRepository;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.google.gerrit.common.data.Capable;
import com.google.gerrit.common.data.LabelType;
import com.google.gerrit.common.data.PermissionRange;
import com.google.gerrit.common.data.PermissionRule;
import com.google.gerrit.common.errors.InvalidNameException;
import com.google.gerrit.extensions.api.projects.CommentLinkInfo;
import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.rules.PrologEnvironment;
import com.google.gerrit.rules.RulesCache;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.account.CapabilityCollection;
import com.google.gerrit.server.account.CapabilityControl;
import com.google.gerrit.server.account.GroupMembership;
import com.google.gerrit.server.account.ListGroupMembership;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.AllProjectsNameProvider;
import com.google.gerrit.server.config.AllUsersName;
import com.google.gerrit.server.config.AllUsersNameProvider;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.git.ProjectConfig;
import com.google.gerrit.server.index.SingleVersionModule.SingleVersionListener;
import com.google.gerrit.server.permissions.ProjectPermission;
import com.google.gerrit.server.permissions.RefPermission;
import com.google.gerrit.server.query.change.InternalChangeQuery;
import com.google.gerrit.server.schema.SchemaCreator;
import com.google.gerrit.server.util.RequestContext;
import com.google.gerrit.server.util.ThreadLocalRequestContext;
import com.google.gerrit.testutil.InMemoryDatabase;
import com.google.gerrit.testutil.InMemoryModule;
import com.google.gerrit.testutil.InMemoryRepositoryManager;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provider;
import com.google.inject.util.Providers;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.Repository;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class RefControlTest {
  private void assertAdminsAreOwnersAndDevsAreNot() {
    ProjectControl uBlah = user(local, DEVS);
    ProjectControl uAdmin = user(local, DEVS, ADMIN);

    assertThat(uBlah.isOwner()).named("not owner").isFalse();
    assertThat(uAdmin.isOwner()).named("is owner").isTrue();
  }

  private void assertOwner(String ref, ProjectControl u) {
    assertThat(u.controlForRef(ref).isOwner()).named("OWN " + ref).isTrue();
  }

  private void assertNotOwner(ProjectControl u) {
    assertThat(u.isOwner()).named("not owner").isFalse();
  }

  private void assertOwnerAnyRef(ProjectControl u) {
    assertThat(u.isOwnerAnyRef()).named("owns ref").isTrue();
  }

  private void assertNotOwner(String ref, ProjectControl u) {
    assertThat(u.controlForRef(ref).isOwner()).named("NOT OWN " + ref).isFalse();
  }

  private void assertCanAccess(ProjectControl u) {
    boolean access = u.asForProject().testOrFalse(ProjectPermission.ACCESS);
    assertThat(access).named("can access").isTrue();
  }

  private void assertAccessDenied(ProjectControl u) {
    boolean access = u.asForProject().testOrFalse(ProjectPermission.ACCESS);
    assertThat(access).named("cannot access").isFalse();
  }

  private void assertCanRead(String ref, ProjectControl u) {
    assertThat(u.controlForRef(ref).isVisible()).named("can read " + ref).isTrue();
  }

  private void assertCannotRead(String ref, ProjectControl u) {
    assertThat(u.controlForRef(ref).isVisible()).named("cannot read " + ref).isFalse();
  }

  private void assertCanSubmit(String ref, ProjectControl u) {
    assertThat(u.controlForRef(ref).canSubmit(false)).named("can submit " + ref).isTrue();
  }

  private void assertCannotSubmit(String ref, ProjectControl u) {
    assertThat(u.controlForRef(ref).canSubmit(false)).named("can submit " + ref).isFalse();
  }

  private void assertCanUpload(ProjectControl u) {
    assertThat(u.canPushToAtLeastOneRef()).named("can upload").isEqualTo(Capable.OK);
  }

  private void assertCanUpload(String ref, ProjectControl u) {
    assertThat(u.controlForRef(ref).canUpload()).named("can upload " + ref).isTrue();
  }

  private void assertCannotUpload(ProjectControl u) {
    assertThat(u.canPushToAtLeastOneRef()).named("cannot upload").isNotEqualTo(Capable.OK);
  }

  private void assertCannotUpload(String ref, ProjectControl u) {
    assertThat(u.controlForRef(ref).canUpload()).named("cannot upload " + ref).isFalse();
  }

  private void assertBlocked(String p, String ref, ProjectControl u) {
    assertThat(u.controlForRef(ref).isBlocked(p)).named(p + " is blocked for " + ref).isTrue();
  }

  private void assertNotBlocked(String p, String ref, ProjectControl u) {
    assertThat(u.controlForRef(ref).isBlocked(p)).named(p + " is blocked for " + ref).isFalse();
  }

  private void assertCanUpdate(String ref, ProjectControl u) {
    boolean update = u.asForProject().ref(ref).testOrFalse(RefPermission.UPDATE);
    assertThat(update).named("can update " + ref).isTrue();
  }

  private void assertCannotUpdate(String ref, ProjectControl u) {
    boolean update = u.asForProject().ref(ref).testOrFalse(RefPermission.UPDATE);
    assertThat(update).named("cannot update " + ref).isFalse();
  }

  private void assertCanForceUpdate(String ref, ProjectControl u) {
    boolean update = u.asForProject().ref(ref).testOrFalse(RefPermission.FORCE_UPDATE);
    assertThat(update).named("can force push " + ref).isTrue();
  }

  private void assertCannotForceUpdate(String ref, ProjectControl u) {
    boolean update = u.asForProject().ref(ref).testOrFalse(RefPermission.FORCE_UPDATE);
    assertThat(update).named("cannot force push " + ref).isFalse();
  }

  private void assertCanVote(int score, PermissionRange range) {
    assertThat(range.contains(score)).named("can vote " + score).isTrue();
  }

  private void assertCannotVote(int score, PermissionRange range) {
    assertThat(range.contains(score)).named("cannot vote " + score).isFalse();
  }

  private final AllProjectsName allProjectsName =
      new AllProjectsName(AllProjectsNameProvider.DEFAULT);
  private final AllUsersName allUsersName = new AllUsersName(AllUsersNameProvider.DEFAULT);
  private final AccountGroup.UUID fixers = new AccountGroup.UUID("test.fixers");
  private final Map<Project.NameKey, ProjectState> all = new HashMap<>();
  private Project.NameKey localKey = new Project.NameKey("local");
  private ProjectConfig local;
  private Project.NameKey parentKey = new Project.NameKey("parent");
  private ProjectConfig parent;
  private InMemoryRepositoryManager repoManager;
  private ProjectCache projectCache;
  private PermissionCollection.Factory sectionSorter;
  private ChangeControl.Factory changeControlFactory;
  private ReviewDb db;

  @Inject private CapabilityCollection.Factory capabilityCollectionFactory;
  @Inject private CapabilityControl.Factory capabilityControlFactory;
  @Inject private SchemaCreator schemaCreator;
  @Inject private SingleVersionListener singleVersionListener;
  @Inject private InMemoryDatabase schemaFactory;
  @Inject private ThreadLocalRequestContext requestContext;
  @Inject private Provider<InternalChangeQuery> queryProvider;
  @Inject private ProjectControl.Metrics metrics;

  @Before
  public void setUp() throws Exception {
    repoManager = new InMemoryRepositoryManager();
    projectCache =
        new ProjectCache() {
          @Override
          public ProjectState getAllProjects() {
            return get(allProjectsName);
          }

          @Override
          public ProjectState getAllUsers() {
            return null;
          }

          @Override
          public ProjectState get(Project.NameKey projectName) {
            return all.get(projectName);
          }

          @Override
          public void evict(Project p) {}

          @Override
          public void remove(Project p) {}

          @Override
          public Iterable<Project.NameKey> all() {
            return Collections.emptySet();
          }

          @Override
          public Iterable<Project.NameKey> byName(String prefix) {
            return Collections.emptySet();
          }

          @Override
          public void onCreateProject(Project.NameKey newProjectName) {}

          @Override
          public Set<AccountGroup.UUID> guessRelevantGroupUUIDs() {
            return Collections.emptySet();
          }

          @Override
          public ProjectState checkedGet(Project.NameKey projectName) throws IOException {
            return all.get(projectName);
          }

          @Override
          public void evict(Project.NameKey p) {}
        };

    Injector injector = Guice.createInjector(new InMemoryModule());
    injector.injectMembers(this);

    try {
      Repository repo = repoManager.createRepository(allProjectsName);
      ProjectConfig allProjects = new ProjectConfig(new Project.NameKey(allProjectsName.get()));
      allProjects.load(repo);
      LabelType cr = Util.codeReview();
      allProjects.getLabelSections().put(cr.getName(), cr);
      add(allProjects);
    } catch (IOException | ConfigInvalidException e) {
      throw new RuntimeException(e);
    }

    db = schemaFactory.open();
    singleVersionListener.start();
    try {
      schemaCreator.create(db);
    } finally {
      singleVersionListener.stop();
    }

    Cache<SectionSortCache.EntryKey, SectionSortCache.EntryVal> c =
        CacheBuilder.newBuilder().build();
    sectionSorter = new PermissionCollection.Factory(new SectionSortCache(c));

    parent = new ProjectConfig(parentKey);
    parent.load(newRepository(parentKey));
    add(parent);

    local = new ProjectConfig(localKey);
    local.load(newRepository(localKey));
    add(local);
    local.getProject().setParentName(parentKey);

    requestContext.setContext(
        new RequestContext() {
          @Override
          public CurrentUser getUser() {
            return null;
          }

          @Override
          public Provider<ReviewDb> getReviewDbProvider() {
            return Providers.of(db);
          }
        });

    changeControlFactory = injector.getInstance(ChangeControl.Factory.class);
  }

  @After
  public void tearDown() {
    requestContext.setContext(null);
    if (db != null) {
      db.close();
    }
    InMemoryDatabase.drop(schemaFactory);
  }

  @Test
  public void ownerProject() {
    allow(local, OWNER, ADMIN, "refs/*");

    assertAdminsAreOwnersAndDevsAreNot();
  }

  @Test
  public void denyOwnerProject() {
    allow(local, OWNER, ADMIN, "refs/*");
    deny(local, OWNER, DEVS, "refs/*");

    assertAdminsAreOwnersAndDevsAreNot();
  }

  @Test
  public void blockOwnerProject() {
    allow(local, OWNER, ADMIN, "refs/*");
    block(local, OWNER, DEVS, "refs/*");

    assertAdminsAreOwnersAndDevsAreNot();
  }

  @Test
  public void branchDelegation1() {
    allow(local, OWNER, ADMIN, "refs/*");
    allow(local, OWNER, DEVS, "refs/heads/x/*");

    ProjectControl uDev = user(local, DEVS);
    assertNotOwner(uDev);
    assertOwnerAnyRef(uDev);

    assertOwner("refs/heads/x/*", uDev);
    assertOwner("refs/heads/x/y", uDev);
    assertOwner("refs/heads/x/y/*", uDev);

    assertNotOwner("refs/*", uDev);
    assertNotOwner("refs/heads/master", uDev);
  }

  @Test
  public void branchDelegation2() {
    allow(local, OWNER, ADMIN, "refs/*");
    allow(local, OWNER, DEVS, "refs/heads/x/*");
    allow(local, OWNER, fixers, "refs/heads/x/y/*");
    doNotInherit(local, OWNER, "refs/heads/x/y/*");

    ProjectControl uDev = user(local, DEVS);
    assertNotOwner(uDev);
    assertOwnerAnyRef(uDev);

    assertOwner("refs/heads/x/*", uDev);
    assertOwner("refs/heads/x/y", uDev);
    assertOwner("refs/heads/x/y/*", uDev);
    assertNotOwner("refs/*", uDev);
    assertNotOwner("refs/heads/master", uDev);

    ProjectControl uFix = user(local, fixers);
    assertNotOwner(uFix);
    assertOwnerAnyRef(uFix);

    assertOwner("refs/heads/x/y/*", uFix);
    assertOwner("refs/heads/x/y/bar", uFix);
    assertNotOwner("refs/heads/x/*", uFix);
    assertNotOwner("refs/heads/x/y", uFix);
    assertNotOwner("refs/*", uFix);
    assertNotOwner("refs/heads/master", uFix);
  }

  @Test
  public void inheritRead_SingleBranchDeniesUpload() {
    allow(parent, READ, REGISTERED_USERS, "refs/*");
    allow(parent, PUSH, REGISTERED_USERS, "refs/for/refs/*");
    allow(local, READ, REGISTERED_USERS, "refs/heads/foobar");
    doNotInherit(local, READ, "refs/heads/foobar");
    doNotInherit(local, PUSH, "refs/for/refs/heads/foobar");

    ProjectControl u = user(local);
    assertCanUpload(u);
    assertCanUpload("refs/heads/master", u);
    assertCannotUpload("refs/heads/foobar", u);
  }

  @Test
  public void blockPushDrafts() {
    allow(parent, PUSH, REGISTERED_USERS, "refs/for/refs/*");
    block(parent, PUSH, ANONYMOUS_USERS, "refs/drafts/*");

    ProjectControl u = user(local);
    assertCanUpload("refs/heads/master", u);
    assertBlocked(PUSH, "refs/drafts/refs/heads/master", u);
  }

  @Test
  public void blockPushDraftsUnblockAdmin() {
    block(parent, PUSH, ANONYMOUS_USERS, "refs/drafts/*");
    allow(parent, PUSH, ADMIN, "refs/drafts/*");

    ProjectControl u = user(local);
    ProjectControl a = user(local, "a", ADMIN);
    assertBlocked(PUSH, "refs/drafts/refs/heads/master", u);
    assertNotBlocked(PUSH, "refs/drafts/refs/heads/master", a);
  }

  @Test
  public void inheritRead_SingleBranchDoesNotOverrideInherited() {
    allow(parent, READ, REGISTERED_USERS, "refs/*");
    allow(parent, PUSH, REGISTERED_USERS, "refs/for/refs/*");
    allow(local, READ, REGISTERED_USERS, "refs/heads/foobar");

    ProjectControl u = user(local);
    assertCanUpload(u);
    assertCanUpload("refs/heads/master", u);
    assertCanUpload("refs/heads/foobar", u);
  }

  @Test
  public void inheritDuplicateSections() throws Exception {
    allow(parent, READ, ADMIN, "refs/*");
    allow(local, READ, DEVS, "refs/heads/*");
    assertCanAccess(user(local, "a", ADMIN));

    local = new ProjectConfig(localKey);
    local.load(newRepository(localKey));
    local.getProject().setParentName(parentKey);
    allow(local, READ, DEVS, "refs/*");
    assertCanAccess(user(local, "d", DEVS));
  }

  @Test
  public void inheritRead_OverrideWithDeny() {
    allow(parent, READ, REGISTERED_USERS, "refs/*");
    deny(local, READ, REGISTERED_USERS, "refs/*");

    assertAccessDenied(user(local));
  }

  @Test
  public void inheritRead_AppendWithDenyOfRef() {
    allow(parent, READ, REGISTERED_USERS, "refs/*");
    deny(local, READ, REGISTERED_USERS, "refs/heads/*");

    ProjectControl u = user(local);
    assertCanAccess(u);
    assertCanRead("refs/master", u);
    assertCanRead("refs/tags/foobar", u);
    assertCanRead("refs/heads/master", u);
  }

  @Test
  public void inheritRead_OverridesAndDeniesOfRef() {
    allow(parent, READ, REGISTERED_USERS, "refs/*");
    deny(local, READ, REGISTERED_USERS, "refs/*");
    allow(local, READ, REGISTERED_USERS, "refs/heads/*");

    ProjectControl u = user(local);
    assertCanAccess(u);
    assertCannotRead("refs/foobar", u);
    assertCannotRead("refs/tags/foobar", u);
    assertCanRead("refs/heads/foobar", u);
  }

  @Test
  public void inheritSubmit_OverridesAndDeniesOfRef() {
    allow(parent, SUBMIT, REGISTERED_USERS, "refs/*");
    deny(local, SUBMIT, REGISTERED_USERS, "refs/*");
    allow(local, SUBMIT, REGISTERED_USERS, "refs/heads/*");

    ProjectControl u = user(local);
    assertCannotSubmit("refs/foobar", u);
    assertCannotSubmit("refs/tags/foobar", u);
    assertCanSubmit("refs/heads/foobar", u);
  }

  @Test
  public void cannotUploadToAnyRef() {
    allow(parent, READ, REGISTERED_USERS, "refs/*");
    allow(local, READ, DEVS, "refs/heads/*");
    allow(local, PUSH, DEVS, "refs/for/refs/heads/*");

    ProjectControl u = user(local);
    assertCannotUpload(u);
    assertCannotUpload("refs/heads/master", u);
  }

  @Test
  public void usernamePatternCanUploadToAnyRef() {
    allow(local, PUSH, REGISTERED_USERS, "refs/heads/users/${username}/*");
    ProjectControl u = user(local, "a-registered-user");
    assertCanUpload(u);
  }

  @Test
  public void usernamePatternNonRegex() {
    allow(local, READ, DEVS, "refs/sb/${username}/heads/*");

    ProjectControl u = user(local, "u", DEVS);
    ProjectControl d = user(local, "d", DEVS);
    assertCannotRead("refs/sb/d/heads/foobar", u);
    assertCanRead("refs/sb/d/heads/foobar", d);
  }

  @Test
  public void usernamePatternWithRegex() {
    allow(local, READ, DEVS, "^refs/sb/${username}/heads/.*");

    ProjectControl u = user(local, "d.v", DEVS);
    ProjectControl d = user(local, "dev", DEVS);
    assertCannotRead("refs/sb/dev/heads/foobar", u);
    assertCanRead("refs/sb/dev/heads/foobar", d);
  }

  @Test
  public void usernameEmailPatternWithRegex() {
    allow(local, READ, DEVS, "^refs/sb/${username}/heads/.*");

    ProjectControl u = user(local, "d.v@ger-rit.org", DEVS);
    ProjectControl d = user(local, "dev@ger-rit.org", DEVS);
    assertCannotRead("refs/sb/dev@ger-rit.org/heads/foobar", u);
    assertCanRead("refs/sb/dev@ger-rit.org/heads/foobar", d);
  }

  @Test
  public void sortWithRegex() {
    allow(local, READ, DEVS, "^refs/heads/.*");
    allow(parent, READ, ANONYMOUS_USERS, "^refs/heads/.*-QA-.*");

    ProjectControl u = user(local, DEVS);
    ProjectControl d = user(local, DEVS);
    assertCanRead("refs/heads/foo-QA-bar", u);
    assertCanRead("refs/heads/foo-QA-bar", d);
  }

  @Test
  public void blockRule_ParentBlocksChild() {
    allow(local, PUSH, DEVS, "refs/tags/*");
    block(parent, PUSH, ANONYMOUS_USERS, "refs/tags/*");
    ProjectControl u = user(local, DEVS);
    assertCannotUpdate("refs/tags/V10", u);
  }

  @Test
  public void blockRule_ParentBlocksChildEvenIfAlreadyBlockedInChild() {
    allow(local, PUSH, DEVS, "refs/tags/*");
    block(local, PUSH, ANONYMOUS_USERS, "refs/tags/*");
    block(parent, PUSH, ANONYMOUS_USERS, "refs/tags/*");

    ProjectControl u = user(local, DEVS);
    assertCannotUpdate("refs/tags/V10", u);
  }

  @Test
  public void blockLabelRange_ParentBlocksChild() {
    allow(local, LABEL + "Code-Review", -2, +2, DEVS, "refs/heads/*");
    block(parent, LABEL + "Code-Review", -2, +2, DEVS, "refs/heads/*");

    ProjectControl u = user(local, DEVS);

    PermissionRange range = u.controlForRef("refs/heads/master").getRange(LABEL + "Code-Review");
    assertCanVote(-1, range);
    assertCanVote(1, range);
    assertCannotVote(-2, range);
    assertCannotVote(2, range);
  }

  @Test
  public void blockLabelRange_ParentBlocksChildEvenIfAlreadyBlockedInChild() {
    allow(local, LABEL + "Code-Review", -2, +2, DEVS, "refs/heads/*");
    block(local, LABEL + "Code-Review", -2, +2, DEVS, "refs/heads/*");
    block(parent, LABEL + "Code-Review", -2, +2, DEVS, "refs/heads/*");

    ProjectControl u = user(local, DEVS);

    PermissionRange range = u.controlForRef("refs/heads/master").getRange(LABEL + "Code-Review");
    assertCanVote(-1, range);
    assertCanVote(1, range);
    assertCannotVote(-2, range);
    assertCannotVote(2, range);
  }

  @Test
  public void inheritSubmit_AllowInChildDoesntAffectUnblockInParent() {
    block(parent, SUBMIT, ANONYMOUS_USERS, "refs/heads/*");
    allow(parent, SUBMIT, REGISTERED_USERS, "refs/heads/*");
    allow(local, SUBMIT, REGISTERED_USERS, "refs/heads/*");

    ProjectControl u = user(local);
    assertNotBlocked(SUBMIT, "refs/heads/master", u);
  }

  @Test
  public void unblockNoForce() {
    block(local, PUSH, ANONYMOUS_USERS, "refs/heads/*");
    allow(local, PUSH, DEVS, "refs/heads/*");

    ProjectControl u = user(local, DEVS);
    assertCanUpdate("refs/heads/master", u);
  }

  @Test
  public void unblockForce() {
    PermissionRule r = block(local, PUSH, ANONYMOUS_USERS, "refs/heads/*");
    r.setForce(true);
    allow(local, PUSH, DEVS, "refs/heads/*").setForce(true);

    ProjectControl u = user(local, DEVS);
    assertCanForceUpdate("refs/heads/master", u);
  }

  @Test
  public void unblockForceWithAllowNoForce_NotPossible() {
    PermissionRule r = block(local, PUSH, ANONYMOUS_USERS, "refs/heads/*");
    r.setForce(true);
    allow(local, PUSH, DEVS, "refs/heads/*");

    ProjectControl u = user(local, DEVS);
    assertCannotForceUpdate("refs/heads/master", u);
  }

  @Test
  public void unblockMoreSpecificRef_Fails() {
    block(local, PUSH, ANONYMOUS_USERS, "refs/heads/*");
    allow(local, PUSH, DEVS, "refs/heads/master");

    ProjectControl u = user(local, DEVS);
    assertCannotUpdate("refs/heads/master", u);
  }

  @Test
  public void unblockMoreSpecificRefInLocal_Fails() {
    block(parent, PUSH, ANONYMOUS_USERS, "refs/heads/*");
    allow(local, PUSH, DEVS, "refs/heads/master");

    ProjectControl u = user(local, DEVS);
    assertCannotUpdate("refs/heads/master", u);
  }

  @Test
  public void unblockMoreSpecificRefWithExclusiveFlag() {
    block(local, PUSH, ANONYMOUS_USERS, "refs/heads/*");
    allow(local, PUSH, DEVS, "refs/heads/master", true);

    ProjectControl u = user(local, DEVS);
    assertCanUpdate("refs/heads/master", u);
  }

  @Test
  public void unblockMoreSpecificRefInLocalWithExclusiveFlag_Fails() {
    block(parent, PUSH, ANONYMOUS_USERS, "refs/heads/*");
    allow(local, PUSH, DEVS, "refs/heads/master", true);

    ProjectControl u = user(local, DEVS);
    assertCannotUpdate("refs/heads/master", u);
  }

  @Test
  public void unblockOtherPermissionWithMoreSpecificRefAndExclusiveFlag_Fails() {
    block(local, PUSH, ANONYMOUS_USERS, "refs/heads/*");
    allow(local, PUSH, DEVS, "refs/heads/master");
    allow(local, SUBMIT, DEVS, "refs/heads/master", true);

    ProjectControl u = user(local, DEVS);
    assertCannotUpdate("refs/heads/master", u);
  }

  @Test
  public void unblockLargerScope_Fails() {
    block(local, PUSH, ANONYMOUS_USERS, "refs/heads/master");
    allow(local, PUSH, DEVS, "refs/heads/*");

    ProjectControl u = user(local, DEVS);
    assertCannotUpdate("refs/heads/master", u);
  }

  @Test
  public void unblockInLocal_Fails() {
    block(parent, PUSH, ANONYMOUS_USERS, "refs/heads/*");
    allow(local, PUSH, fixers, "refs/heads/*");

    ProjectControl f = user(local, fixers);
    assertCannotUpdate("refs/heads/master", f);
  }

  @Test
  public void unblockInParentBlockInLocal() {
    block(parent, PUSH, ANONYMOUS_USERS, "refs/heads/*");
    allow(parent, PUSH, DEVS, "refs/heads/*");
    block(local, PUSH, DEVS, "refs/heads/*");

    ProjectControl d = user(local, DEVS);
    assertCannotUpdate("refs/heads/master", d);
  }

  @Test
  public void unblockVisibilityByRegisteredUsers() {
    block(local, READ, ANONYMOUS_USERS, "refs/heads/*");
    allow(local, READ, REGISTERED_USERS, "refs/heads/*");

    ProjectControl u = user(local, REGISTERED_USERS);
    assertThat(u.controlForRef("refs/heads/master").isVisibleByRegisteredUsers())
        .named("u can read")
        .isTrue();
  }

  @Test
  public void unblockInLocalVisibilityByRegisteredUsers_Fails() {
    block(parent, READ, ANONYMOUS_USERS, "refs/heads/*");
    allow(local, READ, REGISTERED_USERS, "refs/heads/*");

    ProjectControl u = user(local, REGISTERED_USERS);
    assertThat(u.controlForRef("refs/heads/master").isVisibleByRegisteredUsers())
        .named("u can't read")
        .isFalse();
  }

  @Test
  public void unblockForceEditTopicName() {
    block(local, EDIT_TOPIC_NAME, ANONYMOUS_USERS, "refs/heads/*");
    allow(local, EDIT_TOPIC_NAME, DEVS, "refs/heads/*").setForce(true);

    ProjectControl u = user(local, DEVS);
    assertThat(u.controlForRef("refs/heads/master").canForceEditTopicName())
        .named("u can edit topic name")
        .isTrue();
  }

  @Test
  public void unblockInLocalForceEditTopicName_Fails() {
    block(parent, EDIT_TOPIC_NAME, ANONYMOUS_USERS, "refs/heads/*");
    allow(local, EDIT_TOPIC_NAME, DEVS, "refs/heads/*").setForce(true);

    ProjectControl u = user(local, REGISTERED_USERS);
    assertThat(u.controlForRef("refs/heads/master").canForceEditTopicName())
        .named("u can't edit topic name")
        .isFalse();
  }

  @Test
  public void unblockRange() {
    block(local, LABEL + "Code-Review", -1, +1, ANONYMOUS_USERS, "refs/heads/*");
    allow(local, LABEL + "Code-Review", -2, +2, DEVS, "refs/heads/*");

    ProjectControl u = user(local, DEVS);
    PermissionRange range = u.controlForRef("refs/heads/master").getRange(LABEL + "Code-Review");
    assertCanVote(-2, range);
    assertCanVote(2, range);
  }

  @Test
  public void unblockRangeOnMoreSpecificRef_Fails() {
    block(local, LABEL + "Code-Review", -1, +1, ANONYMOUS_USERS, "refs/heads/*");
    allow(local, LABEL + "Code-Review", -2, +2, DEVS, "refs/heads/master");

    ProjectControl u = user(local, DEVS);
    PermissionRange range = u.controlForRef("refs/heads/master").getRange(LABEL + "Code-Review");
    assertCannotVote(-2, range);
    assertCannotVote(2, range);
  }

  @Test
  public void unblockRangeOnLargerScope_Fails() {
    block(local, LABEL + "Code-Review", -1, +1, ANONYMOUS_USERS, "refs/heads/master");
    allow(local, LABEL + "Code-Review", -2, +2, DEVS, "refs/heads/*");

    ProjectControl u = user(local, DEVS);
    PermissionRange range = u.controlForRef("refs/heads/master").getRange(LABEL + "Code-Review");
    assertCannotVote(-2, range);
    assertCannotVote(2, range);
  }

  @Test
  public void unblockInLocalRange_Fails() {
    block(parent, LABEL + "Code-Review", -1, 1, ANONYMOUS_USERS, "refs/heads/*");
    allow(local, LABEL + "Code-Review", -2, +2, DEVS, "refs/heads/*");

    ProjectControl u = user(local, DEVS);
    PermissionRange range = u.controlForRef("refs/heads/master").getRange(LABEL + "Code-Review");
    assertCannotVote(-2, range);
    assertCannotVote(2, range);
  }

  @Test
  public void unblockRangeForChangeOwner() {
    allow(local, LABEL + "Code-Review", -2, +2, CHANGE_OWNER, "refs/heads/*");

    ProjectControl u = user(local, DEVS);
    PermissionRange range =
        u.controlForRef("refs/heads/master").getRange(LABEL + "Code-Review", true);
    assertCanVote(-2, range);
    assertCanVote(2, range);
  }

  @Test
  public void unblockRangeForNotChangeOwner() {
    allow(local, LABEL + "Code-Review", -2, +2, CHANGE_OWNER, "refs/heads/*");

    ProjectControl u = user(local, DEVS);
    PermissionRange range = u.controlForRef("refs/heads/master").getRange(LABEL + "Code-Review");
    assertCannotVote(-2, range);
    assertCannotVote(2, range);
  }

  @Test
  public void blockOwner() {
    block(parent, OWNER, ANONYMOUS_USERS, "refs/*");
    allow(local, OWNER, DEVS, "refs/*");

    assertThat(user(local, DEVS).isOwner()).isFalse();
  }

  @Test
  public void validateRefPatternsOK() throws Exception {
    RefPattern.validate("refs/*");
    RefPattern.validate("^refs/heads/*");
    RefPattern.validate("^refs/tags/[0-9a-zA-Z-_.]+");
    RefPattern.validate("refs/heads/review/${username}/*");
  }

  @Test(expected = InvalidNameException.class)
  public void testValidateBadRefPatternDoubleCaret() throws Exception {
    RefPattern.validate("^^refs/*");
  }

  @Test(expected = InvalidNameException.class)
  public void testValidateBadRefPatternDanglingCharacter() throws Exception {
    RefPattern.validate("^refs/heads/tmp/sdk/[0-9]{3,3}_R[1-9][A-Z][0-9]{3,3}*");
  }

  @Test
  public void validateRefPatternNoDanglingCharacter() throws Exception {
    RefPattern.validate("^refs/heads/tmp/sdk/[0-9]{3,3}_R[1-9][A-Z][0-9]{3,3}");
  }

  private InMemoryRepository add(ProjectConfig pc) {
    PrologEnvironment.Factory envFactory = null;
    ProjectControl.AssistedFactory projectControlFactory = null;
    RulesCache rulesCache = null;
    SitePaths sitePaths = null;
    List<CommentLinkInfo> commentLinks = null;

    InMemoryRepository repo;
    try {
      repo = repoManager.createRepository(pc.getName());
      if (pc.getProject() == null) {
        pc.load(repo);
      }
    } catch (IOException | ConfigInvalidException e) {
      throw new RuntimeException(e);
    }
    all.put(
        pc.getName(),
        new ProjectState(
            sitePaths,
            projectCache,
            allProjectsName,
            allUsersName,
            projectControlFactory,
            envFactory,
            repoManager,
            rulesCache,
            commentLinks,
            capabilityCollectionFactory,
            pc));
    return repo;
  }

  private ProjectControl user(ProjectConfig local, AccountGroup.UUID... memberOf) {
    return user(local, null, memberOf);
  }

  private ProjectControl user(ProjectConfig local, String name, AccountGroup.UUID... memberOf) {
    String canonicalWebUrl = "http://localhost";

    return new ProjectControl(
        Collections.<AccountGroup.UUID>emptySet(),
        Collections.<AccountGroup.UUID>emptySet(),
        projectCache,
        sectionSorter,
        null,
        changeControlFactory,
        null,
        queryProvider,
        null,
        canonicalWebUrl,
        new MockUser(name, memberOf),
        newProjectState(local),
        metrics);
  }

  private ProjectState newProjectState(ProjectConfig local) {
    add(local);
    return all.get(local.getProject().getNameKey());
  }

  private class MockUser extends CurrentUser {
    private final String username;
    private final GroupMembership groups;

    MockUser(String name, AccountGroup.UUID[] groupId) {
      super(capabilityControlFactory);
      username = name;
      ArrayList<AccountGroup.UUID> groupIds = Lists.newArrayList(groupId);
      groupIds.add(REGISTERED_USERS);
      groupIds.add(ANONYMOUS_USERS);
      groups = new ListGroupMembership(groupIds);
    }

    @Override
    public GroupMembership getEffectiveGroups() {
      return groups;
    }

    @Override
    public String getUserName() {
      return username;
    }
  }
}
