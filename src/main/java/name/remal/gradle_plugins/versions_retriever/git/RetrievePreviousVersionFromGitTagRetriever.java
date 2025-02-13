package name.remal.gradle_plugins.versions_retriever.git;

import static java.lang.Math.toIntExact;
import static java.util.Collections.emptySet;
import static java.util.stream.Collectors.toList;
import static lombok.AccessLevel.PRIVATE;
import static name.remal.gradle_plugins.toolkit.ObjectUtils.defaultValue;
import static name.remal.gradle_plugins.toolkit.ObjectUtils.isEmpty;
import static name.remal.gradle_plugins.toolkit.ObjectUtils.isNotEmpty;
import static name.remal.gradle_plugins.toolkit.PredicateUtils.not;
import static name.remal.gradle_plugins.toolkit.SneakyThrowUtils.sneakyThrows;
import static name.remal.gradle_plugins.versions_retriever.git.GitUtils.FETCH_TIMEOUT;
import static name.remal.gradle_plugins.versions_retriever.git.GitUtils.GIT_DEFAULT_LOG_LEVEL;
import static name.remal.gradle_plugins.versions_retriever.git.GitUtils.GIT_ERROR_LOG_LEVEL;
import static name.remal.gradle_plugins.versions_retriever.git.GitUtils.GIT_WARN_LOG_LEVEL;
import static org.eclipse.jgit.lib.Constants.DEFAULT_REMOTE_NAME;
import static org.eclipse.jgit.lib.Constants.DOT_GIT;
import static org.eclipse.jgit.lib.Constants.HEAD;
import static org.eclipse.jgit.lib.Constants.R_TAGS;
import static org.eclipse.jgit.lib.Repository.shortenRefName;
import static org.eclipse.jgit.revwalk.RevFlag.UNINTERESTING;

import com.google.common.annotations.VisibleForTesting;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.CustomLog;
import lombok.RequiredArgsConstructor;
import lombok.Singular;
import lombok.SneakyThrows;
import lombok.val;
import name.remal.gradle_plugins.toolkit.SneakyThrowUtils.SneakyThrowsConsumer;
import name.remal.gradle_plugins.toolkit.Version;
import name.remal.gradle_plugins.versions_retriever.VersionInfo;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.SubmoduleConfig.FetchRecurseSubmodulesMode;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.TagOpt;
import org.gradle.initialization.BuildCancellationToken;

@Builder
@RequiredArgsConstructor(access = PRIVATE)
@CustomLog
class RetrievePreviousVersionFromGitTagRetriever {

    @Singular("tagPattern")
    private final List<Pattern> tagPatterns;

    @Default
    private final boolean ignoreCurrentCommit = true;

    @Nullable
    private final BuildCancellationToken buildCancellationToken;


    @Nullable
    @VisibleForTesting
    @SneakyThrows
    public VersionInfo retrieve(Path repositoryPath) {
        logger.log(
            GIT_DEFAULT_LOG_LEVEL,
            "Retrieving previous version for Git repository {}",
            repositoryPath
        );
        try (
            val repository = FileRepositoryBuilder.create(repositoryPath.resolve(DOT_GIT).toFile());
            val git = new Git(repository)
        ) {
            return retrieve(git);
        }
    }

