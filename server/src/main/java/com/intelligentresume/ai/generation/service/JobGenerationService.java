package com.intelligentresume.ai.generation.service;

import com.intelligentresume.ai.generation.dto.JobGenerationRequest;
import com.intelligentresume.ai.generation.dto.MissingItem;
import com.intelligentresume.ai.generation.dto.SelectedMaterialEntry;
import com.intelligentresume.ai.provider.*;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.careermaterial.domain.*;
import com.intelligentresume.careermaterial.repository.CareerMaterialRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.resume.repository.ResumeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class JobGenerationService {

    private static final Logger log = LoggerFactory.getLogger(JobGenerationService.class);
    private static final long DEFAULT_TIMEOUT_MS = 60_000;
    private static final Pattern LEGACY_SOURCE_ID = Pattern.compile("materialId\\s*=\\s*(\\d+)");
    private final CareerMaterialRepository materialRepository;
    private final JobDescriptionRepository jobRepository;
    private final ResumeRepository resumeRepository;
    private final MaterialSelector materialSelector;
    private final PromptInjectionDetector injectionDetector;
    private final JobGenerationPromptBuilder promptBuilder;
    private final JobGenerationSchemaValidator schemaValidator;
    private final AiProviderRegistry providerRegistry;

    @Value("${app.ai.generation.prompt-version:v1.0.0}")
    private String promptVersion;
    @Value("${app.ai.generation.schema-version:v1.0.0}")
    private String schemaVersion;

    // Keep the established constructor shape so existing tests and wiring remain compatible.
    public JobGenerationService(CareerMaterialRepository materialRepository,
                                JobDescriptionRepository jobRepository,
                                ResumeRepository resumeRepository,
                                AiTaskRepository ignoredTaskRepository,
                                MaterialSelector materialSelector,
                                PromptInjectionDetector injectionDetector,
                                JobGenerationPromptBuilder promptBuilder,
                                JobGenerationSchemaValidator schemaValidator,
                                AiProviderRegistry providerRegistry) {
        this.materialRepository = materialRepository;
        this.jobRepository = jobRepository;
        this.resumeRepository = resumeRepository;
        this.materialSelector = materialSelector;
        this.injectionDetector = injectionDetector;
        this.promptBuilder = promptBuilder;
        this.schemaValidator = schemaValidator;
        this.providerRegistry = providerRegistry;
    }

    public void validateMaterialIds(Long userId, List<Long> includedIds,
                                    List<Long> preferredIds, List<Long> excludedIds) {
        Set<Long> ownedIds = new HashSet<>();
        materialRepository.findByUserIdOrderByUpdatedAtDesc(userId)
                .forEach(material -> ownedIds.add(material.getId()));
        validateOwned(includedIds, ownedIds);
        validateOwned(preferredIds, ownedIds);
    }

    private void validateOwned(List<Long> ids, Set<Long> ownedIds) {
        if (ids != null && !ownedIds.containsAll(ids)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Career material not found");
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> executeTask(AiTask task) {
        Map<String, Object> snapshot = task.getInputSnapshotJson();
        if (!snapshot.containsKey("jobSnapshot")) {
            return executeLegacyTask(task, snapshot);
        }
        Long resumeId = toLong(snapshot.get("targetResumeId"));
        if (resumeId != null) {
            resumeRepository.findByIdAndUserId(resumeId, task.getUserId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Resume not found"));
        }
        JobDescription job = jobFromSnapshot(snapshot.get("jobSnapshot"));
        List<CareerMaterial> materials = materialsFromSnapshot(snapshot.get("materialSnapshots"));
        Map<String, Object> profile = mapValue(snapshot.get("personalProfileSnapshot"));
        Long jobId = toLong(snapshot.get("jobDescriptionId"));
        if (job == null || !Objects.equals(job.getId(), jobId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Generation task has no confirmed job snapshot");
        }
        if (materials.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "No confirmed career materials");
        }

        List<String> sourceTexts = materials.stream().map(CareerMaterial::getSourceText)
                .filter(Objects::nonNull).toList();
        PromptInjectionDetector.DetectionResult detection = injectionDetector.detect(job.getJdText(), sourceTexts);
        List<String> warnings = new ArrayList<>();
        if (detection.suspicious()) {
            warnings.add("PromptInjectionDetected");
            log.warn("Prompt injection detected for task {}: {}", task.getId(), detection.matchedPatterns());
        }

        JobGenerationPromptBuilder.Prompt prompt = promptBuilder.build(job, materials, List.of(), List.of(),
                careerProfileContext(profile), promptVersion);
        Map<String, Object> providerInput = new LinkedHashMap<>();
        providerInput.put("_systemPrompt", prompt.system());
        providerInput.put("_taskPrompt", prompt.task());
        providerInput.put("_dataPrompt", prompt.data());
        AiCallResult response = providerRegistry.route(AiTaskType.JOB_GENERATION)
                .call(new AiCallContext(AiTaskType.JOB_GENERATION, providerInput, DEFAULT_TIMEOUT_MS));
        if (!response.success()) {
            throw new BusinessException(ErrorCode.AI_FAILURE, "Resume generation failed: " + response.errorMessage());
        }
        Object rawDraft = response.data().get("draftResumeJson");
        if (!(rawDraft instanceof Map<?, ?> rawMap)) {
            throw new BusinessException(ErrorCode.AI_FAILURE, "AI output has no draftResumeJson");
        }
        Map<String, Object> draft = (Map<String, Object>) rawMap;
        normalizeAliases(draft);
        try {
            schemaValidator.validate(draft, schemaVersion,
                    materials.stream().map(CareerMaterial::getId).collect(java.util.stream.Collectors.toSet()));
        } catch (BusinessException e) {
            throw new BusinessException(ErrorCode.AI_FAILURE, "Draft schema validation failed: " + e.getMessage());
        }
        mergePersonalProfile(draft, profile);

        Map<String, CareerMaterial> byId = new HashMap<>();
        materials.forEach(material -> byId.put(String.valueOf(material.getId()), material));
        List<SelectedMaterialEntry> selected = new ArrayList<>();
        collectSources(draft, "", byId, selected);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", providerRegistry.route(AiTaskType.JOB_GENERATION).code());
        result.put("draftResumeJson", draft);
        result.put("selected", selected.stream().distinct().toList());
        result.put("unselected", List.of());
        List<MissingItem> missing = buildMissing(draft);
        result.put("missing", missing);
        result.put("qualitySummary", buildQualitySummary(draft, missing,
                stringList(snapshot.get("missingRequirements")), hasTrustedProfile(profile)));
        result.put("warnings", warnings);
        result.put("promptVersion", promptVersion);
        result.put("schemaVersion", schemaVersion);
        return result;
    }

    /**
     * Older queued tasks predate the material-selection snapshot. Keep them
     * executable during the migration, while every new task must use the
     * immutable snapshot path above.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> executeLegacyTask(AiTask task, Map<String, Object> snapshot) {
        Long resumeId = toLong(snapshot.get("targetResumeId"));
        if (resumeId != null) {
            resumeRepository.findByIdAndUserId(resumeId, task.getUserId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Resume not found"));
        }
        Long jobId = toLong(snapshot.get("jobDescriptionId"));
        JobDescription job = jobRepository.findByIdAndUserId(jobId, task.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Job description not found"));
        Map<String, Object> input = snapshot.get("input") instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw : snapshot;
        JobGenerationRequest request = new JobGenerationRequest(resumeId, jobId,
                longList(input.get("includedMaterialIds")), longList(input.get("preferredMaterialIds")),
                longList(input.get("excludedMaterialIds")));
        List<CareerMaterial> allMaterials = materialRepository.findByUserIdOrderByUpdatedAtDesc(task.getUserId());
        MaterialSelector.SelectionResult selection = materialSelector.select(task.getUserId(), allMaterials, request);
        List<CareerMaterial> materials = new ArrayList<>();
        materials.addAll(selection.fixed());
        materials.addAll(selection.preferred());
        materials.addAll(selection.normal());
        if (materials.isEmpty()) {
            Map<String, Object> emptyDraft = Map.of();
            List<Map<String, Object>> missing = List.of(
                    Map.of("section", "basics", "reason", "No eligible career materials"));
            return Map.of("draftResumeJson", emptyDraft, "selected", List.of(), "unselected", List.of(),
                    "missing", missing, "qualitySummary", buildQualitySummary(emptyDraft, missing, List.of(), false),
                    "warnings", List.of("InsufficientMaterials"), "promptVersion", promptVersion,
                    "schemaVersion", schemaVersion);
        }
        PromptInjectionDetector.DetectionResult detection = injectionDetector.detect(job.getJdText(),
                materials.stream().map(CareerMaterial::getSourceText).filter(Objects::nonNull).toList());
        List<String> warnings = detection.suspicious() ? List.of("PromptInjectionDetected") : List.of();
        JobGenerationPromptBuilder.Prompt prompt = promptBuilder.build(job, selection.fixed(), selection.preferred(),
                selection.normal(), promptVersion);
        Map<String, Object> providerInput = new LinkedHashMap<>();
        providerInput.put("_systemPrompt", prompt.system());
        providerInput.put("_taskPrompt", prompt.task());
        providerInput.put("_dataPrompt", prompt.data());
        AiCallResult response = providerRegistry.route(AiTaskType.JOB_GENERATION)
                .call(new AiCallContext(AiTaskType.JOB_GENERATION, providerInput, DEFAULT_TIMEOUT_MS));
        if (!response.success()) {
            throw new BusinessException(ErrorCode.AI_FAILURE, "Resume generation failed: " + response.errorMessage());
        }
        Object rawDraft = response.data().get("draftResumeJson");
        if (!(rawDraft instanceof Map<?, ?> rawMap)) {
            throw new BusinessException(ErrorCode.AI_FAILURE, "AI output has no draftResumeJson");
        }
        Map<String, Object> draft = (Map<String, Object>) rawMap;
        normalizeAliases(draft);
        try {
            schemaValidator.validate(draft, schemaVersion,
                    materials.stream().map(CareerMaterial::getId).collect(java.util.stream.Collectors.toSet()));
        } catch (BusinessException e) {
            throw new BusinessException(ErrorCode.AI_FAILURE, "Draft schema validation failed: " + e.getMessage());
        }
        Map<String, CareerMaterial> byId = new HashMap<>();
        materials.forEach(material -> byId.put(String.valueOf(material.getId()), material));
        List<SelectedMaterialEntry> selected = new ArrayList<>();
        collectSources(draft, "", byId, selected);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", providerRegistry.route(AiTaskType.JOB_GENERATION).code());
        result.put("draftResumeJson", draft);
        result.put("selected", selected.stream().distinct().toList());
        result.put("unselected", selection.excluded());
        List<MissingItem> missing = buildMissing(draft);
        result.put("missing", missing);
        result.put("qualitySummary", buildQualitySummary(draft, missing, List.of(), false));
        result.put("warnings", warnings);
        result.put("promptVersion", promptVersion);
        result.put("schemaVersion", schemaVersion);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void normalizeAliases(Map<String, Object> draft) {
        renameEach(draft.get("work"), Map.of("title", "position", "role", "position"));
        renameEach(draft.get("projects"), Map.of("title", "name", "position", "role"));
        renameEach(draft.get("education"), Map.of("institution", "school", "field", "major", "studyType", "degree"));
        renameEach(draft.get("certificates"), Map.of("title", "name", "organization", "issuer"));
        renameEach(draft.get("skills"), Map.of("keywords", "items"));
        promoteCustomSectionSources(draft.get("customSections"));
    }

    @SuppressWarnings("unchecked")
    private void promoteCustomSectionSources(Object value) {
        if (!(value instanceof List<?> sections)) return;
        for (Object item : sections) {
            if (!(item instanceof Map<?, ?> rawSection)) continue;
            Map<String, Object> section = (Map<String, Object>) rawSection;
            if (section.containsKey("_source") || section.containsKey("_sources")
                    || section.containsKey("_pending")) continue;
            if (!(section.get("entries") instanceof List<?> entries)) continue;

            Map<String, Map<String, Object>> uniqueSources = new LinkedHashMap<>();
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> rawEntry)) continue;
                Object sources = rawEntry.get("_sources");
                if (sources instanceof List<?> list) {
                    for (Object source : list) addPromotedSource(source, uniqueSources);
                }
                addPromotedSource(rawEntry.get("_source"), uniqueSources);
            }
            if (!uniqueSources.isEmpty()) {
                section.put("_sources", new ArrayList<>(uniqueSources.values()));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void addPromotedSource(Object value, Map<String, Map<String, Object>> sources) {
        if (!(value instanceof Map<?, ?> rawSource)) return;
        Map<String, Object> source = (Map<String, Object>) rawSource;
        Object materialId = source.get("materialId");
        if (materialId == null) return;
        String key = materialId + "\u0000" + Objects.toString(source.get("materialType"), "");
        sources.putIfAbsent(key, new LinkedHashMap<>(source));
    }

    @SuppressWarnings("unchecked")
    private void renameEach(Object value, Map<String, String> aliases) {
        if (!(value instanceof List<?> list)) return;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Map<String, Object> map = (Map<String, Object>) raw;
            aliases.forEach((from, to) -> {
                if (!map.containsKey(to) && map.containsKey(from)) map.put(to, map.remove(from));
            });
            Object highlights = map.get("highlights");
            if (highlights instanceof String text) map.put("highlights", List.of(text));
        }
    }

    @SuppressWarnings("unchecked")
    private void mergePersonalProfile(Map<String, Object> draft, Map<String, Object> profile) {
        Map<String, Object> basics = draft.get("basics") instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw : new LinkedHashMap<>();
        for (String key : List.of("name", "email", "phone", "location", "website")) {
            basics.remove(key);
        }
        putKnown(basics, "name", profile.get("fullName"));
        putKnown(basics, "email", profile.get("email"));
        putKnown(basics, "phone", profile.get("phone"));
        putKnown(basics, "location", profile.get("location"));
        putKnown(basics, "website", profile.get("website"));
        if (!basics.isEmpty()) basics.remove("_pending");
        draft.put("basics", basics);
    }

    private void putKnown(Map<String, Object> target, String key, Object value) {
        if (value != null && !value.toString().isBlank()) target.put(key, value);
    }

    private List<MissingItem> buildMissing(Map<String, Object> draft) {
        List<MissingItem> missing = new ArrayList<>();
        for (String section : List.of("basics", "work", "education", "skills")) {
            Object value = draft.get(section);
            if (isEmptySection(value)) {
                missing.add(new MissingItem(section, "No supported content"));
            } else {
                collectPending(value, section, missing);
            }
        }
        return missing;
    }

    private boolean isEmptySection(Object value) {
        return value == null
                || value instanceof Collection<?> collection && collection.isEmpty()
                || value instanceof Map<?, ?> map && map.isEmpty();
    }

    /**
     * Produces a small, user-facing review summary. It intentionally measures
     * only top-level resume entries because those are the items users can
     * accept, edit, or reject on the confirmation page.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildQualitySummary(Map<String, Object> draft, Collection<?> draftGaps,
                                                    Collection<?> missingRequirements, boolean personalProfileAvailable) {
        int total = 0;
        int sourced = 0;
        int pending = 0;
        int unsupported = 0;
        for (String section : JobGenerationSchemaValidator.SUPPORTED_SECTIONS) {
            Object value = draft.get(section);
            List<Map<String, Object>> entries = new ArrayList<>();
            if (value instanceof Map<?, ?> raw) {
                entries.add((Map<String, Object>) raw);
            } else if (value instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry instanceof Map<?, ?> raw) entries.add((Map<String, Object>) raw);
                }
            }
            for (Map<String, Object> entry : entries) {
                total++;
                boolean hasSource = hasSource(section, entry, personalProfileAvailable);
                boolean hasPending = containsPending(entry);
                if (hasSource) sourced++;
                if (hasPending) pending++;
                if (!hasSource && !hasPending) unsupported++;
            }
        }
        String readiness = pending > 0 || unsupported > 0 ? "REQUIRES_ACTION"
                : !draftGaps.isEmpty() || !missingRequirements.isEmpty() ? "REVIEW_RECOMMENDED" : "READY";
        return Map.of(
                "totalDraftItems", total,
                "sourcedItems", sourced,
                "pendingItems", pending,
                "unsupportedItems", unsupported,
                "draftGapCount", draftGaps.size(),
                "missingRequirementCount", missingRequirements.size(),
                "readiness", readiness);
    }

    private boolean hasSource(String section, Map<String, Object> entry, boolean personalProfileAvailable) {
        return entry.containsKey("_source") || entry.containsKey("_sources")
                || "basics".equals(section) && personalProfileAvailable;
    }

    @SuppressWarnings("unchecked")
    private boolean containsPending(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = (Map<String, Object>) raw;
            if (map.containsKey("_pending")) return true;
            return map.entrySet().stream()
                    .filter(entry -> !entry.getKey().startsWith("_"))
                    .anyMatch(entry -> containsPending(entry.getValue()));
        }
        if (value instanceof List<?> list) return list.stream().anyMatch(this::containsPending);
        return false;
    }

    @SuppressWarnings("unchecked")
    private void collectPending(Object value, String path, List<MissingItem> missing) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = (Map<String, Object>) raw;
            if (map.containsKey("_pending")) {
                Object pending = map.get("_pending");
                String reason = pending instanceof Map<?, ?> detail
                        ? Objects.toString(detail.get("reason"), "Missing information")
                        : Objects.toString(pending, "Missing information");
                missing.add(new MissingItem(path, reason));
            }
            map.forEach((key, child) -> {
                if (!key.startsWith("_")) collectPending(child, path + "." + key, missing);
            });
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) collectPending(list.get(i), path + "[" + i + "]", missing);
        }
    }

    @SuppressWarnings("unchecked")
    private void collectSources(Object value, String path, Map<String, CareerMaterial> byId,
                                List<SelectedMaterialEntry> selected) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = (Map<String, Object>) raw;
            Object sources = map.get("_sources");
            if (sources instanceof List<?> list) {
                for (Object source : list) {
                    if (source instanceof Map<?, ?> detail) addSource(detail.get("materialId"), path, byId, selected);
                }
            }
            Object legacy = map.get("_source");
            if (legacy != null) {
                Matcher matcher = LEGACY_SOURCE_ID.matcher(legacy.toString());
                while (matcher.find()) addSource(matcher.group(1), path, byId, selected);
            }
            map.forEach((key, child) -> {
                if (!key.startsWith("_")) collectSources(child, path.isBlank() ? key : path + "." + key, byId, selected);
            });
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) collectSources(list.get(i), path + "[" + i + "]", byId, selected);
        }
    }

    private void addSource(Object rawId, String path, Map<String, CareerMaterial> byId,
                           List<SelectedMaterialEntry> selected) {
        CareerMaterial material = byId.get(String.valueOf(rawId));
        if (material != null) selected.add(new SelectedMaterialEntry(material.getId(), path, "AI_REFERENCED"));
    }

    @SuppressWarnings("unchecked")
    private JobDescription jobFromSnapshot(Object raw) {
        Map<String, Object> value = mapValue(raw);
        if (value.isEmpty()) return null;
        JobDescription job = new JobDescription();
        job.setId(toLong(value.get("id")));
        job.setTitle(stringValue(value.get("title")));
        job.setCompanyName(stringValue(value.get("companyName")));
        job.setJdText(stringValue(value.get("jdText")));
        return job;
    }

    private List<CareerMaterial> materialsFromSnapshot(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<CareerMaterial> materials = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> value = mapValue(item);
            if (value.isEmpty()) continue;
            CareerMaterial material = new CareerMaterial();
            material.setId(toLong(value.get("id")));
            material.setTitle(stringValue(value.get("title")));
            material.setMaterialType(MaterialType.valueOf(stringValue(value.get("materialType"))));
            material.setUsagePreference(UsagePreference.valueOf(stringValue(value.get("usagePreference"))));
            material.setSourceText(stringValue(value.get("sourceText")));
            material.setContentJson(mapValue(value.get("contentJson")));
            materials.add(material);
        }
        return materials;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, child) -> result.put(String.valueOf(key), child));
        return result;
    }

    private String stringValue(Object value) { return value == null ? null : value.toString(); }

    private List<String> stringList(Object value) {
        if (!(value instanceof Collection<?> values)) return List.of();
        return values.stream().filter(String.class::isInstance).map(String.class::cast)
                .map(String::trim).filter(text -> !text.isEmpty()).toList();
    }

    private boolean hasTrustedProfile(Map<String, Object> profile) {
        return List.of("fullName", "email", "phone", "location", "website").stream()
                .map(profile::get)
                .anyMatch(value -> value != null && !value.toString().isBlank());
    }

    private Map<String, Object> careerProfileContext(Map<String, Object> profile) {
        Map<String, Object> context = new LinkedHashMap<>();
        for (String key : List.of("profileSummary", "targetRoleTitles", "targetSeniority", "targetIndustries",
                "targetWorkPreferences", "careerPositioningSummary")) {
            Object value = profile.get(key);
            if (value instanceof Collection<?> collection && collection.isEmpty()) continue;
            if (value != null && !value.toString().isBlank()) context.put(key, value);
        }
        return context;
    }
    private Long toLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? null : Long.valueOf(value.toString()); }
        catch (NumberFormatException ignored) { return null; }
    }

    private List<Long> longList(Object value) {
        if (!(value instanceof Collection<?> values)) return List.of();
        return values.stream().map(this::toLong).filter(Objects::nonNull).toList();
    }
}
