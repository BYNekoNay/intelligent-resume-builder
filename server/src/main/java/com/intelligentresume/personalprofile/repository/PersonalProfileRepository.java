package com.intelligentresume.personalprofile.repository;

import com.intelligentresume.personalprofile.domain.PersonalProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PersonalProfileRepository extends JpaRepository<PersonalProfile, Long> {

    Optional<PersonalProfile> findByUserId(Long userId);
}
