package com.intelligentresume.ai.selection.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.provider.*;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.careermaterial.domain.*;
import com.intelligentresume.careermaterial.repository.CareerMaterialRepository;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.personalprofile.repository.PersonalProfileRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobMaterialSelectionServiceTest {

    @Test
    void excludesGlobalExcludedUnlessForcedAndKeepsForcedOutsideAiQuota() {
        Fixture fixture = new Fixture();
        List<CareerMaterial> materials = new ArrayList<>();
        List<Long> forced = new ArrayList<>();
        for (long id = 1; id <= 14; id++) {
            CareerMaterial material = fixture.material(id, "Java backend " + id);
            if (id <= 13) forced.add(id);
            materials.add(material);
        }
        materials.get(0).setUsagePreference(UsagePreference.EXCLUDED);
        CareerMaterial globallyExcluded = fixture.material(99L, "Internal award");
        globallyExcluded.setUsagePreference(UsagePreference.EXCLUDED);
        materials.add(globallyExcluded);
        when(fixture.materialRepository.findByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(materials);
        when(fixture.provider.call(any())).thenReturn(AiCallResult.ok(Map.of(
                "recommended", List.of(Map.of("materialId", 14, "relevanceScore", 80,
                        "reason", "Matches Java", "matchedRequirements", List.of("Java"))),
                "unselected", List.of(), "missingRequirements", List.of()), "req"));

        Map<String, Object> result = fixture.service.executeTask(fixture.task(Map.of(
                "includedMaterialIds", forced, "excludedMaterialIds", List.of(),
                "preferredMaterialIds", List.of())));

        assertEquals(14, ((List<?>) result.get("recommended")).size());
        List<?> excluded = (List<?>) result.get("excluded");
        assertTrue(excluded.stream().map(item -> (Map<?, ?>) item)
                .anyMatch(item -> Objects.equals(item.get("materialId"), 99L)));
        assertFalse(excluded.stream().map(item -> (Map<?, ?>) item)
                .anyMatch(item -> Objects.equals(item.get("materialId"), 1L)));
    }

    @Test
    void chineseKeywordOverlapRanksRelevantMaterialAheadOfNewerIrrelevantMaterial() {
        Fixture fixture = new Fixture();
        CareerMaterial irrelevant = fixture.material(1L, "视觉设计与品牌内容");
        CareerMaterial relevant = fixture.material(2L, "高并发微服务架构与团队协作");
        when(fixture.materialRepository.findByUserIdOrderByUpdatedAtDesc(7L))
                .thenReturn(List.of(irrelevant, relevant));
        when(fixture.provider.call(any())).thenReturn(AiCallResult.ok(Map.of(
                "recommended", List.of(Map.of("materialId", 2, "relevanceScore", 90,
                        "reason", "相关", "matchedRequirements", List.of("高并发", "微服务"))),
                "unselected", List.of(), "missingRequirements", List.of()), "req"));

        fixture.service.executeTask(fixture.task(Map.of()));

        ArgumentCaptor<AiCallContext> captor = ArgumentCaptor.forClass(AiCallContext.class);
        verify(fixture.provider).call(captor.capture());
        String prompt = captor.getValue().input().get("_dataPrompt").toString();
        assertTrue(prompt.indexOf("高并发微服务") < prompt.indexOf("视觉设计"));
    }

    private static final class Fixture {
        final CareerMaterialRepository materialRepository = mock(CareerMaterialRepository.class);
        final JobDescriptionRepository jobRepository = mock(JobDescriptionRepository.class);
        final PersonalProfileRepository profileRepository = mock(PersonalProfileRepository.class);
        final AiProvider provider = mock(AiProvider.class);
        final JobMaterialSelectionService service;

        Fixture() {
            JobDescription job = new JobDescription();
            job.setId(8L);
            job.setUserId(7L);
            job.setTitle("Java 后端工程师");
            job.setJdText("负责高并发微服务架构和跨团队协作");
            when(jobRepository.findByIdAndUserId(8L, 7L)).thenReturn(Optional.of(job));
            when(profileRepository.findByUserId(7L)).thenReturn(Optional.empty());
            when(provider.supports(AiTaskType.JOB_MATERIAL_SELECTION)).thenReturn(true);
            service = new JobMaterialSelectionService(materialRepository, jobRepository,
                    profileRepository, new MaterialSelectionPromptBuilder(new ObjectMapper()),
                    new AiProviderRegistry(List.of(provider)));
        }

        CareerMaterial material(Long id, String text) {
            CareerMaterial material = new CareerMaterial();
            material.setId(id);
            material.setUserId(7L);
            material.setMaterialType(MaterialType.WORK_EXPERIENCE);
            material.setTitle(text);
            material.setSourceText(text);
            material.setContentJson(Map.of("description", text));
            material.setUsagePreference(UsagePreference.NORMAL);
            return material;
        }

        AiTask task(Map<String, Object> input) {
            AiTask task = new AiTask();
            task.setId(10L);
            task.setUserId(7L);
            task.setTaskType(AiTaskType.JOB_MATERIAL_SELECTION);
            task.setInputSnapshotJson(Map.of("jobDescriptionId", 8L, "input", input));
            return task;
        }
    }
}
