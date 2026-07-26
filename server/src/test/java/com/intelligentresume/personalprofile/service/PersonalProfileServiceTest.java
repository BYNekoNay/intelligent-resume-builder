package com.intelligentresume.personalprofile.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.personalprofile.domain.PersonalProfile;
import com.intelligentresume.personalprofile.dto.PersonalProfileRequest;
import com.intelligentresume.personalprofile.dto.PersonalProfileResponse;
import com.intelligentresume.personalprofile.repository.PersonalProfileRepository;
import com.intelligentresume.resume.domain.Resume;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PersonalProfileServiceTest {

    @Mock private PersonalProfileRepository profileRepository;
    @Mock private ResumeRepository resumeRepository;
    @Mock private ResumeVersionRepository versionRepository;

    @Test
    void getReturnsEmptyProfileWhenUserHasNotCreatedOne() {
        PersonalProfileService service = service();
        when(profileRepository.findByUserId(7L)).thenReturn(Optional.empty());

        PersonalProfileResponse response = service.get(7L);

        assertNull(response.fullName());
        assertNull(response.email());
        assertNull(response.profileSummary());
    }

    @Test
    void upsertCreatesThenUpdatesTheProfileOwnedByTheUser() {
        PersonalProfileService service = service();
        when(profileRepository.findByUserId(7L)).thenReturn(Optional.empty());
        when(profileRepository.save(any(PersonalProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PersonalProfileResponse created = service.upsert(
                new PersonalProfileRequest(" Zhang San ", "zhang@example.com", " 13800000000 ",
                        " Shanghai ", " https://example.com ", " Backend engineer ",
                        List.of(" Java Engineer ", "Java Engineer"), " Senior ",
                        List.of(" Internet "), List.of(" Remote "), " Platform specialist "), 7L);

        ArgumentCaptor<PersonalProfile> captor = ArgumentCaptor.forClass(PersonalProfile.class);
        verify(profileRepository).save(captor.capture());
        assertEquals(7L, captor.getValue().getUserId());
        assertEquals("Zhang San", created.fullName());
        assertEquals("https://example.com", created.website());
        assertEquals(List.of("Java Engineer"), created.targetRoleTitles());
        assertEquals("Senior", created.targetSeniority());

        PersonalProfile existing = captor.getValue();
        when(profileRepository.findByUserId(7L)).thenReturn(Optional.of(existing));
        service.upsert(new PersonalProfileRequest("Li Si", null, null, null, null, null,
                null, null, null, null, null), 7L);

        verify(profileRepository, times(2)).save(existing);
        assertEquals("Li Si", existing.getFullName());
        assertNull(existing.getEmail());
    }

    @Test
    void importSuggestionReadsBasicsFromTheOwnedCurrentVersionWithoutSaving() {
        PersonalProfileService service = service();
        Resume resume = new Resume();
        resume.setId(11L);
        resume.setUserId(7L);
        resume.setCurrentVersionId(21L);
        ResumeVersion version = new ResumeVersion();
        version.setId(21L);
        version.setResumeId(11L);
        version.setResumeJson(Map.of("basics", Map.of(
                "name", "Zhang San",
                "email", "zhang@example.com",
                "phone", "13800000000",
                "location", "Shanghai",
                "url", "https://example.com",
                "summary", "Backend engineer"
        )));
        when(resumeRepository.findByIdAndUserId(11L, 7L)).thenReturn(Optional.of(resume));
        when(versionRepository.findByIdAndResumeId(21L, 11L)).thenReturn(Optional.of(version));

        PersonalProfileResponse suggestion = service.importSuggestion(11L, 7L);

        assertEquals("Zhang San", suggestion.fullName());
        assertEquals("https://example.com", suggestion.website());
        assertEquals("Backend engineer", suggestion.profileSummary());
        verifyNoInteractions(profileRepository);
    }

    @Test
    void importSuggestionRejectsAnotherUsersResume() {
        PersonalProfileService service = service();
        when(resumeRepository.findByIdAndUserId(11L, 7L)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.importSuggestion(11L, 7L));
        verifyNoInteractions(versionRepository, profileRepository);
    }

    private PersonalProfileService service() {
        return new PersonalProfileService(profileRepository, resumeRepository, versionRepository);
    }
}
