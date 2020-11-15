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

package org.jfrog.teamcity.server.summary;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import jetbrains.buildServer.controllers.ActionErrors;
import jetbrains.buildServer.controllers.BaseFormXmlController;
import jetbrains.buildServer.controllers.BuildDataExtensionUtil;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildRunnerDescriptor;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.web.util.SessionUser;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfrog.build.api.builder.PromotionBuilder;
import org.jfrog.build.extractor.clientConfiguration.client.ArtifactoryBuildInfoClient;
import org.jfrog.teamcity.api.ProxyInfo;
import org.jfrog.teamcity.api.ServerConfigBean;
import org.jfrog.teamcity.api.credentials.CredentialsBean;
import org.jfrog.teamcity.api.credentials.CredentialsHelper;
import org.jfrog.teamcity.common.PromotionTargetStatusType;
import org.jfrog.teamcity.common.RunnerParameterKeys;
import org.jfrog.teamcity.server.global.DeployableArtifactoryServers;
import org.jfrog.teamcity.server.util.ServerUtils;
import org.jfrog.teamcity.server.util.TeamcityServerBuildInfoLog;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author Noam Y. Tenne
 */
public class PromotionResultsFragmentController extends BaseFormXmlController {

    private SBuildServer buildServer;
    private DeployableArtifactoryServers deployableServers;

    public PromotionResultsFragmentController(SBuildServer buildServer,
                                              DeployableArtifactoryServers deployableServers) {
        this.buildServer = buildServer;
        this.deployableServers = deployableServers;
    }

    @Override
    protected ModelAndView doGet(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response) {
        return null;
    }