    @Nullable
    @SneakyThrows
    @SuppressWarnings("JavaDurationGetSecondsToToSeconds")
    private VersionInfo retrieve(Git git) {
        val fetchRemote = getFetchRemote(git);
        if (fetchRemote == null) {
            logger.log(
                GIT_ERROR_LOG_LEVEL,
                "No remotes are configured for Git repository {}",
                git.getRepository().getDirectory()
            );
            return null;
        }

        logger.log(
            GIT_DEFAULT_LOG_LEVEL,
            "Fetching tags"
        );
        val fetchRemoteName = fetchRemote.getName();
        git.fetch()
            .setRefSpecs(R_TAGS + "*" + ':' + R_TAGS + "*")
            .setRecurseSubmodules(FetchRecurseSubmodulesMode.NO)
            .setRemote(fetchRemoteName)
            .setProgressMonitor(new GradleProgressMonitor(buildCancellationToken))
            .setTimeout(toIntExact(FETCH_TIMEOUT.getSeconds()))
            .call();

        val repository = git.getRepository();
        val objectIdVersions = collectObjectIdVersions(repository);
        if (objectIdVersions.isEmpty()) {
            logger.log(
                GIT_WARN_LOG_LEVEL,
                "No version tags found for Git repository {}",
                repository.getDirectory()
            );
            return null;
        }


        VersionInfo commitVersion = retrieveImpl(repository);

        if (commitVersion == null && isNotEmpty(repository.getObjectDatabase().getShallowCommits())) {
            int depth = 1000;
            logger.log(
                GIT_WARN_LOG_LEVEL,
                "The repository was cloned/fetched partially, and existing local commits don't have version tags"
                    + " => fetching with {} depth",
                depth
            );
            git.fetch()
                .setDepth(depth)
                .setTagOpt(TagOpt.NO_TAGS)
                .setRecurseSubmodules(FetchRecurseSubmodulesMode.NO)
                .setRemote(fetchRemoteName)
                .setProgressMonitor(new GradleProgressMonitor(buildCancellationToken))
                .setTimeout(toIntExact(FETCH_TIMEOUT.getSeconds()))
                .call();

            commitVersion = retrieveImpl(repository);
        }

        if (commitVersion == null && isNotEmpty(repository.getObjectDatabase().getShallowCommits())) {
            logger.log(
                GIT_WARN_LOG_LEVEL,
                "The repository was fetched partially, and existing local commits don't have version tags"
                    + " => fetching all commits"
            );
            git.fetch()
                .setUnshallow(true)
                .setTagOpt(TagOpt.NO_TAGS)
                .setRecurseSubmodules(FetchRecurseSubmodulesMode.NO)
                .setRemote(fetchRemoteName)
                .setProgressMonitor(new GradleProgressMonitor(buildCancellationToken))
                .setTimeout(toIntExact(FETCH_TIMEOUT.getSeconds()))
                .call();

            commitVersion = retrieveImpl(repository);
        }

        if (commitVersion == null) {
            val headRef = repository.getRefDatabase().exactRef(HEAD);
            logger.warn(
                "No reachable version found for Git commit {} in repository {}",
                headRef.getObjectId(),
                repository.getDirectory()
            );
            return null;
        }

        return commitVersion;
    }

    @Nullable
    @SneakyThrows
    @SuppressWarnings("java:S3776")
    private VersionInfo retrieveImpl(Repository repository) {
        try (val walk = new RevWalk(repository)) {
            walk.sort(RevSort.NONE);
            walk.setRetainBody(false);

            val headRef = repository.getRefDatabase().exactRef(HEAD);
            val headCommit = walk.parseCommit(headRef.getObjectId());
            walk.markStart(headCommit);

            val allRefsByPeeledObjectId = repository.getAllRefsByPeeledObjectId();

            val commitQueue = new ArrayDeque<RevCommit>();
            val addParentsToQueue = sneakyThrows((SneakyThrowsConsumer<RevCommit>) commit -> {
                for (int parentIndex = 0; parentIndex < commit.getParentCount(); ++parentIndex) {
                    val parentCommit = commit.getParent(parentIndex);
                    walk.parseHeaders(parentCommit);
                    commitQueue.addLast(parentCommit);
                }
            });

            if (ignoreCurrentCommit) {
                addParentsToQueue.accept(walk.next());
            } else {
                commitQueue.addLast(walk.next());
            }

            VersionInfo maxRefVersion = null;
            while (true) {
                val commit = commitQueue.pollFirst();
                if (commit == null) {
                    break;
                }

                if (commit.has(UNINTERESTING)) {
                    continue;
                } else {
                    commit.add(UNINTERESTING);
                }

                val tagRefVersion = getTagRefVersion(commit, allRefsByPeeledObjectId);
                if (tagRefVersion != null) {
                    if (maxRefVersion == null || maxRefVersion.compareTo(tagRefVersion) <= 0) {
                        maxRefVersion = tagRefVersion;
                    }
                    continue;
                }

                addParentsToQueue.accept(commit);
            }

            return maxRefVersion;
        }
    }

