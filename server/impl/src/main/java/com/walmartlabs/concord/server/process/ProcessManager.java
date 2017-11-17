package com.walmartlabs.concord.server.process;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmartlabs.concord.project.InternalConstants;
import com.walmartlabs.concord.server.agent.AgentManager;
import com.walmartlabs.concord.server.api.process.ProcessEntry;
import com.walmartlabs.concord.server.api.process.ProcessStatus;
import com.walmartlabs.concord.server.process.ConcordFormService.FormSubmitResult;
import com.walmartlabs.concord.server.process.logs.LogManager;
import com.walmartlabs.concord.server.process.pipelines.ArchivePipeline;
import com.walmartlabs.concord.server.process.pipelines.ForkPipeline;
import com.walmartlabs.concord.server.process.pipelines.ProjectPipeline;
import com.walmartlabs.concord.server.process.pipelines.ResumePipeline;
import com.walmartlabs.concord.server.process.pipelines.processors.Chain;
import com.walmartlabs.concord.server.process.queue.ProcessQueueDao;
import com.walmartlabs.concord.server.process.state.ProcessStateManager;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response.Status;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.walmartlabs.concord.server.process.state.ProcessStateManager.path;
import static com.walmartlabs.concord.server.process.state.ProcessStateManager.zipTo;

@Named
public class ProcessManager {

    private static final Logger log = LoggerFactory.getLogger(ProcessManager.class);

    private final ProcessQueueDao queueDao;
    private final ProcessStateManager stateManager;
    private final AgentManager agentManager;
    private final LogManager logManager;
    private final ConcordFormService formService;

    private final Chain archivePipeline;
    private final Chain projectPipeline;
    private final Chain resumePipeline;
    private final Chain forkPipeline;

    private final ObjectMapper objectMapper;

    @Inject
    public ProcessManager(ProcessQueueDao queueDao,
                          ProcessStateManager stateManager,
                          AgentManager agentManager,
                          LogManager logManager,
                          ConcordFormService formService,
                          ArchivePipeline archivePipeline,
                          ProjectPipeline projectPipeline,
                          ResumePipeline resumePipeline,
                          ForkPipeline forkPipeline) {

        this.queueDao = queueDao;
        this.stateManager = stateManager;
        this.agentManager = agentManager;
        this.logManager = logManager;
        this.formService = formService;

        this.archivePipeline = archivePipeline;
        this.projectPipeline = projectPipeline;
        this.resumePipeline = resumePipeline;
        this.forkPipeline = forkPipeline;

        this.objectMapper = new ObjectMapper();
    }

    public PayloadEntry nextPayload() throws IOException {
        ProcessEntry p = queueDao.poll();
        if (p == null) {
            return null;
        }

        UUID instanceId = p.getInstanceId();

        // TODO this probably can be replaced with an in-memory buffer
        Path tmp = Files.createTempFile("payload", ".zip");
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(Files.newOutputStream(tmp))) {
            stateManager.export(instanceId, zipTo(zip));
        }

