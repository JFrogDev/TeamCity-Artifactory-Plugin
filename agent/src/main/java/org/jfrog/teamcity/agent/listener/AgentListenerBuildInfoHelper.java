/*
 * Copyright (C) 2010 JFrog Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jfrog.teamcity.agent.listener;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import jetbrains.buildServer.ExtensionHolder;
import jetbrains.buildServer.agent.*;
import jetbrains.buildServer.agent.artifacts.ArtifactsWatcher;
import jetbrains.buildServer.agent.impl.artifacts.ArtifactsBuilder;
import jetbrains.buildServer.agent.impl.artifacts.ArtifactsCollection;
import jetbrains.buildServer.log.Loggers;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.jfrog.build.api.BuildRetention;
import org.jfrog.build.api.Dependency;
import org.jfrog.build.api.dependency.BuildDependency;
import org.jfrog.build.client.DeployDetailsArtifact;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryBuildInfoClientBuilder;
import org.jfrog.build.extractor.clientConfiguration.IncludeExcludePatterns;
import org.jfrog.build.extractor.clientConfiguration.PatternMatcher;
import org.jfrog.build.extractor.clientConfiguration.client.ArtifactoryBuildInfoClient;
import org.jfrog.teamcity.agent.*;
import org.jfrog.teamcity.agent.api.ExtractedBuildInfo;
import org.jfrog.teamcity.agent.util.BuildInfoUtils;
import org.jfrog.teamcity.agent.util.BuildRetentionFactory;
import org.jfrog.teamcity.common.ConstantValues;
import org.jfrog.teamcity.common.RunTypeUtils;
import org.jfrog.teamcity.common.RunnerParameterKeys;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.jfrog.teamcity.common.ConstantValues.BUILD_STARTED;

/**
 * @author Noam Y. Tenne
 */
public class AgentListenerBuildInfoHelper {
    public static final String MAVEN_BUILD_INFO_XML = "maven-build-info.xml";

    private ExtensionHolder extensionHolder;
    private ArtifactsWatcher watcher;

    public AgentListenerBuildInfoHelper(ExtensionHolder extensionHolder, ArtifactsWatcher watcher) {
        this.extensionHolder = extensionHolder;
        this.watcher = watcher;
    }

    public void beforeRunnerStart(BuildRunnerContext runner,
            List<Dependency> publishedDependencies,
            List<BuildDependency> buildDependencies) {
        Map<String, String> runnerParams = runner.getRunnerParameters();
        /**
         * This method handles the generic build info dependency publication which is not applicable to gradle or ant
         * builds run with the extractor activated, so if that's the case, just skip
         */
        if (RunTypeUtils.isGradleOrAntWithExtractorActivated(runner.getRunType(), runnerParams)) {
            return;
        }

        runner.addRunnerParameter(BUILD_STARTED, String.valueOf(new Date().getTime()));
        BuildProgressLogger logger = runner.getBuild().getBuildLogger();
        if (BooleanUtils.toBoolean(runnerParams.get(RunnerParameterKeys.USE_SPECS))) {
            retrieveDependenciesFromSpec(runner, publishedDependencies, logger);
        } else {
            retrievePublishedAndBuildDependencies(runner, publishedDependencies, buildDependencies, logger);
        }
    }

    private void retrieveDependenciesFromSpec(BuildRunnerContext runner, List<Dependency> publishedDependencies,
            BuildProgressLogger logger) {
        DependenciesResolver dependenciesResolver = new DependenciesResolver(runner);
        try {
            publishedDependencies.addAll(dependenciesResolver.retrieveDependenciesBySpec());
        } catch (Exception e) {
            String errorMessage = "Error occurred while resolving dependencies from the spec: " + e.getMessage();
            throwExceptionAndLogError(e, errorMessage, logger);
        } finally {
            dependenciesResolver.close();
        }
    }

