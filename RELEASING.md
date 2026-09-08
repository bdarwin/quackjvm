# Releasing quackjvm to Maven Central

This document describes how to publish `io.github.bdarwin:quackjvm-core` and
`io.github.bdarwin:quackjvm-cqengine` to Maven Central.

Sonatype retired the old OSSRH flow (`oss.sonatype.org`, `nexus-staging-maven-plugin`,
`<distributionManagement>` pointing at a staging repository). Everything below uses the Central
Publisher Portal at `central.sonatype.com` and the `central-publishing-maven-plugin`. If you find
instructions elsewhere that mention `oss.sonatype.org` or `s01.oss.sonatype.org`, they are out of
date.

Nothing has been published from this repository yet, so the whole of section 1 has to be done once
before the first release.

## 1. Prerequisites that a human has to do

None of the steps in this section can be performed by an automated agent. They require an
interactive login, an email address, a browser, and control of the GitHub account. Do them in
order.

### 1.1 Create a Central Portal account

Register at https://central.sonatype.com. A GitHub login is accepted and is the least work here,
because it also feeds into namespace verification.

### 1.2 Verify the `io.github.bdarwin` namespace

The `io.github.<user>` namespaces are verified by proving control of the matching GitHub account.

1. In the Portal, go to the namespaces page and add the namespace `io.github.bdarwin`.
2. The Portal shows a verification key next to the pending namespace, for example
   `abc123xyz456`. Copy it.
3. Create a public GitHub repository under `github.com/bdarwin` whose name is exactly that
   verification key: `https://github.com/bdarwin/abc123xyz456`. It can be empty.
4. Back in the Portal, trigger verification on the namespace and refresh. Verification usually
   completes within a few minutes.
5. Once the namespace shows as verified, the temporary repository can be deleted.

Verification is permanent. It does not need repeating for later releases.

### 1.3 Generate a user token

The Portal does not accept your account password for deployment. Generate a token instead.

1. Go to https://central.sonatype.com/usertoken.
2. Press "Generate User Token", give it a name and an expiry.
3. Copy both values immediately. The token cannot be retrieved again after the dialog closes; if
   it is lost, generate a new one.

The token has a username part and a password part. Both are opaque strings; neither is your email
address.

### 1.4 Generate a GPG key and publish the public half

Every file uploaded to Central must carry a detached GPG signature, and the public key must be
resolvable from a keyserver Central trusts.

```sh
# Generate a key. Use a real name and an email address you control.
gpg --gen-key

# Find the key id.
gpg --list-keys --keyid-format long
# pub   ed25519/ABCDEF0123456789 2026-09-08 [SC]

# Publish the public key. Central accepts keyserver.ubuntu.com, keys.openpgp.org and pgp.mit.edu.
gpg --keyserver keyserver.ubuntu.com --send-keys ABCDEF0123456789
```

Propagation to the keyserver takes a few minutes. If the key is not visible when Central
validates the upload, validation fails with a signature error even though the signatures
themselves are correct.

Keep the passphrase somewhere durable. Losing it means generating a new key, publishing it, and
signing future releases with the new one; already-published artifacts are unaffected.

### 1.5 Put the credentials where Maven can find them

Two things need to reach the build: the Portal user token, and the GPG passphrase.

The user token goes in `~/.m2/settings.xml` under a server whose id matches the plugin's
`publishingServerId` (the default is `central`):

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>THE_TOKEN_USERNAME</username>
      <password>THE_TOKEN_PASSWORD</password>
    </server>
  </servers>
</settings>
```

If you prefer not to have the token literal in a file, `settings.xml` interpolates environment
variables, so this also works with `CENTRAL_USERNAME` and `CENTRAL_TOKEN` exported in the shell:

```xml
<server>
  <id>central</id>
  <username>${env.CENTRAL_USERNAME}</username>
  <password>${env.CENTRAL_TOKEN}</password>
</server>
```

The GPG passphrase should *not* go in `settings.xml`. `maven-gpg-plugin` 3.2.x deprecated the
`<passphrase>` and `<passphraseServerId>` parameters as leak-prone, and the `<bestPractices>true`
setting used below makes the build fail if they are present. Supply it either through a running
`gpg-agent` (the normal case on a developer machine, where gpg prompts once and caches it) or
through the `MAVEN_GPG_PASSPHRASE` environment variable:

```sh
export MAVEN_GPG_PASSPHRASE='...'
```

## 2. POM changes required

These changes have not been applied. Apply them by hand; the snippets below are complete and can
be pasted as they stand.

Plugin versions were checked against `repo1.maven.org` metadata on 2026-09-08:
`central-publishing-maven-plugin` 0.11.0, `maven-gpg-plugin` 3.2.8, `maven-javadoc-plugin` 3.12.0,
`maven-source-plugin` 3.4.0. (`maven-source-plugin` also has a 4.0.0-beta-1; the snippet uses the
stable 3.4.0.)

Everything goes in the **parent** `pom.xml`. The modules inherit it, so `quackjvm-core/pom.xml`
and `quackjvm-cqengine/pom.xml` need no changes at all.

### 2.1 `<developers>` block — required

Central rejects a POM with no developer information. In `pom.xml`, insert this immediately after
the closing `</licenses>` tag and before `<scm>`:

```xml
    <developers>
        <developer>
            <id>bdarwin</id>
            <name>Ben Darwin</name>
            <url>https://github.com/bdarwin</url>
        </developer>
    </developers>