    @Nullable
    @SuppressWarnings("java:S3776")
    private VersionInfo getTagRefVersion(
        RevCommit commit,
        Map<AnyObjectId, Set<Ref>> allRefsByPeeledObjectId
    ) {
        VersionInfo maxTagRefVersion = null;

        val commitRefs = allRefsByPeeledObjectId.getOrDefault(commit.getId(), emptySet());
        for (val ref : commitRefs) {
            val refName = ref.getName();
            if (isNotEmpty(refName) && refName.startsWith(R_TAGS)) {
                val refShortenName = refName.substring(R_TAGS.length());
                forTagPatterns:
                for (val tagPattern : tagPatterns) {
                    val matcher = tagPattern.matcher(refShortenName);
                    if (matcher.matches()) {
                        val versionString = matcher.group("version");
                        if (isEmpty(versionString)) {
                            continue;
                        }

                        val refVersion = VersionInfo.builder()
                            .version(versionString)
                            .gitCommitHash(commit.getId().getName())
                            .gitTag(refShortenName)
                            .build();
                        if (maxTagRefVersion == null || maxTagRefVersion.compareTo(refVersion) < 0) {
                            maxTagRefVersion = refVersion;
                        }
                        break forTagPatterns;
                    }
                }
            }
        }

        return maxTagRefVersion;
    }

    @SneakyThrows
    private Map<ObjectId, SortedSet<Version>> collectObjectIdVersions(Repository repository) {
        val objectIdVersions = new LinkedHashMap<ObjectId, SortedSet<Version>>();

        val tagRefs = repository.getRefDatabase().getRefsByPrefix(R_TAGS);
        for (val tagRef : tagRefs) {
            val tagName = shortenRefName(tagRef.getName());
            forTagPatterns:
            for (val tagPattern : tagPatterns) {
                val matcher = tagPattern.matcher(tagName);
                if (matcher.matches()) {
                    val versionString = matcher.group("version");
                    if (versionString == null) {
                        logger.warn(
                            "Capturing group `version` was not matched for pattern /{}/ and Git tag `{}`",
                            tagPattern,
                            tagName
                        );
                    } else if (versionString.isEmpty()) {
                        logger.warn(
                            "Capturing group `version` is empty for pattern /{}/ and Git tag `{}`",
                            tagPattern,
                            tagName
                        );
                    } else {
                        val objectId = getPeeledObjectId(repository, tagRef);
                        val version = Version.parse(versionString);
                        objectIdVersions.computeIfAbsent(objectId, __ -> new TreeSet<>()).add(version);
                        break forTagPatterns;
                    }
                }
            }
        }

        return objectIdVersions;
    }

    @SneakyThrows
    @SuppressWarnings("java:S2583")
    private static ObjectId getPeeledObjectId(Repository repository, Ref ref) {
        val peeledRef = defaultValue(repository.getRefDatabase().peel(ref), ref);
        return defaultValue(peeledRef.getPeeledObjectId(), peeledRef.getObjectId());
    }

    @Nullable
    @SneakyThrows
    private static RemoteConfig getFetchRemote(Git git) {
        val remotes = git.remoteList().call().stream()
            .filter(not(it -> isEmpty(it.getURIs())))
            .collect(toList());

        RemoteConfig remote = remotes.stream()
            .filter(it -> DEFAULT_REMOTE_NAME.equals(it.getName()))
            .findFirst()
            .orElse(null);

        if (remote == null && isNotEmpty(remotes)) {
            remote = remotes.get(0);
        }

        return remote;
    }


}
