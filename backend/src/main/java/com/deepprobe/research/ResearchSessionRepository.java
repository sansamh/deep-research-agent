package com.deepprobe.research;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ResearchSessionRepository extends JpaRepository<ResearchSession, Long> {

    List<ResearchSession> findAllByOrderByCreatedAtDesc();
}