```

Replace the name with the one you want on the published POM. An `<email>` element is conventional
but the address becomes permanently public; a `<url>` pointing at the GitHub profile is accepted
in its place.

### 2.2 Add `<developerConnection>` to `<scm>` — required

The existing `<scm>` block has `url` and `connection` but not `developerConnection`, which Central
lists as required. In `pom.xml`, replace the whole `<scm>` block with:

```xml
    <scm>
        <url>https://github.com/bdarwin/quackjvm</url>
        <connection>scm:git:https://github.com/bdarwin/quackjvm.git</connection>
        <developerConnection>scm:git:ssh://git@github.com/bdarwin/quackjvm.git</developerConnection>
        <tag>HEAD</tag>
    </scm>
```

### 2.3 The `release` profile — sources, javadoc, signing, publishing

Putting these four plugins in a profile keeps ordinary `mvn install` and `mvn test` as fast as
they are now: no javadoc pass, no signing prompt, no network calls.

In `pom.xml`, insert this block immediately after the closing `</build>` tag and before the
closing `</project>` tag:

```xml
    <profiles>
        <profile>
            <id>release</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-source-plugin</artifactId>
                        <version>3.4.0</version>
                        <executions>
                            <execution>
                                <id>attach-sources</id>
                                <goals>
                                    <goal>jar-no-fork</goal>
                                </goals>
                            </execution>
                        </executions>
                    </plugin>

                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-javadoc-plugin</artifactId>
                        <version>3.12.0</version>
                        <executions>
                            <execution>
                                <id>attach-javadocs</id>
                                <goals>
                                    <goal>jar</goal>
                                </goals>
                            </execution>
                        </executions>
                        <configuration>
                            <doclint>none</doclint>
                            <quiet>true</quiet>
                        </configuration>
                    </plugin>

                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-gpg-plugin</artifactId>
                        <version>3.2.8</version>
                        <configuration>
                            <bestPractices>true</bestPractices>
                        </configuration>
                        <executions>
                            <execution>
                                <id>sign-artifacts</id>
                                <phase>verify</phase>
                                <goals>
                                    <goal>sign</goal>
                                </goals>
                            </execution>
                        </executions>
                    </plugin>

                    <plugin>
                        <groupId>org.sonatype.central</groupId>
                        <artifactId>central-publishing-maven-plugin</artifactId>
                        <version>0.11.0</version>
                        <extensions>true</extensions>
                        <configuration>
                            <publishingServerId>central</publishingServerId>
                            <deploymentName>quackjvm ${project.version}</deploymentName>
                            <autoPublish>false</autoPublish>
                            <waitUntil>validated</waitUntil>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>