    private void retrievePublishedAndBuildDependencies(BuildRunnerContext runner,
            List<Dependency> publishedDependencies, List<BuildDependency> buildDependencies, BuildProgressLogger logger) {

        // In case the BUILD_DEPENDENCIES property value contains the DISABLED_MESSAGE value,
        // we do not want to pass it on:
        String buildDependenciesValue = runner.getRunnerParameters().get(RunnerParameterKeys.BUILD_DEPENDENCIES);
        if (ConstantValues.DISABLED_MESSAGE.equals(buildDependenciesValue)) {
            runner.addRunnerParameter(RunnerParameterKeys.BUILD_DEPENDENCIES, "");
        }

        DependenciesResolver dependenciesResolver = new DependenciesResolver(runner);
        try {
            publishedDependencies.addAll(dependenciesResolver.retrievePublishedDependencies());
            buildDependencies.addAll(dependenciesResolver.retrieveBuildDependencies());
        } catch (Exception e) {
            String errorMessage = "Error occurred while resolving published or build dependencies: " + e.getMessage();
            throwExceptionAndLogError(e, errorMessage, logger);
        } finally {
            dependenciesResolver.close();
        }
    }

    private void throwExceptionAndLogError(Exception e, String errorMessage, BuildProgressLogger logger) {
        Loggers.AGENT.error(errorMessage, e);
        logger.buildFailureDescription(errorMessage);
        logger.exception(e);
        throw new RuntimeException(errorMessage, e);
    }

    public void runnerFinished(BuildRunnerContext runner,
            BuildFinishedStatus status,
            List<Dependency> dependencies,
            List<BuildDependency> buildDependencies) throws Exception {

        /**
         * This method handles the build info and artifact publication which is not applicable to gradle or ant
         * builds run with the extractor activated, so if that's the case, just skip
         */
        Map<String, String> runnerParams = runner.getRunnerParameters();
        String runType = runner.getRunType();
        if (RunTypeUtils.isGradleOrAntWithExtractorActivated(runType, runnerParams) || status.isFailed()) {
            return;
        }

        AgentRunningBuild build = runner.getBuild();
        BuildProgressLogger logger = build.getBuildLogger();

        String selectedServerUrl = runnerParams.get(RunnerParameterKeys.URL);
        ArtifactoryBuildInfoClient infoClient = BuildInfoUtils.getArtifactoryBuildInfoClientBuilder(selectedServerUrl, runnerParams, logger).build();
        ExtractedBuildInfo extractedBuildInfo = extractBuildInfo(runner, dependencies, selectedServerUrl, runnerParams, logger);

        if (extractedBuildInfo == null) {
            String m = "Could not generate build-info.";
            Loggers.AGENT.warn(m);
            build.getBuildLogger().warning(m);
            infoClient.close();
            return;
        }

        extractedBuildInfo.getBuildInfo().setBuildDependencies(buildDependencies);

        try {
            List<DeployDetailsArtifact> deployableArtifacts = extractedBuildInfo.getDeployableArtifacts();
            if (!deployableArtifacts.isEmpty()) {

                boolean skipIncludeExcludeChecks = RunTypeUtils.isGenericRunType(runType, runnerParams);
                IncludeExcludePatterns patterns = new IncludeExcludePatterns(
                        runnerParams.get(RunnerParameterKeys.DEPLOY_INCLUDE_PATTERNS),
                        runnerParams.get(RunnerParameterKeys.DEPLOY_EXCLUDE_PATTERNS));

                logger.progressStarted("Deploying artifacts to " + selectedServerUrl);
                for (DeployDetailsArtifact deployableArtifact : deployableArtifacts) {

                    String deploymentPath = deployableArtifact.getDeploymentPath();
                    if (!skipIncludeExcludeChecks && PatternMatcher.pathConflicts(deploymentPath, patterns)) {
                        logger.progressMessage("Skipping the deployment of '" + deploymentPath +
                                "' due to the defined include-exclude patterns.");
                        continue;
                    }
                    try {
                        infoClient.deployArtifact(deployableArtifact.getDeployDetails());
                    } catch (IOException e) {
                        throw new RuntimeException("Error deploying artifact: " + deployableArtifact.getFile() +
                                ".\n Skipping deployment of remaining artifacts (if any) and build info.", e);
                    }
                }
            }

            String publishBuildInfoValue = runnerParams.get(RunnerParameterKeys.PUBLISH_BUILD_INFO);
            BuildRetention retention = BuildRetentionFactory.createBuildRetention(runnerParams, logger);
            String isAsyncBuildRetention = runnerParams.get(RunnerParameterKeys.DISCARD_OLD_BUILDS_ASYNC);
            if (Boolean.parseBoolean(publishBuildInfoValue)) {
                BuildInfoUtils.publishBuildInfoToTeamCityServer(build, extractedBuildInfo.getBuildInfo(), watcher);
                BuildInfoUtils.sendBuildAndBuildRetention(build, extractedBuildInfo.getBuildInfo(), retention, Boolean.parseBoolean(isAsyncBuildRetention), infoClient);
            }
        } finally {
            infoClient.close();
        }
    }

