package com.walmartlabs.concord.server.events;

import com.walmartlabs.concord.sdk.Constants;
import com.walmartlabs.concord.server.api.trigger.TriggerEntry;
import com.walmartlabs.concord.server.process.*;
import com.walmartlabs.concord.server.project.ProjectDao;
import com.walmartlabs.concord.server.security.UserPrincipal;
import com.walmartlabs.concord.server.triggers.TriggersDao;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public abstract class AbstractEventResource {

    private final Logger log;

    private final PayloadManager payloadManager;
    private final ProcessManager processManager;
    private final TriggersDao triggersDao;
    private final ProjectDao projectDao;

    public AbstractEventResource(PayloadManager payloadManager,
                                 ProcessManager processManager,
                                 TriggersDao triggersDao, ProjectDao projectDao) {

        this.payloadManager = payloadManager;
        this.processManager = processManager;
        this.triggersDao = triggersDao;
        this.projectDao = projectDao;
        this.log = LoggerFactory.getLogger(this.getClass());
    }

    protected int process(String eventId, String eventName, Map<String, Object> conditions, Map<String, Object> event) {
        List<TriggerEntry> triggers = triggersDao.list(eventName).stream()
                .filter(t -> filter(conditions, t))
                .collect(Collectors.toList());

        for (TriggerEntry t : triggers) {
            Map<String, Object> args = new HashMap<>();
            if (t.getArguments() != null) {
                args.putAll(t.getArguments());
            }
            args.put("event", event);

            UUID teamId = projectDao.getTeamId(t.getProjectId());
            UUID instanceId = startProcess(teamId, t.getProjectName(), t.getRepositoryName(), t.getEntryPoint(), args);
            log.info("process ['{}'] -> new process ('{}') triggered by {}", eventId, instanceId, t);
        }

        return triggers.size();
    }

    private boolean filter(Map<String, Object> conditions, TriggerEntry t) {
        return EventMatcher.matches(conditions, t.getConditions());
    }

    private UUID startProcess(UUID teamId, String projectName, String repoName, String flowName, Map<String, Object> args) {
        UUID instanceId = UUID.randomUUID();

        String initiator = getInitiator();

        PayloadParser.EntryPoint ep = new PayloadParser.EntryPoint(teamId, projectName, repoName, flowName);
        Map<String, Object> request = new HashMap<>();
        request.put(Constants.Request.ARGUMENTS_KEY, args);

        Payload payload;
        try {
            payload = payloadManager.createPayload(instanceId, null, initiator, ep, request, null);
        } catch (ProcessException e) {
            log.error("startProcess ['{}', '{}', '{}'] -> error creating a payload", projectName, repoName, flowName, e);
            throw e;
        } catch (Exception e) {
            log.error("startProcess ['{}', '{}', '{}'] -> error creating a payload", projectName, repoName, flowName, e);
            throw new ProcessException(instanceId, "Error while creating a payload: " + e.getMessage(), e);
        }

        processManager.startProject(payload, false);
        return instanceId;
    }

    private static String getInitiator() {
        Subject subject = SecurityUtils.getSubject();
        if (subject == null || !subject.isAuthenticated()) {
            return null;
        }

        UserPrincipal p = (UserPrincipal) subject.getPrincipal();
        return p != null ? p.getUsername() : null;
    }
}