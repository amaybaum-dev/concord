package com.walmartlabs.concord.server.repository;

import com.google.common.base.Throwables;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.walmartlabs.concord.common.IOUtils;
import com.walmartlabs.concord.common.secret.KeyPair;
import com.walmartlabs.concord.common.secret.UsernamePassword;
import com.walmartlabs.concord.sdk.Secret;
import com.walmartlabs.concord.server.api.project.RepositoryEntry;
import com.walmartlabs.concord.server.project.RepositoryException;
import com.walmartlabs.concord.server.security.secret.SecretManager;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.SubmoduleInitCommand;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidConfigurationException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.SubmoduleConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.submodule.SubmoduleWalk;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.util.FS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.eclipse.jgit.transport.CredentialItem.Password;
import static org.eclipse.jgit.transport.CredentialItem.Username;

@Named
public class GithubRepositoryProvider implements RepositoryProvider {

    private static final Logger log = LoggerFactory.getLogger(GithubRepositoryProvider.class);

    private static final String DEFAULT_BRANCH = "master";

    private final SecretManager secretManager;

    @Inject
    public GithubRepositoryProvider(SecretManager secretManager) {
        this.secretManager = secretManager;
    }

    @Override
    public void fetch(UUID teamId, RepositoryEntry repository, Path dest) {
        Secret secret = getSecret(teamId, repository.getSecret());

        if (repository.getCommitId() != null) {
            fetchByCommit(repository.getUrl(), repository.getCommitId(), secret, dest);
        } else {
            String branch = Optional.ofNullable(repository.getBranch()).orElse(DEFAULT_BRANCH);
            fetch(repository.getUrl(), branch, secret, dest);
        }
    }

    private void fetch(String uri, String branch, Secret secret, Path dest) {
        TransportConfigCallback transportCallback = createTransportConfigCallback(secret);

        try (Git repo = openRepo(dest)) {
            if (repo != null) {
                repo.checkout()
                        .setName(branch)
                        .call();

                repo.pull()
                        .setRecurseSubmodules(SubmoduleConfig.FetchRecurseSubmodulesMode.NO)
                        .setTransportConfigCallback(transportCallback)
                        .call();

                fetchSubmodules(repo.getRepository(), transportCallback);

                log.info("fetch ['{}', '{}'] -> repository updated", uri, branch);
                return;
            }
        } catch (GitAPIException e) {
            throw new RepositoryException("Error while updating a repository", e);
        }

        try (Git ignored = cloneRepo(uri, dest, branch, transportCallback)) {
            log.info("fetch ['{}', '{}'] -> initial clone completed", uri, branch);
        }
    }

    private void fetchByCommit(String uri, String commitId, Secret secret, Path dest) {
        try (Git repo = openRepo(dest)) {
            if (repo != null) {
                log.info("fetch ['{}', '{}'] -> repository exists", uri, commitId);
                return;
            }
        }

        try (Git repo = cloneRepo(uri, dest, null, createTransportConfigCallback(secret))) {
            repo.checkout()
                    .setName(commitId)
                    .call();

            log.info("fetchByCommit ['{}', '{}'] -> initial clone completed", uri, commitId);
        } catch (GitAPIException e) {
            throw new RepositoryException("Error while updating a repository", e);
        }
    }

    private Secret getSecret(UUID teamId, String secretName) {
        Secret secret = null;
        if (secretName != null) {
            secret = secretManager.getSecret(teamId, secretName, null);
            if (secret == null) {
                throw new RepositoryException("Secret not found: " + secretName);
            }
        }
        return secret;
    }

    private static Git openRepo(Path path) {
        if (!Files.exists(path)) {
            return null;
        }

        // check if there is an existing git repo
        try {
            return Git.open(path.toFile());
        } catch (RepositoryNotFoundException e) {
            // ignore
        } catch (IOException e) {
            throw new RepositoryException("Error while opening a repository", e);
        }

        return null;
    }