    private ExtractedBuildInfo extractBuildInfo(BuildRunnerContext runnerContext, List<Dependency> dependencies, String selectedServerUrl, Map<String, String> runnerParams, BuildProgressLogger logger) throws Exception {
        AgentRunningBuild build = runnerContext.getBuild();
        Multimap<File, String> publishableArtifacts = getPublishableArtifacts(runnerContext);
        if (RunTypeUtils.isMavenRunType(runnerContext.getRunType())) {
            File mavenBuildInfoFile = new File(build.getBuildTempDirectory(), MAVEN_BUILD_INFO_XML);

            if (!mavenBuildInfoFile.exists()) {
                String missingReport = "Skipping build info collection: Maven build info report doesn't exist.";
                Loggers.AGENT.warn(missingReport);
                build.getBuildLogger().warning(missingReport);
                throw new RuntimeException("Error occurred during build info collection. Skipping deployment.");
            }

            BaseBuildInfoExtractor<File> buildInfoExtractor = new MavenBuildInfoExtractor(runnerContext, publishableArtifacts, dependencies);
            return new ExtractedBuildInfo(buildInfoExtractor.extract(mavenBuildInfoFile), buildInfoExtractor.getDeployableArtifact());
        }
        ArtifactoryBuildInfoClientBuilder buildInfoClientBuilder = BuildInfoUtils.getArtifactoryBuildInfoClientBuilder(selectedServerUrl, runnerParams, logger);
        GenericBuildInfoExtractor buildInfoExtractor = new GenericBuildInfoExtractor(runnerContext, publishableArtifacts, dependencies, buildInfoClientBuilder);

        return new ExtractedBuildInfo(buildInfoExtractor.extract(null), buildInfoExtractor.getDeployableArtifact());
    }

    private Multimap<File, String> getPublishableArtifacts(BuildRunnerContext runnerContext) {
        BuildProgressLogger logger = runnerContext.getBuild().getBuildLogger();

        Multimap<File, String> map = HashMultimap.create();
        String publishedArtifactPropVal = runnerContext.getRunnerParameters().get(RunnerParameterKeys.PUBLISHED_ARTIFACTS);
        String useSpecs = runnerContext.getRunnerParameters().get(RunnerParameterKeys.USE_SPECS);
        if (StringUtils.isNotBlank(publishedArtifactPropVal) && !BooleanUtils.toBoolean(useSpecs)) {

            //Add artifacts to publish
            logInfo(logger, "[" + Thread.currentThread().getId() + "] Adding artifact paths: " +
                    publishedArtifactPropVal);
            ArtifactsBuilder builder = new ArtifactsBuilder();
            builder.setPreprocessors(extensionHolder.getExtensions(ArtifactsPreprocessor.class));
            builder.setBaseDir(runnerContext.getBuild().getCheckoutDirectory());
            builder.setArtifactsPaths(publishedArtifactPropVal);
            builder.addListener(new LoggingArtifactsBuilderAdapter(logger));

            try {
                List<ArtifactsCollection> artifactsCollections = builder.build();
                for (ArtifactsCollection artifactCollection : artifactsCollections) {
                    Map<File, String> pathMap = artifactCollection.getFilePathMap();
                    for (Map.Entry<File, String> entry : pathMap.entrySet()) {
                        StringBuilder infoBuilder = new StringBuilder().append("[").
                                append(Thread.currentThread().getId()).append("] Adding published artifact: ").
                                append(entry.getKey());
                        if (StringUtils.isNotBlank(entry.getValue())) {
                            infoBuilder.append("->").append(entry.getValue());
                        }
                        logInfo(logger, infoBuilder.toString());
                    }
                    for (Map.Entry<File, String> entry : pathMap.entrySet()) {
                        map.put(entry.getKey(), entry.getValue());
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Error occurred during artifacts publishing: " + e.getMessage(), e);
            }
        }
        return map;
    }

    private void logInfo(BuildProgressLogger logger, String message) {
        Loggers.AGENT.info(message);
        logger.message(message);
    }
}