    @Override
    protected void doPost(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull Element xmlResponse) {
        ActionErrors errors = new ActionErrors();
        SBuild build = getSBuild(request, xmlResponse, errors);
        if (build == null)
            return;

        SBuildType type = getSBuildType(xmlResponse, errors, build);
        if (type == null) {
            return;
        }

        Map<String, String> parameters = getBuildRunnerParameters(type);
        String selectUrlIdParam = getSelectedUrlId(xmlResponse, errors, parameters);
        if (selectUrlIdParam == null) {
            return;
        }

        long selectedUrlId = Long.parseLong(selectUrlIdParam);
        ServerConfigBean server = getServerConfigBean(xmlResponse, errors, selectedUrlId);
        if (server == null) {
            return;
        }

        boolean overrideDeployerCredentials = Boolean.valueOf(parameters.get(RunnerParameterKeys.OVERRIDE_DEFAULT_DEPLOYER));
        CredentialsBean preferredDeployer = getDeployerCredentialsBean(parameters, server, overrideDeployerCredentials);
        String loadTargetRepos = request.getParameter("loadTargetRepos");
        if (StringUtils.isNotBlank(loadTargetRepos) && Boolean.valueOf(loadTargetRepos)) {
            populateTargetRepos(xmlResponse, selectedUrlId, overrideDeployerCredentials, preferredDeployer);
            return;
        }

        //Promotion
        ArtifactoryBuildInfoClient client = null;
        try {
            client = getBuildInfoClient(server, preferredDeployer.getUsername(), preferredDeployer.getPassword());
            promoteBuild(request, xmlResponse, errors, build, parameters, client);
        } catch (IOException e) {
            Loggers.SERVER.error("Failed to execute promotion: " + e.getMessage());
            Loggers.SERVER.error(e);
            addError(errors, "errorPromotion", "Failed to execute the promotion. Please review the TeamCity " +
                    "server and Artifactory logs for further details.", xmlResponse);
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    private void promoteBuild(@NotNull HttpServletRequest request, @NotNull Element xmlResponse, ActionErrors errors,
                                 SBuild build, Map<String, String> parameters, ArtifactoryBuildInfoClient client) throws IOException {
                PromotionBuilder promotionBuilder = new PromotionBuilder()
                .status(PromotionTargetStatusType.valueOf(request.getParameter("targetStatus")).
                        getStatusDisplayName())
                .comment(request.getParameter("comment"))
                .ciUser(SessionUser.getUser(request).getUsername())
                .targetRepo(request.getParameter("promotionRepository"))
                .dependencies(Boolean.valueOf(request.getParameter("includeDependencies")))
                .copy(Boolean.valueOf(request.getParameter("useCopy")))
                .dryRun(true);

        // Do a dry run first
        if (!sendPromotionRequest(xmlResponse, errors, build, parameters, client, promotionBuilder, true)){
            return;
        }
        sendPromotionRequest(xmlResponse, errors, build, parameters, client, promotionBuilder, false);
    }

    private boolean sendPromotionRequest(@NotNull Element xmlResponse, ActionErrors errors, SBuild build,
                                      Map<String, String> parameters, ArtifactoryBuildInfoClient client,
                                      PromotionBuilder promotionBuilder, boolean isDryRun) throws IOException {
        if (isDryRun) {
            Loggers.SERVER.info("Performing dry run promotion (no changes are made during dry run)...");
        }
        HttpResponse wetResponse = client.stageBuild(
                ServerUtils.getArtifactoryBuildName(build, parameters),
                build.getBuildNumber(),
                promotionBuilder.dryRun(isDryRun).build());

        Set<String> promotionErrors = checkSuccess(wetResponse, isDryRun);
        if (promotionErrors.size() > 0) {
            StringBuilder sb = new StringBuilder("Failed to execute the ");
            if (isDryRun) {
                sb.append("dry run ");
            }
            sb.append("promotion operation: </br>");
            for (String promotionError : promotionErrors) {
                sb.append(promotionError.replace("\n", "</br>")).append("</br>");
            }
            addError(errors, "errorPromotion", sb.toString(), xmlResponse);
            return false;
        }

        if (isDryRun) {
            Loggers.SERVER.info("Dry run promotion completed successfully.\nPerforming promotion...");
            return true;
        }

        Loggers.SERVER.info("Promotion completed successfully!");
        return true;
    }

    private void populateTargetRepos(@NotNull Element xmlResponse, long selectedUrlId, boolean overrideDeployerCredentials, CredentialsBean preferredDeployer) {
        Element deployableReposElement = new Element("deployableRepos");
        List<String> deployableRepos = deployableServers.getServerDeployableRepos(selectedUrlId,
                overrideDeployerCredentials, preferredDeployer.getUsername(), preferredDeployer.getPassword());
        for (String deployableRepo : deployableRepos) {
            deployableReposElement.addContent(new Element("repoName").addContent(deployableRepo));
        }
        xmlResponse.addContent(deployableReposElement);
    }

    @NotNull
    private CredentialsBean getDeployerCredentialsBean(Map<String, String> parameters, ServerConfigBean server, boolean overrideDeployerCredentials) {
        String username = "";
        String password = "";
        if (overrideDeployerCredentials) {
            if (StringUtils.isNotBlank(parameters.get(RunnerParameterKeys.DEPLOYER_USERNAME))) {
                username = parameters.get(RunnerParameterKeys.DEPLOYER_USERNAME);
            }
            if (StringUtils.isNotBlank(parameters.get(RunnerParameterKeys.DEPLOYER_PASSWORD))) {
                password = parameters.get(RunnerParameterKeys.DEPLOYER_PASSWORD);
            }
        }
        return CredentialsHelper.getPreferredDeployingCredentials(server,
                overrideDeployerCredentials, username, password);
    }

    @Nullable
    private ServerConfigBean getServerConfigBean(@NotNull Element xmlResponse, ActionErrors errors, long selectedUrlId) {
        ServerConfigBean server = deployableServers.getServerConfigById(selectedUrlId);
        if (server == null) {
            addError(errors, "errorPromotion", "Unable to perform any promotion operations: could not find an " +
                    "Artifactory server associated with the configuration ID of '" + selectedUrlId + "'.", xmlResponse);
            return null;
        }
        return server;
    }

    @Nullable
    private String getSelectedUrlId(@NotNull Element xmlResponse, ActionErrors errors, Map<String, String> parameters) {
        String selectUrlIdParam = parameters.get(RunnerParameterKeys.URL_ID);
        if (StringUtils.isBlank(selectUrlIdParam)) {
            addError(errors, "errorDetails", "Unable to perform any promotion operations: could not find an " +
                    "Artifactory server ID associated with the configuration of the selected build.", xmlResponse);
            return null;
        }
        return selectUrlIdParam;
    }

    @Nullable
    private SBuildType getSBuildType(@NotNull Element xmlResponse, ActionErrors errors, SBuild build) {
        SBuildType type = build.getBuildType();
        if (type == null) {
            addError(errors, "errorDetails", "The type of the selected build configuration could not be resolved.",
                    xmlResponse);
            return null;
        }
        return type;
    }

    @Nullable
    private SBuild getSBuild(@NotNull HttpServletRequest request, @NotNull Element xmlResponse, ActionErrors errors) {
        SBuild build = BuildDataExtensionUtil.retrieveBuild(request, buildServer);
        if (build == null) {
            addError(errors, "errorDetails", "The selected build configuration could not be resolved: make sure " +
                    "that the request sent to the promotion controller includes the 'buildId' parameter.", xmlResponse);
            return null;
        }
        return build;
    }

    private Map<String, String> getBuildRunnerParameters(SBuildType buildType) {
        for (SBuildRunnerDescriptor buildRunner : buildType.getBuildRunners()) {
            Map<String, String> runnerParameters = buildRunner.getParameters();
            if (Boolean.valueOf(runnerParameters.get(RunnerParameterKeys.ENABLE_RELEASE_MANAGEMENT))) {
                return runnerParameters;
            }
        }

        return Maps.newHashMap();
    }

    private void addError(ActionErrors errors, String errorKey, String errorMessage, Element xmlResponse) {
        errors.addError(errorKey, errorMessage);
        errors.serialize(xmlResponse);
    }

    private ArtifactoryBuildInfoClient getBuildInfoClient(ServerConfigBean serverConfigBean,
                                                          String username, String password) {
        ArtifactoryBuildInfoClient infoClient = new ArtifactoryBuildInfoClient(serverConfigBean.getUrl(),
                username, password, new TeamcityServerBuildInfoLog());
        infoClient.setConnectionTimeout(serverConfigBean.getTimeout());

        ProxyInfo proxyInfo = ProxyInfo.getInfo();
        if (proxyInfo != null) {
            if (StringUtils.isNotBlank(proxyInfo.getUsername())) {
                infoClient.setProxyConfiguration(proxyInfo.getHost(), proxyInfo.getPort(), proxyInfo.getUsername(),
                        proxyInfo.getPassword());
            } else {
                infoClient.setProxyConfiguration(proxyInfo.getHost(), proxyInfo.getPort());
            }
        }

        return infoClient;
    }

    private Set<String> checkSuccess(HttpResponse response, boolean isDryRun) {
        StatusLine status = response.getStatusLine();
        Set<String> errorsList = Sets.newHashSet();
        try {
            String content = entityToString(response);
            if (status.getStatusCode() != 200) {
                String error;
                if (isDryRun) {
                    error = "Promotion failed during dry run (no change in Artifactory was done): " + status + "\n" + content;
                    Loggers.SERVER.error(error);
                } else {
                    error = "Promotion failed. View Artifactory logs for more details: " + status + "\n" + content;
                    Loggers.SERVER.error(error);
                }
                errorsList.add(error);
                return errorsList;
            }

            JsonFactory factory = new JsonFactory();
            JsonParser parser = factory.createParser(content);
            ObjectMapper mapper = new ObjectMapper(factory);
            parser.setCodec(mapper);

            JsonNode resultNode = parser.readValueAsTree();
            JsonNode messagesNode = resultNode.get("messages");
            if ((messagesNode != null) && messagesNode.isArray()) {
                Iterator<JsonNode> messageIterator = messagesNode.iterator();
                while ((messageIterator != null) && messageIterator.hasNext()) {
                    JsonNode messagesIteration = messageIterator.next();
                    JsonNode levelNode = messagesIteration.get("level");
                    JsonNode messageNode = messagesIteration.get("message");

                    if ((levelNode != null) && (messageNode != null)) {
                        String level = levelNode.asText();
                        String message = messageNode.asText();
                        if (StringUtils.isNotBlank(level) && StringUtils.isNotBlank(message) &&
                                !message.startsWith("No items were")) {
                            String error = "Promotion failed. Received " + level + ": " + message;
                            Loggers.SERVER.error(error);
                            errorsList.add(error);
                        }
                    }
                }
            }

            return errorsList;
        } catch (IOException e) {
            String error = "Failed to parse promotion response: " + e.getMessage();
            Loggers.SERVER.error(error);
            Loggers.SERVER.error(e);
            errorsList.add(error);
            return errorsList;
        }
    }

    private String entityToString(HttpResponse response) throws IOException {
        HttpEntity entity = response.getEntity();
        InputStream is = entity.getContent();
        return IOUtils.toString(is, "UTF-8");
    }
}
