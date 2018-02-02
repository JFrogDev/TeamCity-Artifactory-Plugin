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

package org.jfrog.teamcity.agent;

import com.google.common.collect.Multimap;
import jetbrains.buildServer.agent.BuildRunnerContext;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.jfrog.build.api.Artifact;
import org.jfrog.build.api.BuildAgent;
import org.jfrog.build.api.BuildType;
import org.jfrog.build.api.Dependency;
import org.jfrog.build.api.builder.BuildInfoBuilder;
import org.jfrog.build.api.builder.ModuleBuilder;
import org.jfrog.build.client.DeployDetailsArtifact;
import org.jfrog.build.extractor.clientConfiguration.client.ArtifactoryBuildInfoClient;
import org.jfrog.build.extractor.clientConfiguration.util.spec.SpecsHelper;
import org.jfrog.teamcity.agent.util.PathHelper;
import org.jfrog.teamcity.agent.util.TeamcityAgenBuildInfoLog;
import org.jfrog.teamcity.common.ConstantValues;
import org.jfrog.teamcity.common.RunnerParameterKeys;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Noam Y. Tenne
 */
public class GenericBuildInfoExtractor extends BaseBuildInfoExtractor<Object> {

    final private List<Artifact> deployedArtifacts = new ArrayList<Artifact>();
    final private ArtifactoryBuildInfoClient infoClient;

    public GenericBuildInfoExtractor(BuildRunnerContext runnerContext, Multimap<File, String> artifactsToPublish,
                                     List<Dependency> publishedDependencies, ArtifactoryBuildInfoClient infoClient) {
        super(runnerContext, artifactsToPublish, publishedDependencies);
        this.infoClient = infoClient;
    }

    @Override
    protected void appendRunnerSpecificDetails(BuildInfoBuilder builder, Object object)
            throws Exception {
        builder.type(BuildType.GENERIC);
        builder.buildAgent(new BuildAgent(runnerContext.getRunType()));
        boolean isUsesSpecs = BooleanUtils.toBoolean(runnerContext.getRunnerParameters().get(RunnerParameterKeys.USE_SPECS));
        if (!isUsesSpecs || !isSpecValid()) {
            return;
        }
        SpecsHelper specsHelper = new SpecsHelper(new TeamcityAgenBuildInfoLog(logger));
        String uploadSpec = getUploadSpec();
        try {
            deployedArtifacts.addAll(specsHelper.uploadArtifactsBySpec(uploadSpec, runnerContext.getWorkingDirectory(), matrixParams, infoClient));
        } catch (IOException e) {
            throw new Exception(
                    String.format("Could not collect artifacts details from the spec: %s", e.getMessage()), e);
        }
    }

    private String getUploadSpec() throws IOException {
        String uploadSpecSource = runnerParams.get(RunnerParameterKeys.UPLOAD_SPEC_SOURCE);
        String uploadSpecFilePath = runnerParams.get(RunnerParameterKeys.UPLOAD_SPEC_FILE_PATH);

        if (StringUtils.isNotEmpty(uploadSpecFilePath) && uploadSpecSource.equals(ConstantValues.SPEC_FILE_SOURCE)) {
            return PathHelper.getSpecFromFile(runnerContext.getWorkingDirectory().getCanonicalPath(), uploadSpecFilePath);
        }

        if (uploadSpecSource == null || !uploadSpecSource.equals(ConstantValues.SPEC_FILE_SOURCE)) {
            return runnerParams.get(RunnerParameterKeys.UPLOAD_SPEC);
        }

        return "";
    }

    @Override
    protected List<DeployDetailsArtifact> getDeployableArtifacts() {
        return null;
    }

    private boolean isSpecValid() {
        String uploadSpecSource = runnerParams.get(RunnerParameterKeys.UPLOAD_SPEC_SOURCE);
        return StringUtils.isNotBlank(
                runnerContext.getRunnerParameters().get(RunnerParameterKeys.UPLOAD_SPEC))
                || (uploadSpecSource != null && uploadSpecSource.equals(ConstantValues.SPEC_FILE_SOURCE) && StringUtils.isNotEmpty(runnerParams.get(RunnerParameterKeys.UPLOAD_SPEC_FILE_PATH)));
    }


    /**
     * This method is used when using specs (not the legacy pattern).
     * This method goes over the provided DeployDetailsArtifact list and adds it to the provided moduleBuilder with
     * the needed properties.
     *
     * @param moduleBuilder     the moduleBuilder that contains the build information
     */
    @Override
    void updatePropsAndModuleArtifacts(ModuleBuilder moduleBuilder) {
        moduleBuilder.artifacts(deployedArtifacts);
    }
}