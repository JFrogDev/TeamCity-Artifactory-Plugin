<%@ taglib prefix="props" tagdir="/WEB-INF/tags/props" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>

<%--
  ~ Copyright (C) 2010 JFrog Ltd.
  ~
  ~ Licensed under the Apache License, Version 2.0 (the "License");
  ~ you may not use this file except in compliance with the License.
  ~ You may obtain a copy of the License at
  ~
  ~ http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing, software
  ~ distributed under the License is distributed on an "AS IS" BASIS,
  ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  ~ See the License for the specific language governing permissions and
  ~ limitations under the License.
  --%>

<jsp:useBean id="propertiesBean" scope="request" type="jetbrains.buildServer.controllers.BasePropertiesBean"/>
<c:set var="shouldDisplay" value="${param.shouldDisplay}" scope="request"/>

<c:set var="foundIncludeEnvVarsSelected"
       value="${(not empty propertiesBean.properties['org.jfrog.artifactory.selectedDeployableServer.includeEnvVars'])
       && (propertiesBean.properties['org.jfrog.artifactory.selectedDeployableServer.includeEnvVars'] == true) ? true : false}"/>

<c:if test="${shouldDisplay}">
    <div class="nestedParameter">
        Include Environment Variables: <props:displayValue
            name="org.jfrog.artifactory.selectedDeployableServer.includeEnvVars" emptyValue="false"/>
    </div>

    <c:if test="${foundIncludeEnvVarsSelected}">
        <div class="nestedParameter">
            Environment Variables Include Patterns: <props:displayValue
                name="org.jfrog.artifactory.selectedDeployableServer.envVarsIncludePatterns"
                emptyValue="not specified"/>
        </div>
        <div class="nestedParameter">
            Environment Variables Exclude Patterns: <props:displayValue
                name="org.jfrog.artifactory.selectedDeployableServer.envVarsExcludePatterns"
                emptyValue="not specified"/>
        </div>
    </c:if>
</c:if>