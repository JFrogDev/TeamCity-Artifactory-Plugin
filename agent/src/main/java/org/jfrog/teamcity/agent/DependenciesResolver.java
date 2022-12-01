package org.jfrog.teamcity.agent;

import com.google.common.collect.Lists;
import jetbrains.buildServer.agent.BuildRunnerContext;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jfrog.build.api.dependency.BuildDependency;
import org.jfrog.build.api.util.Log;
import org.jfrog.build.extractor.ci.Dependency;
import org.jfrog.build.extractor.clientConfiguration.client.artifactory.ArtifactoryManager;
import org.jfrog.build.extractor.clientConfiguration.util.AntPatternsDependenciesHelper;
import org.jfrog.build.extractor.clientConfiguration.util.BuildDependenciesHelper;
import org.jfrog.build.extractor.clientConfiguration.util.DependenciesDownloader;
import org.jfrog.build.extractor.clientConfiguration.util.DependenciesDownloaderImpl;
import org.jfrog.build.extractor.clientConfiguration.util.spec.SpecsHelper;
import org.jfrog.teamcity.agent.util.SpecHelper;
import org.jfrog.teamcity.agent.util.TeamcityAgenBuildInfoLog;
import org.jfrog.teamcity.common.ConstantValues;
import org.jfrog.teamcity.common.RunnerParameterKeys;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.jfrog.teamcity.common.ConstantValues.*;

/**
 * Resolves artifacts from Artifactory (published dependencies and build dependencies)
 *
 * @author Shay Yaakov
 */
public class DependenciesResolver implements Closeable {

    private final BuildRunnerContext runnerContext;
    private final Log log;
    private final Map<String, String> runnerParams;
    private final String serverUrl;
    private final String selectedPublishedDependencies;
    private final DependenciesDownloader dependenciesDownloader;

    public DependenciesResolver(@NotNull BuildRunnerContext runnerContext) {
        this.runnerContext = runnerContext;
        this.log = new TeamcityAgenBuildInfoLog(runnerContext.getBuild().getBuildLogger());
        this.runnerParams = runnerContext.getRunnerParameters();
        this.serverUrl = runnerParams.get(RunnerParameterKeys.URL);
        this.selectedPublishedDependencies = runnerParams.get(RunnerParameterKeys.BUILD_DEPENDENCIES);
        this.dependenciesDownloader = createDependenciesDownloader();
    }

    public List<Dependency> retrievePublishedDependencies() throws IOException, InterruptedException {
        if (!verifyParameters()) {
            return Lists.newArrayList();
        }
        AntPatternsDependenciesHelper helper = new AntPatternsDependenciesHelper(dependenciesDownloader, log);
        return helper.retrievePublishedDependencies(selectedPublishedDependencies);
    }

    public List<BuildDependency> retrieveBuildDependencies() throws IOException, InterruptedException {
        if (!verifyParameters()) {
            return Lists.newArrayList();
        }
        BuildDependenciesHelper helper = new BuildDependenciesHelper(dependenciesDownloader, log);
        return helper.retrieveBuildDependencies(selectedPublishedDependencies);
    }

    /**
     * Downloads Dependencies according to a spec which should be provided by the RunnerParameterKeys.DOWNLOAD_SPEC property.
     *
     * @return list of the downloaded dependencies represented by List of Dependency objects
     */
    public List<Dependency> retrieveDependenciesBySpec() throws IOException {
        SpecsHelper specsHelper = new SpecsHelper(log);
        String spec = getDownloadSpec();
        if (StringUtils.isNotEmpty(spec)) {
            return specsHelper.downloadArtifactsBySpec(spec, dependenciesDownloader.getArtifactoryManager(), runnerContext.getWorkingDirectory().getAbsolutePath());
        }
        return Collections.emptyList();
    }

    private String getDownloadSpec() throws IOException {
        String downloadSpecSource = runnerParams.get(RunnerParameterKeys.DOWNLOAD_SPEC_SOURCE);
        if (downloadSpecSource == null || !downloadSpecSource.equals(ConstantValues.SPEC_FILE_SOURCE)) {
            return runnerParams.get(RunnerParameterKeys.DOWNLOAD_SPEC);
        }
        String downloadSpecFilePath = runnerParams.get(RunnerParameterKeys.DOWNLOAD_SPEC_FILE_PATH);
        if (StringUtils.isEmpty(downloadSpecFilePath)) {
            return downloadSpecFilePath;
        }
        return SpecHelper.getSpecFromFile(runnerContext.getWorkingDirectory().getCanonicalPath(), downloadSpecFilePath);
    }

    private boolean verifyParameters() {
        // Don't run if no server was configured
        if (StringUtils.isBlank(serverUrl)) {
            return false;
        }

        // Don't run if no build dependency patterns were specified.
        return dependencyEnabled(selectedPublishedDependencies);
    }

    /**
     * Determines if dependency parameter specified is enabled.
     *
     * @param s dependency parameter (published or build)
     * @return true, if dependency parameter specified is enabled,
     * false otherwise
     */
    private boolean dependencyEnabled(String s) {
        return StringUtils.isNotBlank(s) && !ConstantValues.DISABLED_MESSAGE.equals(s);
    }

    private DependenciesDownloader createDependenciesDownloader() {
        String workspacePath = runnerContext.getWorkingDirectory().getAbsolutePath();
        return new DependenciesDownloaderImpl(newArtifactoryManager(), workspacePath, log);
    }

    /**
     * Retrieves Artifactory HTTP client.
     *
     * @return Artifactory HTTP client.
     */
    private ArtifactoryManager newArtifactoryManager() {
        ArtifactoryManager artifactoryManager = new ArtifactoryManager(serverUrl,
                runnerParams.get(RunnerParameterKeys.RESOLVER_USERNAME),
                runnerParams.get(RunnerParameterKeys.RESOLVER_PASSWORD),
                log);

        artifactoryManager.setConnectionTimeout(Integer.parseInt(runnerParams.get(RunnerParameterKeys.TIMEOUT)));

        if (runnerParams.containsKey(PROXY_HOST)) {
            if (StringUtils.isNotBlank(runnerParams.get(PROXY_USERNAME))) {
                artifactoryManager.setProxyConfiguration(runnerParams.get(PROXY_HOST),
                        Integer.parseInt(runnerParams.get(PROXY_PORT)), runnerParams.get(PROXY_USERNAME),
                        runnerParams.get(PROXY_PASSWORD));
            } else {
                artifactoryManager.setProxyConfiguration(runnerParams.get(PROXY_HOST),
                        Integer.parseInt(runnerParams.get(PROXY_PORT)));
            }
        }

        return artifactoryManager;
    }

    public void close() {
        if (dependenciesDownloader != null) {
            ArtifactoryManager artifactoryManager = dependenciesDownloader.getArtifactoryManager();
            if (artifactoryManager != null) {
                artifactoryManager.close();
            }
        }
    }
}
