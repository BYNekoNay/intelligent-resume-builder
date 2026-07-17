package com.intelligentresume.interview.repository;
import com.intelligentresume.interview.domain.InterviewSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface InterviewSessionRepository extends JpaRepository<InterviewSession,Long>{Optional<InterviewSession> findByIdAndUserId(Long id,Long userId);}
