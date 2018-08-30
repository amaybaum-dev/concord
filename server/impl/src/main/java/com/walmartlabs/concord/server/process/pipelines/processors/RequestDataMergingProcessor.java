package com.walmartlabs.concord.server.process.pipelines.processors;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2018 Walmart Inc.
 * -----
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =====
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.walmartlabs.concord.common.ConfigurationUtils;
import com.walmartlabs.concord.project.InternalConstants;
import com.walmartlabs.concord.project.model.ProjectDefinition;
import com.walmartlabs.concord.project.model.ProjectDefinitionUtils;
import com.walmartlabs.concord.sdk.Constants;
import com.walmartlabs.concord.server.cfg.DefaultVariablesConfiguration;
import com.walmartlabs.concord.server.org.project.ProjectDao;
import com.walmartlabs.concord.server.process.Payload;
import com.walmartlabs.concord.server.process.ProcessException;
import com.walmartlabs.concord.server.process.keys.AttachmentKey;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.core.Response.Status;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Named
public class RequestDataMergingProcessor implements PayloadProcessor {

    public static final AttachmentKey REQUEST_ATTACHMENT_KEY = AttachmentKey.register("request");
    public static final List<String> DEFAULT_PROFILES = Collections.singletonList("default");

    private final ProjectDao projectDao;
    private final DefaultVariablesConfiguration defaultVars;

    @Inject
    public RequestDataMergingProcessor(ProjectDao projectDao, DefaultVariablesConfiguration defaultVars) {
        this.projectDao = projectDao;
        this.defaultVars = defaultVars;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Payload process(Chain chain, Payload payload) {
        // system-level default variables
        Map<String, Object> defVars = Collections.singletonMap(Constants.Request.ARGUMENTS_KEY, new HashMap<>(defaultVars.getVars()));

        // configuration from the user's request
        Map<String, Object> req = payload.getHeader(Payload.REQUEST_DATA_MAP, Collections.emptyMap());

        // project configuration
        Map<String, Object> projectCfg = getProjectCfg(payload);

        // _main.json file in the workspace
        Map<String, Object> workspaceCfg = getWorkspaceCfg(payload);

        // attached to the request JSON file
        Map<String, Object> attachedCfg = getAttachedCfg(payload);

        // determine the active profile names
        List<String> activeProfiles = getActiveProfiles(ImmutableList.of(req, attachedCfg, workspaceCfg, projectCfg));
        payload = payload.putHeader(Payload.ACTIVE_PROFILES, activeProfiles);

        // merged profile data
        Map<String, Object> profileCfg = getProfileCfg(payload, activeProfiles);

        // create the resulting configuration
        Map<String, Object> m = ConfigurationUtils.deepMerge(defVars, projectCfg, profileCfg, workspaceCfg, attachedCfg, req);
        m.put(InternalConstants.Request.ACTIVE_PROFILES_KEY, activeProfiles);

        payload = payload.putHeader(Payload.REQUEST_DATA_MAP, m);

        return chain.process(payload);
    }

    private Map<String, Object> getProjectCfg(Payload payload) {
        UUID projectId = payload.getHeader(Payload.PROJECT_ID);
        if (projectId == null) {
            return Collections.emptyMap();
        }

        Map<String, Object> m = projectDao.getConfiguration(projectId);
        return m != null ? m : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getWorkspaceCfg(Payload payload) {
        Path workspace = payload.getHeader(Payload.WORKSPACE_DIR);
        Path src = workspace.resolve(InternalConstants.Files.REQUEST_DATA_FILE_NAME);
        if (!Files.exists(src)) {
            return Collections.emptyMap();
        }

        try (InputStream in = Files.newInputStream(src)) {
            ObjectMapper om = new ObjectMapper();
            return om.readValue(in, Map.class);
        } catch (IOException e) {
            throw new ProcessException(payload.getInstanceId(), "Invalid request data format", e, Status.BAD_REQUEST);
        }
    }

    private Map<String, Object> getProfileCfg(Payload payload, List<String> activeProfiles) {
        if (activeProfiles == null) {
            activeProfiles = Collections.emptyList();
        }

        ProjectDefinition pd = payload.getHeader(Payload.PROJECT_DEFINITION);
        if (pd == null) {
            return Collections.emptyMap();
        }

        Map<String, Object> m = ProjectDefinitionUtils.getVariables(pd, activeProfiles);
        return m != null ? m : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getAttachedCfg(Payload payload) {
        Path p = payload.getAttachment(REQUEST_ATTACHMENT_KEY);
        if (p == null) {
            return Collections.emptyMap();
        }

        try (InputStream in = Files.newInputStream(p)) {
            ObjectMapper om = new ObjectMapper();
            return om.readValue(in, Map.class);
        } catch (IOException e) {
            throw new ProcessException(payload.getInstanceId(), "Invalid request data format", e, Status.BAD_REQUEST);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> getActiveProfiles(List<Map<String, Object>> mm) {
        for (Map<String, Object> m : mm) {
            Object o = m.get(InternalConstants.Request.ACTIVE_PROFILES_KEY);
            if (o == null) {
                continue;
            }

            if (o instanceof String) {
                String s = (String) o;
                String[] as = s.trim().split(",");
                return Arrays.asList(as);
            } else if (o instanceof List) {
                return (List<String>) o;
            } else {
                throw new IllegalArgumentException("Invalid '" + InternalConstants.Request.ACTIVE_PROFILES_KEY +
                        "' value. Expected a JSON array or a comma-delimited list of profiles, got: " + o);
            }
        }

        return DEFAULT_PROFILES;
    }
}