        return new PayloadEntry(p, tmp);
    }

    public ProcessResult startArchive(Payload payload, boolean sync) {
        return start(archivePipeline, payload, sync);
    }

    public ProcessResult startProject(Payload payload, boolean sync) {
        return start(projectPipeline, payload, sync);
    }

    public ProcessResult startFork(Payload payload, boolean sync) {
        return start(forkPipeline, payload, sync);
    }

    public void resume(Payload payload) {
        resumePipeline.process(payload);
    }

    public void kill(UUID instanceId) {
        ProcessEntry entry = queueDao.get(instanceId);
        if (entry == null) {
            throw new ProcessException(null, "Process not found: " + instanceId, Status.NOT_FOUND);
        }

        ProcessStatus s = entry.getStatus();
        if (s == ProcessStatus.CANCELLED || s == ProcessStatus.FINISHED) {
            return;
        }

        if (cancel(instanceId, s, ProcessStatus.ENQUEUED, ProcessStatus.PREPARING, ProcessStatus.SUSPENDED)) {
            return;
        }

        agentManager.killProcess(instanceId);
    }

    public void updateStatus(UUID instanceId, String agentId, ProcessStatus status) {
        if (status == ProcessStatus.FINISHED && isSuspended(instanceId)) {
            status = ProcessStatus.SUSPENDED;
        }

        queueDao.updateAgentId(instanceId, agentId, status);
        logManager.info(instanceId, "Process status: {}", status);

        log.info("updateStatus [{}, '{}', {}] -> done", instanceId, agentId, status);
    }

    private boolean isSuspended(UUID instanceId) {
        String resource = path(InternalConstants.Files.JOB_ATTACHMENTS_DIR_NAME,
                InternalConstants.Files.JOB_STATE_DIR_NAME,
                InternalConstants.Files.SUSPEND_MARKER_FILE_NAME);

        return stateManager.exists(instanceId, resource);
    }

    private ProcessResult start(Chain pipeline, Payload payload, boolean sync) {
        UUID instanceId = payload.getInstanceId();

        try {
            pipeline.process(payload);
        } catch (ProcessException e) {
            throw e;
        } catch (Exception e) {
            log.error("start ['{}'] -> error starting the process", instanceId, e);
            throw new ProcessException(instanceId, "Error starting the process", e, Status.INTERNAL_SERVER_ERROR);
        }

        Map<String, Object> out = null;
        if (sync) {
            Map<String, Object> args = readArgs(instanceId);
            out = process(instanceId, args);
        }

        return new ProcessResult(instanceId, out);
    }

    private Map<String, Object> process(UUID instanceId, Map<String, Object> params) {
        while (true) {
            ProcessEntry psr = queueDao.get(instanceId);
            ProcessStatus status = psr.getStatus();

            if (status == ProcessStatus.SUSPENDED) {
                wakeUpProcess(instanceId, params);
            } else if (status == ProcessStatus.FAILED || status == ProcessStatus.CANCELLED) {
                throw new ProcessException(instanceId, "Process error", Status.INTERNAL_SERVER_ERROR);
            } else if (status == ProcessStatus.FINISHED) {
                return readOutValues(instanceId);
            }

            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void wakeUpProcess(UUID instanceId, Map<String, Object> data) {
        FormSubmitResult r = formService.submitNext(instanceId, data);
        if (r != null && !r.isValid()) {
            String error = "n/a";
            if (r.getErrors() != null) {
                error = r.getErrors().stream().map(e -> e.getFieldName() + ": " + e.getError()).collect(Collectors.joining(","));
            }
            throw new ProcessException(instanceId, "Form '" + r.getFormName() + "' submit error: " + error, Status.BAD_REQUEST);
        }
    }

    private boolean cancel(UUID instanceId, ProcessStatus current, ProcessStatus... expected) {
        boolean found = false;
        for (ProcessStatus s : expected) {
            if (current == s) {
                found = true;
                break;
            }
        }

        return found && queueDao.update(instanceId, current, ProcessStatus.CANCELLED);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readOutValues(UUID instanceId) {
        String resource = path(InternalConstants.Files.JOB_ATTACHMENTS_DIR_NAME, InternalConstants.Files.OUT_VALUES_FILE_NAME);

        Optional<Map<String, Object>> o = stateManager.get(instanceId, resource, in -> {
            try {
                return Optional.of(objectMapper.readValue(in, Map.class));
            } catch (IOException e) {
                throw new ProcessException(instanceId, "Error while reading OUT variables data", e);
            }
        });

        return o.orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readArgs(UUID instanceId) {
        String resource = InternalConstants.Files.REQUEST_DATA_FILE_NAME;
        Optional<Map<String, Object>> o = stateManager.get(instanceId, resource, in -> {
            try {
                ObjectMapper om = new ObjectMapper();

                Map<String, Object> cfg = om.readValue(in, Map.class);
                Map<String, Object> args = (Map<String, Object>) cfg.get(InternalConstants.Request.ARGUMENTS_KEY);

                return Optional.ofNullable(args);
            } catch (IOException e) {
                throw new WebApplicationException("Error while reading request data", e);
            }
        });
        return o.orElse(Collections.emptyMap());
    }

    public static final class PayloadEntry {

        private final ProcessEntry processEntry;
        private final Path payloadArchive;

        public PayloadEntry(ProcessEntry processEntry, Path payloadArchive) {
            this.processEntry = processEntry;
            this.payloadArchive = payloadArchive;
        }

        public ProcessEntry getProcessEntry() {
            return processEntry;
        }

        public Path getPayloadArchive() {
            return payloadArchive;
        }
    }

    public static final class ProcessResult implements Serializable {

        private final UUID instanceId;
        private final Map<String, Object> out;

        public ProcessResult(UUID instanceId, Map<String, Object> out) {
            this.instanceId = instanceId;
            this.out = out;
        }

        public UUID getInstanceId() {
            return instanceId;
        }

        public Map<String, Object> getOut() {
            return out;
        }
    }
}