    private static Git cloneRepo(String uri, Path path, String branch, TransportConfigCallback transportCallback) {
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                throw new RepositoryException("Can't create a directory for a repository", e);
            }
        }

        try {
            Git repo = Git.cloneRepository()
                    .setURI(uri)
                    .setBranch(branch)
                    .setBranchesToClone(branch != null ? Collections.singleton(branch) : null)
                    .setDirectory(path.toFile())
                    .setTransportConfigCallback(transportCallback)
                    .call();

            // check if the branch actually exists
            if (branch != null) {
                repo.checkout()
                        .setName(branch)
                        .call();
            }

            cloneSubmodules(uri, repo, transportCallback);

            return repo;
        } catch (ConfigInvalidException | IOException | GitAPIException e) {
            try {
                IOUtils.deleteRecursively(path);
            } catch (IOException ee) {
                log.warn("cloneRepo ['{}', '{}'] -> cleanup error: {}", uri, branch, ee.getMessage());
            }
            throw new RepositoryException("Error while cloning a repository", e);
        }
    }

    private static void cloneSubmodules(String mainRepoUrl, Git repo, TransportConfigCallback transportConfigCallback) throws IOException, GitAPIException, ConfigInvalidException {
        SubmoduleInitCommand init = repo.submoduleInit();
        Collection<String> submodules = init.call();
        if (submodules.isEmpty()) {
            return;
        }

        cloneSubmodules(mainRepoUrl, repo.getRepository(), transportConfigCallback);

        // process sub-submodules
        SubmoduleWalk walk = SubmoduleWalk.forIndex(repo.getRepository());
        while (walk.next()) {
            try (Repository subRepo = walk.getRepository()) {
                if (subRepo != null) {
                    cloneSubmodules(mainRepoUrl, subRepo, transportConfigCallback);
                }
            }
        }
    }

    private static void cloneSubmodules(String mainRepoUrl, Repository repo, TransportConfigCallback transportConfigCallback) throws IOException, ConfigInvalidException, GitAPIException {
        try (SubmoduleWalk walk = SubmoduleWalk.forIndex(repo)) {
            while (walk.next()) {
                // Skip submodules not registered in .gitmodules file
                if (walk.getModulesPath() == null)
                    continue;
                // Skip submodules not registered in parent repository's config
                String url = walk.getConfigUrl();
                if (url == null)
                    continue;

                Repository submoduleRepo = walk.getRepository();
                // Clone repository if not present
                if (submoduleRepo == null) {
                    CloneCommand clone = Git.cloneRepository();
                    clone.setTransportConfigCallback(transportConfigCallback);

                    clone.setURI(url);
                    clone.setDirectory(walk.getDirectory());
                    clone.setGitDir(new File(new File(repo.getDirectory(), Constants.MODULES), walk.getPath()));
                    submoduleRepo = clone.call().getRepository();
                }

                try (RevWalk revWalk = new RevWalk(submoduleRepo)) {
                    RevCommit commit = revWalk.parseCommit(walk.getObjectId());
                    Git.wrap(submoduleRepo).checkout()
                            .setName(commit.getName())
                            .call();

                    log.info("cloneSubmodules ['{}'] -> '{}'@{}", mainRepoUrl, url, commit.getName());
                } finally {
                    if (submoduleRepo != null) {
                        submoduleRepo.close();
                    }
                }
            }
        }
    }

    private void fetchSubmodules(Repository repo, TransportConfigCallback transportCallback)
            throws GitAPIException {
        try (SubmoduleWalk walk = new SubmoduleWalk(repo);
             RevWalk revWalk = new RevWalk(repo)) {
            // Walk over submodules in the parent repository's FETCH_HEAD.
            ObjectId fetchHead = repo.resolve(Constants.FETCH_HEAD);
            if (fetchHead == null) {
                return;
            }
            walk.setTree(revWalk.parseTree(fetchHead));
            while (walk.next()) {
                Repository submoduleRepo = walk.getRepository();

                // Skip submodules that don't exist locally (have not been
                // cloned), are not registered in the .gitmodules file, or
                // not registered in the parent repository's config.
                if (submoduleRepo == null || walk.getModulesPath() == null
                        || walk.getConfigUrl() == null) {
                    continue;
                }

                Git.wrap(submoduleRepo).fetch()
                        .setRecurseSubmodules(SubmoduleConfig.FetchRecurseSubmodulesMode.NO)
                        .setTransportConfigCallback(transportCallback)
                        .call();

                fetchSubmodules(submoduleRepo, transportCallback);

                log.info("fetchSubmodules ['{}'] -> done", submoduleRepo.getDirectory());
            }
        } catch (IOException e) {
            throw new JGitInternalException(e.getMessage(), e);
        } catch (ConfigInvalidException e) {
            throw new InvalidConfigurationException(e.getMessage(), e);
        }
    }

    private static TransportConfigCallback createTransportConfigCallback(Secret secret) {
        return transport -> {
            if (transport instanceof SshTransport) {
                configureSshTransport((SshTransport) transport, secret);
            } else if (transport instanceof HttpTransport) {
                configureHttpTransport((HttpTransport) transport, secret);
            }
        };
    }

    private static void configureSshTransport(SshTransport t, Secret secret) {
        if (!(secret instanceof KeyPair)) {
            throw new RepositoryException("Invalid secret type, expected a key pair");
        }

        SshSessionFactory f = new JschConfigSessionFactory() {

            @Override
            protected JSch createDefaultJSch(FS fs) throws JSchException {
                JSch d = super.createDefaultJSch(fs);

                d.removeAllIdentity();

                KeyPair kp = (KeyPair) secret;
                d.addIdentity("concord-server", kp.getPrivateKey(), kp.getPublicKey(), null);
                log.debug("configureSshTransport -> using the supplied secret");
                return d;
            }

            @Override
            protected void configure(OpenSshConfig.Host hc, Session session) {
                Properties config = new Properties();
                config.put("StrictHostKeyChecking", "no");
                session.setConfig(config);
                log.warn("configureSshTransport -> strict host key checking is disabled");
            }
        };

        t.setSshSessionFactory(f);
    }

    private static void configureHttpTransport(HttpTransport t, Secret secret) {
        if (secret != null) {
            if (!(secret instanceof UsernamePassword)) {
                throw new RepositoryException("Invalid secret type, expected a username/password credentials");
            }

            UsernamePassword up = (UsernamePassword) secret;

            t.setCredentialsProvider(new CredentialsProvider() {
                @Override
                public boolean isInteractive() {
                    return false;
                }

                @Override
                public boolean supports(CredentialItem... items) {
                    return true;
                }

                @Override
                public boolean get(URIish uri, CredentialItem... items) throws UnsupportedCredentialItem {
                    int cnt = 0;

                    for (CredentialItem i : items) {
                        if (i instanceof Username) {
                            ((Username) i).setValue(up.getUsername());
                            cnt += 1;
                        } else if (i instanceof Password) {
                            ((Password) i).setValue(up.getPassword());
                            cnt += 1;
                        }
                    }

                    boolean ok = cnt == 2;
                    if (ok) {
                        log.debug("configureHttpTransport -> using the supplied secret");
                    }

                    return ok;
                }
            });
        }

        try {
            // unfortunately JGit doesn't expose sslVerify in the clone command
            // use reflection to disable it

            Field cfgField = t.getClass().getDeclaredField("http");
            cfgField.setAccessible(true);

            Object cfg = cfgField.get(t);
            if (cfg == null) {
                log.warn("configureHttpTransport -> can't disable SSL verification");
                return;
            }

            Field paramField = cfg.getClass().getDeclaredField("sslVerify");
            paramField.setAccessible(true);
            paramField.set(cfg, false);

            log.warn("configureHttpTransport -> SSL verification is disabled");
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw Throwables.propagate(e);
        }
    }
}