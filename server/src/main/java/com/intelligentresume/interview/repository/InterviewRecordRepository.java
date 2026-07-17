package com.intelligentresume.interview.repository;
import com.intelligentresume.interview.domain.InterviewRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface InterviewRecordRepository extends JpaRepository<InterviewRecord,Long>{List<InterviewRecord> findBySessionIdOrderByCreatedAtAsc(Long sessionId);}