```

Notes on the choices made here:

- `<extensions>true</extensions>` on the publishing plugin replaces the default `deploy` behaviour
  for the whole reactor, so `mvn deploy` uploads to the Portal rather than to a repository named
  in `<distributionManagement>`. Do not add a `<distributionManagement>` block; it is not used by
  this flow.
- `autoPublish` is `false` and `waitUntil` is `validated`, so `mvn deploy` uploads the bundle,
  waits for Central to validate it, and then stops with the deployment sitting in the Portal
  awaiting a manual "Publish" click. That is the safe default for the first few releases. Set
  `autoPublish` to `true` and `waitUntil` to `published` once you trust the process, which is also
  what the GitHub Actions workflow in section 5 needs.
- `<doclint>none</doclint>` stops javadoc warnings (missing `@param`, malformed HTML) from failing
  the release. If the javadoc is clean, removing the line gives stricter checking.
- The parent POM has `packaging` of `pom` and produces no jar, so it needs no sources or javadoc
  jar. The source and javadoc plugins simply do nothing there.
- The `release` profile is defined only in the parent, but profiles are inherited, so activating it
  with `-Prelease` on the reactor applies it to both modules.

### 2.4 Drop the `-SNAPSHOT` version

Central rejects any version ending in `-SNAPSHOT`, and the Portal does not host snapshots for
this flow. The version has to be set to `1.0.0` in the parent POM and in the `<parent>` block of
both module POMs. Section 3 uses `versions:set` to do all three at once rather than editing by
hand.

## 3. Release commands

Run all of these from the repository root.

### 3.1 Check the working tree

```sh
mvn -q clean verify
git status --porcelain     # should be empty
```

### 3.2 Set the release version

```sh
mvn versions:set -DnewVersion=1.0.0 -DgenerateBackupPoms=false
```

This rewrites the version in the parent and the `<parent>` reference in both modules. Review the
diff before committing.

### 3.3 Dry run — build the bundle without uploading anything

This is the fastest way to find a missing signature, a broken javadoc build, or missing POM
metadata, and it makes no network calls to the Portal:

```sh
mvn -Prelease -DskipPublishing=true clean deploy
```

On success the plugin writes the exact bundle it would upload to
`target/central-publishing/central-bundle.zip`. Inspect it:

```sh
unzip -l target/central-publishing/central-bundle.zip
```

Every jar in the listing should be accompanied by `-sources.jar`, `-javadoc.jar`, a `.pom`, and an
`.asc` for each of those four, plus `.md5` and `.sha1` checksums. The bundle can also be uploaded
by hand through the Portal's web UI if you would rather not deploy from the command line at all.

### 3.4 Real run — upload and validate

```sh
mvn -Prelease clean deploy
```

With the configuration in section 2.3 this uploads, blocks until Central finishes validating, and
returns. The deployment then appears at https://central.sonatype.com/publishing/deployments in the
`VALIDATED` state.

Review it there and press "Publish". If something is wrong, press "Drop" instead; a dropped
deployment frees the coordinates and the same version can be uploaded again. Once published, a
version is immutable and cannot be deleted or replaced, so the review step is worth doing.

Artifacts appear on `repo1.maven.org` within roughly 10 to 30 minutes of publishing, and become
searchable on `central.sonatype.com` some hours later. The delay between the two is normal and is
not a sign of failure.

### 3.5 Tag, and move to the next snapshot

```sh
git commit -am "Release 1.0.0"
git tag -a v1.0.0 -m "quackjvm 1.0.0"
git push origin main --tags

mvn versions:set -DnewVersion=1.0.1-SNAPSHOT -DgenerateBackupPoms=false
git commit -am "Back to snapshot"
git push
```

## 4. What Central validates

If an upload is rejected, the Portal shows the specific rule that failed against the specific
file. The rules are:

**Per file**

- A detached GPG signature `.asc` exists for every deployed file, verifies against the file, and
  is made by a key that resolves on `keyserver.ubuntu.com`, `keys.openpgp.org` or `pgp.mit.edu`.
  A signature that verifies locally but whose public key was never pushed to a keyserver is the
  single most common first-release failure.
- `.md5` and `.sha1` checksums are present and correct. `.sha256` and `.sha512` are optional. The
  publishing plugin generates all of these, so this only fails if files were assembled by hand.

**Per module producing a jar**

- `<artifactId>-<version>.jar`
- `<artifactId>-<version>-sources.jar`
- `<artifactId>-<version>-javadoc.jar`
- `<artifactId>-<version>.pom`

A module with `pom` packaging, like the parent here, needs only the `.pom`.

**POM metadata**

- `groupId` inside a namespace verified to this account (`io.github.bdarwin`).
- `artifactId` and a `version` that does not end in `-SNAPSHOT` and is not already published.
- `name`, `description` and `url` present. Already satisfied.
- `<licenses>` with at least one license. Already satisfied.
- `<developers>` with at least one developer. **Currently missing** — see 2.1.
- `<scm>` with `connection`, `developerConnection` and `url`. `developerConnection` is
  **currently missing** — see 2.2.

Central validates the metadata of the POMs being uploaded. It does not resolve the full transitive
dependency graph, but a dependency that is not on Central will make the artifact unusable for
everyone who consumes it, so section 6 checks them anyway.

## 5. GitHub Actions workflow

`.github/workflows/release.yml` in this repository publishes on a `v*` tag push. It is written to
work with the POM changes above, with one difference: CI needs `autoPublish` set to `true` so that
nothing waits on a human clicking Publish. The workflow passes that as a command-line override
(`-Dcentral.autoPublish=true`) rather than requiring the POM to change, but note that the plugin's
parameters are only settable from the command line if the POM leaves them unset or refers to a
property. If the override does not take effect, change `<autoPublish>` to `true` and `<waitUntil>`
to `published` in the POM and drop the `-D` flags from the workflow.

The workflow will not run until a human adds four repository secrets under
**Settings → Secrets and variables → Actions**. An agent cannot create these.

| Secret | Value |
| --- | --- |
| `CENTRAL_USERNAME` | username half of the Portal user token from 1.3 |
| `CENTRAL_TOKEN` | password half of the Portal user token from 1.3 |
| `GPG_PRIVATE_KEY` | ASCII-armored private key, produced by `gpg --armor --export-secret-keys ABCDEF0123456789` — paste the whole block including the BEGIN and END lines |
| `GPG_PASSPHRASE` | the passphrase for that key |

Exporting the private key to a CI system is a real expansion of what the key protects. Consider
using a key dedicated to this project rather than a personal signing key, and set an expiry on it.

Releasing then means:

```sh
git tag -a v1.0.0 -m "quackjvm 1.0.0"
git push origin v1.0.0
```

The workflow can also be started by hand from the Actions tab, which is useful for a first trial
run.

## 6. Problems with the current setup

### Blocking

1. **Version is `1.0.0-SNAPSHOT`.** Central rejects snapshot versions outright. See 3.2.
2. **No `<developers>` block.** Central requires one. See 2.1.
3. **No `<developerConnection>` in `<scm>`.** Central requires one. See 2.2.
4. **No sources jar, javadoc jar, signing or publishing plugin configured.** See 2.3.
5. **The namespace `io.github.bdarwin` is not verified and no GPG key is published.** See 1.2 and
   1.4. These are the long-lead items; do them first, because the rest can be prepared while
   verification is pending.

### Dependency availability

Every compile-scope and optional dependency was checked directly against
`https://repo1.maven.org/maven2/` on 2026-09-08. All of them resolve (HTTP 200 on the `.pom`):

