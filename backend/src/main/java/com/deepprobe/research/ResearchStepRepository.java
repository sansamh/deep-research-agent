package com.deepprobe.research;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ResearchStepRepository extends JpaRepository<ResearchStep, Long> {

    List<ResearchStep> findBySessionIdOrderBySeqAsc(Long sessionId);
}