| Dependency | Version | On Central |
| --- | --- | --- |
| `com.googlecode.cqengine:cqengine` | 3.6.0 | yes |
| `org.duckdb:duckdb_jdbc` | 1.4.1.0 | yes |
| `org.objenesis:objenesis` | 2.6 | yes |
| `com.esotericsoftware:kryo` | 5.6.2 | yes |
| `org.xerial:sqlite-jdbc` | 3.46.1.3 | yes |
| `org.apache.arrow:arrow-vector` | 18.1.0 | yes |
| `org.apache.arrow:arrow-c-data` | 18.1.0 | yes |
| `org.apache.arrow:arrow-memory-unsafe` | 18.1.0 | yes |
| `junit:junit` | 4.13.2 | yes (test scope) |
| `org.openjdk.jmh:jmh-core`, `jmh-generator-annprocess` | 1.37 | yes (test scope) |

So there is nothing here that would make the published artifacts unresolvable. The three optional
Arrow artifacts are all present, which matters because an `<optional>true</optional>` dependency
still has to resolve for anyone who chooses to opt in to it.

### Worth fixing before the first release, but not blocking

1. **`org.xerial:sqlite-jdbc` is a compile-scope dependency of `quackjvm-cqengine`.** The comment
   in that POM says the module does not use SQLite and the version is bumped only so the
   benchmarks can compare against CQEngine's own SQLite persistence. As compile scope it becomes a
   transitive compile dependency of every consumer of `quackjvm-cqengine`, pulling in a native
   library nobody asked for. It should almost certainly be `<scope>test</scope>`. Once 1.0.0 is
   published this cannot be corrected without a new version.

2. **`com.esotericsoftware:kryo` 5.6.2 is compile scope with CQEngine's own Kryo excluded.** That
   is deliberate and documented, and it is the right call, but it does mean consumers get a Kryo
   version that differs from the one CQEngine declares. Worth a sentence in the README so that
   nobody debugging a Kryo mismatch has to work it out from the POM.

3. **Javadoc has never been built.** `mvn -Prelease -DskipPublishing=true clean deploy` will be the
   first time the javadoc tool runs over this source. Expect errors on the first attempt; the
   `<doclint>none</doclint>` setting suppresses the pedantic ones but genuine malformed HTML in a
   comment will still fail the build.

4. **No `<inceptionYear>` or `<organization>`.** Neither is required. Mentioned only because they
   commonly appear alongside the metadata being added in 2.1.

## Not verified

The following were taken from official Sonatype and Apache documentation rather than tested
against a live deployment, since this project has never published:

- That `-Dcentral.autoPublish=true` on the command line overrides the POM's `<autoPublish>false</autoPublish>`.
  Plugin parameter property names are not documented on the Portal Maven page. If the CI release
  stalls waiting for a manual publish, set the values in the POM instead, as noted in section 5.
- Whether `maven-gpg-plugin` 3.2.8 needs an explicit `--pinentry-mode loopback` argument under
  GitHub Actions. Recent versions handle this when the passphrase arrives via
  `MAVEN_GPG_PASSPHRASE`, and `actions/setup-java` configures the agent for it. If signing hangs
  or fails in CI with a pinentry error, add to the gpg plugin configuration:

  ```xml
  <gpgArguments>
      <arg>--pinentry-mode</arg>
      <arg>loopback</arg>
  </gpgArguments>
  ```

- The exact wording and layout of the Portal UI in section 1.2 and 1.3. The steps are correct in
  substance but button labels move around.
